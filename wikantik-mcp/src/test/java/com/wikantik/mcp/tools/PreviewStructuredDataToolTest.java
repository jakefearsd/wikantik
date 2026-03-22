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
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreviewStructuredDataToolTest {

    private TestEngine engine;
    private PreviewStructuredDataTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new PreviewStructuredDataTool(
                engine.getManager( PageManager.class ), engine );
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
    void testArticleWithFullMetadata() throws Exception {
        engine.saveText( "PreviewFull", "---\ntype: article\ntags:\n- ai\n- ml\ndate: 2026-03-15\n" +
                "summary: A comprehensive guide to modern AI techniques and applications\n" +
                "cluster: ai-fundamentals\nrelated:\n- AiOverview\n---\nBody content." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PreviewFull" );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "exists" ) );
        assertEquals( "PreviewFull", data.get( "pageName" ) );

        // Meta description
        final Map< String, Object > metaDesc = ( Map< String, Object > ) data.get( "metaDescription" );
        assertNotNull( metaDesc );
        assertEquals( "frontmatter_summary", metaDesc.get( "source" ) );
        assertTrue( metaDesc.get( "text" ).toString().contains( "AI techniques" ) );

        // Keywords
        assertNotNull( data.get( "metaKeywords" ) );

        // Open Graph
        final Map< String, Object > og = ( Map< String, Object > ) data.get( "openGraph" );
        assertNotNull( og );
        assertEquals( "article", og.get( "type" ) );

        // JSON-LD
        final Map< String, Object > jsonLd = ( Map< String, Object > ) data.get( "jsonLd" );
        assertNotNull( jsonLd );
        assertEquals( "Article", jsonLd.get( "@type" ) );
        assertEquals( "2026-03-15", jsonLd.get( "datePublished" ) );
        assertEquals( "ai-fundamentals", jsonLd.get( "articleSection" ) );
        assertNotNull( jsonLd.get( "isPartOf" ) );
        assertNotNull( jsonLd.get( "relatedLink" ) );

        // Breadcrumb
        final List< String > breadcrumb = ( List< String > ) data.get( "breadcrumbList" );
        assertNotNull( breadcrumb );
        assertEquals( 3, breadcrumb.size() );
        assertEquals( "Home", breadcrumb.get( 0 ) );
        assertEquals( "ai-fundamentals", breadcrumb.get( 1 ) );
        assertEquals( "PreviewFull", breadcrumb.get( 2 ) );

        // Atom feed
        final Map< String, Object > atom = ( Map< String, Object > ) data.get( "atomFeed" );
        assertNotNull( atom );
        assertEquals( true, atom.get( "included" ) );
        assertEquals( true, atom.get( "hasSummary" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testHubPage() throws Exception {
        engine.saveText( "PreviewHub", "---\ntype: hub\ntags:\n- ai\ndate: 2026-03-15\n" +
                "summary: Hub page for AI content cluster\n" +
                "cluster: ai-fundamentals\nrelated:\n- SubArticle1\n- SubArticle2\n---\nHub body." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PreviewHub" );

        final Map< String, Object > data = executeAndParse( args );

        final Map< String, Object > jsonLd = ( Map< String, Object > ) data.get( "jsonLd" );
        assertEquals( "CollectionPage", jsonLd.get( "@type" ) );
        assertNotNull( jsonLd.get( "hasPart" ) );
        assertNull( jsonLd.get( "isPartOf" ), "Hub pages should not have isPartOf" );

        // Hub pages don't get breadcrumbs
        assertNull( data.get( "breadcrumbList" ), "Hub pages should not have breadcrumbs" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPageWithoutFrontmatter() throws Exception {
        engine.saveText( "PreviewPlain", "Just plain body text without any frontmatter." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PreviewPlain" );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "exists" ) );

        // Fallback description
        final Map< String, Object > metaDesc = ( Map< String, Object > ) data.get( "metaDescription" );
        assertEquals( "generic_fallback", metaDesc.get( "source" ) );

        // No feed summary
        final Map< String, Object > atom = ( Map< String, Object > ) data.get( "atomFeed" );
        assertEquals( false, atom.get( "hasSummary" ) );

        // Not news-eligible
        final Map< String, Object > news = ( Map< String, Object > ) data.get( "newsSitemap" );
        assertEquals( false, news.get( "eligible" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testNewsSitemapEligibility() throws Exception {
        // Use today's date to ensure recency
        final String today = java.time.LocalDate.now().toString();
        engine.saveText( "PreviewNews", "---\ntype: article\ntags:\n- breaking\ndate: " + today + "\n" +
                "summary: Breaking news about AI developments\n---\nNews body." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PreviewNews" );

        final Map< String, Object > data = executeAndParse( args );
        final Map< String, Object > news = ( Map< String, Object > ) data.get( "newsSitemap" );
        assertEquals( true, news.get( "eligible" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testNewsSitemapIneligible_noTags() throws Exception {
        final String today = java.time.LocalDate.now().toString();
        engine.saveText( "PreviewNoTagNews", "---\ntype: article\ndate: " + today + "\n" +
                "summary: An article without tags\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PreviewNoTagNews" );

        final Map< String, Object > data = executeAndParse( args );
        final Map< String, Object > news = ( Map< String, Object > ) data.get( "newsSitemap" );
        assertEquals( false, news.get( "eligible" ) );
        assertTrue( news.get( "reason" ).toString().contains( "tags" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSummaryLengthWarnings() throws Exception {
        // Short summary
        engine.saveText( "PreviewWarnShort", "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\nsummary: Short\n---\nBody." );
        Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PreviewWarnShort" );
        Map< String, Object > data = executeAndParse( args );
        List< String > warnings = ( List< String > ) data.get( "warnings" );
        assertTrue( warnings.stream().anyMatch( w -> w.contains( "short" ) || w.contains( "50" ) ) );

        // Long summary
        engine.saveText( "PreviewWarnLong", "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\nsummary: " +
                "A".repeat( 200 ) + "\n---\nBody." );
        args = new HashMap<>();
        args.put( "pageName", "PreviewWarnLong" );
        data = executeAndParse( args );
        warnings = ( List< String > ) data.get( "warnings" );
        assertTrue( warnings.stream().anyMatch( w -> w.contains( "long" ) || w.contains( "truncate" ) ) );

        // Good summary
        engine.saveText( "PreviewWarnGood", "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\n" +
                "summary: A well-sized summary that describes the page content effectively for search\n---\nBody." );
        args = new HashMap<>();
        args.put( "pageName", "PreviewWarnGood" );
        data = executeAndParse( args );
        warnings = ( List< String > ) data.get( "warnings" );
        assertTrue( warnings.stream().anyMatch( w -> w.contains( "good" ) ) );
    }

    @Test
    void testNonExistentPage() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PreviewNonExistent" );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( false, data.get( "exists" ) );
        assertNotNull( data.get( "error" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "preview_structured_data", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
    }
}
