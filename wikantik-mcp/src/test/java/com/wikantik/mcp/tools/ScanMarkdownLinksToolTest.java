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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScanMarkdownLinksToolTest {

    private StubPageManager pm;
    private ScanMarkdownLinksTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        tool = new ScanMarkdownLinksTool( pm );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > executeAndParse( final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = tool.execute( args );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return gson.fromJson( json, Map.class );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testLocalLinks() {
        pm.savePage( "ScanLocal", "Check [this page](OtherPage) and [another](SecondPage)." );

        final Map< String, Object > data = executeAndParse( Map.of( "pageName", "ScanLocal" ) );
        final List< Map< String, String > > links = ( List< Map< String, String > > ) data.get( "links" );
        final List< String > localLinks = ( List< String > ) data.get( "localLinks" );

        assertEquals( 2, links.size() );
        assertEquals( "local", links.get( 0 ).get( "type" ) );
        assertEquals( "OtherPage", links.get( 0 ).get( "target" ) );
        assertTrue( localLinks.contains( "OtherPage" ) );
        assertTrue( localLinks.contains( "SecondPage" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testExternalLinksFiltered() {
        pm.savePage( "ScanExternal", "Visit [Google](https://google.com) and [FTP](ftp://files.example.com)." );

        final Map< String, Object > data = executeAndParse( Map.of( "pageName", "ScanExternal" ) );
        final List< Map< String, String > > links = ( List< Map< String, String > > ) data.get( "links" );
        final List< String > localLinks = ( List< String > ) data.get( "localLinks" );

        assertEquals( 2, links.size() );
        assertEquals( "external", links.get( 0 ).get( "type" ) );
        assertEquals( "external", links.get( 1 ).get( "type" ) );
        assertTrue( localLinks.isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testAnchorLinksFiltered() {
        pm.savePage( "ScanAnchor", "Jump to [section](#overview)." );

        final Map< String, Object > data = executeAndParse( Map.of( "pageName", "ScanAnchor" ) );
        final List< Map< String, String > > links = ( List< Map< String, String > > ) data.get( "links" );
        final List< String > localLinks = ( List< String > ) data.get( "localLinks" );

        assertEquals( 1, links.size() );
        assertEquals( "anchor", links.get( 0 ).get( "type" ) );
        assertTrue( localLinks.isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testMixedLinks() {
        pm.savePage( "ScanMixed", "See [page](WikiPage), [ext](https://example.com), and [anchor](#top)." );

        final Map< String, Object > data = executeAndParse( Map.of( "pageName", "ScanMixed" ) );
        final List< Map< String, String > > links = ( List< Map< String, String > > ) data.get( "links" );
        final List< String > localLinks = ( List< String > ) data.get( "localLinks" );

        assertEquals( 3, links.size() );
        assertEquals( 1, localLinks.size() );
        assertEquals( "WikiPage", localLinks.get( 0 ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void testNoLinks() {
        pm.savePage( "ScanEmpty", "Just plain text with no links at all." );

        final Map< String, Object > data = executeAndParse( Map.of( "pageName", "ScanEmpty" ) );
        final List< Map< String, String > > links = ( List< Map< String, String > > ) data.get( "links" );
        final List< String > localLinks = ( List< String > ) data.get( "localLinks" );

        assertTrue( links.isEmpty() );
        assertTrue( localLinks.isEmpty() );
    }

    @Test
    void testPageNotFound() {
        final McpSchema.CallToolResult result = tool.execute( Map.of( "pageName", "NonExistentScanPage" ) );
        assertTrue( result.isError() );
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( json.contains( "Page not found" ) );
    }

    @Test
    void testToolDefinition() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "scan_markdown_links", def.name() );
        assertNotNull( def.description() );
        assertTrue( def.annotations().readOnlyHint() );
    }
}
