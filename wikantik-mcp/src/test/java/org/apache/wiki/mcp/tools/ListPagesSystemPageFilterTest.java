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
import org.apache.wiki.content.SystemPageRegistry;
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that ListPagesTool filters system pages by default and includes them when requested.
 */
class ListPagesSystemPageFilterTest {

    private TestEngine engine;
    private ListPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        final PageManager pageManager = engine.getManager( PageManager.class );
        final SystemPageRegistry systemPageRegistry = engine.getManager( SystemPageRegistry.class );
        tool = new ListPagesTool( pageManager, systemPageRegistry );

        // Create a user content page and a system page (About.txt is on test classpath)
        engine.saveText( "UserArticle", "This is a user article" );
        engine.saveText( "About", "About page content" );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testDefaultExcludesSystemPages() {
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        // UserArticle should be present
        assertTrue( pages.stream().anyMatch( p -> "UserArticle".equals( p.get( "name" ) ) ),
                "User content page should be in default results" );
        // About is a system page (discovered from About.txt on classpath) and should be excluded
        assertFalse( pages.stream().anyMatch( p -> "About".equals( p.get( "name" ) ) ),
                "System page About should be excluded by default" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testIncludeSystemPagesShowsAll() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "includeSystemPages", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        // Both should be present
        assertTrue( pages.stream().anyMatch( p -> "UserArticle".equals( p.get( "name" ) ) ),
                "User content page should be present" );
        assertTrue( pages.stream().anyMatch( p -> "About".equals( p.get( "name" ) ) ),
                "System page About should be present when includeSystemPages=true" );

        // System page should have systemPage flag
        final Map< String, Object > aboutPage = pages.stream()
                .filter( p -> "About".equals( p.get( "name" ) ) )
                .findFirst().orElseThrow();
        assertEquals( true, aboutPage.get( "systemPage" ), "About should be flagged as system page" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSystemPageFlagOnUserPage() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "includeSystemPages", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        final Map< String, Object > userArticle = pages.stream()
                .filter( p -> "UserArticle".equals( p.get( "name" ) ) )
                .findFirst().orElseThrow();
        assertEquals( false, userArticle.get( "systemPage" ), "UserArticle should not be flagged as system page" );
    }
}
