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
package com.wikantik.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Servlet filter that records HTTP request duration using a Micrometer Timer.
 * Produces the {@code http.server.requests} metric with tags for method, URI pattern, and status.
 *
 * <p>URI paths are normalized to replace dynamic segments with placeholders to avoid
 * high-cardinality metrics (e.g., {@code /api/pages/MainPage} becomes {@code /api/pages/{page}}).</p>
 */
public class RequestMetricsFilter implements Filter {

    private static final Pattern WIKI_PAGE = Pattern.compile( "^/wiki/.+" );
    private static final Pattern API_RESOURCE = Pattern.compile( "^/api/[^/]+/.+" );
    private static final Pattern ADMIN_RESOURCE = Pattern.compile( "^/admin/[^/]+/.+" );

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        // no initialization needed — registry comes from ServletContext at request time
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        final MeterRegistry registry =
                (MeterRegistry) request.getServletContext().getAttribute( MetricsServlet.REGISTRY_ATTR );

        if ( registry == null ) {
            // Metrics not yet initialized; pass through without timing
            chain.doFilter( request, response );
            return;
        }

        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;
        final long startNanos = System.nanoTime();

        try {
            chain.doFilter( request, response );
        } finally {
            final long durationNanos = System.nanoTime() - startNanos;
            final String uri = normalizeUri( httpRequest.getRequestURI() );
            Timer.builder( "http.server.requests" )
                    .tag( "method", httpRequest.getMethod() )
                    .tag( "uri", uri )
                    .tag( "status", String.valueOf( httpResponse.getStatus() ) )
                    .register( registry )
                    .record( durationNanos, TimeUnit.NANOSECONDS );
        }
    }

    @Override
    public void destroy() {
        // no cleanup needed
    }

    /**
     * Normalizes a URI by replacing dynamic path segments with placeholders.
     * This prevents high-cardinality metrics from page names and resource IDs.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code /wiki/MainPage} → {@code /wiki/{page}}</li>
     *   <li>{@code /api/pages/TestPage} → {@code /api/pages/{id}}</li>
     *   <li>{@code /api/search} → {@code /api/search} (unchanged)</li>
     * </ul>
     */
    static String normalizeUri( final String uri ) {
        if ( uri == null ) {
            return "unknown";
        }
        if ( WIKI_PAGE.matcher( uri ).matches() ) {
            return "/wiki/{page}";
        }
        if ( API_RESOURCE.matcher( uri ).matches() ) {
            // /api/pages/Something → /api/pages/{id}
            final int secondSlash = uri.indexOf( '/', 5 ); // after "/api/"
            if ( secondSlash > 0 ) {
                final String base = uri.substring( 0, secondSlash );
                return base + "/{id}";
            }
        }
        if ( ADMIN_RESOURCE.matcher( uri ).matches() ) {
            final int secondSlash = uri.indexOf( '/', 8 ); // after "/admin/"
            if ( secondSlash > 0 ) {
                final String base = uri.substring( 0, secondSlash );
                return base + "/{id}";
            }
        }
        return uri;
    }

}
