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
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.WikiSecurityException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Servlet filter that authenticates REST/admin requests via the standard HTTP
 * Basic {@code Authorization} header.
 *
 * <p>Without this filter the wiki treats every API client as anonymous even
 * when {@code Authorization: Basic} credentials are attached, because the web
 * container is configured with no {@code <login-config>} and the JSPWiki
 * filter chain only authenticates cookie/JAAS sessions. Automation (curl, CI
 * scripts, the admin API key provisioning flow in CLAUDE.md) expects Basic
 * auth to Just Work — hence this filter.
 *
 * <p>Behavior:
 * <ul>
 *   <li>No {@code Authorization} header → pass through unchanged. The downstream
 *       filter chain decides whether anonymous access is allowed.</li>
 *   <li>{@code Authorization: Basic} with malformed base64 → 400.</li>
 *   <li>{@code Authorization: Basic user:pass} with bad credentials → 401.</li>
 *   <li>Valid credentials → {@link AuthenticationManager#login} is invoked
 *       against the current wiki session; all the usual role principals
 *       (Authenticated, Admin if applicable, custom roles) are attached, so
 *       the existing {@link AdminAuthFilter} and ACL checks see the user as a
 *       proper wiki principal.</li>
 *   <li>Non-{@code Basic} scheme → pass through so Bearer token filters down
 *       the chain still fire.</li>
 * </ul>
 *
 * <p>Successful Basic logins are not cached — every request re-authenticates.
 * This is fine for REST automation latency and avoids the session-fixation
 * surface area of caching credentials in memory.</p>
 */
public class BasicAuthFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( BasicAuthFilter.class );
    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );

    private static final String BASIC_PREFIX = "Basic ";
    private static final String AUTHORIZATION = "Authorization";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    private Engine engine;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        engine = Wiki.engine().find( filterConfig.getServletContext(), null );
        LOG.info( "BasicAuthFilter initialized" );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {

        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        final String authHeader = req.getHeader( AUTHORIZATION );
        if ( authHeader == null || !authHeader.startsWith( BASIC_PREFIX ) ) {
            chain.doFilter( request, response );
            return;
        }

        final String[] creds = decodeCredentials( authHeader );
        if ( creds == null ) {
            respondError( resp, HttpServletResponse.SC_BAD_REQUEST,
                "Malformed Authorization: Basic header" );
            return;
        }
        final String username = creds[ 0 ];
        final String password = creds[ 1 ];
        if ( username.isEmpty() ) {
            respondError( resp, HttpServletResponse.SC_BAD_REQUEST,
                "Authorization: Basic requires a username" );
            return;
        }

        final Session session = Wiki.session().find( engine, req );
        // If the session is already authenticated as this user, don't re-login
        // on every request — saves the JAAS round-trip on chatty automation.
        if ( session.isAuthenticated()
                && session.getUserPrincipal() != null
                && username.equals( session.getUserPrincipal().getName() ) ) {
            chain.doFilter( request, response );
            return;
        }

        final AuthenticationManager authMgr = engine.getManager( AuthenticationManager.class );
        final boolean ok;
        try {
            ok = authMgr.login( session, req, username, password );
        } catch ( final WikiSecurityException e ) {
            LOG.warn( "Basic-auth login for '{}' raised: {}", username, e.getMessage() );
            sendUnauthorized( resp, "Authentication failed" );
            return;
        }
        if ( !ok ) {
            SECURITY.warn( "Basic-auth login failed: user='{}' ip={}", username, req.getRemoteAddr() );
            sendUnauthorized( resp, "Invalid credentials" );
            return;
        }

        chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }

    /**
     * Decodes {@code Authorization: Basic <base64>}. Returns a 2-element array
     * {@code [username, password]} or {@code null} if the header is malformed.
     * A missing {@code :} separator yields {@code ["", ""]} which the caller
     * treats as a bad request.
     */
    static String[] decodeCredentials( final String authHeader ) {
        try {
            final String b64 = authHeader.substring( BASIC_PREFIX.length() ).trim();
            final byte[] decoded = Base64.getDecoder().decode( b64 );
            final String raw = new String( decoded, StandardCharsets.UTF_8 );
            final int colon = raw.indexOf( ':' );
            if ( colon < 0 ) return new String[] { "", "" };
            return new String[] { raw.substring( 0, colon ), raw.substring( colon + 1 ) };
        } catch ( final IllegalArgumentException e ) {
            LOG.info( "Rejecting Basic auth header — base64 decode failed: {}", e.getMessage() );
            return null;
        }
    }

    private static void respondError( final HttpServletResponse resp, final int code, final String message )
            throws IOException {
        resp.setStatus( code );
        resp.setContentType( "application/json" );
        resp.setCharacterEncoding( "UTF-8" );
        resp.getWriter().write( "{\"error\":true,\"status\":" + code
                + ",\"message\":\"" + escape( message ) + "\"}" );
    }

    private static void sendUnauthorized( final HttpServletResponse resp, final String message ) throws IOException {
        resp.setHeader( WWW_AUTHENTICATE, "Basic realm=\"Wikantik\"" );
        respondError( resp, HttpServletResponse.SC_UNAUTHORIZED, message );
    }

    private static String escape( final String s ) {
        return s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" );
    }
}
