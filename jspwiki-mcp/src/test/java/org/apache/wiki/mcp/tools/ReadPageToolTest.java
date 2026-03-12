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
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadPageToolTest {

    private TestEngine engine;
    private ReadPageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new ReadPageTool( engine.getManager( PageManager.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testReadExistingPage() throws Exception {
        engine.saveText( "TestMcpRead", "Hello from MCP!" );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "TestMcpRead" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( true, data.get( "exists" ) );
        assertEquals( "TestMcpRead", data.get( "pageName" ) );
        // JSPWiki normalizes stored text to CRLF with trailing newline
        assertTrue( data.get( "content" ).toString().strip().contains( "Hello from MCP!" ) );
    }

    @Test
    void testReadPageWithFrontmatter() throws Exception {
        engine.saveText( "TestMcpFm", "---\ntype: concept\ntags: [ai]\n---\nBody text here." );

        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "TestMcpFm" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( true, data.get( "exists" ) );
        assertTrue( data.get( "content" ).toString().strip().contains( "Body text here." ) );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > metadata = ( Map< String, Object > ) data.get( "metadata" );
        assertEquals( "concept", metadata.get( "type" ) );
    }

    @Test
    void testReadNonexistentPage() {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "NoSuchPageXyz" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertEquals( false, data.get( "exists" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "read_page", def.name() );
        assertNotNull( def.description() );
        assertNotNull( def.inputSchema() );
    }
}
