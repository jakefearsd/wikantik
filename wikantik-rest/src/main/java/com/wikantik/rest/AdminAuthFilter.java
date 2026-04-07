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

    private static final String FORBIDDEN_HTML = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Wikantik: Access Denied</title>
                <link rel="preconnect" href="https://fonts.googleapis.com" />
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
                <link href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@400;700&family=DM+Sans:wght@400;500&display=swap" rel="stylesheet" />
                <style>
                body { margin: 0; min-height: 100vh; display: flex; align-items: center;
                       justify-content: center; background: #FEFCF8; color: #2D2D2D;
                       font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, sans-serif; }
                .card { max-width: 480px; padding: 2.5rem; }
                h1 { font-family: 'Playfair Display', Georgia, serif; font-size: 2rem;
                     font-weight: 700; margin-bottom: 1.5rem; letter-spacing: -0.02em; }
                p { line-height: 1.7; color: #6B6560; margin-bottom: 1rem; }
                .actions { margin-top: 2rem; display: flex; gap: 0.75rem; }
                .btn { display: inline-block; padding: 0.6em 1.5em; border-radius: 8px;
                       font-family: inherit; font-size: 0.9rem; font-weight: 500;
                       text-decoration: none; transition: background 150ms, border-color 150ms; }
                .btn-primary { background: #C45D3E; color: #fff; border: 1px solid #C45D3E; }
                .btn-primary:hover { background: #A8442A; border-color: #A8442A; }
                .btn-secondary { background: transparent; color: #2D2D2D; border: 1px solid #E8E4DC; }
                .btn-secondary:hover { border-color: #D0C8BC; }
                </style>
              </head>
              <body>
                <div class="card">
                  <h1>Access Denied</h1>
                  <p>Your session has expired or you are not logged in as an administrator.</p>
                  <p>Admin pages require an active session with administrator privileges.\
             This commonly happens after a server restart or when your session times out.</p>
                  <div class="actions">
                    <a class="btn btn-primary" href="/wiki/Main">Go to Home</a>
                  </div>
                </div>
              </body>
            </html>
            """;

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
            resp.setCharacterEncoding( "UTF-8" );

            final String accept = req.getHeader( "Accept" );
            if ( accept != null && accept.contains( "text/html" ) ) {
                resp.setContentType( "text/html" );
                resp.getWriter().write( FORBIDDEN_HTML );
            } else {
                resp.setContentType( "application/json" );
                resp.getWriter().write( "{\"error\":true,\"status\":403,\"message\":\"Forbidden\"}" );
            }
            return;
        }

        chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }
}
