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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiEngine;
import com.wikantik.WikiSubsystems;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgEdgeView;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.PageKnowledgeSlice;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.subsystem.KnowledgeSubsystem;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PageKnowledgeResource}.
 *
 * <p>Covers permission gating (view/edit), SHACL edge refusal → 422, and the GET slice path.
 * All KG services are mocked — no database required.</p>
 *
 * <p>Three test subclasses are used to isolate concerns:
 * <ul>
 *   <li>{@code DenyViewServlet} — overrides checkPagePermission to deny "view".</li>
 *   <li>{@code DenyEditServlet} — overrides checkPagePermission to deny "edit".</li>
 *   <li>{@code StubbedServlet}  — overrides getSubsystems() to inject mocked KG + ops.</li>
 * </ul>
 * </p>
 */
class PageKnowledgeResourceTest {

    private TestEngine engine;
    private KnowledgeGraphService kgService;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        kgService = Mockito.mock( KnowledgeGraphService.class );
        ( (WikiEngine) engine ).setManager( KnowledgeGraphService.class, kgService );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    // -------------------------------------------------------------------------
    // GET /{name} — view permission denied → 403, service not called
    // -------------------------------------------------------------------------

    @Test
    void getRequiresViewPermission_deniedReturns403() throws Exception {
        // Arrange: servlet that always denies "view"
        final PageKnowledgeResource servlet = new DenyPermissionServlet( engine, "view" );

        final HttpServletRequest req = request( "/TestPage" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        // Act
        servlet.doGet( req, resp );

        // Assert: 403 sent; KG service never called
        verify( resp ).setStatus( 403 );
        Mockito.verify( kgService, Mockito.never() ).getPageSlice( anyString() );
    }

    // -------------------------------------------------------------------------
    // POST /{name}/edges — edit permission denied → 403
    // -------------------------------------------------------------------------

    @Test
    void postEdgeRequiresEditPermission_deniedReturns403() throws Exception {
        final PageKnowledgeResource servlet = new DenyPermissionServlet( engine, "edit" );

        final JsonObject body = new JsonObject();
        body.addProperty( "sourceId", UUID.randomUUID().toString() );
        body.addProperty( "targetId", UUID.randomUUID().toString() );
        body.addProperty( "relationshipType", "related" );

        final HttpServletRequest req = requestWithBody( "/TestPage/edges", body );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 403 );
    }

    // -------------------------------------------------------------------------
    // SHACL refusal on edge upsert → 422 with violation envelope
    // -------------------------------------------------------------------------

