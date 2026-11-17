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

import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class WorkApiKeyFilterTest {

    private ApiKeyService keys;
    private boolean enabled;
    private WorkApiKeyFilter filter;
    private FilterChain chain;
    private HttpServletResponse resp;
    private StringWriter body;
    private final AtomicInteger status = new AtomicInteger( 0 );

    @BeforeEach
    void setUp() throws Exception {
        PostgresTestDb.createDataSource();
        keys = new ApiKeyService( PostgresTestDb.createDataSource() );
        enabled = true;
        filter = new WorkApiKeyFilter( () -> keys, () -> enabled );
        chain = Mockito.mock( FilterChain.class );
        resp = Mockito.mock( HttpServletResponse.class );
        body = new StringWriter();
        Mockito.doReturn( new PrintWriter( body ) ).when( resp ).getWriter();
        Mockito.doAnswer( inv -> {
            status.set( inv.getArgument( 0 ) );
            return null;
        } ).when( resp ).setStatus( Mockito.anyInt() );
    }

    private HttpServletRequest request( final String authorization ) {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( authorization ).when( req ).getHeader( "Authorization" );
        Mockito.doReturn( "10.0.0.1" ).when( req ).getRemoteAddr();
        final java.util.Map< String, Object > attrs = new java.util.HashMap<>();
        Mockito.doAnswer( inv -> attrs.put( inv.getArgument( 0 ), inv.getArgument( 1 ) ) ).when( req )
            .setAttribute( Mockito.anyString(), Mockito.any() );
        Mockito.doAnswer( inv -> attrs.get( ( String ) inv.getArgument( 0 ) ) ).when( req )
            .getAttribute( Mockito.anyString() );
        return req;
    }

    private String token( final ApiKeyService.Scope scope ) {
        return keys.generate( "alice", "k", scope, "admin" ).plaintext();
    }

    private void run( final HttpServletRequest req ) throws Exception {
        filter.doFilter( req, resp, chain );
        resp.getWriter().flush();
    }

    @Test
    void noBearerPassesThroughUnwrapped() throws Exception {
        final HttpServletRequest req = request( null );
        run( req );
        Mockito.verify( chain ).doFilter( req, resp );
        final HttpServletRequest basic = request( "Basic YTpi" );
        run( basic );
        Mockito.verify( chain ).doFilter( basic, resp );
    }

    @Test
    void workKeyWrapsRequestWithRecord() throws Exception {
        final HttpServletRequest req = request( "Bearer " + token( ApiKeyService.Scope.WORK ) );
        run( req );
        final ArgumentCaptor< jakarta.servlet.ServletRequest > passed = ArgumentCaptor.forClass( jakarta.servlet.ServletRequest.class );
        Mockito.verify( chain ).doFilter( passed.capture(), Mockito.eq( resp ) );
        final HttpServletRequest wrapped = ( HttpServletRequest ) passed.getValue();
        final ApiKeyService.Record rec = ( ApiKeyService.Record ) wrapped.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD );
        assertEquals( ApiKeyService.Scope.WORK, rec.scope() );
        assertEquals( "alice", rec.principalLogin() );
        assertNoPrincipal( wrapped );
    }

    @Test
    void mixedCaseSchemeIsStillABearerRequest() throws Exception {
        run( request( "bearer " + token( ApiKeyService.Scope.WORK ) ) );
        Mockito.verify( chain ).doFilter( Mockito.any(), Mockito.eq( resp ) );
        final HttpServletRequest bad = request( "BEARER nope" );
        run( bad );
        assertEquals( 401, status.get() );
    }

    /** The owner of a key must not leak to later filters: no principal, no remote user. */
    private static void assertNoPrincipal( final HttpServletRequest forwarded ) {
        org.junit.jupiter.api.Assertions.assertNull( forwarded.getUserPrincipal() );
        org.junit.jupiter.api.Assertions.assertNull( forwarded.getRemoteUser() );
        assertEquals( ApiKeyPrincipalRequest.AUTH_TYPE, forwarded.getAuthType() );
    }

    @Test
    void allKeyAccepted() throws Exception {
        final HttpServletRequest req = request( "Bearer " + token( ApiKeyService.Scope.ALL ) );
        run( req );
        final ArgumentCaptor< jakarta.servlet.ServletRequest > passed = ArgumentCaptor.forClass( jakarta.servlet.ServletRequest.class );
        Mockito.verify( chain ).doFilter( passed.capture(), Mockito.eq( resp ) );
        assertNoPrincipal( ( HttpServletRequest ) passed.getValue() );
    }

    @Test
    void unknownMalformedAndEmptyKeysAre401() throws Exception {
        for ( final String header : new String[] { "Bearer wkk_not-a-real-token", "Bearer ", "Bearer", "Bearer    " } ) {
            status.set( 0 );
            body.getBuffer().setLength( 0 );
            run( request( header ) );
            assertEquals( 401, status.get(), header );
            assertEquals( "{\"error\":\"Invalid API key\"}", body.toString(), header );
        }
        Mockito.verify( chain, Mockito.never() ).doFilter( Mockito.any(), Mockito.any() );
    }

    @Test
    void revokedKeyIs401() throws Exception {
        final ApiKeyService.Generated g = keys.generate( "alice", "k", ApiKeyService.Scope.WORK, "admin" );
        run( request( "Bearer " + g.plaintext() ) );
        Mockito.verify( chain, Mockito.times( 1 ) ).doFilter( Mockito.any(), Mockito.eq( resp ) );
        keys.revoke( g.record().id(), "admin" );
        status.set( 0 );
        run( request( "Bearer " + g.plaintext() ) );
        assertEquals( 401, status.get(), "revocation must hold on the very next request, not after a cache TTL" );
        assertEquals( "{\"error\":\"Invalid API key\"}", body.toString() );
        Mockito.verify( chain, Mockito.times( 1 ) ).doFilter( Mockito.any(), Mockito.eq( resp ) );
    }

    @Test
    void mcpReadKeyIs403() throws Exception {
        for ( final ApiKeyService.Scope scope : new ApiKeyService.Scope[] { ApiKeyService.Scope.MCP_READ,
            ApiKeyService.Scope.MCP_ADMIN, ApiKeyService.Scope.TOOLS } ) {
            status.set( 0 );
            body.getBuffer().setLength( 0 );
            run( request( "Bearer " + token( scope ) ) );
            assertEquals( 403, status.get(), scope.wire() );
            assertEquals( "{\"error\":\"Key not authorized for work\"}", body.toString(), scope.wire() );
        }
        Mockito.verify( chain, Mockito.never() ).doFilter( Mockito.any(), Mockito.any() );
    }

    @Test
    void disabledFeaturePassesThrough() throws Exception {
        enabled = false;
        final HttpServletRequest req = request( "Bearer wkk_whatever" );
        run( req );
        Mockito.verify( chain ).doFilter( req, resp );
        assertEquals( 0, status.get(), "the resource answers 404; the filter says nothing" );
        assertFalse( body.toString().contains( "error" ) );
        assertSame( req, req );
    }

    @Test
    void noKeyServiceIs401() throws Exception {
        keys = null;
        run( request( "Bearer wkk_whatever" ) );
        assertEquals( 401, status.get() );
    }

    @Test
    void aKeyLookupFailureIs503NotInvalidKeyAndRecovers() throws Exception {
        final com.wikantik.jdbc.testing.FaultInjectingDataSource faulty =
            new com.wikantik.jdbc.testing.FaultInjectingDataSource( PostgresTestDb.createDataSource() );
        keys = new ApiKeyService( faulty );
        final String token = token( ApiKeyService.Scope.WORK );
        faulty.reset();
        faulty.failOn( 1, new IllegalStateException( "test: database blip" ) );

        run( request( "Bearer " + token ) );

        assertEquals( 503, status.get() );
        assertEquals( "{\"error\":\"Service temporarily unavailable\"}", body.toString() );
        Mockito.verify( chain, Mockito.never() ).doFilter( Mockito.any(), Mockito.any() );

        final HttpServletRequest again = request( "Bearer " + token );
        run( again );
        Mockito.verify( chain ).doFilter( Mockito.any( WorkKeyRequest.class ), Mockito.eq( resp ) );
    }

    // ---- agent tokens (phase 3a): verified by the work module, never by the API-key store

    private static final com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef CODEX =
        new com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef( 11, 7 );

    private final java.util.concurrent.atomic.AtomicInteger agentLookups = new java.util.concurrent.atomic.AtomicInteger();
    private boolean agentStoreDown;

    private WorkApiKeyFilter agentFilter( final ApiKeyService keyService, final int perKeyLimit ) {
        final com.wikantik.auth.apikeys.AgentTokenAuth.Verifier verifier = token -> {
            agentLookups.incrementAndGet();
            if ( agentStoreDown ) {
                throw new ApiKeyService.LookupFailedException( new IllegalStateException( "down" ) );
            }
            return "wka_codex".equals( token ) ? Optional.of( CODEX ) : Optional.empty();
        };
        return new WorkApiKeyFilter( () -> keyService, () -> enabled, () -> verifier,
            new com.wikantik.http.ratelimit.SlidingWindowRateLimiter( 0, perKeyLimit ) );
    }

    private HttpServletRequest forwarded() throws Exception {
        final org.mockito.ArgumentCaptor< jakarta.servlet.ServletRequest > seen =
            org.mockito.ArgumentCaptor.forClass( jakarta.servlet.ServletRequest.class );
        Mockito.verify( chain, Mockito.atLeastOnce() ).doFilter( seen.capture(), Mockito.any() );
        return ( HttpServletRequest ) seen.getValue();
    }

    @Test
    void anAgentTokenReachesTheChainWithNoPrincipal() throws Exception {
        final ApiKeyService spy = Mockito.spy( keys );
        filter = agentFilter( spy, 0 );
        run( request( "Bearer wka_codex" ) );
        final HttpServletRequest forwarded = forwarded();
        assertNoPrincipal( forwarded );
        org.junit.jupiter.api.Assertions.assertEquals( CODEX,
            forwarded.getAttribute( com.wikantik.auth.apikeys.AgentTokenAuth.ATTR_AGENT_REF ) );
        org.junit.jupiter.api.Assertions.assertNull(
            forwarded.getAttribute( com.wikantik.auth.apikeys.ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) );
        Mockito.verify( spy, Mockito.never() ).verify( Mockito.anyString() );
    }

    @Test
    void anUnknownAgentTokenIs401() throws Exception {
        filter = agentFilter( keys, 0 );
        run( request( "Bearer wka_nobody" ) );
        org.junit.jupiter.api.Assertions.assertEquals( 401, status.get() );
        org.junit.jupiter.api.Assertions.assertEquals( WorkApiKeyFilter.INVALID_KEY_BODY, body.toString() );
        Mockito.verifyNoInteractions( chain );
    }

    @Test
    void aCredentialStoreFailureIs503NotCached() throws Exception {
        filter = agentFilter( keys, 0 );
        agentStoreDown = true;
        run( request( "Bearer wka_codex" ) );
        org.junit.jupiter.api.Assertions.assertEquals( 503, status.get() );
        org.junit.jupiter.api.Assertions.assertEquals( WorkApiKeyFilter.UNAVAILABLE_BODY, body.toString() );
        Mockito.verifyNoInteractions( chain );
        agentStoreDown = false;
        run( request( "Bearer wka_codex" ) );
        org.junit.jupiter.api.Assertions.assertEquals( CODEX,
            forwarded().getAttribute( com.wikantik.auth.apikeys.AgentTokenAuth.ATTR_AGENT_REF ), "it recovers at once" );
    }

    @Test
    void aPersonalKeyIsNeverLookedUpAsAnAgentToken() throws Exception {
        filter = agentFilter( keys, 0 );
        run( request( "Bearer " + token( ApiKeyService.Scope.WORK ) ) );
        org.junit.jupiter.api.Assertions.assertEquals( 0, agentLookups.get() );
        org.junit.jupiter.api.Assertions.assertNotNull(
            forwarded().getAttribute( com.wikantik.auth.apikeys.ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) );
    }

    @Test
    void withNoWorkVerifierAnAgentTokenIs401() throws Exception {
        filter = new WorkApiKeyFilter( () -> keys, () -> enabled );
        run( request( "Bearer wka_codex" ) );
        org.junit.jupiter.api.Assertions.assertEquals( 401, status.get() );
    }

    @Test
    void theSixthRequestInASecondIs429AndTheLimitIsPerCredential() throws Exception {
        filter = agentFilter( keys, 5 );
        final String personal = token( ApiKeyService.Scope.WORK );
        for ( int i = 0; i < 5; i++ ) {
            run( request( "Bearer wka_codex" ) );
        }
        org.junit.jupiter.api.Assertions.assertEquals( 0, status.get(), "five are let through" );
        run( request( "Bearer " + personal ) );
        org.junit.jupiter.api.Assertions.assertEquals( 0, status.get(), "another credential has its own allowance" );
        final HttpServletResponse limited = Mockito.mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        Mockito.doReturn( new PrintWriter( out ) ).when( limited ).getWriter();
        filter.doFilter( request( "Bearer wka_codex" ), limited, chain );
        limited.getWriter().flush();
        Mockito.verify( limited ).setStatus( 429 );
        Mockito.verify( limited ).setHeader( "Retry-After", "1" );
        org.junit.jupiter.api.Assertions.assertEquals( "{\"error\":\"Rate limit exceeded\"}", out.toString() );
        Mockito.verify( chain, Mockito.times( 6 ) ).doFilter( Mockito.any(), Mockito.any() );
    }

    // ---- how the container builds it

    @Test
    void theContainerBuiltFilterReadsTheEngineAndPassesThroughWhenWorkIsOff() throws Exception {
        final com.wikantik.WikiEngine engine = Mockito.mock( com.wikantik.WikiEngine.class );
        Mockito.when( engine.getWikiProperties() ).thenReturn( new java.util.Properties() );
        Mockito.when( engine.workService() ).thenReturn( null );
        final com.wikantik.api.spi.EngineSPI spi = Mockito.mock( com.wikantik.api.spi.EngineSPI.class );
        Mockito.when( spi.find( Mockito.any( jakarta.servlet.ServletContext.class ), Mockito.isNull() ) ).thenReturn( engine );
        final jakarta.servlet.FilterConfig config = Mockito.mock( jakarta.servlet.FilterConfig.class );
        Mockito.when( config.getServletContext() ).thenReturn( Mockito.mock( jakarta.servlet.ServletContext.class ) );
        final WorkApiKeyFilter built = new WorkApiKeyFilter();
        try ( org.mockito.MockedStatic< com.wikantik.api.spi.Wiki > wiki = Mockito.mockStatic( com.wikantik.api.spi.Wiki.class ) ) {
            wiki.when( com.wikantik.api.spi.Wiki::engine ).thenReturn( spi );
            built.init( config );
        }
        final HttpServletRequest req = request( "Bearer wkt_anything" );
        built.doFilter( req, resp, chain );
        Mockito.verify( chain ).doFilter( req, resp );
    }

    @Test
    void aLookupThatFailedIs503WhetherOrNotItHasACause() throws Exception {
        for ( final Throwable cause : new Throwable[] { null, new java.sql.SQLException( "down" ) } ) {
            final ApiKeyService failing = Mockito.mock( ApiKeyService.class );
            Mockito.when( failing.verify( Mockito.anyString() ) ).thenThrow( new ApiKeyService.LookupFailedException( cause ) );
            status.set( 200 );
            new WorkApiKeyFilter( () -> failing, () -> true ).doFilter( request( "Bearer wkt_abc" ), resp, chain );
            assertEquals( 503, status.get() );
        }
        Mockito.verifyNoInteractions( chain );
    }
}
