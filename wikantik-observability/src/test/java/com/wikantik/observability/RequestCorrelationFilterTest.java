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

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class RequestCorrelationFilterTest {

    private RequestCorrelationFilter filter;

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RequestCorrelationFilter();
        ThreadContext.clearAll();
    }

    @Test
    void generatesRequestIdWhenNoneProvided() throws Exception {
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( null );
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/wiki/Main" );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        final AtomicReference<String> capturedId = new AtomicReference<>();
        doAnswer( inv -> {
            capturedId.set( ThreadContext.get( "requestId" ) );
            return null;
        } ).when( chain ).doFilter( request, response );

        filter.doFilter( request, response, chain );

        assertNotNull( capturedId.get(), "requestId should be set in ThreadContext during chain execution" );
        assertFalse( capturedId.get().isBlank() );
        verify( response ).setHeader( eq( "X-Request-Id" ), eq( capturedId.get() ) );
    }

    @Test
    void usesIncomingRequestIdWhenProvided() throws Exception {
        final String incomingId = "test-request-id-123";
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( incomingId );
        when( request.getMethod() ).thenReturn( "POST" );
        when( request.getRequestURI() ).thenReturn( "/api/pages/TestPage" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final AtomicReference<String> capturedId = new AtomicReference<>();
        doAnswer( inv -> {
            capturedId.set( ThreadContext.get( "requestId" ) );
            return null;
        } ).when( chain ).doFilter( request, response );

        filter.doFilter( request, response, chain );

        assertEquals( incomingId, capturedId.get() );
        verify( response ).setHeader( "X-Request-Id", incomingId );
    }

    @Test
    void enrichesMdcWithRequestMetadata() throws Exception {
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( null );
        when( request.getMethod() ).thenReturn( "PUT" );
        when( request.getRequestURI() ).thenReturn( "/api/pages/NewPage" );
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );

        final AtomicReference<String> capturedMethod = new AtomicReference<>();
        final AtomicReference<String> capturedUri = new AtomicReference<>();
        final AtomicReference<String> capturedAddr = new AtomicReference<>();

        doAnswer( inv -> {
            capturedMethod.set( ThreadContext.get( "method" ) );
            capturedUri.set( ThreadContext.get( "uri" ) );
            capturedAddr.set( ThreadContext.get( "remoteAddr" ) );
            return null;
        } ).when( chain ).doFilter( request, response );

        filter.doFilter( request, response, chain );

        assertEquals( "PUT", capturedMethod.get() );
        assertEquals( "/api/pages/NewPage", capturedUri.get() );
        assertEquals( "192.168.1.1", capturedAddr.get() );
    }

    @Test
    void clearsMdcAfterFilterChainCompletes() throws Exception {
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( null );
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/wiki/Main" );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        filter.doFilter( request, response, chain );

        assertNull( ThreadContext.get( "requestId" ), "requestId should be cleared after filter completes" );
        assertNull( ThreadContext.get( "method" ) );
        assertNull( ThreadContext.get( "uri" ) );
        assertNull( ThreadContext.get( "remoteAddr" ) );
    }

    @Test
    void clearsMdcEvenWhenChainThrows() throws Exception {
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( null );
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/wiki/Main" );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );
        doThrow( new RuntimeException( "simulated error" ) ).when( chain ).doFilter( request, response );

        assertThrows( RuntimeException.class, () -> filter.doFilter( request, response, chain ) );

        assertNull( ThreadContext.get( "requestId" ), "requestId should be cleared even after exception" );
        assertNull( ThreadContext.get( "method" ) );
    }

    @Test
    void trimsWhitespaceFromIncomingRequestId() throws Exception {
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( "  trimmed-id  " );
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/wiki/Main" );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        final AtomicReference<String> capturedId = new AtomicReference<>();
        doAnswer( inv -> {
            capturedId.set( ThreadContext.get( "requestId" ) );
            return null;
        } ).when( chain ).doFilter( request, response );

        filter.doFilter( request, response, chain );

        assertEquals( "trimmed-id", capturedId.get() );
    }

    @Test
    void generatesNewIdWhenIncomingHeaderIsBlank() throws Exception {
        when( request.getHeader( "X-Request-Id" ) ).thenReturn( "   " );
        when( request.getMethod() ).thenReturn( "GET" );
        when( request.getRequestURI() ).thenReturn( "/wiki/Main" );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );

        final AtomicReference<String> capturedId = new AtomicReference<>();
        doAnswer( inv -> {
            capturedId.set( ThreadContext.get( "requestId" ) );
            return null;
        } ).when( chain ).doFilter( request, response );

        filter.doFilter( request, response, chain );

        assertNotNull( capturedId.get() );
        assertNotEquals( "   ", capturedId.get(), "blank header should trigger UUID generation" );
    }

    @Test
    void initAndDestroyDoNotThrow() throws Exception {
        assertDoesNotThrow( () -> filter.init( null ) );
        assertDoesNotThrow( () -> filter.destroy() );
    }

}
