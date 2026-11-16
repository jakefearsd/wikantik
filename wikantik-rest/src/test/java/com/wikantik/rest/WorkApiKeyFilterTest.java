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
        PostgresTestDb.truncate( "api_keys" );
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
        assertTrue( wrapped instanceof ApiKeyPrincipalRequest );
        final ApiKeyService.Record rec = ( ApiKeyService.Record ) wrapped.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD );
        assertEquals( ApiKeyService.Scope.WORK, rec.scope() );
        assertEquals( "alice", rec.principalLogin() );
    }

    @Test
    void mixedCaseSchemeIsStillABearerRequest() throws Exception {
        run( request( "bearer " + token( ApiKeyService.Scope.WORK ) ) );
        Mockito.verify( chain ).doFilter( Mockito.any(), Mockito.eq( resp ) );
        final HttpServletRequest bad = request( "BEARER nope" );
        run( bad );
        assertEquals( 401, status.get() );
    }

    @Test
    void allKeyAccepted() throws Exception {
        run( request( "Bearer " + token( ApiKeyService.Scope.ALL ) ) );
        Mockito.verify( chain ).doFilter( Mockito.any(), Mockito.eq( resp ) );
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
}
