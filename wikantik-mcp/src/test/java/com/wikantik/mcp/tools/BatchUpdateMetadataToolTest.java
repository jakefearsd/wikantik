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

class BatchUpdateMetadataToolTest {

    private StubPageManager pm;
    private BatchUpdateMetadataTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new BatchUpdateMetadataTool( new StubPageSaveHelper( pm ), pm );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > executeAndParse( final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testBatchMultiplePages() {
        pm.savePage( "BatchMeta1", "---\ntype: article\ntags:\n- finance\n---\nBody 1." );
        pm.savePage( "BatchMeta2", "---\ntype: article\ntags:\n- tech\n---\nBody 2." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchMeta1", "operations", List.of(
                        Map.of( "field", "tags", "action", "append_to_list", "value", "investing" ) ) ),
                Map.of( "pageName", "BatchMeta2", "operations", List.of(
                        Map.of( "field", "tags", "action", "append_to_list", "value", "ai" ) ) )
        ) );
        args.put( "author", "TestAuthor" );
        args.put( "changeNote", "Batch metadata update" );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 2, results.size() );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( true, results.get( 1 ).get( "success" ) );

        // Verify actual metadata
        final String stored1 = pm.getPureText( "BatchMeta1", -1 );
        final ParsedPage parsed1 = FrontmatterParser.parse( stored1 );
        final List< String > tags1 = ( List< String > ) parsed1.metadata().get( "tags" );
        assertTrue( tags1.contains( "finance" ) );
        assertTrue( tags1.contains( "investing" ) );

        final String stored2 = pm.getPureText( "BatchMeta2", -1 );
        final ParsedPage parsed2 = FrontmatterParser.parse( stored2 );
        final List< String > tags2 = ( List< String > ) parsed2.metadata().get( "tags" );
        assertTrue( tags2.contains( "tech" ) );
        assertTrue( tags2.contains( "ai" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPartialFailure() {
        pm.savePage( "BatchMetaOk", "---\ntype: guide\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchMetaOk", "operations", List.of(
                        Map.of( "field", "status", "action", "set", "value", "active" ) ) ),
                Map.of( "pageName", "BatchMetaMissing", "operations", List.of(
                        Map.of( "field", "status", "action", "set", "value", "x" ) ) )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 2, results.size() );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( false, results.get( 1 ).get( "success" ) );
        assertNotNull( results.get( 1 ).get( "error" ) );
    }

    @Test
    void testEmptyPagesFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of() );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testBodyPreserved() {
        pm.savePage( "BatchMetaBody", "---\ntype: guide\n---\nImportant body content." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchMetaBody", "operations", List.of(
                        Map.of( "field", "status", "action", "set", "value", "published" ) ) )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( true, results.get( 0 ).get( "success" ) );

        final String stored = pm.getPureText( "BatchMetaBody", -1 );
        assertTrue( stored.contains( "Important body content." ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testOperationError() {
        pm.savePage( "BatchMetaTypeErr", "---\ntype: guide\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchMetaTypeErr", "operations", List.of(
                        Map.of( "field", "type", "action", "append_to_list", "value", "extra" ) ) )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( false, results.get( 0 ).get( "success" ) );
        assertTrue( results.get( 0 ).get( "error" ).toString().contains( "not a list" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "batch_update_metadata", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
        assertNotNull( def.annotations() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAuthorPropagation() {
        pm.savePage( "BatchMetaAuthor", "---\ntype: guide\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchMetaAuthor", "operations", List.of(
                        Map.of( "field", "status", "action", "set", "value", "active" ) ) )
        ) );
        args.put( "author", "TestAuthor" );

        tool.execute( args );

        final com.wikantik.api.core.Page saved = pm.getPage( "BatchMetaAuthor" );
        assertEquals( "TestAuthor", saved.getAuthor() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testResultIncludesMetadata() {
        pm.savePage( "BatchMetaResult", "---\ntype: guide\n---\nBody." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchMetaResult", "operations", List.of(
                        Map.of( "field", "status", "action", "set", "value", "published" ) ) )
        ) );

        final Map< String, Object > data = executeAndParse( args );
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        final Map< String, Object > firstResult = results.get( 0 );
        assertEquals( true, firstResult.get( "success" ) );
        assertNotNull( firstResult.get( "version" ) );
        assertNotNull( firstResult.get( "contentHash" ) );
        assertNotNull( firstResult.get( "metadata" ) );

        final Map< String, Object > metadata = ( Map< String, Object > ) firstResult.get( "metadata" );
        assertEquals( "guide", metadata.get( "type" ) );
        assertEquals( "published", metadata.get( "status" ) );
    }
}
