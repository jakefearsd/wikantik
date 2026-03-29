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

import static org.mockito.Mockito.*;

/**
 * Tests for {@link SpaRoutingFilter} — redirects root paths to /wiki/Main,
 * forwards SPA routes to /index.html for React Router, and lets static assets
 * pass through.
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

    // ---- Redirect tests ----

    @Test
    void testRootRedirectsToWikiMain() throws Exception {
        final HttpServletRequest request = mockRequest( "/" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testWikiSlashRedirectsToWikiMain() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testWikiNoSlashRedirectsToWikiMain() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    // ---- Forward tests (SPA routes) ----

    @Test
    void testWikiPageForwardsToIndex() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/SomePage" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    @Test
    void testEditPageForwardsToIndex() throws Exception {
        final HttpServletRequest request = mockRequest( "/edit/SomePage" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    @Test
    void testDiffPageForwardsToIndex() throws Exception {
        final HttpServletRequest request = mockRequest( "/diff/SomePage" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    @Test
    void testSearchForwardsToIndex() throws Exception {
        final HttpServletRequest request = mockRequest( "/search" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    @Test
    void testPreferencesForwardsToIndex() throws Exception {
        final HttpServletRequest request = mockRequest( "/preferences" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    @Test
    void testResetPasswordForwardsToIndex() throws Exception {
        final HttpServletRequest request = mockRequest( "/reset-password" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    @Test
    void testAdminForwardsToIndex() throws Exception {
        final HttpServletRequest request = mockRequest( "/admin/security" );
        final RequestDispatcher dispatcher = mock( RequestDispatcher.class );
        when( request.getRequestDispatcher( "/index.html" ) ).thenReturn( dispatcher );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( dispatcher ).forward( request, response );
    }

    // ---- Passthrough tests (static assets) ----

    @Test
    void testJsAssetsPassThrough() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-ABC.js" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @Test
    void testCssAssetsPassThrough() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-XYZ.css" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @Test
    void testFaviconPassesThrough() throws Exception {
        final HttpServletRequest request = mockRequest( "/favicon.ico" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    private HttpServletRequest mockRequest( final String uri ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getRequestURI() ).thenReturn( uri );
        return request;
    }
}
