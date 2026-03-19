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
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnlockPageToolTest {

    private TestEngine engine;
    private UnlockPageTool tool;
    private PageManager pageManager;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        pageManager = engine.getManager( PageManager.class );
        tool = new UnlockPageTool( pageManager );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testUnlockLockedPage() throws Exception {
        engine.saveText( "UnlockTestPage", "Content" );
        final Page page = pageManager.getPage( "UnlockTestPage" );
        pageManager.lockPage( page, "SomeUser" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "UnlockTestPage" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertTrue( ( Boolean ) data.get( "wasLocked" ) );
        assertNull( pageManager.getCurrentLock( page ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testUnlockUnlockedPageIsIdempotent() throws Exception {
        engine.saveText( "NotLockedPage", "Content" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NotLockedPage" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertFalse( ( Boolean ) data.get( "wasLocked" ) );
    }

    @Test
    void testUnlockNonexistentPageFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NonExistentPage" );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "unlock_page", def.name() );
        assertFalse( def.annotations().readOnlyHint() );
    }
}
