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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * SPA routing filter for the React frontend served at the root context.
 *
 * <p>Handles three concerns:
 * <ol>
 *   <li><b>Redirects</b> — {@code /}, {@code /wiki}, and {@code /wiki/} redirect
 *       to {@code /wiki/Main} so the wiki always has a concrete page.</li>
 *   <li><b>SPA forwarding</b> — known SPA prefixes ({@code /wiki/}, {@code /edit/},
 *       {@code /diff/}, {@code /admin/}) and exact SPA routes ({@code /search},
 *       {@code /preferences}, {@code /reset-password}) are forwarded to
 *       {@code /index.html} for React Router.</li>
 *   <li><b>Static assets</b> — requests containing a file extension (other than
 *       {@code .html}) pass through to Tomcat's default servlet.</li>
 * </ol>
 */
public class SpaRoutingFilter implements Filter {

    private static final String[] SPA_PREFIXES = { "/wiki/", "/edit/", "/diff/", "/admin/" };
    private static final String[] SPA_EXACT = { "/search", "/preferences", "/reset-password" };

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;
        final String path = req.getRequestURI();

        // Redirect / and /wiki/ to /wiki/Main (server-side, independent of SPA)
        if ( "/".equals( path ) || "/wiki/".equals( path ) || "/wiki".equals( path ) ) {
            resp.sendRedirect( "/wiki/Main" );
            return;
        }

        // Let static assets through (JS, CSS, images, fonts, favicon)
        if ( path.contains( "." ) && !path.endsWith( ".html" ) ) {
            chain.doFilter( request, response );
            return;
        }

        // Check if this is a SPA route — forward to index.html
        for ( final String prefix : SPA_PREFIXES ) {
            if ( path.startsWith( prefix ) ) {
                req.getRequestDispatcher( "/index.html" ).forward( request, response );
                return;
            }
        }
        for ( final String exact : SPA_EXACT ) {
            if ( path.equals( exact ) || path.startsWith( exact + "?" ) ) {
                req.getRequestDispatcher( "/index.html" ).forward( request, response );
                return;
            }
        }

        // Everything else passes through
        chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }
}
