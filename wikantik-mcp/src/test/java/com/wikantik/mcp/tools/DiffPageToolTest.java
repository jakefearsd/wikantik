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
import com.wikantik.diff.DifferenceManager;
import com.wikantik.pages.PageManager;
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
        tool = new DiffPageTool( engine, engine.getManager( PageManager.class ),
                engine.getManager( DifferenceManager.class ) );
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
    void testStripHtmlBlockElements() {
        // Closing block tags add \n — with existing \n, duplicates collapse to \n\n
        assertEquals( "paragraph\n\ncontent", DiffPageTool.stripHtml( "<p>paragraph</p>\ncontent" ) );
        assertEquals( "div\n\ncontent", DiffPageTool.stripHtml( "<div>div</div>\ncontent" ) );
        assertEquals( "row\n\ncell", DiffPageTool.stripHtml( "<tr>row</tr>\ncell" ) );
        assertEquals( "item\n\nnext", DiffPageTool.stripHtml( "<li>item</li>\nnext" ) );
    }

    @Test
    void testStripHtmlEntityDecoding() {
        assertEquals( "\"quoted\"", DiffPageTool.stripHtml( "&quot;quoted&quot;" ) );
        assertEquals( "it's", DiffPageTool.stripHtml( "it&#39;s" ) );
        assertEquals( "a > b < c", DiffPageTool.stripHtml( "a &gt; b &lt; c" ) );
    }

    @Test
    void testStripHtmlSelfClosingBr() {
        assertEquals( "line1\nline2", DiffPageTool.stripHtml( "line1<br>line2" ) );
        assertEquals( "line1\nline2", DiffPageTool.stripHtml( "line1<br />line2" ) );
    }

    @Test
    void testStripHtmlCollapseMultipleNewlines() {
        // 3+ consecutive newlines should be collapsed to 2
        assertEquals( "a\n\nb", DiffPageTool.stripHtml( "a\n\n\n\nb" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "diff_page", def.name() );
        assertNotNull( def.annotations() );
        assertTrue( def.annotations().readOnlyHint() );
        assertTrue( def.inputSchema().required().contains( "pageName" ) );
        assertTrue( def.inputSchema().required().contains( "version1" ) );
        assertTrue( def.inputSchema().required().contains( "version2" ) );
    }

    @Test
    void testToolName() {
        assertEquals( "diff_page", tool.name() );
    }
}
