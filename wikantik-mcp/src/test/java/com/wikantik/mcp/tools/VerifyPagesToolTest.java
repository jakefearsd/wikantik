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
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubReferenceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VerifyPagesToolTest {

    private StubPageManager pm;
    private StubReferenceManager refMgr;
    private VerifyPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        refMgr = new StubReferenceManager();
        tool = new VerifyPagesTool( pm, refMgr );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > executeAndParse( final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testExistingPage() throws Exception {
        pm.savePage( "VerifyExist", "---\ntype: article\ntags:\n- test\nsummary: A test page\n---\nBody." );
        refMgr.addReferences( "VerifyExist", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "VerifyExist" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( true, pages.get( 0 ).get( "exists" ) );
        assertNotNull( pages.get( 0 ).get( "version" ) );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        assertEquals( true, summary.get( "allExist" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testNonExistentPage() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "VerifyMissing" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( false, pages.get( 0 ).get( "exists" ) );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        assertEquals( false, summary.get( "allExist" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMissingMetadata() throws Exception {
        pm.savePage( "VerifyNoMeta", "Body with no frontmatter." );
        refMgr.addReferences( "VerifyNoMeta", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "VerifyNoMeta" ) );
        args.put( "checks", List.of( "existence", "metadata_completeness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > missing = ( List< String > ) pages.get( 0 ).get( "missingMetadata" );
        assertTrue( missing.contains( "type" ) );
        assertTrue( missing.contains( "tags" ) );
        assertTrue( missing.contains( "summary" ) );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        final List< String > metadataIssues = ( List< String > ) summary.get( "metadataIssues" );
        assertFalse( metadataIssues.isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testBrokenLinks() throws Exception {
        pm.savePage( "VerifyLinks", "---\ntype: article\ntags:\n- test\nsummary: test\n---\nSee [page](NonExistentTarget)." );
        refMgr.addReferences( "VerifyLinks", Set.of( "NonExistentTarget" ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "VerifyLinks" ) );
        args.put( "checks", List.of( "existence", "broken_links", "outbound_links" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final Map< String, Object > page = pages.get( 0 );
        assertEquals( true, page.get( "exists" ) );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        assertNotNull( summary.get( "totalBrokenLinks" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSelectiveChecks() throws Exception {
        pm.savePage( "VerifySelective", "---\ntype: article\n---\nBody." );
        refMgr.addReferences( "VerifySelective", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "VerifySelective" ) );
        args.put( "checks", List.of( "existence" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final Map< String, Object > page = pages.get( 0 );
        assertEquals( true, page.get( "exists" ) );
        // Should NOT have link or metadata checks
        assertNull( page.get( "outboundLinks" ) );
        assertNull( page.get( "brokenLinks" ) );
        assertNull( page.get( "backlinks" ) );
        assertNull( page.get( "missingMetadata" ) );
    }

    @Test
    void testEmptyPageNamesFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of() );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "verify_pages", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMultiplePages() throws Exception {
        pm.savePage( "VerifyMulti1", "---\ntype: article\ntags:\n- a\nsummary: p1\n---\nBody 1." );
        pm.savePage( "VerifyMulti2", "---\ntype: article\ntags:\n- b\nsummary: p2\n---\nBody 2." );
        refMgr.addReferences( "VerifyMulti1", Set.of() );
        refMgr.addReferences( "VerifyMulti2", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "VerifyMulti1", "VerifyMulti2", "VerifyMultiMissing" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 3, pages.size() );
        assertEquals( true, pages.get( 0 ).get( "exists" ) );
        assertEquals( true, pages.get( 1 ).get( "exists" ) );
        assertEquals( false, pages.get( 2 ).get( "exists" ) );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        assertEquals( 3.0, summary.get( "totalPages" ) );
        assertEquals( false, summary.get( "allExist" ) );
    }

    // --- SEO Readiness Tests ---

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_goodMetadata() throws Exception {
        pm.savePage( "SeoGood", "---\ntype: article\ntags:\n- ai\n- ml\ndate: 2026-03-15\n" +
                "summary: A well-written summary that is between fifty and one hundred sixty characters long for SEO\n---\nBody." );
        refMgr.addReferences( "SeoGood", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoGood" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertNotNull( seoWarnings );
        assertTrue( seoWarnings.isEmpty(), "Good metadata should produce no SEO warnings but got: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_summaryTooShort() throws Exception {
        pm.savePage( "SeoShort", "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\nsummary: Short\n---\nBody." );
        refMgr.addReferences( "SeoShort", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoShort" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "too short" ) ),
                "Should warn about short summary: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_summaryTooLong() throws Exception {
        final String longSummary = "A".repeat( 200 );
        pm.savePage( "SeoLong", "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\nsummary: " + longSummary + "\n---\nBody." );
        refMgr.addReferences( "SeoLong", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoLong" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "truncate" ) ),
                "Should warn about long summary: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_missingSummary() throws Exception {
        pm.savePage( "SeoNoSummary", "---\ntype: article\ntags:\n- ai\ndate: 2026-03-15\n---\nBody." );
        refMgr.addReferences( "SeoNoSummary", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoNoSummary" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "meta description" ) ),
                "Should warn about missing summary: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_missingTags() throws Exception {
        pm.savePage( "SeoNoTags", "---\ntype: article\ndate: 2026-03-15\n" +
                "summary: A reasonable summary that is long enough for SEO purposes and search engines\n---\nBody." );
        refMgr.addReferences( "SeoNoTags", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoNoTags" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "News Sitemap" ) ),
                "Should warn about missing tags: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_hubMissingRelated() throws Exception {
        pm.savePage( "SeoHubNoRel", "---\ntype: hub\ntags:\n- ai\ndate: 2026-03-15\n" +
                "summary: A hub page about AI topics with enough characters for a good meta description\n---\nBody." );
        refMgr.addReferences( "SeoHubNoRel", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoHubNoRel" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "hasPart" ) ),
                "Should warn about hub with no related: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_hubBrokenRelated() throws Exception {
        pm.savePage( "SeoHubBroken", "---\ntype: hub\ntags:\n- ai\ndate: 2026-03-15\n" +
                "summary: A hub page about AI topics with enough characters for a good meta description\n" +
                "related:\n- NonExistentPage\n---\nBody." );
        refMgr.addReferences( "SeoHubBroken", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoHubBroken" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "broken" ) && w.contains( "hasPart" ) ),
                "Should warn about broken hasPart: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_missingDate() throws Exception {
        pm.savePage( "SeoNoDate", "---\ntype: article\ntags:\n- ai\n" +
                "summary: A reasonable summary that is long enough for SEO purposes and search engines\n---\nBody." );
        refMgr.addReferences( "SeoNoDate", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoNoDate" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "datePublished" ) ),
                "Should warn about missing date: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_clusterWithoutType() throws Exception {
        pm.savePage( "SeoClusterNoType", "---\ncluster: my-cluster\ntags:\n- ai\ndate: 2026-03-15\n" +
                "summary: A reasonable summary that is long enough for SEO purposes and search engines\n---\nBody." );
        refMgr.addReferences( "SeoClusterNoType", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoClusterNoType" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final List< String > seoWarnings = ( List< String > ) pages.get( 0 ).get( "seoWarnings" );
        assertTrue( seoWarnings.stream().anyMatch( w -> w.contains( "type" ) ),
                "Should warn about cluster without type: " + seoWarnings );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_notInDefaultChecks() throws Exception {
        pm.savePage( "SeoDefault", "---\ntype: article\n---\nBody." );
        refMgr.addReferences( "SeoDefault", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoDefault" ) );
        // No checks parameter -- uses defaults

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertNull( pages.get( 0 ).get( "seoWarnings" ), "seo_readiness should not run by default" );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        assertNull( summary.get( "seoIssues" ), "seoIssues should not appear in default summary" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSeoReadiness_summaryInSummary() throws Exception {
        pm.savePage( "SeoSummary1", "---\ntype: article\n---\nBody." );
        refMgr.addReferences( "SeoSummary1", Set.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageNames", List.of( "SeoSummary1" ) );
        args.put( "checks", List.of( "seo_readiness" ) );

        final Map< String, Object > data = executeAndParse( args );
        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        final List< String > seoIssues = ( List< String > ) summary.get( "seoIssues" );
        assertNotNull( seoIssues, "seo_readiness check should produce seoIssues in summary" );
        assertFalse( seoIssues.isEmpty() );
    }
}
