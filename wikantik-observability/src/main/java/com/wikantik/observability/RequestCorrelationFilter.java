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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.ThreadContext;

import com.wikantik.audit.RequestContextKeys;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Servlet filter that generates a unique request ID for each HTTP request and enriches
 * the Log4j2 ThreadContext (MDC) with request metadata. This enables request correlation
 * across all log lines emitted during the request lifecycle.
 *
 * <p>The filter checks for an incoming {@code X-Request-Id} header (e.g., from a reverse
 * proxy or load balancer). If absent, a new UUID is generated. The request ID is added
 * as a response header so clients and downstream systems can correlate requests.</p>
 *
 * <p>ThreadContext keys set by this filter:</p>
 * <ul>
 *   <li>{@code requestId} — unique request identifier</li>
 *   <li>{@code method} — HTTP method (GET, POST, etc.)</li>
 *   <li>{@code uri} — request URI</li>
 *   <li>{@code remoteAddr} — client IP address</li>
 *   <li>{@code userAgent} — client User-Agent header</li>
 * </ul>
 */
public class RequestCorrelationFilter implements Filter {

    static final String HEADER_REQUEST_ID = "X-Request-Id";
    static final String HEADER_USER_AGENT = "User-Agent";
    static final String MDC_REQUEST_ID = RequestContextKeys.REQUEST_ID;
    static final String MDC_METHOD = RequestContextKeys.METHOD;
    static final String MDC_URI = RequestContextKeys.URI;
    static final String MDC_REMOTE_ADDR = RequestContextKeys.REMOTE_ADDR;
    static final String MDC_USER_AGENT = RequestContextKeys.USER_AGENT;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        // no initialization needed
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;

        final String requestId = resolveRequestId( httpRequest );

        ThreadContext.put( MDC_REQUEST_ID, requestId );
        ThreadContext.put( MDC_METHOD, httpRequest.getMethod() );
        ThreadContext.put( MDC_URI, httpRequest.getRequestURI() );
        ThreadContext.put( MDC_REMOTE_ADDR, httpRequest.getRemoteAddr() );
        final String userAgent = httpRequest.getHeader( HEADER_USER_AGENT );
        ThreadContext.put( MDC_USER_AGENT, userAgent == null ? "" : userAgent );

        httpResponse.setHeader( HEADER_REQUEST_ID, requestId );

        try {
            chain.doFilter( request, response );
        } finally {
            ThreadContext.remove( MDC_REQUEST_ID );
            ThreadContext.remove( MDC_METHOD );
            ThreadContext.remove( MDC_URI );
            ThreadContext.remove( MDC_REMOTE_ADDR );
            ThreadContext.remove( MDC_USER_AGENT );
        }
    }

    @Override
    public void destroy() {
        // no cleanup needed
    }

    private String resolveRequestId( final HttpServletRequest request ) {
        final String incoming = request.getHeader( HEADER_REQUEST_ID );
        if ( incoming != null && !incoming.isBlank() ) {
            return incoming.trim();
        }
        // Correlation IDs need uniqueness, not unpredictability. UUID.randomUUID()
        // draws from a shared SecureRandom whose DRBG is synchronized — every request
        // serialized on that lock here, upstream of the backpressure gate. Build the
        // id from ThreadLocalRandom instead: lock-free, per-thread, same 36-char form.
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return new UUID( rnd.nextLong(), rnd.nextLong() ).toString();
    }

}
