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
package com.wikantik.http.filter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.io.IOException;


/**
 * CSRF protection Filter which uses the synchronizer token pattern – an anti-CSRF token is created and stored in the
 * user session and in a hidden field on subsequent form submits. At every submit the server checks the token from the
 * session matches the one submitted from the form.
 */
public class CsrfProtectionFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( CsrfProtectionFilter.class );

    public static final String ANTICSRF_PARAM = "X-XSRF-TOKEN";

    /** {@inheritDoc} */
    @Override
    public void init( final FilterConfig filterConfig ) {
    }

    /** {@inheritDoc} */
    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest httpRequest = ( HttpServletRequest ) request;
        if( isPost( httpRequest ) && !isMcpEndpoint( httpRequest ) && !isRestApiEndpoint( httpRequest ) ) {
            final Engine engine = Wiki.engine().find( request.getServletContext(), null );
            final Session session = Wiki.session().find( engine, httpRequest );
            if( !requestContainsValidCsrfToken( request, session ) ) {
                LOG.error( "Incorrect {} param with value '{}' received for {}",
                           ANTICSRF_PARAM, request.getParameter( ANTICSRF_PARAM ), httpRequest.getPathInfo() );
                ( ( HttpServletResponse ) response ).sendRedirect( "/error/Forbidden.html" );
                return;
            }
        }
        chain.doFilter( request, response );
    }

    public static boolean isCsrfProtectedPost( final HttpServletRequest request ) {
        if( isPost( request ) ) {
            final Engine engine = Wiki.engine().find( request.getServletContext(), null );
            final Session session = Wiki.session().find( engine, request );
            return requestContainsValidCsrfToken( request, session );
        }
        return false;
    }

    private static boolean requestContainsValidCsrfToken( final ServletRequest request, final Session session ) {
        return session.antiCsrfToken().equals( request.getParameter( ANTICSRF_PARAM ) );
    }

    static boolean isPost( final HttpServletRequest request ) {
        return "POST".equalsIgnoreCase( request.getMethod() );
    }

    static boolean isMcpEndpoint( final HttpServletRequest request ) {
        final String servletPath = request.getServletPath();
        return "/mcp".equals( servletPath );
    }

    /**
     * REST API endpoints use {@code Content-Type: application/json} which provides natural CSRF
     * protection — browsers cannot forge cross-origin JSON POST requests without a CORS preflight.
     *
     * @param request the HTTP request
     * @return {@code true} if the request targets a REST API endpoint
     */
    static boolean isRestApiEndpoint( final HttpServletRequest request ) {
        final String servletPath = request.getServletPath();
        return servletPath != null
                && ( servletPath.startsWith( "/api/" ) || servletPath.startsWith( "/admin/" ) );
    }

    /** {@inheritDoc} */
    @Override
    public void destroy() {
    }

}
