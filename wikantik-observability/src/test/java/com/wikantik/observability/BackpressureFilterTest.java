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

import com.wikantik.api.observability.MeterRegistryHolder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackpressureFilterTest {

    private SimpleMeterRegistry reg;

    @BeforeEach
    void setUpRegistry() {
        reg = new SimpleMeterRegistry();
        MeterRegistryHolder.set( reg );
        // Clear any leftover system property from prior tests so init resolves cleanly.
        System.clearProperty( BackpressureFilter.ENV_MAX_INFLIGHT );
    }

    @AfterEach
    void tearDown() {
        MeterRegistryHolder.set( null );
        System.clearProperty( BackpressureFilter.ENV_MAX_INFLIGHT );
    }

    @Test
    void allowsRequest_underCapacity_andReleasesPermit() throws Exception {
        System.setProperty( BackpressureFilter.ENV_MAX_INFLIGHT, "5" );
        final BackpressureFilter f = new BackpressureFilter();
        f.init( null );

        final HttpServletRequest req = mockHttpRequest( "/api/pages/Foo" );
        final HttpServletResponse res = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        f.doFilter( req, res, chain );

        verify( chain ).doFilter( req, res );
        verify( res, never() ).setStatus( eq( HttpServletResponse.SC_SERVICE_UNAVAILABLE ) );
        // Permit released — inflight back to zero.
        assertEquals( 0, f.currentInflight() );
    }

    @Test
    void rejectsRequest_at_capacity_with503_andRetryAfter_andIncrementsCounter() throws Exception {
        // Capacity = 1. Hold the permit on a parked chain so the second
        // request can't get one.
        System.setProperty( BackpressureFilter.ENV_MAX_INFLIGHT, "1" );
        final BackpressureFilter f = new BackpressureFilter();
        f.init( null );

        final CountDownLatch firstHolding = new CountDownLatch( 1 );
        final CountDownLatch releaseFirst = new CountDownLatch( 1 );

        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // First request: takes the permit, parks, holding it.
            exec.submit( () -> {
                try {
                    final HttpServletRequest r1 = mockHttpRequest( "/api/pages/Foo" );
                    final HttpServletResponse rs1 = mock( HttpServletResponse.class );
                    final FilterChain c1 = ( request, response ) -> {
                        firstHolding.countDown();
                        try { releaseFirst.await( 5, TimeUnit.SECONDS ); }
                        catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); }
                    };
                    f.doFilter( r1, rs1, c1 );
                } catch ( Exception ignored ) {}
            } );

            assertTrue( firstHolding.await( 5, TimeUnit.SECONDS ),
                "First request should have taken the permit before the second tries" );
            assertEquals( 1, f.currentInflight() );

            // Second request: capacity full, must 503.
            final HttpServletRequest r2 = mockHttpRequest( "/api/pages/Bar" );
            final HttpServletResponse rs2 = mock( HttpServletResponse.class );
            final ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            final ServletOutputStream sos = new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener( WriteListener l ) {}
                @Override public void write( int b ) { bodyBuf.write( b ); }
            };
            when( rs2.getOutputStream() ).thenReturn( sos );
            final FilterChain c2 = mock( FilterChain.class );

            f.doFilter( r2, rs2, c2 );

            verify( rs2 ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
            verify( rs2 ).setHeader( "Retry-After", "1" );
            verify( c2, never() ).doFilter( r2, rs2 );

            final Counter rejected =
                reg.find( "wikantik_backpressure.rejected_total" ).counter();
            assertNotNull( rejected, "rejected counter should be registered" );
            assertEquals( 1.0, rejected.count(), 0.0001 );

            // Response body is valid JSON containing the expected error tag.
            final String body = bodyBuf.toString( "UTF-8" );
            assertTrue( body.contains( "service_unavailable" ),
                "503 body should announce the rejection reason; got: " + body );
        } finally {
            releaseFirst.countDown();
            exec.shutdown();
            exec.awaitTermination( 5, TimeUnit.SECONDS );
        }
    }

    @Test
    void exemptPaths_bypassSemaphore_andDoNotCountAgainstInflight() throws Exception {
        // Cap at 1; verify exempt paths still flow even when the cap would
        // reject them, AND that they don't consume a permit.
        System.setProperty( BackpressureFilter.ENV_MAX_INFLIGHT, "1" );
        final BackpressureFilter f = new BackpressureFilter();
        f.init( null );

        for ( final String exempt : new String[] {
                "/api/health", "/api/health/db", "/metrics", "/metrics/prometheus", "/favicon.ico" } ) {
            final HttpServletRequest req = mockHttpRequest( exempt );
            final HttpServletResponse res = mock( HttpServletResponse.class );
            final FilterChain chain = mock( FilterChain.class );
            f.doFilter( req, res, chain );
            verify( chain ).doFilter( req, res );
            verify( res, never() ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
            assertEquals( 0, f.currentInflight(),
                exempt + " must not consume a permit" );
        }

        final Counter rejected =
            reg.find( "wikantik_backpressure.rejected_total" ).counter();
        if ( rejected != null ) {
            assertEquals( 0.0, rejected.count(), 0.0001,
                "exempt paths must NOT increment the rejection counter" );
        }
    }

    @Test
    void inflightGauge_tracksLivePermitHolders() throws Exception {
        System.setProperty( BackpressureFilter.ENV_MAX_INFLIGHT, "3" );
        final BackpressureFilter f = new BackpressureFilter();
        f.init( null );

        final Gauge inflight = reg.find( "wikantik_backpressure.inflight" ).gauge();
        assertNotNull( inflight );
        assertEquals( 0.0, inflight.value(), 0.0001 );

        final CountDownLatch holding = new CountDownLatch( 2 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final ExecutorService exec = Executors.newFixedThreadPool( 2 );
        try {
            for ( int i = 0; i < 2; i++ ) {
                exec.submit( () -> {
                    try {
                        final HttpServletRequest r = mockHttpRequest( "/api/pages/Foo" );
                        final HttpServletResponse rs = mock( HttpServletResponse.class );
                        final FilterChain c = ( req, res ) -> {
                            holding.countDown();
                            try { release.await( 5, TimeUnit.SECONDS ); }
                            catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); }
                        };
                        f.doFilter( r, rs, c );
                    } catch ( Exception ignored ) {}
                } );
            }
            assertTrue( holding.await( 5, TimeUnit.SECONDS ) );
            // Two permits in flight.
            assertEquals( 2.0, inflight.value(), 0.0001 );
        } finally {
            release.countDown();
            exec.shutdown();
            exec.awaitTermination( 5, TimeUnit.SECONDS );
        }
        // Permits released — gauge back to 0.
        assertEquals( 0.0, inflight.value(), 0.0001 );
    }

    @Test
    void disabled_whenMaxInflightSetToZero() throws Exception {
        System.setProperty( BackpressureFilter.ENV_MAX_INFLIGHT, "0" );
        final BackpressureFilter f = new BackpressureFilter();
        f.init( null );

        // 100 sequential calls — none rejected.
        final AtomicInteger chainCalls = new AtomicInteger();
        for ( int i = 0; i < 100; i++ ) {
            final HttpServletRequest req = mockHttpRequest( "/api/pages/Foo" );
            final HttpServletResponse res = mock( HttpServletResponse.class );
            final FilterChain chain = ( q, s ) -> chainCalls.incrementAndGet();
            f.doFilter( req, res, chain );
        }
        assertEquals( 100, chainCalls.get(), "Disabled filter must pass everything through" );
    }

    @Test
    void exemptPredicate_unitChecks() {
        assertTrue( BackpressureFilter.isExempt( "/api/health" ) );
        assertTrue( BackpressureFilter.isExempt( "/api/health/db" ) );
        assertTrue( BackpressureFilter.isExempt( "/metrics" ) );
        assertTrue( BackpressureFilter.isExempt( "/favicon.ico" ) );
        // Reasonable non-exempt paths.
        assertEquals( false, BackpressureFilter.isExempt( "/api/pages/Main" ) );
        assertEquals( false, BackpressureFilter.isExempt( "/api/search" ) );
        assertEquals( false, BackpressureFilter.isExempt( "/" ) );
        assertEquals( false, BackpressureFilter.isExempt( null ) );
    }

    private static HttpServletRequest mockHttpRequest( final String uri ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRequestURI() ).thenReturn( uri );
        return req;
    }
}
