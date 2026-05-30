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
import com.wikantik.auth.subsystem.AuthSubsystemBridge;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Re-establishes an authenticated session from the remember-me cookie on the API
 * surface ({@code /api/*}, {@code /admin/*}).
 *
 * <p>The legacy {@code WikiServletFilter} runs the per-request authentication
 * pipeline (which consults {@code CookieAuthenticationLoginModule}) only for the
 * page/attachment paths — never for {@code /api/*}, where the SPA lives. So when
 * the server-side session is gone (Tomcat restart/redeploy, session timeout) but
 * the browser still holds a valid remember-me cookie, REST calls would resolve to
 * a guest and the user appears logged out. This filter closes that gap: it runs
 * the cookie re-authentication so the next API call silently re-establishes the
 * session instead of bouncing the user to the login screen.</p>
 *
 * <p><strong>Scope discipline:</strong> it acts only when remember-me is enabled
 * AND the request actually carries a remember-me cookie AND the session is not
 * already authenticated. Anonymous requests (no remember-me cookie) are untouched,
 * so the deliberate "don't mint an HttpSession per anonymous request" behaviour is
 * preserved. Already-authenticated requests (valid {@code JSESSIONID}) short-circuit
 * cheaply. Re-auth failures never block the request — it simply proceeds as guest.</p>
 */
public class RememberMeAuthFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( RememberMeAuthFilter.class );

    /** Remember-me cookie names (current + legacy), matching CookieAuthenticationLoginModule. */
    private static final String[] REMEMBER_ME_COOKIES = { "WikantikUID", "JSPWikiUID" };

    private Engine engine;

    @Override
    public void init( final FilterConfig filterConfig ) {
        engine = Wiki.engine().find( filterConfig.getServletContext(), null );
        LOG.info( "RememberMeAuthFilter initialized" );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        try {
            final AuthenticationManager authMgr =
                    AuthSubsystemBridge.fromLegacyEngine( engine ).authentication();
            if ( authMgr.allowsCookieAuthentication() && hasRememberMeCookie( req ) ) {
                final Session session = Wiki.session().find( engine, req );
                if ( !session.isAuthenticated() ) {
                    // Runs the JAAS login modules, including CookieAuthenticationLoginModule,
                    // which validates the remember-me cookie and re-authenticates the session.
                    authMgr.login( req );
                }
            }
        } catch ( final Exception e ) {
            // Re-auth is best-effort: a failure must never block the request. The
            // user simply continues as guest and the SPA routes them to login.
            LOG.warn( "Remember-me re-auth failed for {}: {}", req.getRequestURI(), e.getMessage() );
        }
        chain.doFilter( request, response );
    }

    private static boolean hasRememberMeCookie( final HttpServletRequest req ) {
        final Cookie[] cookies = req.getCookies();
        if ( cookies == null ) {
            return false;
        }
        for ( final Cookie c : cookies ) {
            for ( final String name : REMEMBER_ME_COOKIES ) {
                if ( name.equals( c.getName() ) && c.getValue() != null && !c.getValue().isEmpty() ) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        /* no resources to release */
    }
}
