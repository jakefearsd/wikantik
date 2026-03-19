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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiffPageToolTest {

    private TestEngine engine;
    private DiffPageTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        tool = new DiffPageTool( engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testDiffNonexistentPage() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "pageName", "NoSuchPageXyz" );
        args.put( "version1", 1 );
        args.put( "version2", 2 );

        final McpSchema.CallToolResult result = tool.execute( args );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Page not found" ) );
    }

    @Test
    void testStripHtml() {
        assertEquals( "hello\nworld", DiffPageTool.stripHtml( "hello<br/>world" ) );
        assertEquals( "plain text", DiffPageTool.stripHtml( "<b>plain</b> <i>text</i>" ) );
        assertEquals( "a < b & c", DiffPageTool.stripHtml( "a &lt; b &amp; c" ) );
        assertEquals( "", DiffPageTool.stripHtml( "" ) );
        assertEquals( "", DiffPageTool.stripHtml( null ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.toolDefinition();
        assertEquals( "diff_page", def.name() );
        assertNotNull( def.annotations() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
