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

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link SpaRoutingFilter} — forwards /app/* routes to index.html
 * for React Router, while letting static assets pass through.
 */
class SpaRoutingFilterTest {

    private SpaRoutingFilter filter;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        filter = new SpaRoutingFilter();
        filter.init( null );
        response = mock( HttpServletResponse.class );
        chain = mock( FilterChain.class );
    }

    @ParameterizedTest( name = "Static asset passes through: {0}" )
    @ValueSource( strings = {
            "/app/assets/index-ABC123.js",
            "/app/assets/index-XYZ789.css",
            "/app/favicon.ico",
            "/app/assets/logo.png",
            "/app/assets/font.woff2"
    } )
    void testStaticAssetsPassThrough( final String path ) throws Exception {
        final HttpServletRequest request = mockRequest( path );

        filter.doFilter( request, response, chain );

        // Static assets go directly through the chain — no forwarding
        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @ParameterizedTest( name = "SPA route forwards to index.html: {0}" )
    @ValueSource( strings = {
            "/app/wiki/Main",
            "/app/edit/SomePage",
            "/app/search",
            "/app/admin/users",
            "/app/admin/security",
            "/app/"
    } )
    void testSpaRoutesForwardToIndexHtml( final String path ) throws Exception {
        final HttpServletRequest request = mockRequest( path );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/app/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        // SPA routes forward to index.html — chain is NOT called
        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    @Test
    void testHtmlFilesForwardToIndexHtml() throws Exception {
        // .html files are NOT treated as static assets — they go through React Router
        final HttpServletRequest request = mockRequest( "/app/index.html" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/app/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    private HttpServletRequest mockRequest( final String uri ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getRequestURI() ).thenReturn( uri );
        return request;
    }
}
