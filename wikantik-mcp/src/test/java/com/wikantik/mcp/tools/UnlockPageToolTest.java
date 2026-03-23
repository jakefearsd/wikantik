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
import com.wikantik.test.StubPageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnlockPageToolTest {

    private StubPageManager pm;
    private UnlockPageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new UnlockPageTool( pm );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testUnlockLockedPage() {
        pm.savePage( "UnlockTestPage", "Content" );
        final Page page = pm.getPage( "UnlockTestPage" );
        pm.lockPage( page, "SomeUser" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "UnlockTestPage" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertTrue( ( Boolean ) data.get( "wasLocked" ) );
        assertNull( pm.getCurrentLock( page ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testUnlockUnlockedPageIsIdempotent() {
        pm.savePage( "NotLockedPage", "Content" );

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
        final McpSchema.Tool def = tool.definition();
        assertEquals( "unlock_page", def.name() );
        assertFalse( def.annotations().readOnlyHint() );
    }
}
