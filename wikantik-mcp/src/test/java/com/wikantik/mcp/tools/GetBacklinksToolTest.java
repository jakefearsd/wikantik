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
import com.wikantik.test.StubReferenceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GetBacklinksToolTest {

    private StubReferenceManager refMgr;
    private GetBacklinksTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        refMgr = new StubReferenceManager();
        tool = new GetBacklinksTool( refMgr );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testBacklinksReturnedSortedAlphabetically() {
        // PageC and PageA both link to TargetPage
        refMgr.addReferences( "PageC", Set.of( "TargetPage" ) );
        refMgr.addReferences( "PageA", Set.of( "TargetPage" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "TargetPage" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( "TargetPage", data.get( "pageName" ) );
        final List< String > backlinks = ( List< String > ) data.get( "backlinks" );
        assertEquals( 2, backlinks.size() );
        // Should be sorted alphabetically
        assertEquals( "PageA", backlinks.get( 0 ) );
        assertEquals( "PageC", backlinks.get( 1 ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testNoBacklinksReturnsEmptyArray() {
        // Page exists in references but nothing links to it
        refMgr.addReferences( "LonelyPage", Set.of( "SomeOtherPage" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "NonexistentTarget" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( "NonexistentTarget", data.get( "pageName" ) );
        final List< String > backlinks = ( List< String > ) data.get( "backlinks" );
        assertNotNull( backlinks );
        assertTrue( backlinks.isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSingleBacklink() {
        refMgr.addReferences( "SourcePage", Set.of( "MyPage" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "MyPage" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< String > backlinks = ( List< String > ) data.get( "backlinks" );
        assertEquals( 1, backlinks.size() );
        assertEquals( "SourcePage", backlinks.get( 0 ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_backlinks", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.description().contains( "backlinks" ) );
        assertNotNull( def.inputSchema() );
        assertTrue( def.inputSchema().required().contains( "pageName" ) );
    }

    @Test
    void testToolName() {
        assertEquals( "get_backlinks", tool.name() );
    }

    @Test
    void testResultIsNotError() {
        refMgr.addReferences( "A", Set.of( "B" ) );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "B" ) );
        assertNotEquals( Boolean.TRUE, result.isError() );
    }
}
