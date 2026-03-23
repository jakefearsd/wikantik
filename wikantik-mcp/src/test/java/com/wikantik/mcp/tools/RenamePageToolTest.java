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
import com.wikantik.content.PageRenamer;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RenamePageToolTest {

    private TestEngine engine;
    private RenamePageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new RenamePageTool( engine, engine.getManager( PageManager.class ),
                engine.getManager( PageRenamer.class ), engine.getManager( SystemPageRegistry.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRenamePageSucceeds() throws Exception {
        engine.saveText( "OldPageName", "Some content here" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "oldName", "OldPageName" );
        args.put( "newName", "NewPageName" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertEquals( "OldPageName", data.get( "oldName" ) );
        assertEquals( "NewPageName", data.get( "newName" ) );

        // Verify old page is gone and new page exists
        assertNull( engine.getManager( PageManager.class ).getPage( "OldPageName" ) );
        assertNotNull( engine.getManager( PageManager.class ).getPage( "NewPageName" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRenameWithoutConfirmFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "oldName", "SomePage" );
        args.put( "newName", "OtherPage" );
        args.put( "confirm", false );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not confirmed" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRenameNonexistentPageFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "oldName", "DoesNotExist" );
        args.put( "newName", "NewName" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not found" ) || json.contains( "does not exist" ),
                "Expected error about missing page, got: " + json );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "rename_page", def.name() );
        assertTrue( def.annotations().destructiveHint() );
    }
}
