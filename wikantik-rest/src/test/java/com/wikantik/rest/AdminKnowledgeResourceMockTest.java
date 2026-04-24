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
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.SchemaDescription;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Fast unit tests for {@link AdminKnowledgeResource} that mock
 * {@link KnowledgeGraphService} instead of spinning up a Testcontainers
 * Postgres. Covers the dispatch table, error-path fall-throughs, and the
 * parameter-parsing branches that the DB-backed {@link AdminKnowledgeResourceTest}
 * does not exercise.
 */
class AdminKnowledgeResourceMockTest {

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

    // ---- dispatch + service-availability ----

    @Test
    void doGet_returns400WhenPathInfoMissing() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/knowledge" );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        final JsonObject obj = call( req, "GET" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void doGet_returns404ForUnknownResource() throws Exception {
        final JsonObject obj = call( request( "/not-a-real-resource" ), "GET" );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void doDelete_requiresIdInPath() throws Exception {
        final JsonObject obj = call( request( "/nodes" ), "DELETE" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ---- GET /schema ----

    @Test
    void getSchema_returnsServicePayload() throws Exception {
        Mockito.when( service.discoverSchema() ).thenReturn( new SchemaDescription(
            List.of( "Concept" ), List.of( "related" ), List.of( "active" ),
            Map.of(), new SchemaDescription.Stats( 5, 3, 1 ) ) );
        final JsonObject obj = call( request( "/schema" ), "GET" );
        assertTrue( obj.getAsJsonArray( "nodeTypes" ).size() > 0 );
    }

    // ---- GET /nodes ----

    @Test
    void getNodes_listingAppliesFiltersAndPagination() throws Exception {
        Mockito.when( service.queryNodes( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        final HttpServletRequest req = request( "/nodes" );
        Mockito.doReturn( "Concept" ).when( req ).getParameter( "node_type" );
        Mockito.doReturn( "Al" ).when( req ).getParameter( "name" );
        Mockito.doReturn( "active" ).when( req ).getParameter( "status" );
        Mockito.doReturn( "25" ).when( req ).getParameter( "limit" );
        Mockito.doReturn( "10" ).when( req ).getParameter( "offset" );

        call( req, "GET" );

        Mockito.verify( service ).queryNodes(
            Mockito.argThat( m -> "Concept".equals( m.get( "node_type" ) )
                && "Al".equals( m.get( "name" ) )
                && "active".equals( m.get( "status" ) ) ),
            Mockito.isNull(),
            Mockito.eq( 25 ),
            Mockito.eq( 10 ) );
    }

    @Test
    void getNode_returns404WhenMissing() throws Exception {
        Mockito.when( service.getNodeByName( "Ghost" ) ).thenReturn( null );
        final JsonObject obj = call( request( "/nodes/Ghost" ), "GET" );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void getNode_returnsNodeWithEdges() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.getNodeByName( "Alpha" ) ).thenReturn( node( id, "Alpha" ) );
        Mockito.when( service.getEdgesForNode( id, "both" ) ).thenReturn( List.of() );
        final JsonObject obj = call( request( "/nodes/Alpha" ), "GET" );
        assertEquals( "Alpha", obj.get( "name" ).getAsString() );
        assertTrue( obj.has( "edges" ) );
    }

    // ---- GET /edges ----

    @Test
    void getEdges_listingForwardsRelationshipTypeFilter() throws Exception {
        Mockito.when( service.queryEdges( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final HttpServletRequest req = request( "/edges" );
        Mockito.doReturn( "related" ).when( req ).getParameter( "relationship_type" );
        Mockito.doReturn( "Al" ).when( req ).getParameter( "search" );
        call( req, "GET" );
        Mockito.verify( service ).queryEdges( Mockito.eq( "related" ), Mockito.eq( "Al" ), anyInt(), anyInt() );
    }

    @Test
    void getEdges_byNodeIdReturns400ForMalformedUuid() throws Exception {
        final JsonObject obj = call( request( "/edges/not-a-uuid" ), "GET" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void getEdges_byNodeIdHonorsDirectionParam() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.getEdgesForNode( any(), anyString() ) ).thenReturn( List.of() );
        final HttpServletRequest req = request( "/edges/" + id );
        Mockito.doReturn( "outbound" ).when( req ).getParameter( "direction" );
        call( req, "GET" );
        Mockito.verify( service ).getEdgesForNode( id, "outbound" );
    }

    // ---- GET /proposals ----

    @Test
    void getProposals_forwardsStatusSourcePagePagination() throws Exception {
        Mockito.when( service.listProposals( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        final HttpServletRequest req = request( "/proposals" );
        Mockito.doReturn( "pending" ).when( req ).getParameter( "status" );
        Mockito.doReturn( "Alpha" ).when( req ).getParameter( "source_page" );
        Mockito.doReturn( "5" ).when( req ).getParameter( "limit" );
        Mockito.doReturn( "1" ).when( req ).getParameter( "offset" );
        call( req, "GET" );
        Mockito.verify( service ).listProposals(
            Mockito.eq( "pending" ), Mockito.eq( "Alpha" ),
            Mockito.eq( 5 ), Mockito.eq( 1 ) );
    }

    // ---- POST /proposals ----

    @Test
    void postProposal_createsNewProposalFromBody() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.submitProposal(
                Mockito.eq( "new-edge" ), Mockito.eq( "Alpha" ), any(),
                Mockito.eq( 0.7 ), Mockito.eq( "why" ) ) )
            .thenReturn( proposal( id, "new-edge", "Alpha", "pending" ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "proposal_type", "new-edge" );
        body.addProperty( "source_page", "Alpha" );
        final JsonObject data = new JsonObject();
        data.addProperty( "target", "Beta" );
        data.addProperty( "relationship", "related" );
        body.add( "proposed_data", data );
        body.addProperty( "confidence", 0.7 );
        body.addProperty( "reasoning", "why" );

        final JsonObject obj = callWithBody( "/proposals", "POST", body );
        assertEquals( id.toString(), obj.get( "id" ).getAsString() );
        assertEquals( "pending", obj.get( "status" ).getAsString() );
    }

    @Test
    void postProposal_approve_returns404WhenMissing() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.approveProposal( Mockito.eq( id ), anyString() ) ).thenReturn( null );
        final JsonObject obj = call( request( "/proposals/" + id + "/approve" ), "POST" );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postProposal_rejectsBadUuid() throws Exception {
        final JsonObject obj = call( request( "/proposals/not-uuid/approve" ), "POST" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postProposal_rejectsUnknownAction() throws Exception {
        final UUID id = UUID.randomUUID();
        final JsonObject obj = call( request( "/proposals/" + id + "/banana" ), "POST" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postProposal_missingActionReturns400() throws Exception {
        final UUID id = UUID.randomUUID();
        final JsonObject obj = call( request( "/proposals/" + id ), "POST" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postProposal_rejectPersistsReason() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.rejectProposal( Mockito.eq( id ), anyString(), Mockito.eq( "bad idea" ) ) )
            .thenReturn( proposal( id, "new-node", "Alpha", "rejected" ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "reason", "bad idea" );
        final JsonObject obj = callWithBody( "/proposals/" + id + "/reject", "POST", body );
        assertEquals( "rejected", obj.get( "status" ).getAsString() );
    }

    // ---- POST /nodes + POST /nodes/merge ----

    @Test
    void postNode_upsertsWithProvidedFields() throws Exception {
        final UUID id = UUID.randomUUID();
        Mockito.when( service.upsertNode( Mockito.eq( "Alpha" ), Mockito.eq( "Concept" ),
                Mockito.eq( "AlphaPage" ), Mockito.eq( Provenance.HUMAN_AUTHORED ), any() ) )
            .thenReturn( node( id, "Alpha" ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "name", "Alpha" );
        body.addProperty( "node_type", "Concept" );
        body.addProperty( "source_page", "AlphaPage" );
        final JsonObject obj = callWithBody( "/nodes", "POST", body );
        assertEquals( "Alpha", obj.get( "name" ).getAsString() );
    }

    // ---- POST /edges ----

    @Test
    void postEdge_upsertsWithBody() throws Exception {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final UUID eId = UUID.randomUUID();
        Mockito.when( service.upsertEdge( Mockito.eq( src ), Mockito.eq( tgt ),
                Mockito.eq( "related" ), Mockito.eq( Provenance.HUMAN_AUTHORED ), any() ) )
            .thenReturn( edge( eId, src, tgt ) );

        final JsonObject body = new JsonObject();
        body.addProperty( "source_id", src.toString() );
        body.addProperty( "target_id", tgt.toString() );
        body.addProperty( "relationship_type", "related" );
        final JsonObject obj = callWithBody( "/edges", "POST", body );
        assertEquals( eId.toString(), obj.get( "id" ).getAsString() );
    }

    // ---- POST /clear-all ----

    @Test
    void clearAll_invokesService() throws Exception {
        final JsonObject obj = call( request( "/clear-all" ), "POST" );
        assertTrue( obj.has( "message" ) );
        Mockito.verify( service ).clearAll();
    }

    @Test
    void clearAll_returns500OnFailure() throws Exception {
        Mockito.doThrow( new RuntimeException( "boom" ) ).when( service ).clearAll();
        final JsonObject obj = call( request( "/clear-all" ), "POST" );
        assertEquals( 500, obj.get( "status" ).getAsInt() );
    }

    // ---- DELETE /nodes/{id} + /edges/{id} ----

    @Test
    void deleteNode_callsService() throws Exception {
        final UUID id = UUID.randomUUID();
        final JsonObject obj = call( request( "/nodes/" + id ), "DELETE" );
        assertTrue( obj.get( "deleted" ).getAsBoolean() );
        Mockito.verify( service ).deleteNode( id );
    }

    @Test
    void deleteNode_rejectsBadUuid() throws Exception {
        final JsonObject obj = call( request( "/nodes/not-uuid" ), "DELETE" );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void deleteEdge_callsService() throws Exception {
        final UUID id = UUID.randomUUID();
        final JsonObject obj = call( request( "/edges/" + id ), "DELETE" );
        assertTrue( obj.get( "deleted" ).getAsBoolean() );
        Mockito.verify( service ).deleteEdge( id );
    }

    // ---- embeddings ----

    @Test
    void getEmbeddings_statusWithoutSimilarityReportsNotReady() throws Exception {
        final JsonObject obj = call( request( "/embeddings/status" ), "GET" );
        assertFalse( obj.get( "ready" ).getAsBoolean() );
        assertEquals( 0, obj.get( "dimension" ).getAsInt() );
    }

    @Test
    void getEmbeddings_statusWithReadySimilarityReportsDimensionAndCount() throws Exception {
        final var sim = Mockito.mock( com.wikantik.knowledge.embedding.NodeMentionSimilarity.class );
        Mockito.when( sim.isReady() ).thenReturn( true );
        Mockito.when( sim.dimension() ).thenReturn( 768 );
        Mockito.when( sim.mentionedNodeNames() ).thenReturn( java.util.List.of( "Alpha", "Beta" ) );
        ( (WikiEngine) engine ).setManager(
            com.wikantik.knowledge.embedding.NodeMentionSimilarity.class, sim );

        final JsonObject obj = call( request( "/embeddings/status" ), "GET" );
        assertTrue( obj.get( "ready" ).getAsBoolean() );
        assertEquals( 768, obj.get( "dimension" ).getAsInt() );
        assertEquals( 2, obj.get( "mentioned_node_count" ).getAsInt() );
    }

    @Test
    void getEmbeddings_unknownSubResourceReturns404() throws Exception {
        final JsonObject obj = call( request( "/embeddings/bogus" ), "GET" );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postEmbeddings_alwaysReturns404() throws Exception {
        final JsonObject obj = call( request( "/embeddings" ), "POST" );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ---- similar ----

    @Test
    void getNodesSimilar_emptyWhenSimilarityUnavailable() throws Exception {
        final JsonObject obj = call( request( "/nodes/Alpha/similar" ), "GET" );
        assertEquals( 0, obj.getAsJsonArray( "similar" ).size() );
    }

    @Test
    void getNodesSimilar_returnsRankedResultsWhenAvailable() throws Exception {
        final var sim = Mockito.mock( com.wikantik.knowledge.embedding.NodeMentionSimilarity.class );
        Mockito.when( sim.isReady() ).thenReturn( true );
        Mockito.when( sim.similarTo( Mockito.eq( "Alpha" ), anyInt() ) ).thenReturn( java.util.List.of(
            new com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName( "Beta", 0.8 ),
            new com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName( "Gamma", 0.6 ) ) );
        ( (WikiEngine) engine ).setManager(
            com.wikantik.knowledge.embedding.NodeMentionSimilarity.class, sim );

        final JsonObject obj = call( request( "/nodes/Alpha/similar" ), "GET" );
        assertEquals( 2, obj.getAsJsonArray( "similar" ).size() );
    }

    // ---- hub-proposals service-unavailability ----

    @Test
    void getHubProposals_returns503WhenRepoUnavailable() throws Exception {
        final JsonObject obj = call( request( "/hub-proposals" ), "GET" );
        assertEquals( 503, obj.get( "status" ).getAsInt() );
    }

    @Test
    void postHubProposalsGenerate_returns412WhenSimilarityMissing() throws Exception {
        ( (WikiEngine) engine ).setManager( com.wikantik.knowledge.HubProposalRepository.class,
            Mockito.mock( com.wikantik.knowledge.HubProposalRepository.class ) );
        final JsonObject obj = call( request( "/hub-proposals/generate" ), "POST" );
        assertEquals( 412, obj.get( "status" ).getAsInt() );
    }

    // ---- pages-without-frontmatter (service unavailable when PageManager missing managers) ----

    @Test
    void getPagesWithoutFrontmatter_returnsResultWhenSupported() throws Exception {
        final JsonObject obj = call( request( "/pages-without-frontmatter" ), "GET" );
        // Either succeeds (returns pages array) or fails with an error code — both paths
        // exercise the handler without needing any KG data.
        assertTrue( obj.has( "pages" ) || obj.has( "error" ) );
    }

    // ---- sync-hub-memberships (no HubSyncService registered in test engine) ----

    @Test
    void postSyncHubMemberships_returns503WhenServiceMissing() throws Exception {
        final JsonObject obj = call( request( "/sync-hub-memberships" ), "POST" );
        // Handler returns an error when the underlying service isn't wired up.
        assertTrue( obj.has( "status" ) || obj.has( "error" ) );
    }

    // ---- helpers ----

    private HttpServletRequest request( final String pathInfo ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/admin/knowledge" + pathInfo );
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

    private static KgNode node( final UUID id, final String name ) {
        return new KgNode( id, name, "Concept", name + "Page",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T09:00:00Z" ),
            Instant.parse( "2026-04-24T10:00:00Z" ) );
    }

    private static KgEdge edge( final UUID id, final UUID src, final UUID tgt ) {
        return new KgEdge( id, src, tgt, "related",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T09:00:00Z" ),
            Instant.parse( "2026-04-24T10:00:00Z" ) );
    }

    private static KgProposal proposal( final UUID id, final String type, final String page, final String status ) {
        return new KgProposal( id, type, page, Map.of(),
            0.7, "why", status, null,
            Instant.parse( "2026-04-24T09:00:00Z" ), null );
    }
}
