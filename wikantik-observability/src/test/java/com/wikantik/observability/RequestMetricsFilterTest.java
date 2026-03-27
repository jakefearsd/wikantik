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

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class RequestMetricsFilterTest {

    private RequestMetricsFilter filter;
    private SimpleMeterRegistry registry;

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;
    @Mock private ServletContext servletContext;

    @BeforeEach
    void setUp() {
        filter = new RequestMetricsFilter();
        registry = new SimpleMeterRegistry();
    }

    private void stubRegistryAvailable() {
        when( request.getServletContext() ).thenReturn( servletContext );
        when( servletContext.getAttribute( MetricsServlet.REGISTRY_ATTR ) ).thenReturn( registry );
    }

    @Test
    void recordsRequestDuration() throws Exception {
        stubRegistryAvailable();
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/api/search" );
        when( response.getStatus() ).thenReturn( 200 );

        filter.doFilter( request, response, chain );

        final Timer timer = registry.find( "http.server.requests" )
                .tag( "method", "GET" )
                .tag( "uri", "/api/search" )
                .tag( "status", "200" )
                .timer();
        assertNotNull( timer, "Timer should be registered" );
        assertEquals( 1, timer.count() );
        assertTrue( timer.totalTime( java.util.concurrent.TimeUnit.NANOSECONDS ) > 0 );
    }

    @Test
    void normalizesWikiPageUri() throws Exception {
        stubRegistryAvailable();
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/wiki/MainPage" );
        when( response.getStatus() ).thenReturn( 200 );

        filter.doFilter( request, response, chain );

        final Timer timer = registry.find( "http.server.requests" )
                .tag( "uri", "/wiki/{page}" )
                .timer();
        assertNotNull( timer, "Wiki page URI should be normalized" );
    }

    @Test
    void normalizesApiResourceUri() throws Exception {
        stubRegistryAvailable();
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/api/pages/TestPage" );
        when( response.getStatus() ).thenReturn( 200 );

        filter.doFilter( request, response, chain );

        final Timer timer = registry.find( "http.server.requests" )
                .tag( "uri", "/api/pages/{id}" )
                .timer();
        assertNotNull( timer, "API resource URI should be normalized" );
    }

    @Test
    void passesThoughWhenRegistryNotAvailable() throws Exception {
        when( request.getServletContext() ).thenReturn( servletContext );
        when( servletContext.getAttribute( MetricsServlet.REGISTRY_ATTR ) ).thenReturn( null );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        // No timer should be registered since there's no registry
    }

    @Test
    void recordsMetricsEvenWhenChainThrows() throws Exception {
        stubRegistryAvailable();
        when( request.getMethod() ).thenReturn( "POST" );
        when( request.getRequestURI() ).thenReturn( "/api/pages" );
        when( response.getStatus() ).thenReturn( 500 );
        doThrow( new RuntimeException( "boom" ) ).when( chain ).doFilter( request, response );

        assertThrows( RuntimeException.class, () -> filter.doFilter( request, response, chain ) );

        final Timer timer = registry.find( "http.server.requests" )
                .tag( "method", "POST" )
                .tag( "status", "500" )
                .timer();
        assertNotNull( timer, "Timer should record even on exception" );
        assertEquals( 1, timer.count() );
    }

    // URI normalization unit tests

    @Test
    void normalizeUri_wikiPage() {
        assertEquals( "/wiki/{page}", RequestMetricsFilter.normalizeUri( "/wiki/MainPage" ) );
        assertEquals( "/wiki/{page}", RequestMetricsFilter.normalizeUri( "/wiki/Some/Deep/Path" ) );
    }

    @Test
    void normalizeUri_apiResource() {
        assertEquals( "/api/pages/{id}", RequestMetricsFilter.normalizeUri( "/api/pages/TestPage" ) );
        assertEquals( "/api/history/{id}", RequestMetricsFilter.normalizeUri( "/api/history/Main" ) );
        assertEquals( "/api/backlinks/{id}", RequestMetricsFilter.normalizeUri( "/api/backlinks/Page" ) );
    }

    @Test
    void normalizeUri_apiEndpointWithoutParam() {
        assertEquals( "/api/search", RequestMetricsFilter.normalizeUri( "/api/search" ) );
        assertEquals( "/api/pages", RequestMetricsFilter.normalizeUri( "/api/pages" ) );
        assertEquals( "/api/recent-changes", RequestMetricsFilter.normalizeUri( "/api/recent-changes" ) );
    }

    @Test
    void normalizeUri_adminResource() {
        assertEquals( "/admin/users/{id}", RequestMetricsFilter.normalizeUri( "/admin/users/john" ) );
        assertEquals( "/admin/content/{id}", RequestMetricsFilter.normalizeUri( "/admin/content/stats" ) );
    }

    @Test
    void normalizeUri_null() {
        assertEquals( "unknown", RequestMetricsFilter.normalizeUri( null ) );
    }

    @Test
    void initAndDestroyDoNotThrow() throws Exception {
        assertDoesNotThrow( () -> filter.init( null ) );
        assertDoesNotThrow( () -> filter.destroy() );
    }

}
