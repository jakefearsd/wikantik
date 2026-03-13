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
package org.apache.wiki.mcp;

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

/**
 * Servlet filter that restricts access to the MCP endpoint.
 *
 * <p>A request is allowed if it satisfies EITHER condition:
 * <ul>
 *   <li>A valid Bearer token matching the configured API key</li>
 *   <li>Source IP within one of the configured CIDR allowlist entries</li>
 * </ul>
 *
 * <p>If neither an API key nor CIDR allowlist is configured, all traffic is permitted
 * (backwards-compatible unrestricted mode).
 */
public class McpAccessFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( McpAccessFilter.class );
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] apiKeyBytes;
    private final List< CidrEntry > cidrEntries;
    private final boolean unrestricted;

    record CidrEntry( byte[] network, int prefixLen ) { }

    public McpAccessFilter( final McpConfig config ) {
        final String key = config.accessKey();
        this.apiKeyBytes = key != null ? key.getBytes( StandardCharsets.UTF_8 ) : null;
        this.cidrEntries = parseCidrs( config.allowedCidrs() );
        this.unrestricted = apiKeyBytes == null && cidrEntries.isEmpty();

        if ( unrestricted ) {
            LOG.info( "MCP access filter: unrestricted (no key or CIDR configured)" );
        } else {
            LOG.info( "MCP access filter: key={}, CIDRs={}",
                    apiKeyBytes != null ? "configured" : "none", cidrEntries.size() );
        }
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        if ( unrestricted ) {
            chain.doFilter( request, response );
            return;
        }

        final HttpServletRequest httpReq = ( HttpServletRequest ) request;
        final HttpServletResponse httpResp = ( HttpServletResponse ) response;

        if ( checkApiKey( httpReq ) || checkIp( httpReq ) ) {
            chain.doFilter( request, response );
            return;
        }

        httpResp.setStatus( HttpServletResponse.SC_FORBIDDEN );
        httpResp.setContentType( "application/json" );
        httpResp.getWriter().write( "{\"error\":\"Access denied\"}" );
    }

    private boolean checkApiKey( final HttpServletRequest request ) {
        if ( apiKeyBytes == null ) {
            return false;
        }
        final String authHeader = request.getHeader( "Authorization" );
        if ( authHeader == null || !authHeader.startsWith( BEARER_PREFIX ) ) {
            return false;
        }
        final byte[] provided = authHeader.substring( BEARER_PREFIX.length() )
                .getBytes( StandardCharsets.UTF_8 );
        return MessageDigest.isEqual( apiKeyBytes, provided );
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
