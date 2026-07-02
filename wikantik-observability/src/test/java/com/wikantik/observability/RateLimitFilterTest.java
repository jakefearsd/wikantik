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

import com.github.benmanes.caffeine.cache.Ticker;
import com.wikantik.api.observability.MeterRegistryHolder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitFilter}: tier resolution, 429 semantics,
 * loopback/CIDR exemption, health-path skip, global expensive cap, and the
 * rejection metric. The wire-level 429 path cannot be integration-tested —
 * IT clients connect from loopback, which is exempt by design — so this
 * servlet-contract coverage is the authoritative test of the reject path.
 */
class RateLimitFilterTest {

    /** Mutable fake clock for deterministic sliding windows. */
    private final AtomicLong clock = new AtomicLong( 1_000_000_000L );
    private final Ticker ticker = clock::get;

    @AfterEach
    void clearRegistry() {
        MeterRegistryHolder.set( null );
    }

    private static RateLimitFilter.Config config( final int defaultPerClient, final int expensivePerClient,
                                                  final int expensiveGlobal, final List< String > exemptCidrs,
                                                  final Ticker ticker ) {
        return RateLimitFilter.Config.of( defaultPerClient, expensivePerClient, expensiveGlobal,
                List.of( "/api/bundle", "/api/search", "/sparql" ), exemptCidrs, ticker );
    }

    private static HttpServletRequest request( final String uri, final String ip ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRequestURI() ).thenReturn( uri );
        when( req.getRemoteAddr() ).thenReturn( ip );
        return req;
    }

    private static HttpServletResponse response() throws Exception {
        final HttpServletResponse res = mock( HttpServletResponse.class );
        when( res.getOutputStream() ).thenReturn( mock( ServletOutputStream.class ) );
        return res;
    }

    @Test
    void expensivePathRejectsOverPerClientLimit() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 100, 2, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        final HttpServletResponse ok = response();
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), ok, chain );
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), ok, chain );
        verify( chain, times( 2 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );

        final FilterChain blockedChain = mock( FilterChain.class );
        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), blocked, blockedChain );
        verify( blockedChain, never() ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
        verify( blocked ).setStatus( 429 );
        verify( blocked ).setHeader( "Retry-After", "1" );
    }

    @Test
    void defaultTierAppliesToGeneralApiPaths() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 2, 100, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/pages/Main", "8.8.8.8" ), response(), chain );
        f.doFilter( request( "/api/pages/Main", "8.8.8.8" ), response(), chain );
        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/pages/Main", "8.8.8.8" ), blocked, chain );

        verify( chain, times( 2 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
        verify( blocked ).setStatus( 429 );
    }

    @Test
    void searchAndSparqlResolveToExpensiveTier() throws Exception {
        // default tier is generous; only the expensive limit (1/s) can bind.
        final RateLimitFilter f = new RateLimitFilter( config( 100, 1, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/search", "8.8.8.8" ), response(), chain );
        final HttpServletResponse blockedSearch = response();
        f.doFilter( request( "/api/search", "8.8.8.8" ), blockedSearch, chain );
        verify( blockedSearch ).setStatus( 429 );

        f.doFilter( request( "/sparql", "9.9.9.9" ), response(), chain );
        final HttpServletResponse blockedSparql = response();
        f.doFilter( request( "/sparql", "9.9.9.9" ), blockedSparql, chain );
        verify( blockedSparql ).setStatus( 429 );
    }

    @Test
    void loopbackIsExemptIpv4AndIpv6() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 1, 1, 1, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        for ( int i = 0; i < 5; i++ ) {
            f.doFilter( request( "/api/bundle", "127.0.0.1" ), response(), chain );
            f.doFilter( request( "/api/bundle", "0:0:0:0:0:0:0:1" ), response(), chain );
        }
        verify( chain, times( 10 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
    }

    @Test
    void configuredCidrIsExemptOthersAreNot() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 100, 1, 0, List.of( "192.168.0.0/16" ), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        for ( int i = 0; i < 4; i++ ) {
            f.doFilter( request( "/api/bundle", "192.168.0.44" ), response(), chain );
        }
        verify( chain, times( 4 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );

        f.doFilter( request( "/api/bundle", "8.8.8.8" ), response(), chain );
        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), blocked, chain );
        verify( blocked ).setStatus( 429 );
    }

    @Test
    void healthPathIsAlwaysExempt() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 1, 1, 1, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        for ( int i = 0; i < 5; i++ ) {
            f.doFilter( request( "/api/health", "10.20.30.40" ), response(), chain );
        }
        verify( chain, times( 5 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
    }

    @Test
    void globalExpensiveCapBindsAcrossClients() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 100, 10, 2, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/bundle", "1.1.1.1" ), response(), chain );
        f.doFilter( request( "/api/bundle", "2.2.2.2" ), response(), chain );
        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/bundle", "3.3.3.3" ), blocked, chain );

        verify( chain, times( 2 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
        verify( blocked ).setStatus( 429 );
    }

    @Test
    void distinctClientsGetIndependentDefaultBuckets() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 1, 100, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/pages/A", "1.1.1.1" ), response(), chain );
        f.doFilter( request( "/api/pages/A", "2.2.2.2" ), response(), chain );
        verify( chain, times( 2 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );

        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/pages/A", "1.1.1.1" ), blocked, chain );
        verify( blocked ).setStatus( 429 );
    }

    @Test
    void windowSlidesAndClientRecovers() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 100, 1, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), response(), chain );
        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), blocked, chain );
        verify( blocked ).setStatus( 429 );

        clock.addAndGet( 1_100_000_000L ); // advance past the 1s window
        final FilterChain freshChain = mock( FilterChain.class );
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), response(), freshChain );
        verify( freshChain, atLeastOnce() ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
    }

    @Test
    void rejectionIncrementsTierTaggedCounter() throws Exception {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MeterRegistryHolder.set( reg );
        final RateLimitFilter f = new RateLimitFilter( config( 100, 1, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), response(), chain );
        f.doFilter( request( "/api/bundle", "8.8.8.8" ), response(), chain );

        assertEquals( 1.0,
                reg.get( "wikantik_ratelimit.rejected_total" ).tag( "tier", "expensive" ).counter().count(),
                0.0001 );
    }

    @Test
    void zeroLimitsDisableTheFilterEntirely() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 0, 0, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        for ( int i = 0; i < 50; i++ ) {
            f.doFilter( request( "/api/bundle", "8.8.8.8" ), response(), chain );
        }
        verify( chain, times( 50 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
    }
}
