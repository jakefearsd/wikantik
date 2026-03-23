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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GetOutboundLinksToolTest {

    private StubPageManager pm;
    private StubReferenceManager refMgr;
    private GetOutboundLinksTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        refMgr = new StubReferenceManager();
        tool = new GetOutboundLinksTool( refMgr );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testFindsOutboundLinks() throws Exception {
        pm.savePage( "TargetA", "Target A content" );
        pm.savePage( "TargetB", "Target B content" );
        pm.savePage( "SourcePage", "[TargetA]()\n\n[TargetB]()" );
        refMgr.addReferences( "TargetA", Set.of() );
        refMgr.addReferences( "TargetB", Set.of() );
        refMgr.addReferences( "SourcePage", Set.of( "TargetA", "TargetB" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "SourcePage" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( "SourcePage", data.get( "pageName" ) );
        final List< String > links = ( List< String > ) data.get( "links" );
        assertTrue( links.contains( "TargetA" ) );
        assertTrue( links.contains( "TargetB" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testPageWithNoLinks() throws Exception {
        pm.savePage( "Isolated", "No links here." );
        refMgr.addReferences( "Isolated", Set.of() );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "Isolated" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< String > links = ( List< String > ) data.get( "links" );
        assertTrue( links.isEmpty() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_outbound_links", def.name() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
