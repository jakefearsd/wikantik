/*
 * Copyright 2025 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.http.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link CacheHeaderFilter} — ensures correct Cache-Control headers
 * for hashed assets, index.html, and passthrough for everything else.
 */
class CacheHeaderFilterTest {

    private CacheHeaderFilter filter;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        filter = new CacheHeaderFilter();
        filter.init( mock( FilterConfig.class ) );
        response = mock( HttpServletResponse.class );
        chain = mock( FilterChain.class );
    }

    @Test
    void testHashedJsAssetGetsImmutableCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-BCNdZRMf.js" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testHashedCssAssetGetsImmutableCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-CCel3tKT.css" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testIndexHtmlGetsNoCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/index.html" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "no-cache" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testNonHashedAssetDoesNotSetCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/favicon.svg" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testApiCallDoesNotSetCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/api/pages/Main" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testSpaRouteDoesNotSetCacheHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/Main" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testShortHashNotMatchedAsImmutable() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-AB.js" );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "Cache-Control" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    private HttpServletRequest mockRequest( final String uri ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getRequestURI() ).thenReturn( uri );
        return request;
    }
}
