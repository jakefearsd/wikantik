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
import com.wikantik.PostgresTestContainer;
import com.wikantik.TestEngine;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class AdminKnowledgeResourceTest {

    private static DataSource dataSource;
    private TestEngine engine;
    private AdminKnowledgeResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
        final DefaultKnowledgeGraphService service = new DefaultKnowledgeGraphService( repo );

        engine.setManager( KnowledgeGraphService.class, service );

        servlet = new AdminKnowledgeResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean knowledge graph tables
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void testGetNodeReturnsEdgesWithNames() throws Exception {
        final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
        final var srcNode = service.upsertNode( "EdgeNameSrc", "article", "EdgeNameSrc.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var tgtNode = service.upsertNode( "EdgeNameTgt", "article", "EdgeNameTgt.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( srcNode.id(), tgtNode.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        final String json = doGet( "/nodes/EdgeNameSrc" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        assertFalse( obj.has( "error" ), "Should succeed, got: " + json );

        final JsonArray edges = obj.getAsJsonArray( "edges" );
        assertTrue( edges.size() >= 1, "Should have at least 1 edge" );
        final JsonObject edge = edges.get( 0 ).getAsJsonObject();
        assertTrue( edge.has( "source_name" ), "Edge should have source_name" );
        assertTrue( edge.has( "target_name" ), "Edge should have target_name" );
        assertEquals( "EdgeNameSrc", edge.get( "source_name" ).getAsString() );
        assertEquals( "EdgeNameTgt", edge.get( "target_name" ).getAsString() );
    }

    @Test
    void testListAllEdgesReturnsEdgesWithNames() throws Exception {
        final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
        final var srcNode = service.upsertNode( "ListEdgeSrc", "article", "ListEdgeSrc.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var tgtNode = service.upsertNode( "ListEdgeTgt", "article", "ListEdgeTgt.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( srcNode.id(), tgtNode.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        final String json = doGetWithParams( "/edges", Map.of( "limit", "50" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        assertFalse( obj.has( "error" ), "Should succeed, got: " + json );
        assertTrue( obj.has( "edges" ), "Response should have edges array" );

        final JsonArray edges = obj.getAsJsonArray( "edges" );
        assertTrue( edges.size() >= 1, "Should have at least 1 edge" );
        final JsonObject edge = edges.get( 0 ).getAsJsonObject();
        assertTrue( edge.has( "source_name" ), "Edge should have source_name" );
        assertTrue( edge.has( "target_name" ), "Edge should have target_name" );
    }

    @Test
    void testListAllEdgesFiltersByRelationshipType() throws Exception {
        final KnowledgeGraphService svc = engine.getManager( KnowledgeGraphService.class );
        final var filterSrc = svc.upsertNode( "FilterSrc", "article", "FilterSrc.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var filterTgtA = svc.upsertNode( "FilterTgtA", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var filterTgtB = svc.upsertNode( "FilterTgtB", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        svc.upsertEdge( filterSrc.id(), filterTgtA.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        svc.upsertEdge( filterSrc.id(), filterTgtB.id(), "mentions", Provenance.HUMAN_AUTHORED, Map.of() );

        final String allJson = doGetWithParams( "/edges", Map.of( "limit", "100" ) );
        final int allCount = gson.fromJson( allJson, JsonObject.class )
                .getAsJsonArray( "edges" ).size();

        final String filteredJson = doGetWithParams( "/edges",
                Map.of( "relationship_type", "related", "limit", "100" ) );
        final int filteredCount = gson.fromJson( filteredJson, JsonObject.class )
                .getAsJsonArray( "edges" ).size();

        assertTrue( filteredCount > 0, "Should find at least 1 'related' edge" );
        assertTrue( filteredCount <= allCount, "Filtered count should be <= total" );
    }

    @Test
    void testSchemaIncludesStatusValues() throws Exception {
        final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
        service.upsertNode( "X", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
        service.upsertNode( "Y", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );

        final String json = doGet( "/schema" );
        final JsonObject schema = gson.fromJson( json, JsonObject.class );
        assertFalse( schema.has( "error" ), "Should not be an error: " + json );
        final JsonArray statuses = schema.getAsJsonArray( "statusValues" );
        assertNotNull( statuses, "Schema should have statusValues" );
        assertTrue( statuses.size() >= 2, "Should have at least 2 status values" );
    }

    @Test
    void testListNodesFiltersByStatus() throws Exception {
        final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
        service.upsertNode( "DeployedPage", "article", "DeployedPage.md",
                Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
        service.upsertNode( "DesignedPage", "article", "DesignedPage.md",
                Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );

        final String json = doGetWithParams( "/nodes",
                Map.of( "status", "deployed", "limit", "50" ) );
        final JsonObject result = gson.fromJson( json, JsonObject.class );
        assertFalse( result.has( "error" ), "Should not be an error: " + json );
        final JsonArray nodes = result.getAsJsonArray( "nodes" );
        assertEquals( 1, nodes.size(), "Should find only the deployed node" );
        assertEquals( "DeployedPage", nodes.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    // ----- Merge frontmatter update tests -----

    @Test
    void testMergeNodesUpdatesFrontmatter() throws Exception {
        // Page references "OldNode" in its frontmatter
        engine.saveText( "MergeSrcPage",
                "---\ntitle: Source\nrelated:\n  - OldNode\n---\nBody." );
        engine.saveText( "OldNode",
                "---\ntitle: Old\n---\nOld content." );
        engine.saveText( "NewNode",
                "---\ntitle: New\n---\nNew content." );

        // Seed the knowledge graph directly
        final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
        final var srcNode = service.upsertNode( "MergeSrcPage", "article", "MergeSrcPage.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var oldNode = service.upsertNode( "OldNode", "article", "OldNode.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var newNode = service.upsertNode( "NewNode", "article", "NewNode.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( srcNode.id(), oldNode.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        assertNotNull( oldNode, "OldNode should exist in the knowledge graph" );
        assertNotNull( newNode, "NewNode should exist in the knowledge graph" );

        // Merge OldNode into NewNode
        final JsonObject mergeBody = new JsonObject();
        mergeBody.addProperty( "sourceId", oldNode.id().toString() );
        mergeBody.addProperty( "targetId", newNode.id().toString() );
        final String result = doPostWithBody( "/nodes/merge", mergeBody );
        final JsonObject obj = gson.fromJson( result, JsonObject.class );
        assertTrue( obj.get( "merged" ).getAsBoolean(), "Merge should succeed" );

        // Verify MergeSrcPage's frontmatter now references NewNode instead of OldNode
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        final String updatedText = pm.getPureText( "MergeSrcPage",
                com.wikantik.api.providers.WikiProvider.LATEST_VERSION );
        assertTrue( updatedText.contains( "NewNode" ),
                "Frontmatter should reference NewNode after merge, got: " + updatedText );
        assertFalse( updatedText.contains( "OldNode" ),
                "Frontmatter should no longer reference OldNode after merge, got: " + updatedText );

        // Verify response includes pages_updated count
        assertTrue( obj.has( "pages_updated" ),
                "Response should include pages_updated count" );
        assertTrue( obj.get( "pages_updated" ).getAsInt() >= 1,
                "At least 1 page should have been updated" );

        // Cleanup
        try { pm.deletePage( "MergeSrcPage" ); } catch ( final Exception e ) { /* ignore */ }
        try { pm.deletePage( "OldNode" ); } catch ( final Exception e ) { /* ignore */ }
        try { pm.deletePage( "NewNode" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testMergeNodesHandlesMultiplePages() throws Exception {
        // Two pages reference OldTarget
        engine.saveText( "MultiMergA",
                "---\ntitle: A\nrelated:\n  - OldTarget\n---\nBody A." );
        engine.saveText( "MultiMergB",
                "---\ntitle: B\nmentions:\n  - OldTarget\n  - OtherNode\n---\nBody B." );
        engine.saveText( "OldTarget",
                "---\ntitle: Old Target\n---\nContent." );
        engine.saveText( "NewTarget",
                "---\ntitle: New Target\n---\nContent." );

        // Seed the knowledge graph directly
        final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
        final var nodeA = service.upsertNode( "MultiMergA", "article", "MultiMergA.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var nodeB = service.upsertNode( "MultiMergB", "article", "MultiMergB.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var oldNode = service.upsertNode( "OldTarget", "article", "OldTarget.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var newNode = service.upsertNode( "NewTarget", "article", "NewTarget.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var otherNode = service.upsertNode( "OtherNode", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( nodeA.id(), oldNode.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( nodeB.id(), oldNode.id(), "mentions", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( nodeB.id(), otherNode.id(), "mentions", Provenance.HUMAN_AUTHORED, Map.of() );

        final JsonObject mergeBody = new JsonObject();
        mergeBody.addProperty( "sourceId", oldNode.id().toString() );
        mergeBody.addProperty( "targetId", newNode.id().toString() );
        final String result = doPostWithBody( "/nodes/merge", mergeBody );
        final JsonObject obj = gson.fromJson( result, JsonObject.class );
        assertTrue( obj.get( "merged" ).getAsBoolean() );
        assertTrue( obj.get( "pages_updated" ).getAsInt() >= 2,
                "Both pages should have been updated" );

        // Verify both pages updated
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        final String textA = pm.getPureText( "MultiMergA",
                com.wikantik.api.providers.WikiProvider.LATEST_VERSION );
        assertTrue( textA.contains( "NewTarget" ), "Page A should reference NewTarget" );
        assertFalse( textA.contains( "OldTarget" ), "Page A should not reference OldTarget" );

        final String textB = pm.getPureText( "MultiMergB",
                com.wikantik.api.providers.WikiProvider.LATEST_VERSION );
        assertTrue( textB.contains( "NewTarget" ), "Page B should reference NewTarget" );
        assertFalse( textB.contains( "OldTarget" ), "Page B should not reference OldTarget" );
        assertTrue( textB.contains( "OtherNode" ), "Page B should still reference OtherNode" );

        // Cleanup
        try { pm.deletePage( "MultiMergA" ); } catch ( final Exception e ) { /* ignore */ }
        try { pm.deletePage( "MultiMergB" ); } catch ( final Exception e ) { /* ignore */ }
        try { pm.deletePage( "OldTarget" ); } catch ( final Exception e ) { /* ignore */ }
        try { pm.deletePage( "NewTarget" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testMergeNodesSkipsStubPages() throws Exception {
        // Create nodes via API — source has edges from a stub (no wiki page)
        final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
        final var stubNode = service.upsertNode( "StubReferrer", null, null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var oldNode = service.upsertNode( "StubOldNode", null, null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var newNode = service.upsertNode( "StubNewNode", null, null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( stubNode.id(), oldNode.id(), "related",
                Provenance.HUMAN_AUTHORED, Map.of() );

        // Merge should succeed even though the referencing node has no sourcePage
        final JsonObject mergeBody = new JsonObject();
        mergeBody.addProperty( "sourceId", oldNode.id().toString() );
        mergeBody.addProperty( "targetId", newNode.id().toString() );
        final String result = doPostWithBody( "/nodes/merge", mergeBody );
        final JsonObject obj = gson.fromJson( result, JsonObject.class );
        assertTrue( obj.get( "merged" ).getAsBoolean(), "Merge should succeed" );
        assertEquals( 0, obj.get( "pages_updated" ).getAsInt(),
                "No pages should be updated for stub nodes" );
    }

    // ----- Helper methods -----

    private String doGet( final String pathInfo ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private String doPost( final String pathInfo ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return sw.toString();
    }

    private String doPostWithBody( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return sw.toString();
    }

    private String doGetWithParams( final String pathInfo, final Map< String, String > params ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        for ( final Map.Entry< String, String > entry : params.entrySet() ) {
            Mockito.doReturn( entry.getValue() ).when( request ).getParameter( entry.getKey() );
        }
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pathInfo ) {
        final String path = "/admin/knowledge" + ( pathInfo != null ? pathInfo : "" );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( path );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        return request;
    }
}
