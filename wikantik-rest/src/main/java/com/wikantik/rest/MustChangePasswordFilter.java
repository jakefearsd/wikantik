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

/**
 * Enforces the "must change password" gate on /api/* and /admin/*: an
 * authenticated session whose user is flagged (users.password_must_change)
 * gets 403 + {"code":"PASSWORD_CHANGE_REQUIRED"} for everything except the
 * auth surface it needs to fix the situation (/api/auth/* — login, logout,
 * status, the password-changing profile PUT, reset-password). Browser HTML
 * navigations pass through so the SPA shell can load and route the user to
 * the change-password screen; the data calls behind it stay gated.
 *
 * Registered in web.xml after RememberMeAuthFilter (so re-authenticated
 * sessions are visible) and before AdminAuthFilter.
 */
public class MustChangePasswordFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( MustChangePasswordFilter.class );

    /** Paths a flagged user may still reach (prefix match on the context-relative path). */
    private static final String[] EXEMPT_PREFIXES = { "/api/auth/", "/api/health" };

    private Engine engine;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        engine = Wiki.engine().find( filterConfig.getServletContext(), null );
        LOG.info( "MustChangePasswordFilter initialized" );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        if ( "OPTIONS".equalsIgnoreCase( req.getMethod() ) || isExempt( req ) || isSpaNavigation( req ) ) {
            chain.doFilter( request, response );
            return;
        }

        final Session session = Wiki.session().find( engine, req );
        if ( !session.isAuthenticated() ) {
            chain.doFilter( request, response );
            return;
        }

        final String loginName = session.getLoginPrincipal().getName();
        if ( PasswordChangeGate.mustChangePassword( engine, req, loginName ) ) {
            LOG.info( "Password-change gate blocked {} {} for user '{}'",
                    req.getMethod(), req.getRequestURI(), loginName );
            resp.setStatus( HttpServletResponse.SC_FORBIDDEN );
            resp.setCharacterEncoding( "UTF-8" );
            resp.setContentType( "application/json" );
            resp.getWriter().write( "{\"error\":true,\"status\":403,\"code\":\""
                    + PasswordChangeGate.ERROR_CODE
                    + "\",\"message\":\"You must change your password before continuing\"}" );
            return;
        }

        chain.doFilter( request, response );
    }

    private boolean isExempt( final HttpServletRequest req ) {
        final String path = req.getRequestURI().substring( req.getContextPath().length() );
        for ( final String prefix : EXEMPT_PREFIXES ) {
            if ( path.startsWith( prefix ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the request looks like a browser asking for an HTML document
     * — a {@code GET} whose {@code Accept} header advertises {@code text/html}.
     * fetch()/XHR API calls don't include that media type by default, so this
     * cleanly distinguishes "user navigating in the SPA" from "SPA calling a
     * JSON endpoint."
     */
    static boolean isSpaNavigation( final HttpServletRequest req ) {
        if ( !"GET".equalsIgnoreCase( req.getMethod() ) ) {
            return false;
        }
        final String accept = req.getHeader( "Accept" );
        return accept != null && accept.contains( "text/html" );
    }

    @Override
    public void destroy() {
    }
}
