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
import com.wikantik.references.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditCrossClusterToolTest {

    private TestEngine engine;
    private AuditCrossClusterTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        tool = new AuditCrossClusterTool(
                engine.getManager( PageManager.class ),
                engine.getManager( ReferenceManager.class ),
                engine.getManager( SystemPageRegistry.class ) );

        // Create Main page that links to ClusterAHub but NOT ClusterBHub
        engine.saveText( "Main", "---\ntype: hub\n---\n# Wiki Main\n\n- [ClusterAHub](ClusterAHub)\n" );

        // Cluster A: tags ai, ml — hub page links to its article
        engine.saveText( "ClusterAHub", "---\ntype: hub\ncluster: cluster-a\ntags:\n- ai\n- ml\n" +
                "summary: Hub for cluster A content and articles\n---\n# Cluster A\n\n- [ClusterAArticle](ClusterAArticle)" );
        engine.saveText( "ClusterAArticle", "---\ntype: article\ncluster: cluster-a\ntags:\n- ai\n" +
                "summary: Article in cluster A about artificial intelligence\n---\nBody." );

        // Cluster B: tags ai, finance — NO cross-reference to Cluster A (shared tag: ai)
        engine.saveText( "ClusterBHub", "---\ntype: hub\ncluster: cluster-b\ntags:\n- ai\n- finance\n" +
                "summary: Hub for cluster B content and articles\n---\n# Cluster B\n\n- [ClusterBArticle](ClusterBArticle)" );
        engine.saveText( "ClusterBArticle", "---\ntype: article\ncluster: cluster-b\ntags:\n- finance\n" +
                "summary: Article in cluster B about financial topics\n---\nBody." );

        // Page with duplicate summary
        engine.saveText( "DupSummary1", "---\ntype: article\ncluster: cluster-a\nsummary: Exact same summary text here\n---\nBody." );
        engine.saveText( "DupSummary2", "---\ntype: article\ncluster: cluster-b\nsummary: Exact same summary text here\n---\nBody." );
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
    void testCrossClusterGap() {
        final Map< String, Object > data = executeAndParse( Map.of() );

        final List< Map< String, Object > > gaps = ( List< Map< String, Object > > ) data.get( "crossClusterGaps" );
        // cluster-a and cluster-b share "ai" tag but have no cross-references
        assertTrue( gaps.stream().anyMatch( g ->
                ( "cluster-a".equals( g.get( "clusterA" ) ) && "cluster-b".equals( g.get( "clusterB" ) ) ) ||
                ( "cluster-b".equals( g.get( "clusterA" ) ) && "cluster-a".equals( g.get( "clusterB" ) ) ) ),
                "Should detect cross-cluster gap between cluster-a and cluster-b" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMainPageMissingHub() {
        final Map< String, Object > data = executeAndParse( Map.of() );

        final List< String > mainGaps = ( List< String > ) data.get( "mainPageGaps" );
        // ClusterBHub is not linked from Main
        assertTrue( mainGaps.contains( "ClusterBHub" ),
                "Should detect ClusterBHub missing from Main page" );
        // ClusterAHub is linked
        assertFalse( mainGaps.contains( "ClusterAHub" ),
                "ClusterAHub should not be in gaps" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testDuplicateSummaries() {
        final Map< String, Object > data = executeAndParse( Map.of() );

        final List< Map< String, Object > > dups = ( List< Map< String, Object > > ) data.get( "duplicateSummaries" );
        assertTrue( dups.stream().anyMatch( d ->
                ( "DupSummary1".equals( d.get( "pageA" ) ) && "DupSummary2".equals( d.get( "pageB" ) ) ) ||
                ( "DupSummary2".equals( d.get( "pageA" ) ) && "DupSummary1".equals( d.get( "pageB" ) ) ) ),
                "Should detect duplicate summaries between DupSummary1 and DupSummary2" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testOrphanedPages() {
        final Map< String, Object > data = executeAndParse( Map.of() );

        final List< String > orphans = ( List< String > ) data.get( "orphanedPages" );
        // Main should not appear in orphaned pages
        assertFalse( orphans.contains( "Main" ), "Main should be excluded from orphaned pages" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPerClusterBrokenLinksDedup() throws Exception {
        // Create a page with a broken link
        engine.saveText( "CrossBrokenSource", "---\ntype: article\n---\nSee [BrokenTarget](BrokenTarget)." );

        final Map< String, Object > argsWithDedup = new HashMap<>();
        argsWithDedup.put( "perClusterBrokenLinks", List.of( "BrokenTarget" ) );

        final Map< String, Object > data = executeAndParse( argsWithDedup );
        final List< Map< String, Object > > broken = ( List< Map< String, Object > > ) data.get( "globalBrokenLinks" );

        // BrokenTarget should be excluded since it's in perClusterBrokenLinks
        assertFalse( broken.stream().anyMatch( b -> "BrokenTarget".equals( b.get( "targetPage" ) ) ),
                "BrokenTarget should be deduplicated" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testScopedMode() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusters", List.of( "cluster-a" ) );

        final Map< String, Object > data = executeAndParse( args );

        // Should still run all checks but scoped
        assertNotNull( data.get( "orphanedPages" ) );
        assertNotNull( data.get( "crossClusterGaps" ) );
        assertNotNull( data.get( "mainPageGaps" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "audit_cross_cluster", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
