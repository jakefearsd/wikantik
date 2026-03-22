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
package com.wikantik.mcp.tools;

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.TestEngine;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetClusterMapToolTest {

    private TestEngine engine;
    private GetClusterMapTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        tool = new GetClusterMapTool(
                engine.getManager( PageManager.class ),
                engine.getManager( SystemPageRegistry.class ) );

        // Create test pages across 3 clusters + a sub-cluster
        engine.saveText( "AiHub", "---\ntype: hub\ncluster: ai\ntags:\n- ai\nsummary: AI cluster hub\nstatus: active\ndate: 2026-01-01\nauthor: Admin\nrelated:\n- AiArticle1\n---\n# AI Hub" );
        engine.saveText( "AiArticle1", "---\ntype: article\ncluster: ai\ntags:\n- ai\n- ml\nsummary: First AI article about machine learning\nstatus: active\ndate: 2026-01-02\nauthor: Admin\nrelated:\n- AiHub\n---\nBody." );

        engine.saveText( "FinanceHub", "---\ntype: hub\ncluster: finance\ntags:\n- finance\nsummary: Finance cluster hub page\nstatus: active\ndate: 2026-01-01\nauthor: Admin\n---\n# Finance Hub" );
        engine.saveText( "FinanceArticle1", "---\ntype: article\ncluster: finance\ntags:\n- finance\nsummary: A finance article about investing\nstatus: active\ndate: 2026-01-03\nauthor: Admin\n---\nBody." );

        engine.saveText( "WoodHub", "---\ntype: hub\ncluster: woodworking\ntags:\n- wood\nsummary: Woodworking cluster hub page\nstatus: active\ndate: 2026-01-01\nauthor: Admin\n---\n# Wood Hub" );
        engine.saveText( "WoodSubHub", "---\ntype: hub\ncluster: woodworking/cnc\ntags:\n- cnc\nsummary: CNC sub-cluster hub page info\nstatus: active\ndate: 2026-01-01\nauthor: Admin\n---\n# CNC Hub" );
        engine.saveText( "CncArticle", "---\ntype: article\ncluster: woodworking/cnc\ntags:\n- cnc\nsummary: CNC article about routing details\nstatus: active\ndate: 2026-01-04\nauthor: Admin\n---\nBody." );

        // Unclustered pages
        engine.saveText( "RandomPage", "---\ntype: article\ntags:\n- misc\nsummary: A random unclustered page here\n---\nBody." );
        engine.saveText( "PlainPage", "Just plain text with no frontmatter at all." );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > executeAndParse( final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testFullMap() {
        final Map< String, Object > data = executeAndParse( Map.of() );

        assertTrue( ( ( Number ) data.get( "totalPages" ) ).intValue() >= 9 );

        final List< Map< String, Object > > clusters = ( List< Map< String, Object > > ) data.get( "clusters" );
        assertNotNull( clusters );
        assertTrue( clusters.size() >= 3 );

        // Find the "ai" cluster
        final Map< String, Object > aiCluster = clusters.stream()
                .filter( c -> "ai".equals( c.get( "name" ) ) )
                .findFirst().orElseThrow( () -> new AssertionError( "ai cluster not found" ) );
        assertEquals( "AiHub", aiCluster.get( "hub" ) );
        final List< String > aiPages = ( List< String > ) aiCluster.get( "pages" );
        assertTrue( aiPages.contains( "AiHub" ) );
        assertTrue( aiPages.contains( "AiArticle1" ) );

        // Unclustered pages should include pages without cluster field
        final List< String > unclustered = ( List< String > ) data.get( "unclusteredPages" );
        assertNotNull( unclustered );
        assertTrue( unclustered.contains( "RandomPage" ) );
        assertTrue( unclustered.contains( "PlainPage" ) );

        // metadataConventions
        final Map< String, Object > conventions = ( Map< String, Object > ) data.get( "metadataConventions" );
        assertNotNull( conventions );
        assertTrue( conventions.containsKey( "type" ) );
        final List< String > typeValues = ( List< String > ) conventions.get( "type" );
        assertTrue( typeValues.contains( "article" ) );
        assertTrue( typeValues.contains( "hub" ) );

        // pageMetadata
        final Map< String, Object > pageMeta = ( Map< String, Object > ) data.get( "pageMetadata" );
        assertNotNull( pageMeta );
        assertTrue( pageMeta.containsKey( "AiHub" ) );
        assertTrue( pageMeta.containsKey( "PlainPage" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSubClusterNested() {
        final Map< String, Object > data = executeAndParse( Map.of() );
        final List< Map< String, Object > > clusters = ( List< Map< String, Object > > ) data.get( "clusters" );

        final Map< String, Object > woodCluster = clusters.stream()
                .filter( c -> "woodworking".equals( c.get( "name" ) ) )
                .findFirst().orElseThrow( () -> new AssertionError( "woodworking cluster not found" ) );

        final List< Map< String, Object > > subClusters = ( List< Map< String, Object > > ) woodCluster.get( "subClusters" );
        assertNotNull( subClusters );
        assertEquals( 1, subClusters.size() );
        assertEquals( "woodworking/cnc", subClusters.get( 0 ).get( "name" ) );
        assertEquals( "WoodSubHub", subClusters.get( 0 ).get( "hub" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testHubDetection() {
        final Map< String, Object > data = executeAndParse( Map.of() );
        final List< Map< String, Object > > clusters = ( List< Map< String, Object > > ) data.get( "clusters" );

        for ( final Map< String, Object > cluster : clusters ) {
            final String name = ( String ) cluster.get( "name" );
            if ( "ai".equals( name ) ) {
                assertEquals( "AiHub", cluster.get( "hub" ) );
            } else if ( "finance".equals( name ) ) {
                assertEquals( "FinanceHub", cluster.get( "hub" ) );
            }
        }
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testScopedMode() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "cluster", "ai" );
        final Map< String, Object > data = executeAndParse( args );

        final List< Map< String, Object > > clusters = ( List< Map< String, Object > > ) data.get( "clusters" );
        assertEquals( 1, clusters.size() );
        assertEquals( "ai", clusters.get( 0 ).get( "name" ) );

        // Scoped mode should not include unclusteredPages or metadataConventions
        assertNull( data.get( "unclusteredPages" ) );
        assertNull( data.get( "metadataConventions" ) );

        // pageMetadata should only contain pages in the scoped cluster
        final Map< String, Object > pageMeta = ( Map< String, Object > ) data.get( "pageMetadata" );
        assertTrue( pageMeta.containsKey( "AiHub" ) );
        assertTrue( pageMeta.containsKey( "AiArticle1" ) );
        assertFalse( pageMeta.containsKey( "FinanceHub" ) );
    }

    @Test
    void testScopedModeClusterNotFound() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "cluster", "nonexistent" );
        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPageMetadataCompleteness() {
        final Map< String, Object > data = executeAndParse( Map.of() );
        final Map< String, Map< String, Object > > pageMeta =
                ( Map< String, Map< String, Object > > ) data.get( "pageMetadata" );

        // AiHub should have all 8 fields
        final Map< String, Object > aiHubMeta = pageMeta.get( "AiHub" );
        assertNotNull( aiHubMeta );
        assertEquals( "hub", aiHubMeta.get( "type" ) );
        assertEquals( "ai", aiHubMeta.get( "cluster" ) );
        assertNotNull( aiHubMeta.get( "tags" ) );
        assertNotNull( aiHubMeta.get( "summary" ) );
        assertNotNull( aiHubMeta.get( "status" ) );
        assertNotNull( aiHubMeta.get( "date" ) );
        assertNotNull( aiHubMeta.get( "author" ) );
        assertNotNull( aiHubMeta.get( "related" ) );

        // PlainPage (no frontmatter) should have an empty map
        final Map< String, Object > plainMeta = pageMeta.get( "PlainPage" );
        assertNotNull( plainMeta );
        assertTrue( plainMeta.isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testClusterWithNoHub() throws Exception {
        engine.saveText( "OrphanClusterPage", "---\ntype: article\ncluster: orphan-cluster\n---\nBody." );

        final Map< String, Object > data = executeAndParse( Map.of() );
        final List< Map< String, Object > > clusters = ( List< Map< String, Object > > ) data.get( "clusters" );

        final Map< String, Object > orphanCluster = clusters.stream()
                .filter( c -> "orphan-cluster".equals( c.get( "name" ) ) )
                .findFirst().orElseThrow( () -> new AssertionError( "orphan-cluster not found" ) );
        assertNull( orphanCluster.get( "hub" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_cluster_map", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
