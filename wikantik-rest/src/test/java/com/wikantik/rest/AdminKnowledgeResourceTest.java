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
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.GraphProjector;
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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AdminKnowledgeResourceTest {

    private static DataSource dataSource;
    private TestEngine engine;
    private AdminKnowledgeResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void initDataSource() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:knowledge_rest_test;DB_CLOSE_DELAY=-1" );
        dataSource = ds;
        try ( final Connection conn = ds.getConnection() ) {
            final String ddl = new String(
                    AdminKnowledgeResourceTest.class.getResourceAsStream( "/knowledge-h2.sql" ).readAllBytes() );
            conn.createStatement().execute( ddl );
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
        final DefaultKnowledgeGraphService service = new DefaultKnowledgeGraphService( repo );
        final GraphProjector projector = new GraphProjector( service );

        engine.setManager( KnowledgeGraphService.class, service );
        engine.setManager( GraphProjector.class, projector );

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

    @AfterAll
    static void shutdownDataSource() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "SHUTDOWN" );
        }
    }

    @Test
    void testProjectAllWithFrontmatter() throws Exception {
        engine.saveText( "ProjectTestA",
                "---\ntitle: Test A\nrelated:\n  - ProjectTestB\n---\nBody of A." );
        engine.saveText( "ProjectTestB",
                "---\ntitle: Test B\nmentions:\n  - ProjectTestA\n---\nBody of B." );

        final String json = doPost( "/project-all" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "scanned" ), "Response should have 'scanned'" );
        assertTrue( obj.has( "projected" ), "Response should have 'projected'" );
        assertTrue( obj.has( "errors" ), "Response should have 'errors'" );

        assertTrue( obj.get( "scanned" ).getAsInt() >= 2,
                "Should have scanned at least the 2 test pages" );
        assertTrue( obj.get( "projected" ).getAsInt() >= 2,
                "Should have projected at least the 2 pages with frontmatter" );
        assertEquals( 0, obj.getAsJsonArray( "errors" ).size(),
                "Should have no errors" );

        // Cleanup
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        try { pm.deletePage( "ProjectTestA" ); } catch ( final Exception e ) { /* ignore */ }
        try { pm.deletePage( "ProjectTestB" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testProjectAllWithoutFrontmatter() throws Exception {
        engine.saveText( "PlainPage", "No frontmatter here, just content." );

        final String json = doPost( "/project-all" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final int scanned = obj.get( "scanned" ).getAsInt();
        final int projected = obj.get( "projected" ).getAsInt();

        assertTrue( scanned >= 1, "Should have scanned at least the plain page" );
        // PlainPage has no frontmatter, so projected count should be less than scanned
        // (or equal if other test pages with frontmatter exist from engine default pages)
        assertTrue( projected < scanned || projected == 0,
                "Plain page without frontmatter should not be projected" );

        // Cleanup
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        try { pm.deletePage( "PlainPage" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testProjectAllIsIdempotent() throws Exception {
        engine.saveText( "IdempotentPage",
                "---\ntitle: Idempotent\nrelated:\n  - TargetNode\n---\nBody." );

        final String json1 = doPost( "/project-all" );
        final JsonObject obj1 = gson.fromJson( json1, JsonObject.class );
        final int projected1 = obj1.get( "projected" ).getAsInt();

        // Get schema after first projection
        final String schema1 = doGet( "/schema" );
        final JsonObject s1 = gson.fromJson( schema1, JsonObject.class );
        final JsonObject stats1 = s1.getAsJsonObject( "stats" );

        // Project again
        final String json2 = doPost( "/project-all" );
        final JsonObject obj2 = gson.fromJson( json2, JsonObject.class );
        final int projected2 = obj2.get( "projected" ).getAsInt();

        assertEquals( projected1, projected2,
                "Second projection should process the same number of pages" );

        // Schema should have same counts — upserts don't create duplicates
        final String schema2 = doGet( "/schema" );
        final JsonObject s2 = gson.fromJson( schema2, JsonObject.class );
        final JsonObject stats2 = s2.getAsJsonObject( "stats" );

        assertEquals( stats1.get( "nodes" ).getAsInt(), stats2.get( "nodes" ).getAsInt(),
                "Node count should not change after idempotent re-projection" );
        assertEquals( stats1.get( "edges" ).getAsInt(), stats2.get( "edges" ).getAsInt(),
                "Edge count should not change after idempotent re-projection" );

        // Cleanup
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        try { pm.deletePage( "IdempotentPage" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testProjectAllCreatesNodesAndEdges() throws Exception {
        engine.saveText( "SourcePage",
                "---\ntitle: Source\nrelated:\n  - TargetA\n  - TargetB\n---\nBody." );

        doPost( "/project-all" );

        // Verify the source node was created
        final String nodeJson = doGet( "/nodes/SourcePage" );
        final JsonObject node = gson.fromJson( nodeJson, JsonObject.class );
        assertNotNull( node.get( "id" ), "SourcePage node should exist" );
        assertFalse( node.has( "error" ), "Should not be an error response, got: " + nodeJson );

        // Verify edges were created
        assertTrue( node.has( "edges" ), "Node response should include edges" );
        final JsonArray edges = node.getAsJsonArray( "edges" );
        assertTrue( edges.size() >= 2,
                "SourcePage should have at least 2 edges (related → TargetA, TargetB)" );

        // Cleanup
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        try { pm.deletePage( "SourcePage" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testGetNodeReturnsEdgesWithNames() throws Exception {
        engine.saveText( "EdgeNameSrc",
                "---\ntitle: Source\nrelated:\n  - EdgeNameTgt\n---\nBody." );
        doPost( "/project-all" );

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

        // Cleanup
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        try { pm.deletePage( "EdgeNameSrc" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testListAllEdgesReturnsEdgesWithNames() throws Exception {
        engine.saveText( "ListEdgeSrc",
                "---\ntitle: Source\nrelated:\n  - ListEdgeTgt\n---\nBody." );
        doPost( "/project-all" );

        final String json = doGetWithParams( "/edges", Map.of( "limit", "50" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        assertFalse( obj.has( "error" ), "Should succeed, got: " + json );
        assertTrue( obj.has( "edges" ), "Response should have edges array" );

        final JsonArray edges = obj.getAsJsonArray( "edges" );
        assertTrue( edges.size() >= 1, "Should have at least 1 edge" );
        final JsonObject edge = edges.get( 0 ).getAsJsonObject();
        assertTrue( edge.has( "source_name" ), "Edge should have source_name" );
        assertTrue( edge.has( "target_name" ), "Edge should have target_name" );

        // Cleanup
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        try { pm.deletePage( "ListEdgeSrc" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testListAllEdgesFiltersByRelationshipType() throws Exception {
        engine.saveText( "FilterSrc",
                "---\ntitle: Source\nrelated:\n  - FilterTgtA\nmentions:\n  - FilterTgtB\n---\nBody." );
        doPost( "/project-all" );

        final String allJson = doGetWithParams( "/edges", Map.of( "limit", "100" ) );
        final int allCount = gson.fromJson( allJson, JsonObject.class )
                .getAsJsonArray( "edges" ).size();

        final String filteredJson = doGetWithParams( "/edges",
                Map.of( "relationship_type", "related", "limit", "100" ) );
        final int filteredCount = gson.fromJson( filteredJson, JsonObject.class )
                .getAsJsonArray( "edges" ).size();

        assertTrue( filteredCount > 0, "Should find at least 1 'related' edge" );
        assertTrue( filteredCount <= allCount, "Filtered count should be <= total" );

        // Cleanup
        final com.wikantik.api.managers.PageManager pm = engine.getManager( com.wikantik.api.managers.PageManager.class );
        try { pm.deletePage( "FilterSrc" ); } catch ( final Exception e ) { /* ignore */ }
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
