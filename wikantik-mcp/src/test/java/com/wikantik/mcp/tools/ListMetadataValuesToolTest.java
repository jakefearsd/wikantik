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
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListMetadataValuesToolTest {

    private TestEngine engine;
    private ListMetadataValuesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new ListMetadataValuesTool( engine.getManager( PageManager.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testCollectsMetadataFromMultiplePages() throws Exception {
        engine.saveText( "MetaPageA", "---\ntype: report\ntags: [security, api]\n---\nContent A" );
        engine.saveText( "MetaPageB", "---\ntype: note\ntags: [api, design]\n---\nContent B" );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final Map< String, List< String > > fields = ( Map< String, List< String > > ) data.get( "fields" );
        assertNotNull( fields.get( "type" ) );
        assertTrue( fields.get( "type" ).contains( "report" ) );
        assertTrue( fields.get( "type" ).contains( "note" ) );

        assertNotNull( fields.get( "tags" ) );
        assertTrue( fields.get( "tags" ).contains( "security" ) );
        assertTrue( fields.get( "tags" ).contains( "api" ) );
        assertTrue( fields.get( "tags" ).contains( "design" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testFilterByField() throws Exception {
        engine.saveText( "MetaPageC", "---\ntype: concept\nstatus: draft\n---\nContent" );

        final Map< String, Object > args = new HashMap<>();
        args.put( "field", "type" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final Map< String, List< String > > fields = ( Map< String, List< String > > ) data.get( "fields" );
        assertTrue( fields.containsKey( "type" ) );
        assertFalse( fields.containsKey( "status" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "list_metadata_values", def.name() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
