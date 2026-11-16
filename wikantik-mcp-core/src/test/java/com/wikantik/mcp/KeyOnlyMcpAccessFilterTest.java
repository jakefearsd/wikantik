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
package com.wikantik.mcp;

import com.github.benmanes.caffeine.cache.Ticker;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.http.ratelimit.SlidingWindowRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The key-only MCP access filter: its requests carry no user principal (P30), and one MCP session serves one key
 * (h11): the key that opened it, read from the {@code Mcp-Session-Id} header the transport writes.
 */
class KeyOnlyMcpAccessFilterTest {

    private ApiKeyService keys;
    private final AtomicLong nanos = new AtomicLong();
    private KeyOnlyMcpAccessFilter filter;

    private static ApiKeyService.Record key( final int id ) {
        return new ApiKeyService.Record( id, "h" + id, "owner" + id, "k", ApiKeyService.Scope.WORK, Instant.now(),
            "admin", null, null, null, false );
    }

    @BeforeEach
    void setUp() {
        keys = mock( ApiKeyService.class );
        for ( final int id : new int[] { 1, 2, 3 } ) {
            when( keys.verify( "tok" + id ) ).thenReturn( Optional.of( key( id ) ) );
        }
        filter = filter( 100 );
    }

    private KeyOnlyMcpAccessFilter filter( final long max ) {
        final Ticker ticker = nanos::get;
        return new KeyOnlyMcpAccessFilter( new SlidingWindowRateLimiter( 0, 0 ), keys, ApiKeyService.Scope.WORK,
            ticker, max );
    }

    private static HttpServletRequest request( final String method, final String token, final String session ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final Map< String, Object > attrs = new HashMap<>();
        doAnswer( inv -> attrs.put( inv.getArgument( 0 ), inv.getArgument( 1 ) ) ).when( req )
            .setAttribute( anyString(), any() );
        when( req.getAttribute( anyString() ) ).thenAnswer( inv -> attrs.get( ( String ) inv.getArgument( 0 ) ) );
        when( req.getMethod() ).thenReturn( method );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.9" );
        when( req.getHeader( "Authorization" ) ).thenReturn( token == null ? null : "Bearer " + token );
        when( req.getHeader( KeyOnlyMcpAccessFilter.SESSION_HEADER ) ).thenReturn( session );
        return req;
    }

    /** Runs one request through the filter; the transport stand-in assigns {@code assign} as the session id. */
    private int run( final String method, final String token, final String session, final String assign )
            throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        final int[] status = { 200 };
        doAnswer( inv -> status[ 0 ] = inv.getArgument( 0 ) ).when( resp ).setStatus( org.mockito.ArgumentMatchers.anyInt() );
        final FilterChain transport = ( rq, rs ) -> {
            if ( assign != null ) {
                ( ( HttpServletResponse ) rs ).setHeader( KeyOnlyMcpAccessFilter.SESSION_HEADER, assign );
            }
        };
        filter.doFilter( request( method, token, session ), resp, transport );
        return status[ 0 ];
    }

    // ---- P30: no user principal

    @Test
    void anAllowedRequestCarriesTheKeyRecordAndNoPrincipal() {
        final McpAccessFilter.Outcome outcome = filter.authorize( request( "POST", "tok1", null ) );
        final HttpServletRequest forwarded = assertInstanceOf( McpAccessFilter.Outcome.Allowed.class, outcome )
            .effectiveReq();
        assertNull( forwarded.getUserPrincipal(), "the key's owner is never the request's principal" );
        assertNull( forwarded.getRemoteUser() );
        assertTrue( !forwarded.isUserInRole( "Admin" ) );
        assertEquals( ApiKeyPrincipalRequest.AUTH_TYPE, forwarded.getAuthType(), "stateless: no session is made" );
        assertEquals( 1, ( ( ApiKeyService.Record ) forwarded.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) )
            .id() );
    }

    @Test
    void theTransportSeesTheNoPrincipalRequest() throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( request( "POST", "tok1", null ), resp, chain );
        final ArgumentCaptor< jakarta.servlet.ServletRequest > seen = ArgumentCaptor.forClass( jakarta.servlet.ServletRequest.class );
        verify( chain ).doFilter( seen.capture(), any() );
        assertNull( ( ( HttpServletRequest ) seen.getValue() ).getUserPrincipal() );
    }

    @Test
    void stillKeyOnlyAKeylessRequestIsRefused() {
        final McpAccessFilter.Outcome outcome = filter.authorize( request( "POST", null, null ) );
        assertEquals( 403, assertInstanceOf( McpAccessFilter.Outcome.Denied.class, outcome ).status() );
    }

    // ---- h11: one session, one key

    @Test
    void aSecondKeyOnASessionIsRefusedAndTheFirstKeepsIt() throws Exception {
        assertEquals( 200, run( "POST", "tok1", null, "s-1" ), "initialize: the transport assigns s-1" );
        assertEquals( 403, run( "POST", "tok2", "s-1", null ), "another key on s-1" );
        assertEquals( 200, run( "POST", "tok1", "s-1", null ), "the key that opened it" );
        assertEquals( 200, run( "POST", "tok2", null, "s-2" ), "the second key opens its own session" );
        assertEquals( 200, run( "POST", "tok2", "s-2", null ) );
        assertEquals( 403, run( "POST", "tok1", "s-2", null ) );
    }

    @Test
    void theRefusalNeverReachesTheTransport() throws Exception {
        run( "POST", "tok1", null, "s-1" );
        final FilterChain chain = mock( FilterChain.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        filter.doFilter( request( "POST", "tok2", "s-1" ), resp, chain );
        verify( chain, never() ).doFilter( any(), any() );
        verify( resp ).setStatus( 403 );
    }

    @Test
    void deleteDropsTheBinding() throws Exception {
        run( "POST", "tok1", null, "s-1" );
        assertEquals( 1, filter.boundSessions() );
        assertEquals( 200, run( "DELETE", "tok1", "s-1", null ) );
        assertEquals( 0, filter.boundSessions() );
    }

    @Test
    void anIdleBindingIsForgottenAndTheNextKeyBindsTheId() throws Exception {
        run( "POST", "tok1", null, "s-1" );
        nanos.addAndGet( KeyOnlyMcpAccessFilter.IDLE.plus( Duration.ofMinutes( 1 ) ).toNanos() );
        assertEquals( 0, filter.boundSessions() );
        assertEquals( 200, run( "POST", "tok2", "s-1", null ), "unbound: the first key to use it binds it" );
        assertEquals( 403, run( "POST", "tok1", "s-1", null ) );
    }

    @Test
    void bindingsAreSizeCapped() throws Exception {
        filter = filter( 2 );
        for ( int i = 0; i < 5; i++ ) {
            run( "POST", "tok1", null, "s-" + i );
        }
        assertTrue( filter.boundSessions() <= 2, "at most the cap: " + filter.boundSessions() );
    }
}
