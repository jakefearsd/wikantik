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
import com.wikantik.test.StubSystemPageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListPagesToolTest {

    private StubPageManager pm;
    private StubSystemPageRegistry registry;
    private ListPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        registry = new StubSystemPageRegistry();
        tool = new ListPagesTool( pm, registry );

        pm.savePage( "McpListAlpha", "Alpha page" );
        pm.savePage( "McpListBeta", "Beta page" );
        pm.savePage( "OtherPage", "Other page" );
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

    @Test
    @SuppressWarnings( "unchecked" )
    void testListExcludesSystemPagesByDefault() {
        pm.savePage( "SystemTemplate", "Template content." );
        registry.addSystemPage( "SystemTemplate" );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        boolean hasSystem = pages.stream()
                .anyMatch( p -> "SystemTemplate".equals( p.get( "name" ) ) );
        assertFalse( hasSystem, "System pages should be excluded by default" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testListIncludesSystemPagesWhenRequested() {
        pm.savePage( "SystemTemplate", "Template content." );
        registry.addSystemPage( "SystemTemplate" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "includeSystemPages", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        boolean hasSystem = pages.stream()
                .anyMatch( p -> "SystemTemplate".equals( p.get( "name" ) ) );
        assertTrue( hasSystem, "System pages should be included when requested" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testListPagesSortedAlphabetically() {
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        for ( int i = 1; i < pages.size(); i++ ) {
            final String prev = ( String ) pages.get( i - 1 ).get( "name" );
            final String curr = ( String ) pages.get( i ).get( "name" );
            assertTrue( prev.compareTo( curr ) <= 0,
                    "Pages should be sorted: '" + prev + "' should come before '" + curr + "'" );
        }
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testListPagesHasExpectedFields() {
        pm.getPage( "McpListAlpha" ).setAuthor( "TestAuthor" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "prefix", "McpListAlpha" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );

        final Map< String, Object > page = pages.get( 0 );
        assertEquals( "McpListAlpha", page.get( "name" ) );
        assertNotNull( page.get( "lastModified" ) );
        assertEquals( "TestAuthor", page.get( "author" ) );
        assertNotNull( page.get( "size" ) );
        assertEquals( false, page.get( "systemPage" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "list_pages", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
