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

class QueryMetadataToolTest {

    private TestEngine engine;
    private QueryMetadataTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        tool = new QueryMetadataTool( engine.getManager( PageManager.class ) );

        engine.saveText( "McpQmConcept", "---\ntype: concept\ntags: [ai, mcp]\n---\nConcept page." );
        engine.saveText( "McpQmRef", "---\ntype: reference\ntags: [java]\n---\nReference page." );
        engine.saveText( "McpQmPlain", "No frontmatter here." );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryByType() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "type", "concept" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( "McpQmConcept", pages.get( 0 ).get( "name" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryByFieldValue() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "field", "tags" );
        args.put( "value", "java" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( "McpQmRef", pages.get( 0 ).get( "name" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryFieldExists() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "field", "type" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 2, pages.size() );
    }

    @Test
    void testQueryMissingParams() {
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        assertTrue( result.isError() );
    }
}
