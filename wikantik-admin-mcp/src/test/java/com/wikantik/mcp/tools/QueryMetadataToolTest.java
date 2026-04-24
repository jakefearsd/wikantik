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

class QueryMetadataToolTest {

    private StubPageManager pm;
    private QueryMetadataTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new QueryMetadataTool( pm );

        pm.savePage( "McpQmConcept", "---\ntype: concept\ntags: [ai, mcp]\n---\nConcept page." );
        pm.savePage( "McpQmRef", "---\ntype: reference\ntags: [java]\n---\nReference page." );
        pm.savePage( "McpQmPlain", "No frontmatter here." );
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
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "field" ) );
        assertTrue( json.contains( "suggestion" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryByTag() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "field", "tags" );
        args.put( "value", "mcp" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( "McpQmConcept", pages.get( 0 ).get( "name" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryWithFiltersAndCombination() {
        // Use filters array for AND combination: type=concept AND tags contains ai
        final Map< String, Object > args = new HashMap<>();
        args.put( "filters", List.of(
                Map.of( "field", "type", "value", "concept" ),
                Map.of( "field", "tags", "value", "ai" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( "McpQmConcept", pages.get( 0 ).get( "name" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryWithFiltersNoMatch() {
        // AND combination that matches nothing: type=concept AND tags=java
        final Map< String, Object > args = new HashMap<>();
        args.put( "filters", List.of(
                Map.of( "field", "type", "value", "concept" ),
                Map.of( "field", "tags", "value", "java" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertTrue( pages.isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryByClusterName() {
        pm.savePage( "McpQmCluster", "---\ntype: article\ncluster: security-hardening\n---\nClustered article." );

        final Map< String, Object > args = new HashMap<>();
        args.put( "field", "cluster" );
        args.put( "value", "security-hardening" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( "McpQmCluster", pages.get( 0 ).get( "name" ) );
        final Map< String, Object > metadata = ( Map< String, Object > ) pages.get( 0 ).get( "metadata" );
        assertEquals( "security-hardening", metadata.get( "cluster" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryFilterFieldExistenceWithoutValue() {
        // Filter with field only (no value) — matches pages that have the field
        final Map< String, Object > args = new HashMap<>();
        args.put( "filters", List.of(
                Map.of( "field", "tags" )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 2, pages.size() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testQueryResultIncludesMetadata() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "type", "reference" );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) data.get( "pages" );
        assertEquals( 1, pages.size() );
        final Map< String, Object > metadata = ( Map< String, Object > ) pages.get( 0 ).get( "metadata" );
        assertEquals( "reference", metadata.get( "type" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "query_metadata", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
