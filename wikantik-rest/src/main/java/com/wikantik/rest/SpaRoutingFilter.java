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
 * SPA routing filter for the React frontend at {@code /app/*}.
 *
 * <p>When a request comes in for a path like {@code /app/wiki/SomePage},
 * Tomcat would normally return 404 because no such file exists.
 * This filter forwards non-asset requests to {@code /app/index.html}
 * so React Router can handle client-side routing.
 *
 * <p>Requests for static assets (JS, CSS, images, fonts) are passed
 * through to be served directly by Tomcat.
 */
public class SpaRoutingFilter implements Filter {

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final String path = req.getRequestURI();

        // Let static assets through (JS, CSS, images, fonts, favicon)
        if ( path.contains( "." ) && !path.endsWith( ".html" ) ) {
            chain.doFilter( request, response );
            return;
        }

        // Forward all other /app/* requests to index.html for React Router
        req.getRequestDispatcher( "/app/index.html" ).forward( request, response );
    }

    @Override
    public void destroy() {
    }
}
