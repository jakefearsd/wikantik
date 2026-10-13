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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiEngine;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Audit-trail coverage for the admin API-key surface. Every admin revocation — single
 * ({@code DELETE /admin/apikeys/{id}}) and bulk ({@code POST /admin/apikeys/bulk-action}) — must
 * write the same {@code apikey.revoke} entry the self-service surface writes, one per revoked key,
 * with the admin as actor.
 */
class AdminApiKeysResourceAuditTest {

    private static TestEngine engine;

    private AdminApiKeysResource servlet;
    private ApiKeyService keys;
    private AuditService audit;

    @BeforeAll
    static void startEngine() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        keys = mock( ApiKeyService.class );
        ApiKeyServiceHolder.setForTesting( keys );
        audit = mock( AuditService.class );
        final WikiEngine wikiEngine = mock( WikiEngine.class );
        when( wikiEngine.getAuditService() ).thenReturn( audit );
        // Fresh servlet per test so swapping its engine for the audit-capturing mock cannot leak.
        servlet = RestTestSupport.initServlet( () -> new AdminApiKeysResource() {
            @Override
            boolean principalExists( final String login ) {
                return "janne".equals( login );
            }
        }, engine );
        servlet.setEngine( wikiEngine );
    }

    @AfterEach
    void tearDown() {
        ApiKeyServiceHolder.setForTesting( null );
    }

    private static ApiKeyService.Record key( final int id, final String owner, final String label ) {
        return new ApiKeyService.Record( id, "hash" + id, owner, label, ApiKeyService.Scope.TOOLS,
                Instant.parse( "2026-04-01T10:00:00Z" ), "admin", null, null, null, false );
    }

    private List< AuditEntry > recorded( final int expected ) {
        final ArgumentCaptor< AuditEntry > captor = ArgumentCaptor.forClass( AuditEntry.class );
        verify( audit, times( expected ) ).record( captor.capture() );
        return captor.getAllValues();
    }

    private static void assertRevokeEntry( final AuditEntry e, final String keyId, final String label ) {
        assertEquals( AuditCategory.ADMIN, e.category() );
        assertEquals( "apikey.revoke", e.eventType() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
        assertEquals( "admin", e.actorPrincipal(), "the admin who revoked is the actor" );
        assertEquals( "user", e.actorType() );
        assertEquals( "apikey", e.targetType() );
        assertEquals( keyId, e.targetId() );
        assertEquals( label, e.targetLabel() );
    }

    // ----- DELETE /admin/apikeys/{id} -----

    @Test
    void singleRevokeRecordsApikeyRevokeEntry() throws Exception {
        when( keys.findById( 42 ) ).thenReturn( Optional.of( key( 42, "alice", "laptop" ) ) );
        when( keys.revoke( 42, "admin" ) ).thenReturn( true );

        delete( "42" );

        assertRevokeEntry( recorded( 1 ).get( 0 ), "42", "laptop" );
    }

    @Test
    void singleRevokeOfUnlabelledKeyUsesIdAsLabel() throws Exception {
        when( keys.findById( 7 ) ).thenReturn( Optional.of( key( 7, "alice", null ) ) );
        when( keys.revoke( 7, "admin" ) ).thenReturn( true );

        delete( "7" );

        assertRevokeEntry( recorded( 1 ).get( 0 ), "7", "7" );
    }

    @Test
    void singleRevokeOfMissingKeyRecordsNothing() throws Exception {
        when( keys.revoke( 99, "admin" ) ).thenReturn( false );

        delete( "99" );

        verifyNoInteractions( audit );
    }

    // ----- POST /admin/apikeys/bulk-action -----

    @Test
    void bulkRevokeRecordsOneEntryPerRevokedKey() throws Exception {
        when( keys.findById( 10 ) ).thenReturn( Optional.of( key( 10, "alice", "ci" ) ) );
        when( keys.findById( 11 ) ).thenReturn( Optional.of( key( 11, "bob", "laptop" ) ) );
        when( keys.revoke( 10, "admin" ) ).thenReturn( true );
        when( keys.revoke( 11, "admin" ) ).thenReturn( true );

        bulkRevoke( "10", "11" );

        final List< AuditEntry > entries = recorded( 2 );
        assertRevokeEntry( entries.get( 0 ), "10", "ci" );
        assertRevokeEntry( entries.get( 1 ), "11", "laptop" );
    }

    @Test
    void bulkRevokeRecordsOnlyTheKeysThatWereActuallyRevoked() throws Exception {
        when( keys.findById( 10 ) ).thenReturn( Optional.of( key( 10, "alice", "ci" ) ) );
        when( keys.revoke( 10, "admin" ) ).thenReturn( true );
        when( keys.revoke( 11, "admin" ) ).thenReturn( false );             // already revoked / missing
        when( keys.revoke( 12, "admin" ) ).thenThrow( new RuntimeException( "db unavailable" ) );

        bulkRevoke( "10", "11", "12", "not-a-number" );

        final List< AuditEntry > entries = recorded( 1 );
        assertRevokeEntry( entries.get( 0 ), "10", "ci" );
    }

    // ----- POST /admin/apikeys (issue) — regression guard for the shared audit helper -----

    @Test
    void issueStillRecordsApikeyIssueEntry() throws Exception {
        when( keys.generate( any(), any(), any( ApiKeyService.Scope.class ), any() ) )
                .thenReturn( new ApiKeyService.Generated( "wkk_t", key( 5, "janne", "deploy" ) ) );
        final JsonObject body = new JsonObject();
        body.addProperty( "principalLogin", "janne" );
        body.addProperty( "label", "deploy" );
        body.addProperty( "scope", "tools" );

        final HttpServletRequest request = request( "/admin/apikeys", null );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        servlet.doPost( request, response() );

        final AuditEntry e = recorded( 1 ).get( 0 );
        assertEquals( "apikey.issue", e.eventType() );
        assertEquals( "admin", e.actorPrincipal() );
        assertEquals( "apikey", e.targetType() );
        assertEquals( "5", e.targetId() );
        assertEquals( "deploy", e.targetLabel() );
    }

    @Test
    void failedIssueRecordsNothing() throws Exception {
        final JsonObject body = new JsonObject();   // no principalLogin -> 400
        final HttpServletRequest request = request( "/admin/apikeys", null );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        servlet.doPost( request, response() );

        verify( audit, never() ).record( any() );
    }

    // ----- helpers -----

    private void delete( final String id ) throws Exception {
        servlet.doDelete( request( "/admin/apikeys/" + id, "/" + id ), response() );
    }

    private void bulkRevoke( final String... ids ) throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "action", "revoke" );
        final JsonArray arr = new JsonArray();
        for ( final String id : ids ) {
            arr.add( id );
        }
        body.add( "ids", arr );
        final HttpServletRequest request = request( "/admin/apikeys/bulk-action", "/bulk-action" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        servlet.doPost( request, response() );
    }

    private static HttpServletRequest request( final String uri, final String pathInfo ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( uri );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        final Principal admin = mock( Principal.class );
        when( admin.getName() ).thenReturn( "admin" );
        Mockito.doReturn( admin ).when( request ).getUserPrincipal();
        return request;
    }

    private static HttpServletResponse response() throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
        return response;
    }
}
