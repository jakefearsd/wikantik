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
package com.wikantik.auth.apikeys;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shared machinery for the bearer-token / CIDR access filters guarding the agent-facing
 * endpoints (MCP servers, OpenAPI tool server). A request is allowed if it satisfies
 * EITHER condition:
 * <ul>
 *   <li>A valid Bearer token matching a <strong>DB-minted</strong> API key (via {@link ApiKeyService})
 *       whose scope covers the surface's {@link Surface#requiredScope()}</li>
 *   <li>Source IP within one of the configured CIDR allowlist entries</li>
 * </ul>
 *
 * <p>If none of DB keys, CIDR allowlist, or the surface's {@code allowUnrestricted}
 * property is configured, all traffic is <strong>rejected (fail-closed)</strong> with the
 * surface's configured 503 response. After authentication, requests are subject to rate
 * limiting. Failed access attempts and rate limit violations are logged to the
 * {@code SecurityLog} logger.
 *
 * <p>CIDR allowlists support both IPv4 and IPv6 entries. Mixed allowlists are honoured
 * per-family — a v4 caller will only match v4 CIDRs, and a v6 caller will only match
 * v6 CIDRs (no cross-family matching).</p>
 *
 * <p>Subclasses supply only surface-specific wording and scope via {@link Surface};
 * all authorization logic lives here so a fix on one surface reaches every surface.</p>
 */
@SuppressWarnings( "PMD.MoreThanOneLogger" ) // SecurityLog and log route to different log destinations by design.
public abstract class AbstractApiAccessFilter implements Filter {

    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int SC_TOO_MANY_REQUESTS = 429;

    /** Per-subclass logger so log categories keep pointing at the concrete filter. */
    private final Logger log = LogManager.getLogger( getClass() );

    private final Surface surface;
    private final List< CidrEntry > cidrEntries;
    private final boolean unrestricted;
    private final boolean failClosed;
    private final Predicate< String > rateLimiter;
    private final ApiKeyService apiKeyService;

    /** One parsed CIDR allowlist entry (network bytes + prefix length). */
    public record CidrEntry( byte[] network, int prefixLen ) { }

    /**
     * Surface-specific wording and policy knobs.
     *
     * @param name            display name used in log lines ("MCP", "Tools", ...)
     * @param propertyPrefix  configuration prefix cited in operator-facing logs
     *                        ("mcp.access", "tools.access", ...)
     * @param requiredScope   the API-key scope a DB-minted key must cover
     * @param failClosedDenied the exact 503 response for the unconfigured case
     * @param scopeDeniedBody  the exact 403 body when a valid key lacks the scope
     */
    public record Surface( String name, String propertyPrefix, ApiKeyService.Scope requiredScope,
                           Outcome.Denied failClosedDenied, String scopeDeniedBody ) { }

    /**
     * Outcome of {@link #authorize(HttpServletRequest)} — either the request is
     * authorised (with a stable {@code clientId} for rate-limiting and a possibly-wrapped
     * {@code effectiveReq}) or it is denied (with an HTTP status, optional headers, and a
     * JSON error body).
     */
    public sealed interface Outcome permits Outcome.Allowed, Outcome.Denied {

        record Allowed( String clientId, HttpServletRequest effectiveReq ) implements Outcome { }

        /** Header pair to be added to the response before writing the body. */
        record Header( String name, String value ) { }

        record Denied( int status, String body, List< Header > headers ) implements Outcome {
            public static Denied of( final int status, final String body ) {
                return new Denied( status, body, List.of() );
            }
            public static Denied of( final int status, final String body, final Header... headers ) {
                return new Denied( status, body, List.of( headers ) );
            }
        }
    }

    protected AbstractApiAccessFilter( final Surface surface, final String allowedCidrs,
                                       final boolean allowUnrestricted, final Predicate< String > rateLimiter,
                                       final ApiKeyService apiKeyService ) {
        this.surface = surface;
        this.cidrEntries = parseCidrs( allowedCidrs, log );
        final boolean hasDbKeys = apiKeyService != null;
        final boolean hasCidr = !cidrEntries.isEmpty();
        this.unrestricted = !hasCidr && allowUnrestricted;
        this.failClosed = !hasCidr && !hasDbKeys && !allowUnrestricted;
        this.rateLimiter = rateLimiter;
        this.apiKeyService = apiKeyService;

        if ( failClosed ) {
            // Justified log.error: operator misconfiguration must be immediately visible at startup.
            log.error( "CRITICAL: {} access filter has no DB-minted API keys, no CIDR allowlist, and "
                    + "{}.allowUnrestricted is not set — all {} requests will be rejected "
                    + "with 503. Mint a key at /admin/apikeys, configure {}.allowedCidrs, or "
                    + "set {}.allowUnrestricted=true to acknowledge.",
                    surface.name(), surface.propertyPrefix(), surface.name(),
                    surface.propertyPrefix(), surface.propertyPrefix() );
        } else if ( unrestricted ) {
            log.warn( "{} access filter: unrestricted mode active ({}.allowUnrestricted=true). "
                    + "Every {} request is treated as a superuser call. Only use this in trusted environments.",
                    surface.name(), surface.propertyPrefix(), surface.name() );
        } else {
            log.info( "{} access filter: dbKeys={}, CIDRs={}", surface.name(), hasDbKeys, cidrEntries.size() );
        }
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest httpReq = ( HttpServletRequest ) request;
        final HttpServletResponse httpResp = ( HttpServletResponse ) response;
        final String remoteAddr = httpReq.getRemoteAddr();

        final Outcome outcome = authorize( httpReq );
        if ( outcome instanceof Outcome.Denied d ) {
            writeDenied( httpResp, d );
            return;
        }

        final Outcome.Allowed allowed = ( Outcome.Allowed ) outcome;
        if ( !rateLimiter.test( allowed.clientId() ) ) {
            SECURITY.warn( "{} rate limit exceeded: client={}, ip={}", surface.name(), allowed.clientId(), remoteAddr );
            writeDenied( httpResp, Outcome.Denied.of( SC_TOO_MANY_REQUESTS,
                    "{\"error\":\"Rate limit exceeded\"}",
                    new Outcome.Header( "Retry-After", "1" ) ) );
            return;
        }

        chain.doFilter( allowed.effectiveReq(), response );
    }

