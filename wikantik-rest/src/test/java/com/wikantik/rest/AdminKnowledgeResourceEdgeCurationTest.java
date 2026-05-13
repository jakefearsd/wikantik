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
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Focused unit tests for the edge curation endpoints added to
 * {@link AdminKnowledgeResource}:
 * <ul>
 *   <li>GET /edges — now includes a {@code total} field</li>
 *   <li>POST /edges — stamps {@link Provenance#HUMAN_CURATED} unconditionally</li>
 *   <li>POST /edges/bulk-delete — drift-check + 409 on count mismatch</li>
 *   <li>POST /edges/{id}/delete-and-reject — delegates to service and audits</li>
 *   <li>GET /edges/{id}/audit — returns audit rows</li>
 * </ul>
 *
 * <p>Audit-write verification is intentionally left to the wire-level IT
 * (Task 12), because {@link AdminKnowledgeResource#getAuditRepo} instanceof-checks
 * for {@link com.wikantik.knowledge.DefaultKnowledgeGraphService} and returns
 * {@code null} when the service is a Mockito mock — so audit writes are skipped
 * in this test path, which is by design.</p>
 */
class AdminKnowledgeResourceEdgeCurationTest {

    private TestEngine engine;
    private AdminKnowledgeResource servlet;
    private KnowledgeGraphService service;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        service = Mockito.mock( KnowledgeGraphService.class );
        ( (WikiEngine) engine ).setManager( KnowledgeGraphService.class, service );

        servlet = new AdminKnowledgeResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    // ---- GET /edges — total field ----

    @Test
    void getEdges_includesTotal() throws Exception {
        Mockito.when( service.queryEdges( any(), any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        Mockito.when( service.countEdges( any(), any(), any() ) ).thenReturn( 42L );

        final JsonObject obj = call( request( "/edges" ), "GET" );
        assertTrue( obj.has( "edges" ), "response must have 'edges' key" );
        assertTrue( obj.has( "total" ), "response must have 'total' key" );
        assertEquals( 42L, obj.get( "total" ).getAsLong() );
    }

    // ---- POST /edges — stamps HUMAN_CURATED ----

    @Test
    void postEdge_stampsHumanCuratedRegardlessOfBody() throws Exception {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final UUID eId = UUID.randomUUID();
        final KgEdge theEdge = edge( eId, src, tgt );
        Mockito.when( service.upsertEdge( eq( src ), eq( tgt ),
                eq( "related" ), eq( Provenance.HUMAN_CURATED ), any() ) )
            .thenReturn( theEdge );
        // Also stub getEdgesForNode so the before-state lookup doesn't fail
        Mockito.when( service.getEdgesForNode( eq( src ), anyString() ) ).thenReturn( List.of() );
        // After routing through the facade, the resource re-fetches the edge for the response.
        Mockito.when( service.getEdge( eId ) ).thenReturn( theEdge );

        final JsonObject body = new JsonObject();
        body.addProperty( "source_id", src.toString() );
        body.addProperty( "target_id", tgt.toString() );
        body.addProperty( "relationship_type", "related" );
        // Body explicitly requests a different provenance — must be ignored.
        body.addProperty( "provenance", "ai-inferred" );

        callWithBody( "/edges", "POST", body );

        Mockito.verify( service ).upsertEdge(
            eq( src ), eq( tgt ), eq( "related" ),
            eq( Provenance.HUMAN_CURATED ), any() );
    }

    // ---- POST /edges — 409 on duplicate key ----

    @Test
    void postEdge_returns409OnDuplicateKey() throws Exception {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        Mockito.when( service.getEdgesForNode( eq( src ), anyString() ) ).thenReturn( List.of() );
        final RuntimeException cause = new RuntimeException( "duplicate key value violates" );
        final RuntimeException wrapper = new RuntimeException( "insert failed", cause );
        Mockito.when( service.upsertEdge( any(), any(), any(), any(), any() ) )
            .thenThrow( wrapper );

        final JsonObject body = new JsonObject();
        body.addProperty( "source_id", src.toString() );
        body.addProperty( "target_id", tgt.toString() );
        body.addProperty( "relationship_type", "related" );

        final JsonObject obj = callWithBody( "/edges", "POST", body );
        assertEquals( 409, obj.get( "status" ).getAsInt() );
    }

    // ---- POST /edges/bulk-delete — 409 on count drift ----

    @Test
    void postEdge_bulkDelete_returns409OnCountDrift() throws Exception {
        // snapshot returns 5 rows but service throws on the mismatch
        Mockito.when( service.queryEdges( any(), any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        Mockito.when( service.bulkDeleteEdges( any(), any(), any(), anyInt() ) )
            .thenThrow( new IllegalStateException( "Count drift: expected 3 but found 5" ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "relationship_type", "related" );
        body.addProperty( "expected_count", 3 );

        final JsonObject obj = callWithBody( "/edges/bulk-delete", "POST", body );
        assertEquals( 409, obj.get( "status" ).getAsInt() );
    }

    // ---- POST /edges/bulk-delete — missing expected_count → 400 ----

    @Test
    void postEdge_bulkDelete_returns400WhenExpectedCountMissing() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "relationship_type", "related" );
        // no expected_count

        final JsonObject obj = callWithBody( "/edges/bulk-delete", "POST", body );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ---- POST /edges/{id}/delete-and-reject ----

    @Test
    void postEdge_deleteAndReject_callsServiceWithCorrectArgs() throws Exception {
        final UUID id = UUID.randomUUID();
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        Mockito.when( service.getEdge( id ) ).thenReturn( edge( id, src, tgt ) );
        Mockito.doNothing().when( service ).deleteEdgeAndRecordRejection( any(), any(), any() );

        final JsonObject body = new JsonObject();
        body.addProperty( "reason", "bad triple" );

        final JsonObject obj = callWithBody( "/edges/" + id + "/delete-and-reject", "POST", body );
        assertTrue( obj.get( "deleted" ).getAsBoolean() );
        assertTrue( obj.get( "rejected" ).getAsBoolean() );

        Mockito.verify( service ).deleteEdgeAndRecordRejection(
            eq( id ),
            anyString(),  // actor — resolved from request.getRemoteUser()
            eq( "bad triple" ) );
    }

    @Test
    void postEdge_deleteAndReject_returns404WhenEdgeMissing() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.getEdge( id ) ).thenReturn( null );

        final JsonObject obj = callWithBody( "/edges/" + id + "/delete-and-reject", "POST",
            new JsonObject() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ---- GET /edges/{id}/audit ----

    @Test
    void getEdgeAudit_returnsAuditRows() throws Exception {
        final UUID id = UUID.randomUUID();
        final List< Map< String, Object > > rows = List.of(
            Map.of( "id", UUID.randomUUID().toString(), "action", "CREATE",
                    "actor", "admin", "created", Instant.now().toString() ),
            Map.of( "id", UUID.randomUUID().toString(), "action", "UPDATE",
                    "actor", "admin", "created", Instant.now().toString() ) );
        Mockito.when( service.getEdgeAudit( eq( id ), anyInt() ) ).thenReturn( rows );

        final JsonObject obj = call( request( "/edges/" + id + "/audit" ), "GET" );
        assertTrue( obj.has( "audit" ), "response must have 'audit' key" );
        assertEquals( 2, obj.getAsJsonArray( "audit" ).size() );
    }

    // ---- helpers (copied from AdminKnowledgeResourceMockTest — tests don't compose via inheritance) ----

    private HttpServletRequest request( final String pathInfo ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/knowledge-graph" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return req;
    }

    private JsonObject call( final HttpServletRequest req, final String method ) throws Exception {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        switch ( method ) {
            case "GET"    -> servlet.doGet( req, resp );
            case "POST"   -> servlet.doPost( req, resp );
            case "DELETE" -> servlet.doDelete( req, resp );
            default -> fail( "unexpected method: " + method );
        }
        final String body = sw.toString();
        return body.isEmpty() ? new JsonObject() : gson.fromJson( body, JsonObject.class );
    }

    private JsonObject callWithBody( final String pathInfo, final String method, final JsonObject body )
            throws Exception {
        final HttpServletRequest req = request( pathInfo );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( req ).getReader();
        return call( req, method );
    }

    private static KgEdge edge( final UUID id, final UUID src, final UUID tgt ) {
        return new KgEdge( id, src, tgt, "related",
            Provenance.HUMAN_CURATED, Map.<String, Object>of(),
            Instant.parse( "2026-04-24T09:00:00Z" ),
            Instant.parse( "2026-04-24T10:00:00Z" ),
            "human", null );
    }
}
