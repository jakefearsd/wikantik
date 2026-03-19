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

class ListPagesToolTest {

    private TestEngine engine;
    private ListPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        tool = new ListPagesTool( engine.getManager( PageManager.class ), engine.getManager( SystemPageRegistry.class ) );

        engine.saveText( "McpListAlpha", "Alpha page" );
        engine.saveText( "McpListBeta", "Beta page" );
        engine.saveText( "OtherPage", "Other page" );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testListAllPages() {
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertTrue( pages.size() >= 3 );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testListWithPrefix() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "prefix", "McpList" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 2, pages.size() );
        assertTrue( pages.stream().allMatch( p -> ( ( String ) p.get( "name" ) ).startsWith( "McpList" ) ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testListWithLimit() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "limit", 1 );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
    }
}
