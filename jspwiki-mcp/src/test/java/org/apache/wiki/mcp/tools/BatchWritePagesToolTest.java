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
import org.apache.wiki.content.SystemPageRegistry;
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchWritePagesToolTest {

    private TestEngine engine;
    private BatchWritePagesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new BatchWritePagesTool( engine, engine.getManager( SystemPageRegistry.class ) );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testBatchWriteMultiplePages() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of(
                Map.of( "pageName", "BatchPage1", "content", "Content one." ),
                Map.of( "pageName", "BatchPage2", "content", "Content two.", "metadata", Map.of( "type", "note" ) )
        ) );

        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > data = gson.fromJson( json, Map.class );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) data.get( "results" );
        assertEquals( 2, results.size() );
        assertEquals( true, results.get( 0 ).get( "success" ) );
        assertEquals( true, results.get( 1 ).get( "success" ) );

        // Verify pages were actually saved
        assertNotNull( engine.getManager( PageManager.class ).getPage( "BatchPage1" ) );
        assertNotNull( engine.getManager( PageManager.class ).getPage( "BatchPage2" ) );
    }

    @Test
    void testBatchWriteEmptyPagesFails() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pages", List.of() );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "batch_write_pages", def.name() );
        assertNotNull( def.annotations() );
    }
}
