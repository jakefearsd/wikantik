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
import org.apache.wiki.references.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetOutboundLinksToolTest {

    private TestEngine engine;
    private GetOutboundLinksTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new GetOutboundLinksTool( engine.getManager( ReferenceManager.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testFindsOutboundLinks() throws Exception {
        engine.saveText( "TargetA", "Target A content" );
        engine.saveText( "TargetB", "Target B content" );
        engine.saveText( "SourcePage", "[TargetA]\n[TargetB]" );

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
        engine.saveText( "Isolated", "No links here." );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "Isolated" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< String > links = ( List< String > ) data.get( "links" );
        assertTrue( links.isEmpty() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "get_outbound_links", def.name() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
