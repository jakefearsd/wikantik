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
import com.wikantik.test.StubReferenceManager;
import com.wikantik.test.StubSystemPageRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GetOrphanedPagesToolTest {

    private StubPageManager pm;
    private StubReferenceManager refMgr;
    private StubSystemPageRegistry spr;
    private GetOrphanedPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        refMgr = new StubReferenceManager();
        spr = new StubSystemPageRegistry();
        tool = new GetOrphanedPagesTool( refMgr, spr );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testFindsOrphanedPages() throws Exception {
        pm.savePage( "OrphanPage", "Nobody links to me." );
        refMgr.addReferences( "OrphanPage", Set.of() );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< String > orphans = ( List< String > ) data.get( "orphanedPages" );
        assertTrue( orphans.contains( "OrphanPage" ) );
        assertTrue( ( ( Number ) data.get( "count" ) ).intValue() > 0 );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testLinkedPageNotOrphaned() throws Exception {
        pm.savePage( "LinkedPage", "I am linked." );
        pm.savePage( "LinkerPage", "[LinkedPage]()" );
        refMgr.addReferences( "LinkedPage", Set.of() );
        refMgr.addReferences( "LinkerPage", Set.of( "LinkedPage" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< String > orphans = ( List< String > ) data.get( "orphanedPages" );
        assertFalse( orphans.contains( "LinkedPage" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_orphaned_pages", def.name() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
