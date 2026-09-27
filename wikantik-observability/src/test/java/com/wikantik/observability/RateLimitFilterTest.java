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
        // In a real container the servlet path is the DECODED, normalized path the
        // container mapped on; for these tests the (unencoded) uri is that path.
        return request( uri, uri, null, ip );
    }

    /**
     * Full control over the request's raw URI vs. its container-decoded servlet
     * path — needed to prove that classification uses the decoded path and cannot
     * be dodged by percent-encoding the raw URI.
     */
    private static HttpServletRequest request( final String rawUri, final String servletPath,
                                               final String pathInfo, final String ip ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRequestURI() ).thenReturn( rawUri );
        when( req.getServletPath() ).thenReturn( servletPath );
        when( req.getPathInfo() ).thenReturn( pathInfo );
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

    /**
     * SECURITY REGRESSION: the expensive tier keyed on the raw request URI, which
     * Tomcat leaves percent-encoded, while servlet mapping runs on the decoded path.
     * So GET /%73parql reached the SPARQL servlet but classified as the cheap tier.
     * Classification now uses the container-decoded servlet path.
     */
    @Test
    void percentEncodedExpensivePathStillHitsExpensiveTier() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 100, 2, 0, List.of(), ticker ) );
        f.init( null );

        // Raw URI "/%73parql" (s -> %73) but the container mapped it to the /sparql servlet.
        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/%73parql", "/sparql", null, "8.8.8.8" ), response(), chain );
        f.doFilter( request( "/%73parql", "/sparql", null, "8.8.8.8" ), response(), chain );

        final HttpServletResponse blocked = response();
        final FilterChain blockedChain = mock( FilterChain.class );
        f.doFilter( request( "/%73parql", "/sparql", null, "8.8.8.8" ), blocked, blockedChain );
        verify( blockedChain, never() ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
        verify( blocked ).setStatus( 429 );
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

    // ------------------------------------------------------------------ Config parsing (env-free paths)

    @Test
    void configOfIgnoresMalformedExemptCidrButKeepsValidOnes() throws Exception {
        // parseCidrs must skip an unresolvable entry (logging a warning) rather than failing
        // the whole config, and still honor the entries that DO parse.
        final RateLimitFilter.Config cfg = RateLimitFilter.Config.of( 100, 1, 0,
                List.of( "/api/bundle" ), List.of( "not a cidr at all", "192.168.0.0/16" ), ticker );
        assertEquals( 1, cfg.exemptRanges().size(), "the malformed entry must be dropped, the valid one kept" );

        final RateLimitFilter f = new RateLimitFilter( cfg );
        f.init( null );
        final FilterChain chain = mock( FilterChain.class );
        for ( int i = 0; i < 4; i++ ) {
            f.doFilter( request( "/api/bundle", "192.168.0.44" ), response(), chain );
        }
        verify( chain, times( 4 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
    }

    @Test
    void fromEnvironmentUsesDocumentedDefaultsWhenUnset() {
        // No WIKANTIK_RATELIMIT_* vars are set in the test/CI environment; fromEnvironment()
        // must fall back to the documented DEFAULT_* constants rather than throwing or nulling out.
        final RateLimitFilter.Config cfg = RateLimitFilter.Config.fromEnvironment();
        assertEquals( RateLimitFilter.DEFAULT_DEFAULT_PERCLIENT, cfg.defaultPerClient() );
        assertEquals( RateLimitFilter.DEFAULT_EXPENSIVE_PERCLIENT, cfg.expensivePerClient() );
        assertEquals( RateLimitFilter.DEFAULT_EXPENSIVE_GLOBAL, cfg.expensiveGlobal() );
        assertEquals( List.of( "/api/bundle", "/api/search", "/sparql" ), cfg.expensivePathPrefixes() );
        assertEquals( List.of(), cfg.exemptRanges() );
    }

    @Test
    void noArgConstructorResolvesConfigFromEnvironmentOnInit() throws Exception {
        // The container constructor (no pre-built Config) must resolve one from the
        // environment during init() rather than NPE-ing on first request.
        final RateLimitFilter f = new RateLimitFilter();
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/health", "8.8.8.8" ), response(), chain );
        verify( chain, times( 1 ) ).doFilter( org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any() );
    }

    // ------------------------------------------------------------------ malformed/degraded request inputs

    @Test
    void malformedRemoteAddressIsTreatedAsNonExemptWithoutThrowing() throws Exception {
        final RateLimitFilter f = new RateLimitFilter( config( 100, 1, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        final String badIp = "not a valid ip/host";
        f.doFilter( request( "/api/bundle", badIp ), response(), chain );
        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/bundle", badIp ), blocked, chain );
        verify( blocked ).setStatus( 429 );
    }

    @Test
    void nullServletPathFallsBackToRawUriForClassification() throws Exception {
        // Defensive fallback: when the container hasn't populated getServletPath(), classify
        // on the raw request URI instead of silently treating everything as the cheap tier.
        final RateLimitFilter f = new RateLimitFilter( config( 100, 1, 0, List.of(), ticker ) );
        f.init( null );

        final FilterChain chain = mock( FilterChain.class );
        f.doFilter( request( "/api/bundle", null, null, "3.3.3.3" ), response(), chain );
        final HttpServletResponse blocked = response();
        f.doFilter( request( "/api/bundle", null, null, "3.3.3.3" ), blocked, chain );
        verify( blocked ).setStatus( 429 );
    }
}
