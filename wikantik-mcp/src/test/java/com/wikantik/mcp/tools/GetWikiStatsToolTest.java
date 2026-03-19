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
import com.wikantik.references.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetWikiStatsToolTest {

    private TestEngine engine;
    private GetWikiStatsTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new GetWikiStatsTool(
                engine.getManager( PageManager.class ),
                engine.getManager( ReferenceManager.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testReturnsAllStatFields() throws Exception {
        engine.saveText( "StatsTestPage", "Some content [BrokenLink]" );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        assertNotNull( data.get( "totalPages" ) );
        assertNotNull( data.get( "brokenLinkCount" ) );
        assertNotNull( data.get( "orphanedPageCount" ) );
        assertNotNull( data.get( "recentChangesCount" ) );
        assertTrue( ( ( Number ) data.get( "totalPages" ) ).intValue() >= 1 );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "get_wiki_stats", def.name() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