    @Test
    void shaclRefusalReturns422WithViolation() throws Exception {
        // Arrange: KgCurationOps mock that returns a SHACL error
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final String shaclMsg = "edge rejected: violates the wk:implements SHACL shape";
        Mockito.when( ops.tryUpsertEdge( any(), any(), anyString(), any(), anyString() ) )
               .thenReturn( KgCurationOps.EdgeResult.fail( shaclMsg ) );

        final PageKnowledgeResource servlet = new GrantEditServlet( engine, kgService, ops );

        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final JsonObject body = new JsonObject();
        body.addProperty( "sourceId", src.toString() );
        body.addProperty( "targetId", tgt.toString() );
        body.addProperty( "relationshipType", "implements" );

        final HttpServletRequest req = requestWithBody( "/TestPage/edges", body );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doPost( req, resp );

        // Assert status 422
        verify( resp ).setStatus( 422 );

        // Assert body has error="kg_edge_refused" and a violation with the SHACL message
        final JsonObject json = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "kg_edge_refused", json.get( "error" ).getAsString() );
        final JsonArray violations = json.getAsJsonArray( "violations" );
        assertNotNull( violations, "violations must be present" );
        assertEquals( 1, violations.size() );
        final JsonObject violation = violations.get( 0 ).getAsJsonObject();
        assertEquals( "edge", violation.get( "field" ).getAsString() );
        assertEquals( "ERROR", violation.get( "severity" ).getAsString() );
        assertEquals( "kg.edge.refused", violation.get( "code" ).getAsString() );
        assertTrue( violation.get( "message" ).getAsString().contains( "implements" ),
                "violation message should contain the SHACL error text" );
    }

    // -------------------------------------------------------------------------
    // GET /{name} — happy path returns slice
    // -------------------------------------------------------------------------

    @Test
    void getSliceReturnsPageKnowledgeSlice() throws Exception {
        final UUID nodeId = UUID.randomUUID();
        final UUID edgeId = UUID.randomUUID();
        final UUID node2Id = UUID.randomUUID();
        final KgNode node = node( nodeId, "Alpha" );
        final KgNode node2 = node( node2Id, "Beta" );
        final KgEdgeView edge = new KgEdgeView( edgeId, nodeId, node2Id,
                "Alpha", "Beta", "related_to", Provenance.AI_INFERRED );
        final PageKnowledgeSlice slice = new PageKnowledgeSlice(
                List.of( node, node2 ), List.of( edge ) );
        Mockito.when( kgService.getPageSlice( "TestPage" ) ).thenReturn( slice );

        final PageKnowledgeResource servlet = new GrantViewServlet( engine, kgService );

        final HttpServletRequest req = request( "/TestPage" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doGet( req, resp );

        // Should not set an error status
        Mockito.verify( resp, Mockito.never() ).setStatus( intThat( s -> s >= 400 ) );
        final JsonObject json = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( json.has( "entities" ), "response must include entities" );
        assertTrue( json.has( "edges" ), "response must include edges" );

        // Assert projected entity shape: camelCase keys, provenance as value()
        final JsonObject entity = json.getAsJsonArray( "entities" ).get( 0 ).getAsJsonObject();
        assertEquals( nodeId.toString(), entity.get( "id" ).getAsString() );
        assertEquals( "Alpha", entity.get( "name" ).getAsString() );
        assertEquals( "Concept", entity.get( "nodeType" ).getAsString() );
        assertEquals( Provenance.HUMAN_AUTHORED.value(), entity.get( "provenance" ).getAsString(),
                "provenance must be the lowercase value() string, not the enum name" );
        assertFalse( entity.has( "sourcePage" ), "sourcePage must not be leaked in the GET slice" );
        assertFalse( entity.has( "properties" ), "properties must not be leaked in the GET slice" );

        // Assert projected edge shape: camelCase keys, provenance as value()
        final JsonObject edgeJson = json.getAsJsonArray( "edges" ).get( 0 ).getAsJsonObject();
        assertEquals( edgeId.toString(), edgeJson.get( "id" ).getAsString() );
        assertEquals( nodeId.toString(), edgeJson.get( "sourceId" ).getAsString() );
        assertEquals( node2Id.toString(), edgeJson.get( "targetId" ).getAsString() );
        assertEquals( "Alpha", edgeJson.get( "sourceName" ).getAsString() );
        assertEquals( "Beta", edgeJson.get( "targetName" ).getAsString() );
        assertEquals( "related_to", edgeJson.get( "relationshipType" ).getAsString() );
        assertEquals( Provenance.AI_INFERRED.value(), edgeJson.get( "provenance" ).getAsString(),
                "edge provenance must be lowercase value() — e.g. 'ai-inferred'" );
    }

    // -------------------------------------------------------------------------
    // Missing pathInfo → 400
    // -------------------------------------------------------------------------

    @Test
    void missingPathInfoReturns400() throws Exception {
        final PageKnowledgeResource servlet = new GrantViewServlet( engine, kgService );
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/page-knowledge" );
        Mockito.doReturn( null ).when( req ).getPathInfo();

        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );
        servlet.doGet( req, resp );

        verify( resp ).setStatus( 400 );
    }

    // -------------------------------------------------------------------------
    // KG disabled (kgService null) → 503 citing wikantik.knowledge.enabled
    // -------------------------------------------------------------------------

    @Test
    void getSliceWhenKgDisabled_returns503CitingFlag() throws Exception {
        // View granted, but the KG subsystem is disabled (null kgService) — the
        // shape wikantik.knowledge.enabled=false produces.
        final PageKnowledgeResource servlet = new GrantViewServlet( engine, null );

        final HttpServletRequest req = request( "/TestPage" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        final JsonObject json = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( json.toString().contains( "wikantik.knowledge.enabled" ),
                "503 body must cite the flag: " + json );
    }

    // -------------------------------------------------------------------------
    // edgeRefusalBody — static helper unit test
    // -------------------------------------------------------------------------

    @Test
    void edgeRefusalBody_hasCorrectShape() {
        final String msg = "violates wk:implements shape";
        final Map< String, Object > body = PageKnowledgeResource.edgeRefusalBody( msg );
        assertEquals( "kg_edge_refused", body.get( "error" ) );
        final List<?> violations = (List<?>) body.get( "violations" );
        assertNotNull( violations );
        assertEquals( 1, violations.size() );
        final com.wikantik.api.frontmatter.schema.FieldViolation fv =
                (com.wikantik.api.frontmatter.schema.FieldViolation) violations.get( 0 );
        assertEquals( "edge", fv.field() );
        assertEquals( com.wikantik.api.frontmatter.schema.Severity.ERROR, fv.severity() );
        assertEquals( "kg.edge.refused", fv.code() );
        assertEquals( msg, fv.message() );
    }

    // -------------------------------------------------------------------------
    // POST /{name}/entities — edit permission denied → 403, ops never called
    // -------------------------------------------------------------------------

    @Test
    void postEntityRequiresEditPermission_deniedReturns403AndOpsNotCalled() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final PageKnowledgeResource servlet = new DenyPermissionServlet( engine, "edit" );

        final JsonObject body = new JsonObject();
        body.addProperty( "name", "SomeEntity" );

        final HttpServletRequest req = requestWithBody( "/TestPage/entities", body );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doPost( req, resp );

        verify( resp ).setStatus( 403 );
        Mockito.verify( ops, Mockito.never() ).tryUpsertNode( anyString(), any(), any(), any(), anyString() );
    }

    // -------------------------------------------------------------------------
    // POST /{name}/entities — happy path, tryUpsertNode succeeds → 200/ok
    // -------------------------------------------------------------------------

    @Test
    void postEntityHappyPath_upsertSucceeds_returnsOk() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final UUID nodeId = UUID.randomUUID();
        Mockito.when( ops.tryUpsertNode( anyString(), any(), anyString(), any(), anyString() ) )
               .thenReturn( KgCurationOps.NodeResult.ok( nodeId ) );
        Mockito.when( kgService.getNode( nodeId, true ) ).thenReturn( node( nodeId, "Alpha" ) );

        final PageKnowledgeResource servlet = new GrantEditServlet( engine, kgService, ops );

        final JsonObject body = new JsonObject();
        body.addProperty( "name", "Alpha" );
        body.addProperty( "nodeType", "Concept" );

        final HttpServletRequest req = requestWithBody( "/TestPage/entities", body );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doPost( req, resp );

        Mockito.verify( resp, Mockito.never() ).setStatus( intThat( s -> s >= 400 ) );
        final JsonObject json = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( json.get( "ok" ).getAsBoolean(), "response must have ok=true" );
        assertEquals( nodeId.toString(), json.get( "id" ).getAsString() );
        Mockito.verify( ops ).tryUpsertNode( eq( "Alpha" ), eq( "Concept" ), eq( "TestPage" ),
                any(), anyString() );
    }

    // -------------------------------------------------------------------------
    // POST /{name}/edges/{id}/confirm — happy path → 200/ok
    // -------------------------------------------------------------------------

    @Test
    void confirmEdgeHappyPath_returnsOk() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final UUID edgeId = UUID.randomUUID();
        Mockito.when( ops.tryConfirmEdge( eq( edgeId ), anyString() ) )
               .thenReturn( Optional.empty() );

        final PageKnowledgeResource servlet = new GrantEditServlet( engine, kgService, ops );

        final HttpServletRequest req = request( "/TestPage/edges/" + edgeId + "/confirm" );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doPost( req, resp );

        Mockito.verify( resp, Mockito.never() ).setStatus( intThat( s -> s >= 400 ) );
        final JsonObject json = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( json.get( "ok" ).getAsBoolean() );
        assertTrue( json.get( "confirmed" ).getAsBoolean() );
        Mockito.verify( ops ).tryConfirmEdge( eq( edgeId ), anyString() );
    }

    // -------------------------------------------------------------------------
    // DELETE /{name}/edges/{id} — happy path → 200/ok
    // -------------------------------------------------------------------------

    @Test
    void deleteEdgeHappyPath_returnsOk() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final UUID edgeId = UUID.randomUUID();
        Mockito.when( ops.tryDeleteEdge( eq( edgeId ), anyString() ) )
               .thenReturn( Optional.empty() );

        final PageKnowledgeResource servlet = new GrantEditServlet( engine, kgService, ops );

        final HttpServletRequest req = request( "/TestPage/edges/" + edgeId );
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doDelete( req, resp );

        Mockito.verify( resp, Mockito.never() ).setStatus( intThat( s -> s >= 400 ) );
        final JsonObject json = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( json.get( "ok" ).getAsBoolean() );
        assertTrue( json.get( "deleted" ).getAsBoolean() );
        Mockito.verify( ops ).tryDeleteEdge( eq( edgeId ), anyString() );
    }

    // -------------------------------------------------------------------------
    // POST /{name}/edges/{id}/reject — no body → succeeds (not 400)
    // -------------------------------------------------------------------------

    @Test
    void rejectEdgeWithoutBody_succeedsWithNullReason() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final UUID edgeId = UUID.randomUUID();
        Mockito.when( ops.tryDeleteAndRejectEdge( eq( edgeId ), anyString(), isNull() ) )
               .thenReturn( Optional.empty() );

        final PageKnowledgeResource servlet = new GrantEditServlet( engine, kgService, ops );

        // Request with no body (empty reader)
        final HttpServletRequest req = request( "/TestPage/edges/" + edgeId + "/reject" );
        Mockito.doReturn( new BufferedReader( new StringReader( "" ) ) ).when( req ).getReader();
        final StringWriter sw = new StringWriter();
        final HttpServletResponse resp = mockResponse( sw );

        servlet.doPost( req, resp );

        // Must NOT 400 — must succeed and call ops with reason=null
        Mockito.verify( resp, Mockito.never() ).setStatus( intThat( s -> s >= 400 ) );
        Mockito.verify( ops ).tryDeleteAndRejectEdge( eq( edgeId ), anyString(), isNull() );
        final JsonObject json = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( json.get( "ok" ).getAsBoolean() );
        assertTrue( json.get( "rejected" ).getAsBoolean() );
    }

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    /** Servlet that denies the specified permission action and grants everything else. */
    private final class DenyPermissionServlet extends PageKnowledgeResource {
        private final String deniedAction;
        DenyPermissionServlet( final TestEngine eng, final String deniedAction ) throws Exception {
            this.deniedAction = deniedAction;
            setEngine( eng );
        }
        @Override
        protected boolean checkPagePermission( final HttpServletRequest req,
                                                final HttpServletResponse resp,
                                                final String pageName,
                                                final String action ) throws IOException {
            if ( deniedAction.equals( action ) ) {
                sendError( resp, HttpServletResponse.SC_FORBIDDEN, "Forbidden" );
                return false;
            }
            return true;
        }
        @Override
        protected com.wikantik.WikiSubsystems getSubsystems() {
            return buildSubsystems( kgService, null );
        }
    }

    /** Servlet that always grants view (but does not short-circuit edit). Uses real service. */
    private final class GrantViewServlet extends PageKnowledgeResource {
        private final KnowledgeGraphService svc;
        GrantViewServlet( final TestEngine eng, final KnowledgeGraphService svc ) throws Exception {
            this.svc = svc;
            setEngine( eng );
        }
        @Override
        protected boolean checkPagePermission( final HttpServletRequest req,
                                                final HttpServletResponse resp,
                                                final String pageName,
                                                final String action ) {
            return true;
        }
        @Override
        protected com.wikantik.WikiSubsystems getSubsystems() {
            return buildSubsystems( svc, null );
        }
    }

    /** Servlet that always grants edit permission and injects the provided KgCurationOps. */
    private final class GrantEditServlet extends PageKnowledgeResource {
        private final KnowledgeGraphService svc;
        private final KgCurationOps ops;
        GrantEditServlet( final TestEngine eng,
                          final KnowledgeGraphService svc,
                          final KgCurationOps ops ) throws Exception {
            this.svc = svc;
            this.ops = ops;
            setEngine( eng );
        }
        @Override
        protected boolean checkPagePermission( final HttpServletRequest req,
                                                final HttpServletResponse resp,
                                                final String pageName,
                                                final String action ) {
            return true;
        }
        @Override
        protected com.wikantik.WikiSubsystems getSubsystems() {
            return buildSubsystems( svc, ops );
        }
    }

    /** Builds a minimal WikiSubsystems with only the KnowledgeSubsystem populated. */
    private com.wikantik.WikiSubsystems buildSubsystems( final KnowledgeGraphService svc,
                                                          final KgCurationOps ops ) {
        final KnowledgeSubsystem.Services kg = new KnowledgeSubsystem.Services(
            svc,         // kgService
            null,        // kgProposalJudgeService
            null,        // judgeRunner
            null,        // kgMaterializationService
            null,        // judgeTimeoutRepository
            null,        // hubProposalService
            null,        // hubDiscoveryService
            null,        // hubOverviewService
            null,        // hubProposalRepository
            null,        // hubDiscoveryRepository
            null,        // contentChunkRepository
            null,        // chunkProjector
            null,        // mentionIndex
            null,        // nodeMentionSimilarity
            null,        // frontmatterDefaultsFilter
            null,        // hubSyncFilter
            null,        // forAgentProjectionService
            null,        // bootstrapEntityExtractionIndexer
            null,        // kgInclusionPolicy
            null,        // reconciliationJobRunner
            null,        // retrievalQualityRunner
            ops,         // kgCurationOps
            null         // retrieval (normalized to an empty set-once holder)
        );
        return new com.wikantik.WikiSubsystems( null, null, null, null, null, null, kg, null );
    }

    // ---- request/response helpers ----

    private HttpServletRequest request( final String pathInfo ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/page-knowledge" + pathInfo );
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        return req;
    }

    private HttpServletRequest requestWithBody( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest req = request( pathInfo );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( req ).getReader();
        return req;
    }

    private HttpServletResponse mockResponse( final StringWriter sw ) throws Exception {
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        return resp;
    }

    private static KgNode node( final UUID id, final String name ) {
        return new KgNode( id, name, "Concept", name + "Page",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T09:00:00Z" ),
            Instant.parse( "2026-04-24T10:00:00Z" ),
            "human", null );
    }
}
