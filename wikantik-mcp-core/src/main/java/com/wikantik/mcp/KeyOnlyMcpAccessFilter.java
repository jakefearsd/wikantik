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

import com.wikantik.auth.apikeys.AgentTokenAuth;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.http.ratelimit.SlidingWindowRateLimiter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;

/**
 * The access filter of a key-only MCP endpoint ({@code /work-mcp}). On top of {@link McpAccessFilter} with no CIDRs
 * and no unrestricted mode:
 * <ul>
 *   <li><b>No user principal.</b> The request forwarded to the transport carries the verified key record as the
 *       attribute {@link ApiKeyPrincipalRequest#ATTR_API_KEY_RECORD} and exposes no user principal and no remote user
 *       (the key's owner must never be visible downstream, ruling P30), with auth type {@code API_KEY} so no session
 *       is created.</li>
 *   <li><b>One session, one key.</b> The key that opened an MCP session is remembered against the session id the
 *       transport assigns (read when the transport writes the {@code Mcp-Session-Id} response header); a later
 *       request on that session id with a different key is refused with 403. A session id not yet bound (the binding
 *       expired or was evicted) is bound to the first key that uses it. Bindings are dropped on {@code DELETE}, after
 *       {@link #IDLE} without use, and beyond {@link #MAX_SESSIONS} (oldest first).</li>
 * </ul>
 */
public class KeyOnlyMcpAccessFilter extends McpAccessFilter {

    /** The MCP session header, as the streamable HTTP transport names it. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";
    /** A binding unused this long is forgotten. */
    static final Duration IDLE = Duration.ofHours( 2 );
    /** At most this many bindings are remembered. */
    static final long MAX_SESSIONS = 10_000L;
    static final String OTHER_KEY_BODY = "{\"error\":\"This MCP session belongs to another API key\"}";

    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );

    /** MCP session id to the client that opened it: {@code key:<id>} or {@code agent:<id>}, so the two never collide. */
    private final Cache< String, String > bindings;

    public KeyOnlyMcpAccessFilter( final SlidingWindowRateLimiter rateLimiter, final ApiKeyService apiKeyService,
                                   final ApiKeyService.Scope requiredScope ) {
        this( rateLimiter, apiKeyService, requiredScope, Ticker.systemTicker(), MAX_SESSIONS );
    }

    /** Tests: a controllable clock and size cap for the session bindings. */
    KeyOnlyMcpAccessFilter( final SlidingWindowRateLimiter rateLimiter, final ApiKeyService apiKeyService,
                            final ApiKeyService.Scope requiredScope, final Ticker ticker, final long maxSessions ) {
        super( "", false, rateLimiter, apiKeyService, requiredScope );
        this.bindings = Caffeine.newBuilder().expireAfterAccess( IDLE ).maximumSize( maxSessions ).ticker( ticker )
            .executor( Runnable::run ).build();
    }

    @Override
    public Outcome authorize( final HttpServletRequest req ) {
        final Outcome outcome = super.authorize( req );
        if ( !( outcome instanceof Outcome.Allowed allowed ) ) {
            return outcome;
        }
        final HttpServletRequest forwarded = forwarded( req, allowed.effectiveReq() );
        if ( forwarded == null ) {
            // Unreachable with no CIDRs and no unrestricted mode, but never forward a request without a credential.
            return Outcome.Denied.of( HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"Access denied\"}" );
        }
        final String session = req.getHeader( SESSION_HEADER );
        if ( session != null && !session.isBlank() ) {
            final String bound = bindings.asMap().putIfAbsent( session, allowed.clientId() );
            if ( bound != null && !bound.equals( allowed.clientId() ) ) {
                SECURITY.warn( "MCP access denied: client {} used an MCP session opened by another, ip={}",
                    allowed.clientId(), req.getRemoteAddr() );
                return Outcome.Denied.of( HttpServletResponse.SC_FORBIDDEN, OTHER_KEY_BODY );
            }
        }
        return new Outcome.Allowed( allowed.clientId(), forwarded );
    }

    /**
     * The principal-less request to forward: an API key's record travels in a {@link KeyRequest}; a verified agent
     * token already arrives as one ({@link AgentTokenAuth.Request}). Null when the request carries neither.
     */
    private static HttpServletRequest forwarded( final HttpServletRequest original, final HttpServletRequest effective ) {
        if ( effective.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) instanceof ApiKeyService.Record key ) {
            return new KeyRequest( original, key );
        }
        return effective.getAttribute( AgentTokenAuth.ATTR_AGENT_REF ) instanceof AgentTokenAuth.AgentRef
            ? effective : null;
    }

    /** The client a forwarded request belongs to, as {@link #authorize} named it. */
    private static String clientOf( final HttpServletRequest forwarded ) {
        if ( forwarded.getAttribute( AgentTokenAuth.ATTR_AGENT_REF ) instanceof AgentTokenAuth.AgentRef ref ) {
            return ref.clientId();
        }
        return "key:" + ( ( ApiKeyService.Record ) forwarded.getAttribute(
            ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) ).id();
    }

    /**
     * Binds a session id to its client the moment the transport assigns it (the {@code Mcp-Session-Id} response
     * header of {@code initialize}), and forgets it when the session is deleted.
     */
    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        super.doFilter( request, response, ( req, resp ) -> {
            final HttpServletRequest httpReq = ( HttpServletRequest ) req;
            chain.doFilter( req, new BindingResponse( ( HttpServletResponse ) resp, clientOf( httpReq ) ) );
            final String session = httpReq.getHeader( SESSION_HEADER );
            if ( "DELETE".equalsIgnoreCase( httpReq.getMethod() ) && session != null ) {
                bindings.invalidate( session );
            }
        } );
    }

    long boundSessions() {
        bindings.cleanUp();
        return bindings.estimatedSize();
    }

    /** Records the key against the session id the transport assigns, when it writes the header. */
    private final class BindingResponse extends HttpServletResponseWrapper {
        private final String client;

        BindingResponse( final HttpServletResponse response, final String client ) {
            super( response );
            this.client = client;
        }

        @Override
        public void setHeader( final String name, final String value ) {
            bind( name, value );
            super.setHeader( name, value );
        }

        @Override
        public void addHeader( final String name, final String value ) {
            bind( name, value );
            super.addHeader( name, value );
        }

        private void bind( final String name, final String value ) {
            if ( SESSION_HEADER.equalsIgnoreCase( name ) && value != null && !value.isBlank() ) {
                bindings.put( value, client );
            }
        }
    }

    /**
     * A request that authenticated with an API key on the key-only endpoint: the record as the one attribute, no
     * user principal, no remote user, auth type {@code API_KEY}.
     */
    static final class KeyRequest extends HttpServletRequestWrapper {
        private final ApiKeyService.Record record;

        KeyRequest( final HttpServletRequest request, final ApiKeyService.Record record ) {
            super( request );
            this.record = record;
        }

        @Override
        public Object getAttribute( final String name ) {
            return ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD.equals( name ) ? record : super.getAttribute( name );
        }

        @Override
        public Principal getUserPrincipal() {
            return null;
        }

        @Override
        public String getRemoteUser() {
            return null;
        }

        @Override
        public boolean isUserInRole( final String role ) {
            return false;
        }

        @Override
        public String getAuthType() {
            return ApiKeyPrincipalRequest.AUTH_TYPE;
        }
    }
}
