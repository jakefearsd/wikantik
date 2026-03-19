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

class PatchPageToolTest {

    private TestEngine engine;
    private PatchPageTool tool;
    private final Gson gson = new Gson();

    private static final String PAGE_CONTENT =
            "---\ntype: guide\ntags: [finance]\n---\n" +
            "## Introduction\n" +
            "Intro text.\n" +
            "\n" +
            "## Details\n" +
            "Detail paragraph.\n" +
            "\n" +
            "## Further Reading\n" +
            "- Link 1";

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new PatchPageTool( engine, engine.getManager( SystemPageRegistry.class ) );
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
    void testAppendToSection() throws Exception {
        engine.saveText( "PatchAppend", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchAppend" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Further Reading", "content", "- Link 2" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "PatchAppend", -1 );
        assertTrue( stored.contains( "- Link 1" ) );
        assertTrue( stored.contains( "- Link 2" ) );
    }

    @Test
    void testAppendToLastSection() throws Exception {
        engine.saveText( "PatchAppendLast", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchAppendLast" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Further Reading", "content", "- Added link" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );
    }

    @Test
    void testInsertBefore() throws Exception {
        engine.saveText( "PatchBefore", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchBefore" );
        args.put( "operations", List.of(
                Map.of( "action", "insert_before", "marker", "Detail paragraph.", "content", "New line before." )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "PatchBefore", -1 );
        assertTrue( stored.contains( "New line before." ) );
    }

    @Test
    void testInsertAfter() throws Exception {
        engine.saveText( "PatchAfter", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchAfter" );
        args.put( "operations", List.of(
                Map.of( "action", "insert_after", "marker", "Intro text.", "content", "Extra intro." )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "PatchAfter", -1 );
        assertTrue( stored.contains( "Extra intro." ) );
    }

    @Test
    void testReplaceSection() throws Exception {
        engine.saveText( "PatchReplace", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchReplace" );
        args.put( "operations", List.of(
                Map.of( "action", "replace_section", "section", "Introduction", "content", "Completely new intro." )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "PatchReplace", -1 );
        assertTrue( stored.contains( "Completely new intro." ) );
        assertFalse( stored.contains( "Intro text." ) );
        assertTrue( stored.contains( "## Introduction" ) ); // heading preserved
    }

    @Test
    void testMultipleOperations() throws Exception {
        engine.saveText( "PatchMulti", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchMulti" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Further Reading", "content", "- Link 2" ),
                Map.of( "action", "insert_after", "marker", "Intro text.", "content", "Added after intro." )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "PatchMulti", -1 );
        assertTrue( stored.contains( "- Link 2" ) );
        assertTrue( stored.contains( "Added after intro." ) );
    }

    @Test
    void testSectionNotFound() throws Exception {
        engine.saveText( "PatchNoSection", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchNoSection" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "NonExistent", "content", "text" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Section not found" ) );
    }

    @Test
    void testMarkerNotFound() throws Exception {
        engine.saveText( "PatchNoMarker", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchNoMarker" );
        args.put( "operations", List.of(
                Map.of( "action", "insert_before", "marker", "NONEXISTENT_MARKER", "content", "text" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Marker not found" ) );
    }

    @Test
    void testOptimisticLockingVersion() throws Exception {
        engine.saveText( "PatchLock", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchLock" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Details", "content", "New detail." )
        ) );
        args.put( "expectedVersion", 999 );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Version conflict" ) );
    }

    @Test
    void testOptimisticLockingHash() throws Exception {
        engine.saveText( "PatchHashLock", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchHashLock" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Details", "content", "New detail." )
        ) );
        args.put( "expectedContentHash", "0000000000000000000000000000000000000000000000000000000000000000" );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Content hash conflict" ) );
    }

    @Test
    void testPreservesMetadata() throws Exception {
        engine.saveText( "PatchMeta", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchMeta" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Details", "content", "Extra detail." )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = engine.getManager( PageManager.class ).getPureText( "PatchMeta", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "guide", parsed.metadata().get( "type" ) );
    }

    @Test
    void testPageNotFound() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NonExistentPatchPage" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Intro", "content", "text" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Page not found" ) );
    }

    @Test
    void testContentHashInResponse() throws Exception {
        engine.saveText( "PatchHashResp", PAGE_CONTENT );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "PatchHashResp" );
        args.put( "operations", List.of(
                Map.of( "action", "append_to_section", "section", "Details", "content", "More." )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );
        assertNotNull( data.get( "contentHash" ) );
        assertEquals( 64, ( ( String ) data.get( "contentHash" ) ).length() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "patch_page", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
    }
}
