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
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublishClusterToolTest {

    private TestEngine engine;
    private PublishClusterTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        tool = new PublishClusterTool( engine, engine.getManager( SystemPageRegistry.class ) );

        // Create a Main page with Article Clusters section
        engine.saveText( "Main", "# Wiki\n\n## Article Clusters\n\n### Existing Section\n\n- [SomePage](SomePage)\n\n## About\n\nFooter." );
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
    void testPublishCluster() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "test-cluster" );
        args.put( "hub", Map.of(
                "name", "TestClusterHub",
                "body", "# Test Cluster Hub\n\nOverview.\n\n## Cluster Articles\n\n- [TestArticle1](TestArticle1)\n- [TestArticle2](TestArticle2)\n\n## See Also\n",
                "metadata", Map.of( "tags", List.of( "test" ), "summary", "Hub for the test cluster with enough characters",
                        "date", "2026-03-20", "author", "TestBot" )
        ) );
        args.put( "articles", List.of(
                Map.of( "name", "TestArticle1",
                        "body", "# Test Article 1\n\nContent.\n\n## See Also\n\n- [TestClusterHub](TestClusterHub)\n",
                        "metadata", Map.of( "tags", List.of( "test" ), "summary", "First test article with enough chars for SEO",
                                "date", "2026-03-20", "author", "TestBot" ) ),
                Map.of( "name", "TestArticle2",
                        "body", "# Test Article 2\n\nContent.\n\n## See Also\n\n- [TestClusterHub](TestClusterHub)\n",
                        "metadata", Map.of( "tags", List.of( "test" ), "summary", "Second test article with enough chars for SEO",
                                "date", "2026-03-20", "author", "TestBot" ) )
        ) );
        args.put( "author", "TestBot" );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( "test-cluster", data.get( "clusterName" ) );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 3, results.size() );
        assertTrue( ( Boolean ) results.get( 0 ).get( "success" ) );
        assertTrue( ( Boolean ) results.get( 1 ).get( "success" ) );
        assertTrue( ( Boolean ) results.get( 2 ).get( "success" ) );

        // Verify pages exist with correct metadata
        final PageManager pm = engine.getManager( PageManager.class );
        assertNotNull( pm.getPage( "TestClusterHub" ) );
        assertNotNull( pm.getPage( "TestArticle1" ) );
        assertNotNull( pm.getPage( "TestArticle2" ) );

        // Verify hub metadata auto-set
        final String hubText = pm.getPureText( "TestClusterHub", -1 );
        final ParsedPage hubParsed = FrontmatterParser.parse( hubText );
        assertEquals( "hub", hubParsed.metadata().get( "type" ) );
        assertEquals( "test-cluster", hubParsed.metadata().get( "cluster" ) );
        assertEquals( "active", hubParsed.metadata().get( "status" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAutoSetArticleMetadata() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "auto-meta" );
        args.put( "hub", Map.of( "name", "AutoMetaHub",
                "body", "# Hub\n\n- [AutoMetaArticle](AutoMetaArticle)\n" ) );
        args.put( "articles", List.of(
                Map.of( "name", "AutoMetaArticle", "body", "# Article\n\nContent.\n" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertTrue( ( Boolean ) results.get( 0 ).get( "success" ) );
        assertTrue( ( Boolean ) results.get( 1 ).get( "success" ) );

        // Article should have auto-set type, cluster, status, related
        final String articleText = engine.getManager( PageManager.class ).getPureText( "AutoMetaArticle", -1 );
        final ParsedPage articleParsed = FrontmatterParser.parse( articleText );
        assertEquals( "article", articleParsed.metadata().get( "type" ) );
        assertEquals( "auto-meta", articleParsed.metadata().get( "cluster" ) );
        assertEquals( "active", articleParsed.metadata().get( "status" ) );

        final List< String > related = ( List< String > ) articleParsed.metadata().get( "related" );
        assertNotNull( related );
        assertTrue( related.contains( "AutoMetaHub" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRelatedAutoPopulation() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "rel-test" );
        args.put( "hub", Map.of( "name", "RelHub",
                "body", "# Hub\n\n- [RelA](RelA)\n- [RelB](RelB)\n" ) );
        args.put( "articles", List.of(
                Map.of( "name", "RelA", "body", "# A\n\n## See Also\n\n- [RelHub](RelHub)\n" ),
                Map.of( "name", "RelB", "body", "# B\n\n## See Also\n\n- [RelHub](RelHub)\n" )
        ) );

        executeAndParse( args );

        // Hub related should contain both articles
        final String hubText = engine.getManager( PageManager.class ).getPureText( "RelHub", -1 );
        final ParsedPage hubParsed = FrontmatterParser.parse( hubText );
        final List< String > hubRelated = ( List< String > ) hubParsed.metadata().get( "related" );
        assertTrue( hubRelated.contains( "RelA" ) );
        assertTrue( hubRelated.contains( "RelB" ) );

        // Article A's related should contain hub + sibling B
        final String aText = engine.getManager( PageManager.class ).getPureText( "RelA", -1 );
        final ParsedPage aParsed = FrontmatterParser.parse( aText );
        final List< String > aRelated = ( List< String > ) aParsed.metadata().get( "related" );
        assertTrue( aRelated.contains( "RelHub" ) );
        assertTrue( aRelated.contains( "RelB" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMainPageUpdate() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "main-test" );
        args.put( "hub", Map.of( "name", "MainTestHub",
                "body", "# Hub\n\n- [MainTestArticle](MainTestArticle)\n",
                "metadata", Map.of( "summary", "Test cluster for Main page update" ) ) );
        args.put( "articles", List.of(
                Map.of( "name", "MainTestArticle", "body", "# Article\n\nContent.\n",
                        "metadata", Map.of( "summary", "An article in the test cluster" ) )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( "success", data.get( "mainPageUpdate" ) );

        // Check Main page contains the new cluster
        final String mainText = engine.getManager( PageManager.class ).getPureText( "Main", -1 );
        assertTrue( mainText.contains( "MainTestHub" ) );
        assertTrue( mainText.contains( "MainTestArticle" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testVerificationWarnings() {
        // Article body does NOT link back to hub
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "warn-test" );
        args.put( "hub", Map.of( "name", "WarnHub",
                "body", "# Hub\n\n- [WarnArticle](WarnArticle)\n" ) );
        args.put( "articles", List.of(
                Map.of( "name", "WarnArticle", "body", "# Article\n\nNo hub link here.\n" )
        ) );
        args.put( "updateMain", false );

        final Map< String, Object > data = executeAndParse( args );
        final Map< String, Object > verification = ( Map< String, Object > ) data.get( "verification" );
        assertFalse( ( Boolean ) verification.get( "allGreen" ) );
        final List< String > warnings = ( List< String > ) verification.get( "warnings" );
        assertTrue( warnings.stream().anyMatch( w -> w.contains( "WarnArticle" ) && w.contains( "hub" ) ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMainPageUpdateDisabled() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "no-main" );
        args.put( "hub", Map.of( "name", "NoMainHub", "body", "# Hub\n" ) );
        args.put( "articles", List.of( Map.of( "name", "NoMainArticle", "body", "# Article\n" ) ) );
        args.put( "updateMain", false );

        final Map< String, Object > data = executeAndParse( args );
        assertNull( data.get( "mainPageUpdate" ) );
    }

    @Test
    void testMissingClusterName() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "hub", Map.of( "name", "X", "body", "Y" ) );
        args.put( "articles", List.of( Map.of( "name", "Z", "body", "W" ) ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "publish_cluster", def.name() );
        assertNotNull( def.description() );
        assertFalse( def.annotations().readOnlyHint() );
    }
}
