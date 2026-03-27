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
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.AllPermission;

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
 * Servlet filter that enforces admin authorization on all {@code /admin/*} endpoints.
 *
 * <p>Checks whether the current user's {@link Session} has {@link AllPermission}
 * (granted to the "Admin" group and "Admin" container role via the security policy).
 * Non-admin requests receive a 403 JSON response. Admin requests pass through.
 */
public class AdminAuthFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( AdminAuthFilter.class );

    private Engine engine;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        engine = Wiki.engine().find( filterConfig.getServletContext(), null );
        LOG.info( "AdminAuthFilter initialized" );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {

        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        // Allow CORS preflight through without auth
        if ( "OPTIONS".equalsIgnoreCase( req.getMethod() ) ) {
            chain.doFilter( request, response );
            return;
        }

        final Session session = Wiki.session().find( engine, req );
        final AllPermission adminPerm = new AllPermission( engine.getApplicationName() );
        final AuthorizationManager authMgr = engine.getManager( AuthorizationManager.class );

        if ( !authMgr.checkPermission( session, adminPerm ) ) {
            LOG.debug( "Admin access denied for {}", session.getLoginPrincipal().getName() );
            resp.setStatus( HttpServletResponse.SC_FORBIDDEN );
            resp.setContentType( "application/json" );
            resp.setCharacterEncoding( "UTF-8" );
            resp.getWriter().write( "{\"error\":true,\"status\":403,\"message\":\"Forbidden\"}" );
            return;
        }

        chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }
}
