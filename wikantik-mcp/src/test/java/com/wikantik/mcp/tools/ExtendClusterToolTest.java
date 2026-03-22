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

class ExtendClusterToolTest {

    private TestEngine engine;
    private ExtendClusterTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        tool = new ExtendClusterTool( engine, engine.getManager( SystemPageRegistry.class ) );

        // Set up an existing cluster
        engine.saveText( "ExtHub", "---\ntype: hub\ncluster: ext-test\ntags:\n- test\n" +
                "summary: Hub for extension tests with enough characters\nstatus: active\n" +
                "date: 2026-01-01\nauthor: Admin\nrelated:\n- ExtArticle1\n---\n" +
                "# Ext Hub\n\n## Cluster Articles\n\n- [ExtArticle1](ExtArticle1) — first article\n\n## See Also\n" );
        engine.saveText( "ExtArticle1", "---\ntype: article\ncluster: ext-test\ntags:\n- test\n" +
                "summary: First article in the extension test cluster here\nstatus: active\n" +
                "date: 2026-01-02\nauthor: Admin\nrelated:\n- ExtHub\n---\n" +
                "# Ext Article 1\n\nContent.\n\n## See Also\n\n- [ExtHub](ExtHub)\n" );

        // Set up Main page with the cluster listed
        engine.saveText( "Main", "# Wiki\n\n## Article Clusters\n\n### Test Cluster\n\n" +
                "- [ExtHub](ExtHub) — Hub for extension tests\n- [ExtArticle1](ExtArticle1) — first article\n\n## About\n" );
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
    void testExtendCluster() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "ext-test" );
        args.put( "article", Map.of(
                "name", "ExtArticle2",
                "body", "# Ext Article 2\n\nNew content.\n\n## See Also\n\n- [ExtHub](ExtHub)\n",
                "metadata", Map.of( "tags", List.of( "test" ),
                        "summary", "Second article added by extend cluster tool",
                        "date", "2026-03-20", "author", "TestBot" )
        ) );
        args.put( "author", "TestBot" );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( "ext-test", data.get( "clusterName" ) );
        assertEquals( "ExtHub", data.get( "hub" ) );

        // Article created successfully
        final Map< String, Object > created = ( Map< String, Object > ) data.get( "articleCreated" );
        assertTrue( ( Boolean ) created.get( "success" ) );

        // Verify article exists with correct metadata
        final PageManager pm = engine.getManager( PageManager.class );
        final String articleText = pm.getPureText( "ExtArticle2", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( articleText );
        assertEquals( "article", parsed.metadata().get( "type" ) );
        assertEquals( "ext-test", parsed.metadata().get( "cluster" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testHubBodyPatched() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "ext-test" );
        args.put( "article", Map.of( "name", "ExtNew",
                "body", "# New\n\n## See Also\n\n- [ExtHub](ExtHub)\n",
                "metadata", Map.of( "summary", "A new article with a summary for SEO" ) ) );
        args.put( "updateMain", false );

        executeAndParse( args );

        // Hub body should now contain a link to ExtNew
        final String hubText = engine.getManager( PageManager.class ).getPureText( "ExtHub", -1 );
        assertTrue( hubText.contains( "(ExtNew)" ), "Hub body should contain link to new article" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testHubRelatedUpdated() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "ext-test" );
        args.put( "article", Map.of( "name", "ExtRelated",
                "body", "# Related\n\n## See Also\n\n- [ExtHub](ExtHub)\n" ) );
        args.put( "updateMain", false );

        executeAndParse( args );

        // Hub's related metadata should include the new article
        final String hubText = engine.getManager( PageManager.class ).getPureText( "ExtHub", -1 );
        final ParsedPage hubParsed = FrontmatterParser.parse( hubText );
        final List< String > hubRelated = ( List< String > ) hubParsed.metadata().get( "related" );
        assertTrue( hubRelated.contains( "ExtRelated" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSiblingRelatedUpdated() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "ext-test" );
        args.put( "article", Map.of( "name", "ExtSibling",
                "body", "# Sibling\n\n## See Also\n\n- [ExtHub](ExtHub)\n" ) );
        args.put( "updateMain", false );

        executeAndParse( args );

        // ExtArticle1's related should now include ExtSibling
        final String art1Text = engine.getManager( PageManager.class ).getPureText( "ExtArticle1", -1 );
        final ParsedPage art1Parsed = FrontmatterParser.parse( art1Text );
        final List< String > art1Related = ( List< String > ) art1Parsed.metadata().get( "related" );
        assertTrue( art1Related.contains( "ExtSibling" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAutoSetRelated() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "ext-test" );
        args.put( "article", Map.of( "name", "ExtAutoRel",
                "body", "# Auto\n\n## See Also\n\n- [ExtHub](ExtHub)\n" ) );
        args.put( "updateMain", false );

        executeAndParse( args );

        // New article's related should contain hub + existing sibling
        final String text = engine.getManager( PageManager.class ).getPureText( "ExtAutoRel", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( text );
        final List< String > related = ( List< String > ) parsed.metadata().get( "related" );
        assertTrue( related.contains( "ExtHub" ) );
        assertTrue( related.contains( "ExtArticle1" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMainPageUpdated() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "ext-test" );
        args.put( "article", Map.of( "name", "ExtMainPage",
                "body", "# Main Page Test\n\n## See Also\n\n- [ExtHub](ExtHub)\n",
                "metadata", Map.of( "summary", "Article to test Main page update" ) ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( "success", data.get( "mainPageUpdate" ) );

        // Main page should contain the new article
        final String mainText = engine.getManager( PageManager.class ).getPureText( "Main", -1 );
        assertTrue( mainText.contains( "(ExtMainPage)" ) );
    }

    @Test
    void testClusterNotFound() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "nonexistent" );
        args.put( "article", Map.of( "name", "X", "body", "Y" ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testVerificationWarnings() {
        // Article body does NOT link back to hub
        final Map< String, Object > args = new HashMap<>();
        args.put( "clusterName", "ext-test" );
        args.put( "article", Map.of( "name", "ExtNoLink",
                "body", "# No Link\n\nNo hub link here.\n" ) );
        args.put( "updateMain", false );

        final Map< String, Object > data = executeAndParse( args );
        final Map< String, Object > verification = ( Map< String, Object > ) data.get( "verification" );
        assertFalse( ( Boolean ) verification.get( "allGreen" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "extend_cluster", def.name() );
        assertNotNull( def.description() );
        assertFalse( def.annotations().readOnlyHint() );
    }
}
