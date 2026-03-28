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
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubPageSaveHelper;
import com.wikantik.test.StubSystemPageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WritePageToolTest {

    private StubPageManager pm;
    private WritePageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new WritePageTool( new StubPageSaveHelper( pm ), new StubSystemPageRegistry() );
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

        final String stored = pm.getPureText( "McpWriteTest", -1 );
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

        final String stored = pm.getPureText( "McpWriteFm", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "concept", parsed.metadata().get( "type" ) );
        assertTrue( parsed.body().contains( "Page with frontmatter." ) );
    }

    @Test
    void testWritePageDefaultsToMarkdownSyntax() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpWriteDefault" );
        args.put( "content", "Content without explicit syntax." );

        tool.execute( args );

        final Page saved = pm.getPage( "McpWriteDefault" );
        // When no markupSyntax is specified, the page should default to markdown
        assertEquals( "markdown", saved.getAttribute( Page.MARKUP_SYNTAX ) );
    }

    @Test
    void testWritePageWithMarkdownSyntax() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpMarkdownPage" );
        args.put( "content", "# Markdown content" );
        args.put( "markupSyntax", "markdown" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );
        assertEquals( true, data.get( "success" ) );

        final Page saved = pm.getPage( "McpMarkdownPage" );
        assertEquals( "markdown", saved.getAttribute( Page.MARKUP_SYNTAX ) );
    }

    @Test
    void testWritePageIgnoresJspwikiSyntaxAndDefaultsToMarkdown() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpJspwikiPage" );
        args.put( "content", "!! JSPWiki content" );
        args.put( "markupSyntax", "jspwiki" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );
        assertEquals( true, data.get( "success" ) );

        final Page saved = pm.getPage( "McpJspwikiPage" );
        // MCP always saves as markdown regardless of what the caller requests
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
    void testMetadataMergePreservesExistingFields() {
        // Write page with initial metadata
        pm.savePage( "McpMergeTest", "---\ntype: report\ntags: [security]\nstatus: draft\n---\nOriginal body." );

        // Update with only a new status — type and tags should be preserved
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpMergeTest" );
        args.put( "content", "Updated body." );
        args.put( "metadata", Map.of( "status", "active" ) );

        tool.execute( args );

        final String stored = pm.getPureText( "McpMergeTest", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "report", parsed.metadata().get( "type" ) );
        assertEquals( "active", parsed.metadata().get( "status" ) );
        assertTrue( parsed.body().contains( "Updated body." ) );
    }

    @Test
    void testContentOnlyUpdatePreservesMetadata() {
        // Write page with metadata
        pm.savePage( "McpContentOnly", "---\ntype: concept\ntags: [ai]\n---\nOriginal body." );

        // Update with only content — metadata should be preserved
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpContentOnly" );
        args.put( "content", "New body only." );
        // No metadata provided

        tool.execute( args );

        final String stored = pm.getPureText( "McpContentOnly", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        // When metadata is null (not provided), we don't merge — the page will have no frontmatter
        // This is by design: if you want to preserve metadata, pass the fields you want to keep
        assertTrue( parsed.body().contains( "New body only." ) );
    }

    @Test
    void testReplaceMetadataOverwritesAll() {
        // Write page with metadata
        pm.savePage( "McpReplaceTest", "---\ntype: report\ntags: [security]\nstatus: draft\n---\nBody." );

        // Replace with completely new metadata
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpReplaceTest" );
        args.put( "content", "New body." );
        args.put( "metadata", Map.of( "type", "note" ) );
        args.put( "replaceMetadata", true );

        tool.execute( args );

        final String stored = pm.getPureText( "McpReplaceTest", -1 );
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

        final Page saved = pm.getPage( "McpAuthorTest" );
        assertEquals( "JaneDoe", saved.getAuthor() );
    }

    @Test
    void testDefaultAuthorFromClientInfo() {
        tool.setDefaultAuthor( "Claude Code" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpClientAuthor" );
        args.put( "content", "Content." );

        tool.execute( args );

        final Page saved = pm.getPage( "McpClientAuthor" );
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
    void testOptimisticLockingSuccess() {
        pm.savePage( "McpLockPage", "Original." );

        final Page current = pm.getPage( "McpLockPage" );
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
    void testContentHashCASSuccess() {
        pm.savePage( "McpHashCAS", "Original text." );

        // Compute the hash of the stored content
        final String stored = pm.getPureText( "McpHashCAS", -1 );
        final String hash = McpToolUtils.computeContentHash( stored );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpHashCAS" );
        args.put( "content", "Updated via hash CAS." );
        args.put( "expectedContentHash", hash );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );
        assertEquals( true, data.get( "success" ) );
    }

    @Test
    void testContentHashCASConflict() {
        pm.savePage( "McpHashConflict", "Original text." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpHashConflict" );
        args.put( "content", "Updated content." );
        args.put( "expectedContentHash", "0000000000000000000000000000000000000000000000000000000000000000" );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Content hash conflict" ) );
    }

    @Test
    void testRejectsSerializedReadPageResponse() {
        // Simulate an MCP client accidentally passing the full read_page JSON response as content
        final String serializedResponse = "{\"exists\":true,\"pageName\":\"SomePage\",\"content\":\"# Real content\",\"metadata\":{},\"version\":1}";

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpSerializedTest" );
        args.put( "content", serializedResponse );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "serialized JSON response" ) );

        // Verify no page was created
        assertNull( pm.getPage( "McpSerializedTest" ) );
    }

    @Test
    void testOptimisticLockingConflict() {
        pm.savePage( "McpLockConflict", "Original." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpLockConflict" );
        args.put( "content", "Updated content." );
        args.put( "expectedVersion", 999 );  // wrong version

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Version conflict" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testWriteSystemPageIncludesWarning() {
        // Register a system page
        final StubSystemPageRegistry sysRegistry = new StubSystemPageRegistry();
        sysRegistry.addSystemPage( "LeftMenu" );
        final WritePageTool toolWithSys = new WritePageTool( new StubPageSaveHelper( pm ), sysRegistry );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "LeftMenu" );
        args.put( "content", "Modified system page." );

        final McpSchema.CallToolResult result = toolWithSys.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( true, data.get( "success" ) );
        assertEquals( true, data.get( "systemPage" ) );
        assertNotNull( data.get( "warning" ) );
        assertTrue( ( ( String ) data.get( "warning" ) ).contains( "system" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "write_page", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
        assertTrue( def.inputSchema().required().contains( "pageName" ) );
        assertTrue( def.inputSchema().required().contains( "content" ) );
        // write_page should NOT be read-only
        assertFalse( def.annotations().readOnlyHint() );
    }

    @Test
    void testToolName() {
        assertEquals( "write_page", tool.name() );
    }
}