    /**
     * Authorises a single HTTP request against the configured policy. Pure function: no
     * response writes happen here. Callers (production: {@link #doFilter}; tests: directly)
     * inspect the returned {@link Outcome}.
     */
    public Outcome authorize( final HttpServletRequest httpReq ) {
        final String remoteAddr = httpReq.getRemoteAddr();

        if ( failClosed ) {
            SECURITY.warn( "{} request rejected: filter fail-closed (no auth configured), ip={}",
                    surface.name(), remoteAddr );
            return surface.failClosedDenied();
        }

        if ( unrestricted ) {
            return new Outcome.Allowed( "ip:" + remoteAddr, httpReq );
        }

        final Optional< ApiKeyService.Record > dbKey = checkDbKey( httpReq );
        if ( dbKey.isPresent() ) {
            final ApiKeyService.Record record = dbKey.get();
            if ( !record.scope().matches( surface.requiredScope() ) ) {
                SECURITY.warn( "{} access denied: key id={} scope={} does not cover {}, ip={}",
                        surface.name(), record.id(), record.scope().wire(),
                        surface.name().toLowerCase( Locale.ROOT ), remoteAddr );
                return Outcome.Denied.of( HttpServletResponse.SC_FORBIDDEN, surface.scopeDeniedBody() );
            }
            final ApiKeyPrincipalRequest wrapper = new ApiKeyPrincipalRequest( httpReq, record.principalLogin() );
            wrapper.setAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, record );
            return new Outcome.Allowed( "key:" + record.id(), wrapper );
        }

        if ( checkIp( httpReq ) ) {
            return new Outcome.Allowed( "ip:" + remoteAddr, httpReq );
        }

        final boolean authHeaderPresent = httpReq.getHeader( "Authorization" ) != null;
        SECURITY.warn( "{} access denied: ip={}, auth={}",
                surface.name(), remoteAddr, authHeaderPresent ? "invalid-key" : "none" );
        return Outcome.Denied.of( HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"Access denied\"}" );
    }

    private static void writeDenied( final HttpServletResponse resp, final Outcome.Denied d ) throws IOException {
        resp.setStatus( d.status() );
        resp.setContentType( "application/json" );
        for ( final Outcome.Header h : d.headers() ) {
            resp.setHeader( h.name(), h.value() );
        }
        resp.getWriter().write( d.body() );
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

    private boolean checkIp( final HttpServletRequest request ) {
        if ( cidrEntries.isEmpty() ) {
            return false;
        }
        final String remoteAddr = request.getRemoteAddr();
        final byte[] addr;
        try {
            addr = InetAddress.getByName( remoteAddr ).getAddress();
        } catch ( final UnknownHostException e ) {
            log.warn( "Could not resolve remote address '{}': {}", remoteAddr, e.getMessage() );
            return false;
        }
        for ( final CidrEntry cidr : cidrEntries ) {
            if ( matches( addr, cidr ) ) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches( final byte[] addr, final CidrEntry cidr ) {
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

    public static List< CidrEntry > parseCidrs( final String cidrsProperty ) {
        return parseCidrs( cidrsProperty, LogManager.getLogger( AbstractApiAccessFilter.class ) );
    }

    private static List< CidrEntry > parseCidrs( final String cidrsProperty, final Logger log ) {
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
                    log.warn( "Malformed CIDR entry (missing prefix length): '{}'", trimmed );
                    continue;
                }
                final byte[] network = InetAddress.getByName( parts[ 0 ].strip() ).getAddress();
                final int prefixLen = Integer.parseInt( parts[ 1 ].strip() );
                final int maxPrefix = network.length * 8;
                if ( prefixLen < 0 || prefixLen > maxPrefix ) {
                    log.warn( "Malformed CIDR entry (prefix out of range): '{}'", trimmed );
                    continue;
                }
                result.add( new CidrEntry( network, prefixLen ) );
            } catch ( final UnknownHostException | NumberFormatException e ) {
                log.warn( "Malformed CIDR entry '{}': {}", trimmed, e.getMessage() );
            }
        }
        return Collections.unmodifiableList( result );
    }
}
