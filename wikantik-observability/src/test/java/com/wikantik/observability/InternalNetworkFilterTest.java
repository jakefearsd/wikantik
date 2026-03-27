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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class InternalNetworkFilterTest {

    private InternalNetworkFilter filter;

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        filter = new InternalNetworkFilter();
        filter.init( null );
    }

    // --- Allowed IPs: should pass through to chain ---

    @ParameterizedTest
    @ValueSource( strings = {
            "127.0.0.1",        // IPv4 loopback
            "127.0.0.2",        // IPv4 loopback range
            "127.255.255.255",  // IPv4 loopback upper bound
            "10.0.0.1",         // 10.0.0.0/8 lower
            "10.255.255.255",   // 10.0.0.0/8 upper
            "172.16.0.1",       // 172.16.0.0/12 lower (Docker bridge)
            "172.18.0.3",       // typical Docker Compose address
            "172.31.255.255",   // 172.16.0.0/12 upper
            "192.168.0.1",      // 192.168.0.0/16 lower
            "192.168.1.100",    // typical home network
            "192.168.255.255"   // 192.168.0.0/16 upper
    } )
    void allowsPrivateAndLoopbackAddresses( final String ip ) throws Exception {
        when( request.getRemoteAddr() ).thenReturn( ip );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( response, never() ).setStatus( anyInt() );
    }

    @Test
    void allowsIpv6Loopback() throws Exception {
        when( request.getRemoteAddr() ).thenReturn( "::1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void allowsIpv6LoopbackLongForm() throws Exception {
        when( request.getRemoteAddr() ).thenReturn( "0:0:0:0:0:0:0:1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    // --- Blocked IPs: should return 403 ---

    @ParameterizedTest
    @ValueSource( strings = {
            "8.8.8.8",          // Google DNS
            "1.1.1.1",          // Cloudflare DNS
            "203.0.113.50",     // TEST-NET-3
            "172.32.0.1",       // just outside 172.16.0.0/12
            "172.15.255.255",   // just below 172.16.0.0/12
            "11.0.0.1",         // just outside 10.0.0.0/8
            "192.167.1.1",      // just outside 192.168.0.0/16
            "192.169.0.1"       // just outside 192.168.0.0/16
    } )
    void blocksExternalAddresses( final String ip ) throws Exception {
        when( request.getRemoteAddr() ).thenReturn( ip );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setStatus( 403 );
        assertTrue( body.toString().contains( "Forbidden" ) );
    }

    // --- Edge cases ---

    @Test
    void blocksNullRemoteAddr() throws Exception {
        when( request.getRemoteAddr() ).thenReturn( null );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setStatus( 403 );
    }

    @Test
    void blocksBlankRemoteAddr() throws Exception {
        when( request.getRemoteAddr() ).thenReturn( "   " );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setStatus( 403 );
    }

    @Test
    void blocksGarbageRemoteAddr() throws Exception {
        when( request.getRemoteAddr() ).thenReturn( "not-an-ip" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setStatus( 403 );
    }

    // --- isAllowed unit tests (no mocks needed) ---

    @Test
    void isAllowed_dockerBridgeNetwork() {
        assertTrue( filter.isAllowed( "172.18.0.3" ) );
        assertTrue( filter.isAllowed( "172.17.0.1" ) );
    }

    @Test
    void isAllowed_outsideDockerRange() {
        assertFalse( filter.isAllowed( "172.32.0.1" ) );
        assertFalse( filter.isAllowed( "172.15.0.1" ) );
    }

    @Test
    void isAllowed_nullAndBlank() {
        assertFalse( filter.isAllowed( null ) );
        assertFalse( filter.isAllowed( "" ) );
        assertFalse( filter.isAllowed( "   " ) );
    }

    @Test
    void destroyDoesNotThrow() {
        assertDoesNotThrow( () -> filter.destroy() );
    }

}
