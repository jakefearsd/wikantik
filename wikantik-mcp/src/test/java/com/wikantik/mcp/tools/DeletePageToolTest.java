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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeletePageToolTest {

    private TestEngine engine;
    private DeletePageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new DeletePageTool(
                engine.getManager( PageManager.class ),
                engine.getManager( SystemPageRegistry.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testDeleteExistingPage() throws Exception {
        engine.saveText( "McpDeleteMe", "Delete this page." );
        assertNotNull( engine.getManager( PageManager.class ).getPage( "McpDeleteMe" ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "McpDeleteMe" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( true, data.get( "success" ) );
        assertNull( engine.getManager( PageManager.class ).getPage( "McpDeleteMe" ) );
    }

    @Test
    void testDeleteWithoutConfirmFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "SomePage" );
        args.put( "confirm", false );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "not confirmed" ) );
    }

    @Test
    void testDeleteNonexistentPageFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NoSuchPageXyz" );
        args.put( "confirm", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Page not found" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "delete_page", def.name() );
        assertNotNull( def.annotations() );
        assertTrue( def.annotations().destructiveHint() );
    }
}
