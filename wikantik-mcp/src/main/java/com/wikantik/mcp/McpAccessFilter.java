/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.mcp;

import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Servlet filter that restricts access to the MCP endpoint.
 *
 * <p>A request is allowed if it satisfies EITHER condition:
 * <ul>
 *   <li>A valid Bearer token matching one of the configured API keys</li>
 *   <li>Source IP within one of the configured CIDR allowlist entries</li>
 * </ul>
 *
 * <p>If neither API keys nor CIDR allowlist is configured, all traffic is permitted
 * (backwards-compatible unrestricted mode).
 *
 * <p>After authentication, requests are subject to rate limiting if configured.
 * Failed access attempts and rate limit violations are logged to the {@code SecurityLog} logger.
 */
public class McpAccessFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( McpAccessFilter.class );
    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int SC_TOO_MANY_REQUESTS = 429;

    private final List< byte[] > apiKeyList;
    private final List< CidrEntry > cidrEntries;
    private final boolean unrestricted;
    private final boolean failClosed;
    private final McpRateLimiter rateLimiter;
    private final ApiKeyService apiKeyService;

    record CidrEntry( byte[] network, int prefixLen ) { }

    public McpAccessFilter( final McpConfig config, final McpRateLimiter rateLimiter ) {
        this( config, rateLimiter, null );
    }

    public McpAccessFilter( final McpConfig config, final McpRateLimiter rateLimiter,
                            final ApiKeyService apiKeyService ) {
        final List< String > keys = config.accessKeys();
        this.apiKeyList = keys.stream()
                .map( k -> k.getBytes( StandardCharsets.UTF_8 ) )
                .toList();
        this.cidrEntries = parseCidrs( config.allowedCidrs() );
        final boolean hasDbKeys = apiKeyService != null;
        // Only legacy keys and CIDRs represent operator-configured gating. A DB-backed
        // ApiKeyService being wired up means the admin *could* mint keys, but without
        // any minted keys it is not by itself a gate; the explicit allowUnrestricted
        // flag still controls whether the filter runs open.
        final boolean hasLegacyAuth = !apiKeyList.isEmpty() || !cidrEntries.isEmpty();
        this.unrestricted = !hasLegacyAuth && config.allowUnrestricted();
        this.failClosed = !hasLegacyAuth && !hasDbKeys && !config.allowUnrestricted();
        this.rateLimiter = rateLimiter;
        this.apiKeyService = apiKeyService;

        if ( failClosed ) {
            LOG.error( "CRITICAL: MCP access filter has no API keys, no CIDR allowlist, and "
                    + "mcp.access.allowUnrestricted is not set — all MCP requests will be rejected "
                    + "with 503. Configure mcp.access.keys, mcp.access.allowedCidrs, or explicitly "
                    + "set mcp.access.allowUnrestricted=true to acknowledge." );
        } else if ( unrestricted ) {
            LOG.warn( "MCP access filter: unrestricted mode active (mcp.access.allowUnrestricted=true). "
                    + "Every MCP request is treated as a superuser call. Only use this in trusted environments." );
        } else {
            LOG.info( "MCP access filter: dbKeys={}, legacyKeys={}, CIDRs={}",
                    hasDbKeys, apiKeyList.size(), cidrEntries.size() );
        }
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest httpReq = ( HttpServletRequest ) request;
        final HttpServletResponse httpResp = ( HttpServletResponse ) response;
        final String remoteAddr = httpReq.getRemoteAddr();

        if ( failClosed ) {
            SECURITY.warn( "MCP request rejected: filter fail-closed (no auth configured), ip={}", remoteAddr );
            httpResp.setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
            httpResp.setContentType( "application/json" );
            httpResp.getWriter().write( "{\"error\":\"MCP not configured\"}" );
            return;
        }

        final String clientId;
        HttpServletRequest effectiveReq = httpReq;

        if ( unrestricted ) {
            clientId = "ip:" + remoteAddr;
        } else {
            final Optional< ApiKeyService.Record > dbKey = checkDbKey( httpReq );
            if ( dbKey.isPresent() ) {
                final ApiKeyService.Record record = dbKey.get();
                if ( !record.scope().matches( ApiKeyService.Scope.MCP ) ) {
                    SECURITY.warn( "MCP access denied: key id={} scope={} does not cover mcp, ip={}",
                            record.id(), record.scope().wire(), remoteAddr );
                    httpResp.setStatus( HttpServletResponse.SC_FORBIDDEN );
                    httpResp.setContentType( "application/json" );
                    httpResp.getWriter().write( "{\"error\":\"Key not authorized for MCP\"}" );
                    return;
                }
                final ApiKeyPrincipalRequest wrapper = new ApiKeyPrincipalRequest( httpReq, record.principalLogin() );
                wrapper.setAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, record );
                effectiveReq = wrapper;
                clientId = "key:" + record.id();
            } else {
                final int keyIndex = checkApiKey( httpReq );
                if ( keyIndex >= 0 ) {
                    clientId = "legacy:" + keyIndex;
                } else if ( checkIp( httpReq ) ) {
                    clientId = "ip:" + remoteAddr;
                } else {
                    final boolean authHeaderPresent = httpReq.getHeader( "Authorization" ) != null;
                    SECURITY.warn( "MCP access denied: ip={}, auth={}",
                            remoteAddr, authHeaderPresent ? "invalid-key" : "none" );
                    httpResp.setStatus( HttpServletResponse.SC_FORBIDDEN );
                    httpResp.setContentType( "application/json" );
                    httpResp.getWriter().write( "{\"error\":\"Access denied\"}" );
                    return;
                }
            }
        }

        if ( !rateLimiter.tryAcquire( clientId ) ) {
            SECURITY.warn( "MCP rate limit exceeded: client={}, ip={}", clientId, remoteAddr );
            httpResp.setStatus( SC_TOO_MANY_REQUESTS );
            httpResp.setHeader( "Retry-After", "1" );
            httpResp.setContentType( "application/json" );
            httpResp.getWriter().write( "{\"error\":\"Rate limit exceeded\"}" );
            return;
        }

        chain.doFilter( effectiveReq, response );
    }

    private Optional< ApiKeyService.Record > checkDbKey( final HttpServletRequest request ) {
        if ( apiKeyService == null ) {
            return Optional.empty();
        }
        final String authHeader = request.getHeader( "Authorization" );
        if ( authHeader == null || !authHeader.startsWith( BEARER_PREFIX ) ) {
            return Optional.empty();
        }
        return apiKeyService.verify( authHeader.substring( BEARER_PREFIX.length() ) );
    }

    /**
     * Checks the request's Bearer token against all configured API keys.
     *
     * @return the index of the matched key, or {@code -1} if no match
     */
    private int checkApiKey( final HttpServletRequest request ) {
        if ( apiKeyList.isEmpty() ) {
            return -1;
        }
        final String authHeader = request.getHeader( "Authorization" );
        if ( authHeader == null || !authHeader.startsWith( BEARER_PREFIX ) ) {
            return -1;
        }
        final byte[] provided = authHeader.substring( BEARER_PREFIX.length() )
                .getBytes( StandardCharsets.UTF_8 );
        for ( int i = 0; i < apiKeyList.size(); i++ ) {
            if ( MessageDigest.isEqual( apiKeyList.get( i ), provided ) ) {
                return i;
            }
        }
        return -1;
    }

    private boolean checkIp( final HttpServletRequest request ) {
        if ( cidrEntries.isEmpty() ) {
            return false;
        }
        final String remoteAddr = request.getRemoteAddr();
        final byte[] addr;
        try {
            addr = InetAddress.getByName( remoteAddr ).getAddress();
        } catch ( final UnknownHostException e ) {
            LOG.warn( "Could not resolve remote address '{}': {}", remoteAddr, e.getMessage() );
            return false;
        }
        for ( final CidrEntry cidr : cidrEntries ) {
            if ( matches( addr, cidr ) ) {
                return true;
            }
        }
        return false;
    }

    static boolean matches( final byte[] addr, final CidrEntry cidr ) {
        if ( addr.length != cidr.network().length ) {
            return false;
        }
        final int fullBytes = cidr.prefixLen() / 8;
        final int remainingBits = cidr.prefixLen() % 8;
        for ( int i = 0; i < fullBytes; i++ ) {
            if ( addr[ i ] != cidr.network()[ i ] ) {
                return false;
            }
        }
        if ( remainingBits > 0 ) {
            final int mask = 0xFF << ( 8 - remainingBits );
            if ( ( addr[ fullBytes ] & mask ) != ( cidr.network()[ fullBytes ] & mask ) ) {
                return false;
            }
        }
        return true;
    }

    static List< CidrEntry > parseCidrs( final String cidrsProperty ) {
        if ( cidrsProperty == null || cidrsProperty.isBlank() ) {
            return Collections.emptyList();
        }
        final List< CidrEntry > result = new ArrayList<>();
        for ( final String entry : cidrsProperty.split( "," ) ) {
            final String trimmed = entry.strip();
            if ( trimmed.isEmpty() ) {
                continue;
            }
            try {
                final String[] parts = trimmed.split( "/" );
                if ( parts.length != 2 ) {
                    LOG.warn( "Malformed CIDR entry (missing prefix length): '{}'", trimmed );
                    continue;
                }
                final byte[] network = InetAddress.getByName( parts[ 0 ].strip() ).getAddress();
                final int prefixLen = Integer.parseInt( parts[ 1 ].strip() );
                final int maxPrefix = network.length * 8;
                if ( prefixLen < 0 || prefixLen > maxPrefix ) {
                    LOG.warn( "Malformed CIDR entry (prefix out of range): '{}'", trimmed );
                    continue;
                }
                result.add( new CidrEntry( network, prefixLen ) );
            } catch ( final UnknownHostException | NumberFormatException e ) {
                LOG.warn( "Malformed CIDR entry '{}': {}", trimmed, e.getMessage() );
            }
        }
        return Collections.unmodifiableList( result );
    }
}
