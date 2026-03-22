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
import com.wikantik.references.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditClusterToolTest {

    private TestEngine engine;
    private AuditClusterTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        tool = new AuditClusterTool(
                engine.getManager( PageManager.class ),
                engine.getManager( ReferenceManager.class ) );

        // Create a hub page that links to Article1 but not Article2
        engine.saveText( "AuditHub", "---\ntype: hub\ncluster: audit-test\ntags:\n- audit\n" +
                "summary: The hub page for audit test cluster with enough chars\n" +
                "status: active\ndate: 2026-01-01\nauthor: Admin\n" +
                "related:\n- AuditArticle1\n- AuditArticle2\n---\n" +
                "# Audit Hub\n\nSee [Article1](AuditArticle1).\n\n## See Also\n\n- [AuditArticle1](AuditArticle1)" );

        // Article1: good metadata, links back to hub
        engine.saveText( "AuditArticle1", "---\ntype: article\ncluster: audit-test\ntags:\n- audit\n" +
                "summary: A proper article with a summary that is long enough for SEO purposes\n" +
                "status: active\ndate: 2026-01-02\nauthor: Admin\n" +
                "related:\n- AuditHub\n---\n" +
                "# Article 1\n\nContent.\n\n## See Also\n\n- [AuditHub](AuditHub)" );

        // Article2: missing hub backlink, short summary, missing status
        engine.saveText( "AuditArticle2", "---\ntype: article\ncluster: audit-test\ntags:\n- audit\n" +
                "summary: Short\nauthor: MCP\ndate: 2026-01-03\n" +
                "related:\n- AuditHub\n---\n" +
                "# Article 2\n\nSee [BrokenPageXyz](BrokenPageXyz).\n\n## See Also\n\n- [OtherPage](OtherPage)" );
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
    void testMissingHubBacklink() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > structural = ( List< Map< String, Object > > ) data.get( "structural" );
        assertTrue( structural.stream().anyMatch(
                s -> "AuditArticle2".equals( s.get( "page" ) ) && "missing_hub_backlink".equals( s.get( "issue" ) ) ),
                "Should detect AuditArticle2 missing hub backlink" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testHubMissingSubLink() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > structural = ( List< Map< String, Object > > ) data.get( "structural" );
        assertTrue( structural.stream().anyMatch(
                s -> "AuditHub".equals( s.get( "page" ) ) && "hub_missing_sub_link".equals( s.get( "issue" ) ) ),
                "Should detect hub missing link to AuditArticle2" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMetadataGaps() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > metadataIssues = ( List< Map< String, Object > > ) data.get( "metadata" );
        // AuditArticle2 is missing "status"
        assertTrue( metadataIssues.stream().anyMatch(
                m -> "AuditArticle2".equals( m.get( "page" ) ) && "status".equals( m.get( "field" ) ) && "missing".equals( m.get( "issue" ) ) ),
                "Should detect missing status on AuditArticle2" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSummaryLengthViolation() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > metadataIssues = ( List< Map< String, Object > > ) data.get( "metadata" );
        assertTrue( metadataIssues.stream().anyMatch(
                m -> "AuditArticle2".equals( m.get( "page" ) ) && "summary".equals( m.get( "field" ) ) && "too_short".equals( m.get( "issue" ) ) ),
                "Should flag AuditArticle2's short summary" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAuthorMcp() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > metadataIssues = ( List< Map< String, Object > > ) data.get( "metadata" );
        assertTrue( metadataIssues.stream().anyMatch(
                m -> "AuditArticle2".equals( m.get( "page" ) ) && "author".equals( m.get( "field" ) ) && "default_author".equals( m.get( "issue" ) ) ),
                "Should flag author=MCP on AuditArticle2" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAutoFixableClassification() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > autoFixable = ( List< Map< String, Object > > ) data.get( "autoFixable" );
        // Missing status should be auto-fixable
        assertTrue( autoFixable.stream().anyMatch(
                f -> "AuditArticle2".equals( f.get( "page" ) ) && "set_metadata".equals( f.get( "action" ) ) && "status".equals( f.get( "field" ) ) ),
                "Missing status should be auto-fixable" );

        // Missing hub backlink with See Also section should be auto-fixable
        assertTrue( autoFixable.stream().anyMatch(
                f -> "AuditArticle2".equals( f.get( "page" ) ) && "add_hub_backlink".equals( f.get( "action" ) ) ),
                "Missing hub backlink with See Also should be auto-fixable" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testDraftExcludedFromSeo() throws Exception {
        engine.saveText( "AuditDraft", "---\ntype: article\ncluster: audit-test\nstatus: draft\nauthor: Admin\ndate: 2026-01-01\n" +
                "related:\n- AuditHub\n---\n# Draft\n\nDraft content.\n\n## See Also\n\n- [AuditHub](AuditHub)" );

        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > seo = ( List< Map< String, Object > > ) data.get( "seo" );
        // Draft pages should not have SEO warnings
        assertFalse( seo.stream().anyMatch( s -> "AuditDraft".equals( s.get( "page" ) ) ),
                "Draft pages should be excluded from SEO checks" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSummaryOutput() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final Map< String, Object > summary = ( Map< String, Object > ) data.get( "summary" );
        assertNotNull( summary.get( "critical" ) );
        assertNotNull( summary.get( "warning" ) );
        assertNotNull( summary.get( "suggestion" ) );
        assertNotNull( summary.get( "autoFixable" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSummariesCollected() {
        final Map< String, Object > data = executeAndParse( Map.of( "cluster", "audit-test" ) );

        final List< Map< String, Object > > summaries = ( List< Map< String, Object > > ) data.get( "summaries" );
        assertTrue( summaries.size() >= 2, "Should collect summaries from cluster pages" );
    }

    @Test
    void testClusterNotFound() {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "cluster", "nonexistent" ) );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "audit_cluster", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
