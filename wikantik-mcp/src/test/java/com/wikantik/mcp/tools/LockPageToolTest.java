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
import com.wikantik.test.StubPageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LockPageToolTest {

    private StubPageManager pm;
    private LockPageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new LockPageTool( pm );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testLockPageSucceeds() {
        pm.savePage( "LockTestPage", "Content to lock" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "LockTestPage" );
        args.put( "user", "TestUser" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertTrue( ( Boolean ) data.get( "success" ) );
        assertEquals( "LockTestPage", data.get( "pageName" ) );
        assertEquals( "TestUser", data.get( "locker" ) );
        assertNotNull( data.get( "expiryTime" ) );
        assertTrue( ( ( Number ) data.get( "minutesLeft" ) ).longValue() > 0 );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testLockAlreadyLockedByAnotherUser() {
        pm.savePage( "LockConflictPage", "Content" );

        // First user locks the page
        final Map< String, Object > args1 = new HashMap<>();
        args1.put( "pageName", "LockConflictPage" );
        args1.put( "user", "UserA" );
        tool.execute( args1 );

        // Second user tries to lock
        final Map< String, Object > args2 = new HashMap<>();
        args2.put( "pageName", "LockConflictPage" );
        args2.put( "user", "UserB" );

        final McpSchema.CallToolResult result = tool.execute( args2 );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "UserA" ) || json.contains( "locked" ),
                "Expected lock conflict info, got: " + json );
    }

    @Test
    void testLockNonexistentPageFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NonExistentLockPage" );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "lock_page", def.name() );
        assertFalse( def.annotations().readOnlyHint() );
    }
}
