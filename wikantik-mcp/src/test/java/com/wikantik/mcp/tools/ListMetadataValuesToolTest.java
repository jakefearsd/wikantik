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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListMetadataValuesToolTest {

    private StubPageManager pm;
    private ListMetadataValuesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new ListMetadataValuesTool( pm );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testCollectsMetadataFromMultiplePages() {
        pm.savePage( "MetaPageA", "---\ntype: report\ntags: [security, api]\n---\nContent A" );
        pm.savePage( "MetaPageB", "---\ntype: note\ntags: [api, design]\n---\nContent B" );

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
    void testFilterByField() {
        pm.savePage( "MetaPageC", "---\ntype: concept\nstatus: draft\n---\nContent" );

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
        final McpSchema.Tool def = tool.definition();
        assertEquals( "list_metadata_values", def.name() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
