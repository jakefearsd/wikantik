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
import com.wikantik.search.SearchManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class SearchPagesToolTest {

    private TestEngine engine;
    private SearchPagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        final String workDir = props.getProperty( "wikantik.workDir" );
        final String workRepo = props.getProperty( "wikantik.fileSystemProvider.pageDir" );

        props.setProperty( SearchManager.PROP_SEARCHPROVIDER, "LuceneSearchProvider" );
        props.setProperty( "wikantik.lucene.indexdelay", "0" );
        props.setProperty( "wikantik.lucene.initialdelay", "0" );
        props.setProperty( "wikantik.workDir", workDir + System.currentTimeMillis() + "-" + getClass().getSimpleName() );
        props.setProperty( "wikantik.fileSystemProvider.pageDir", workRepo + System.currentTimeMillis() + "-" + getClass().getSimpleName() );

        engine = TestEngine.build( props );
        tool = new SearchPagesTool( engine );

        engine.saveText( "McpSearchTarget", "This page contains the unique word xylophone for search testing." );

        // Wait for Lucene indexer to process
        await().atMost( java.time.Duration.ofSeconds( 30 ) )
                .pollInterval( java.time.Duration.ofMillis( 500 ) )
                .until( () -> {
                    try {
                        final McpSchema.CallToolResult r = tool.execute( Map.of( "query", "xylophone" ) );
                        final String j = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
                        @SuppressWarnings( "unchecked" )
                        final Map< String, Object > d = gson.fromJson( j, Map.class );
                        @SuppressWarnings( "unchecked" )
                        final List< ? > results = ( List< ? > ) d.get( "results" );
                        return results != null && !results.isEmpty();
                    } catch ( final Exception e ) {
                        return false;
                    }
                } );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSearchFindsPage() {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "query", "xylophone" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertFalse( results.isEmpty() );
        assertTrue( results.stream().anyMatch( r -> "McpSearchTarget".equals( r.get( "pageName" ) ) ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testSearchNoResults() {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "query", "zzNonexistentTermZZ" ) );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< ? > results = ( List< ? > ) data.get( "results" );
        assertTrue( results.isEmpty() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "search_pages", def.name() );
    }
}
