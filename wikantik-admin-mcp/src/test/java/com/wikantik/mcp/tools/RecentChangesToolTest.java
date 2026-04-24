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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RecentChangesToolTest {

    private StubPageManager pm;
    private StubSystemPageRegistry registry;
    private RecentChangesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        registry = new StubSystemPageRegistry();
        tool = new RecentChangesTool( pm, registry );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRecentChangesReturnsPages() {
        pm.savePage( "PageAlpha", "Content alpha." );
        pm.savePage( "PageBeta", "Content beta." );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) data.get( "changes" );
        assertNotNull( changes );
        assertTrue( changes.size() >= 2 );

        // Each change should have pageName
        for ( final Map< String, Object > change : changes ) {
            assertNotNull( change.get( "pageName" ) );
        }
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRecentChangesWithLimit() {
        pm.savePage( "Page1", "Content 1." );
        pm.savePage( "Page2", "Content 2." );
        pm.savePage( "Page3", "Content 3." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "limit", 2 );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) data.get( "changes" );
        assertEquals( 2, changes.size() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRecentChangesExcludesSystemPagesByDefault() {
        pm.savePage( "NormalPage", "Normal content." );
        pm.savePage( "SystemPage", "System content." );
        registry.addSystemPage( "SystemPage" );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) data.get( "changes" );
        // SystemPage should be excluded
        boolean hasSystem = changes.stream()
                .anyMatch( c -> "SystemPage".equals( c.get( "pageName" ) ) );
        assertFalse( hasSystem );
        boolean hasNormal = changes.stream()
                .anyMatch( c -> "NormalPage".equals( c.get( "pageName" ) ) );
        assertTrue( hasNormal );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRecentChangesIncludesSystemPagesWhenRequested() {
        pm.savePage( "NormalPage", "Normal content." );
        pm.savePage( "SystemPage", "System content." );
        registry.addSystemPage( "SystemPage" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "includeSystemPages", true );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) data.get( "changes" );
        boolean hasSystem = changes.stream()
                .anyMatch( c -> "SystemPage".equals( c.get( "pageName" ) ) );
        assertTrue( hasSystem );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRecentChangesHasExpectedFields() {
        pm.savePage( "FieldTest", "Content." );
        final Page page = pm.getPage( "FieldTest" );
        page.setAuthor( "TestAuthor" );
        page.setAttribute( Page.CHANGENOTE, "Initial commit" );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) data.get( "changes" );
        final Map< String, Object > entry = changes.stream()
                .filter( c -> "FieldTest".equals( c.get( "pageName" ) ) )
                .findFirst()
                .orElseThrow();

        assertEquals( "FieldTest", entry.get( "pageName" ) );
        assertEquals( "TestAuthor", entry.get( "author" ) );
        assertNotNull( entry.get( "lastModified" ) );
        assertEquals( "Initial commit", entry.get( "changeNote" ) );
        // systemPage field should be present (false for non-system pages)
        assertEquals( false, entry.get( "systemPage" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testRecentChangesWithSinceFilter() {
        // Create a page with a known date
        pm.savePage( "OldPage", "Old content." );
        // Set the date to the far past
        pm.getPage( "OldPage" ).setLastModified( new Date( 1000L ) );

        pm.savePage( "NewPage", "New content." );

        // Filter for changes after a recent date — should only return NewPage
        final Map< String, Object > args = new HashMap<>();
        args.put( "since", "2026-01-01T00:00:00Z" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) data.get( "changes" );
        boolean hasOld = changes.stream()
                .anyMatch( c -> "OldPage".equals( c.get( "pageName" ) ) );
        assertFalse( hasOld, "OldPage should be filtered out by 'since'" );
        boolean hasNew = changes.stream()
                .anyMatch( c -> "NewPage".equals( c.get( "pageName" ) ) );
        assertTrue( hasNew, "NewPage should be included" );
    }

    @Test
    void testRecentChangesWithNullRegistryDoesNotThrow() {
        // Create tool with null systemPageRegistry
        final RecentChangesTool toolNoRegistry = new RecentChangesTool( pm, null );
        pm.savePage( "TestPage", "Content." );

        final McpSchema.CallToolResult result = toolNoRegistry.execute( Map.of() );
        assertNotEquals( Boolean.TRUE, result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "recent_changes", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.description().contains( "recently modified" ) );
        assertTrue( def.annotations().readOnlyHint() );
    }

    @Test
    void testToolName() {
        assertEquals( "recent_changes", tool.name() );
    }
}
