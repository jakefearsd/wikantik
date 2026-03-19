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
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchPatchPagesToolTest {

    private TestEngine engine;
    private BatchPatchPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new BatchPatchPagesTool( engine, engine.getManager( SystemPageRegistry.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testBatchMultiplePages() throws Exception {
        engine.saveText( "BatchPatch1", "## Intro\nContent 1.\n\n## Links\n- A" );
        engine.saveText( "BatchPatch2", "## Intro\nContent 2.\n\n## Links\n- B" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchPatch1", "operations", List.of(
                        Map.of( "action", "append_to_section", "section", "Links", "content", "- C" ) ) ),
                Map.of( "pageName", "BatchPatch2", "operations", List.of(
                        Map.of( "action", "append_to_section", "section", "Links", "content", "- D" ) ) )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 2, results.size() );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( true, results.get( 1 ).get( "success" ) );

        final String stored1 = engine.getManager( PageManager.class ).getPureText( "BatchPatch1", -1 );
        assertTrue( stored1.contains( "- C" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPartialFailure() throws Exception {
        engine.saveText( "BatchPatchOk", "## Intro\nContent.\n\n## Links\n- A" );
        // BatchPatchMissing does not exist

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchPatchOk", "operations", List.of(
                        Map.of( "action", "append_to_section", "section", "Links", "content", "- B" ) ) ),
                Map.of( "pageName", "BatchPatchMissing", "operations", List.of(
                        Map.of( "action", "append_to_section", "section", "Links", "content", "- C" ) ) )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

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
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "batch_patch_pages", def.name() );
        assertNotNull( def.annotations() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAuthorPropagation() throws Exception {
        engine.saveText( "BatchPatchAuthor", "## Intro\nContent.\n\n## Links\n- A" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchPatchAuthor", "operations", List.of(
                        Map.of( "action", "append_to_section", "section", "Links", "content", "- B" ) ) )
        ) );
        args.put( "author", "TestAuthor" );

        tool.execute( args );

        final com.wikantik.api.core.Page saved = engine.getManager( PageManager.class ).getPage( "BatchPatchAuthor" );
        assertEquals( "TestAuthor", saved.getAuthor() );
    }
}
