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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.DefaultAuthorizationManager;
import com.wikantik.auth.DatabasePolicy;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Pins the audit records written by the three policy-grant mutations.
 *
 * <p>{@code AdminPolicyResource} hand-rolled the audit-writing block three times (a shared
 * {@code recordGrantAudit} for create/update, plus a third inline copy in the delete path
 * that bypassed it). Under that duplication the three operations drifted apart on what
 * {@code targetId} even means: create recorded the composite descriptor
 * {@code role:Admin:wiki:*} while update and delete recorded the numeric grant id. An
 * operator asking the audit log "what happened to grant 7" therefore found the update and
 * the delete but never the create — the record existed, filed under a different key.
 *
 * <p>These tests fix {@code targetId} as the grant id for every operation, so the three
 * events for one grant sort together, and keep the human-readable descriptor in
 * {@code targetLabel} where it belongs.
 */
class AdminPolicyAuditTest {

    private Engine engine;
    private AdminPolicyResource servlet;
    private AuditService auditService;
    private DatabasePolicy dbPolicy;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        dbPolicy = Mockito.mock( DatabasePolicy.class );
        Mockito.when( dbPolicy.getTableName() ).thenReturn( "policy_grants" );
        // The servlet delegates every mutation to DatabasePolicy; grant 7 exists and each write hits one row.
        Mockito.when( dbPolicy.insertGrant( Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any() ) ).thenReturn( 7 );
        Mockito.when( dbPolicy.updateGrant( Mockito.eq( 7 ), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any() ) ).thenReturn( 1 );
        Mockito.when( dbPolicy.deleteGrant( 7 ) ).thenReturn( 1 );

        final DefaultAuthorizationManager authMgr = Mockito.mock( DefaultAuthorizationManager.class );
        Mockito.when( authMgr.getDatabasePolicy() ).thenReturn( dbPolicy );
        ( (WikiEngine) engine ).setManager( AuthorizationManager.class, authMgr );

        // WikiEngine wires its AuditService during initialize() and exposes only a getter,
        // so a capturing stub goes in by reflection. Nothing else observes audit writes.
        auditService = Mockito.mock( AuditService.class );
        final java.lang.reflect.Field f = WikiEngine.class.getDeclaredField( "auditService" );
        f.setAccessible( true );
        f.set( engine, auditService );

        servlet = new AdminPolicyResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** The descriptor the audit label should carry: principal, permission and target. */
    private static final String DESCRIPTOR = "role:Admin:wiki:*";

    private static JsonObject validBody() {
        final JsonObject body = new JsonObject();
        body.addProperty( "principalType", "role" );
        body.addProperty( "principalName", "Admin" );
        body.addProperty( "permissionType", "wiki" );
        body.addProperty( "target", "*" );
        body.addProperty( "actions", "login" );
        return body;
    }

