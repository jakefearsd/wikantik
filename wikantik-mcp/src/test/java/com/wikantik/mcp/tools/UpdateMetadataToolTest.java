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
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.test.StubPageManager;
import com.wikantik.test.StubPageSaveHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpdateMetadataToolTest {

    private StubPageManager pm;
    private UpdateMetadataTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new UpdateMetadataTool( new StubPageSaveHelper( pm ), pm );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > executeAndParse( final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }

    @Test
    void testSetField() {
        pm.savePage( "MetaSet", "---\ntype: guide\n---\nBody text." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaSet" );
        args.put( "operations", List.of(
                Map.of( "field", "status", "action", "set", "value", "published" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaSet", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "published", parsed.metadata().get( "status" ) );
        assertEquals( "guide", parsed.metadata().get( "type" ) );
    }

    @Test
    void testSetExistingField() {
        pm.savePage( "MetaSetEx", "---\nstatus: draft\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaSetEx" );
        args.put( "operations", List.of(
                Map.of( "field", "status", "action", "set", "value", "active" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaSetEx", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertEquals( "active", parsed.metadata().get( "status" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAppendToList() {
        pm.savePage( "MetaAppend", "---\ntags:\n- finance\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaAppend" );
        args.put( "operations", List.of(
                Map.of( "field", "tags", "action", "append_to_list", "value", "investing" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaAppend", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        final List< String > tags = ( List< String > ) parsed.metadata().get( "tags" );
        assertTrue( tags.contains( "finance" ) );
        assertTrue( tags.contains( "investing" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAppendToNewList() {
        pm.savePage( "MetaNewList", "---\ntype: guide\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaNewList" );
        args.put( "operations", List.of(
                Map.of( "field", "tags", "action", "append_to_list", "value", "new-tag" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaNewList", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        final List< String > tags = ( List< String > ) parsed.metadata().get( "tags" );
        assertEquals( 1, tags.size() );
        assertEquals( "new-tag", tags.get( 0 ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAppendDuplicateIdempotent() {
        pm.savePage( "MetaDup", "---\ntags:\n- finance\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaDup" );
        args.put( "operations", List.of(
                Map.of( "field", "tags", "action", "append_to_list", "value", "finance" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaDup", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        final List< String > tags = ( List< String > ) parsed.metadata().get( "tags" );
        // Should not have duplicates
        assertEquals( 1, tags.stream().filter( t -> t.equals( "finance" ) ).count() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRemoveFromList() {
        pm.savePage( "MetaRemove", "---\ntags:\n- finance\n- investing\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaRemove" );
        args.put( "operations", List.of(
                Map.of( "field", "tags", "action", "remove_from_list", "value", "finance" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaRemove", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        final List< String > tags = ( List< String > ) parsed.metadata().get( "tags" );
        assertFalse( tags.contains( "finance" ) );
        assertTrue( tags.contains( "investing" ) );
    }

    @Test
    void testDeleteField() {
        pm.savePage( "MetaDelete", "---\ntype: guide\nstatus: draft\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaDelete" );
        args.put( "operations", List.of(
                Map.of( "field", "status", "action", "delete" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaDelete", -1 );
        final ParsedPage parsed = FrontmatterParser.parse( stored );
        assertNull( parsed.metadata().get( "status" ) );
        assertEquals( "guide", parsed.metadata().get( "type" ) );
    }

    @Test
    void testBodyPreserved() {
        pm.savePage( "MetaBody", "---\ntype: guide\n---\nImportant body content." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaBody" );
        args.put( "operations", List.of(
                Map.of( "field", "status", "action", "set", "value", "published" )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        assertEquals( true, data.get( "success" ) );

        final String stored = pm.getPureText( "MetaBody", -1 );
        assertTrue( stored.contains( "Important body content." ) );
    }

    @Test
    void testPageNotFound() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NonExistentMetaPage" );
        args.put( "operations", List.of(
                Map.of( "field", "status", "action", "set", "value", "x" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testFieldTypeMismatch() {
        pm.savePage( "MetaMismatch", "---\ntype: guide\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaMismatch" );
        args.put( "operations", List.of(
                Map.of( "field", "type", "action", "append_to_list", "value", "extra" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not a list" ) );
    }

    @Test
    void testOptimisticLocking() {
        pm.savePage( "MetaLock", "---\ntype: guide\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "MetaLock" );
        args.put( "operations", List.of(
                Map.of( "field", "status", "action", "set", "value", "x" )
        ) );
        args.put( "expectedVersion", 999 );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Version conflict" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "update_metadata", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
    }
}
