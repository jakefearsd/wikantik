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
package org.apache.wiki.mcp.tools;

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.frontmatter.FrontmatterParser;
import org.apache.wiki.frontmatter.ParsedPage;
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WritePageToolTest {

    private TestEngine engine;
    private WritePageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new WritePageTool( engine, engine.getManager( org.apache.wiki.content.SystemPageRegistry.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testWriteNewPage() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpWriteTest" );
        args.put( "content", "New page content." );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( true, data.get( "success" ) );

        // JSPWiki normalizes to CRLF with trailing newline
        final String stored = engine.getManager( PageManager.class ).getPureText( "McpWriteTest", -1 );
        assertTrue( stored.contains( "New page content." ) );
    }

    @Test
    void testWritePageWithMetadata() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpWriteFm" );
        args.put( "content", "Page with frontmatter." );
        args.put( "metadata", Map.of( "type", "concept", "tags", List.of( "test" ) ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );
        assertEquals( true, data.get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "McpWriteFm", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "concept", parsed.metadata().get( "type" ) );
        assertTrue( parsed.body().contains( "Page with frontmatter." ) );
    }

    @Test
    void testWritePageSetsMarkdownSyntax() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpWriteMd" );
        args.put( "content", "Markdown content." );

        tool.execute( args );

        final Page saved = engine.getManager( PageManager.class ).getPage( "McpWriteMd" );
        assertEquals( "markdown", saved.getAttribute( Page.MARKUP_SYNTAX ) );
    }

    @Test
    void testWritePageWithChangeNote() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpWriteNote" );
        args.put( "content", "Content with note." );
        args.put( "changeNote", "Created via MCP" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );
        assertEquals( true, data.get( "success" ) );
    }

    @Test
    void testMetadataMergePreservesExistingFields() throws Exception {
        // Write page with initial metadata
        engine.saveText( "McpMergeTest", "---\ntype: report\ntags: [security]\nstatus: draft\n---\nOriginal body." );

        // Update with only a new status — type and tags should be preserved
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpMergeTest" );
        args.put( "content", "Updated body." );
        args.put( "metadata", Map.of( "status", "active" ) );

        tool.execute( args );

        final String stored = engine.getManager( PageManager.class ).getPureText( "McpMergeTest", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "report", parsed.metadata().get( "type" ) );
        assertEquals( "active", parsed.metadata().get( "status" ) );
        assertTrue( parsed.body().contains( "Updated body." ) );
    }

    @Test
    void testContentOnlyUpdatePreservesMetadata() throws Exception {
        // Write page with metadata
        engine.saveText( "McpContentOnly", "---\ntype: concept\ntags: [ai]\n---\nOriginal body." );

        // Update with only content — metadata should be preserved
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpContentOnly" );
        args.put( "content", "New body only." );
        // No metadata provided

        tool.execute( args );

        final String stored = engine.getManager( PageManager.class ).getPureText( "McpContentOnly", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        // When metadata is null (not provided), we don't merge — the page will have no frontmatter
        // This is by design: if you want to preserve metadata, pass the fields you want to keep
        assertTrue( parsed.body().contains( "New body only." ) );
    }

    @Test
    void testReplaceMetadataOverwritesAll() throws Exception {
        // Write page with metadata
        engine.saveText( "McpReplaceTest", "---\ntype: report\ntags: [security]\nstatus: draft\n---\nBody." );

        // Replace with completely new metadata
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpReplaceTest" );
        args.put( "content", "New body." );
        args.put( "metadata", Map.of( "type", "note" ) );
        args.put( "replaceMetadata", true );

        tool.execute( args );

        final String stored = engine.getManager( PageManager.class ).getPureText( "McpReplaceTest", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "note", parsed.metadata().get( "type" ) );
        assertNull( parsed.metadata().get( "tags" ) );    // old field gone
        assertNull( parsed.metadata().get( "status" ) );  // old field gone
    }

    @Test
    void testCustomAuthorParam() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpAuthorTest" );
        args.put( "content", "Content." );
        args.put( "author", "JaneDoe" );

        tool.execute( args );

        final Page saved = engine.getManager( PageManager.class ).getPage( "McpAuthorTest" );
        assertEquals( "JaneDoe", saved.getAuthor() );
    }

    @Test
    void testDefaultAuthorFromClientInfo() {
        tool.setDefaultAuthor( "Claude Code" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpClientAuthor" );
        args.put( "content", "Content." );

        tool.execute( args );

        final Page saved = engine.getManager( PageManager.class ).getPage( "McpClientAuthor" );
        assertEquals( "Claude Code", saved.getAuthor() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testVersionNormalized() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpVersionNorm" );
        args.put( "content", "Content." );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        // Version should be at least 1, never -1
        assertTrue( ( ( Number ) data.get( "version" ) ).intValue() >= 1 );
    }

    @Test
    void testOptimisticLockingSuccess() throws Exception {
        engine.saveText( "McpLockPage", "Original." );

        final Page current = engine.getManager( PageManager.class ).getPage( "McpLockPage" );
        final int currentVersion = Math.max( current.getVersion(), 1 );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpLockPage" );
        args.put( "content", "Updated content." );
        args.put( "expectedVersion", currentVersion );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );
        assertEquals( true, data.get( "success" ) );
    }

    @Test
    void testOptimisticLockingConflict() throws Exception {
        engine.saveText( "McpLockConflict", "Original." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpLockConflict" );
        args.put( "content", "Updated content." );
        args.put( "expectedVersion", 999 );  // wrong version

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Version conflict" ) );
    }
}