    private HttpServletRequest request( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/admin/policy" + ( pathInfo != null ? pathInfo : "" ) );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        if ( body != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) )
                .when( req ).getReader();
        }
        final Principal principal = Mockito.mock( Principal.class );
        Mockito.doReturn( "adminuser" ).when( principal ).getName();
        Mockito.doReturn( principal ).when( req ).getUserPrincipal();
        return req;
    }

    private HttpServletResponse response() throws Exception {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( resp ).getWriter();
        return resp;
    }

    private AuditEntry captureSingleEntry() {
        final ArgumentCaptor< AuditEntry > captor = ArgumentCaptor.forClass( AuditEntry.class );
        Mockito.verify( auditService ).record( captor.capture() );
        return captor.getValue();
    }

    /** Every policy-grant audit entry shares this envelope, whatever the operation. */
    private static void assertCommonEnvelope( final AuditEntry entry ) {
        assertNotNull( entry.eventTime(), "Every audit entry must be timestamped." );
        assertEquals( AuditCategory.ADMIN, entry.category() );
        assertEquals( AuditOutcome.SUCCESS, entry.outcome() );
        assertEquals( "adminuser", entry.actorPrincipal() );
        assertEquals( "user", entry.actorType() );
        assertEquals( "policy", entry.targetType() );
    }

    // ------------------------------------------------------------------
    // The three mutations
    // ------------------------------------------------------------------

    /**
     * The defect. Create recorded the composite descriptor as {@code targetId}, so its
     * event could not be found by the grant id that update and delete file under.
     */
    @Test
    void createGrant_recordsTheGrantIdAsTargetId() throws Exception {

        servlet.doPost( request( null, validBody() ), response() );

        final AuditEntry entry = captureSingleEntry();
        assertCommonEnvelope( entry );
        assertEquals( "7", entry.targetId(),
            "Create must file the audit entry under the grant id, like update and delete do — "
          + "otherwise the create event is invisible when auditing a grant by id." );
        assertEquals( DESCRIPTOR, entry.targetLabel(),
            "The human-readable descriptor belongs in targetLabel." );
    }

    @Test
    void updateGrant_recordsTheGrantIdAsTargetId() throws Exception {

        servlet.doPut( request( "/7", validBody() ), response() );

        final AuditEntry entry = captureSingleEntry();
        assertCommonEnvelope( entry );
        assertEquals( "7", entry.targetId() );
        assertEquals( DESCRIPTOR, entry.targetLabel() );
    }

    @Test
    void deleteGrant_recordsTheGrantIdAsTargetId() throws Exception {

        servlet.doDelete( request( "/7", null ), response() );

        final AuditEntry entry = captureSingleEntry();
        assertCommonEnvelope( entry );
        assertEquals( "7", entry.targetId() );
        assertEquals( "policy.grant.delete", entry.eventType() );
    }

    /**
     * All three events for one grant must share a target key, which is the whole point of
     * auditing by id. Guards against any one path drifting again.
     */
    @Test
    void allThreeMutations_fileUnderTheSameTargetKey() throws Exception {

        servlet.doPost( request( null, validBody() ), response() );
        servlet.doPut( request( "/7", validBody() ), response() );
        servlet.doDelete( request( "/7", null ), response() );

        final ArgumentCaptor< AuditEntry > captor = ArgumentCaptor.forClass( AuditEntry.class );
        Mockito.verify( auditService, Mockito.times( 3 ) ).record( captor.capture() );

        for ( final AuditEntry entry : captor.getAllValues() ) {
            assertEquals( "policy", entry.targetType() );
            assertEquals( "7", entry.targetId(),
                "All three mutations of grant 7 must file under targetId=7; got "
              + entry.eventType() + " under '" + entry.targetId() + "'." );
        }
    }

    /**
     * Pins a known wart rather than hiding it: the CREATE path reports eventType
     * {@code "policy.grant.update"}. That predates the dedup work and is preserved
     * deliberately — changing it would make historical rows and new rows disagree about
     * what a create looks like. This test exists so the oddity is visible and correcting
     * it later is a deliberate, reviewed act rather than an accident.
     */
    @Test
    void createGrant_reportsUpdateEventType_knownWartPreservedDeliberately() throws Exception {

        servlet.doPost( request( null, validBody() ), response() );

        assertEquals( "policy.grant.update", captureSingleEntry().eventType(),
            "Create still reports the update event type. If you are deliberately fixing this, "
          + "update this test and note the discontinuity in historical audit rows." );
    }

    /**
     * A broken audit backend must never fail a mutation that already succeeded — the row
     * is written by the time the audit call runs, so throwing here would report failure
     * for work that actually happened.
     */
    @Test
    void auditFailureDoesNotFailTheMutation() throws Exception {
        Mockito.doThrow( new RuntimeException( "audit backend down" ) )
            .when( auditService ).record( Mockito.any() );

        final HttpServletResponse resp = response();
        servlet.doDelete( request( "/7", null ), resp );

        Mockito.verify( resp, Mockito.never() )
            .sendError( Mockito.eq( HttpServletResponse.SC_INTERNAL_SERVER_ERROR ), anyString() );
        Mockito.verify( dbPolicy ).refresh();
    }
}
