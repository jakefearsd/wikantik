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
package org.apache.wiki.its.mcp;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Multi-tool workflow integration tests simulating real AI agent usage patterns.
 */
public class McpWorkflowIT extends WithMcpTestSetup {

    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds( 30 );
    private static final Duration POLL_INTERVAL = Duration.ofSeconds( 2 );

    @Test
    public void writeReadRoundTrip() {
        final String pageName = uniquePageName( "WFRoundTrip" );
        final Map< String, Object > metadata = Map.of( "type", "note", "priority", "high" );
        final String body = "Round trip body content";

        mcp.writePage( pageName, body, metadata );
        final Map< String, Object > readResult = mcp.readPage( pageName );

        Assertions.assertEquals( true, readResult.get( "exists" ) );
        final String readBody = McpTestClient.normalizeCrlf( ( String ) readResult.get( "content" ) );
        Assertions.assertTrue( readBody.contains( body ) );

        @SuppressWarnings( "unchecked" )
        final Map< String, Object > readMeta = ( Map< String, Object > ) readResult.get( "metadata" );
        Assertions.assertEquals( "note", readMeta.get( "type" ) );
        Assertions.assertEquals( "high", readMeta.get( "priority" ) );
    }

    @Test
    public void writeSearchReadWorkflow() {
        final String keyword = "wfsearch" + UUID.randomUUID().toString().replace( "-", "" );
        final String pageName = uniquePageName( "WFSearch" );
        mcp.writePage( pageName, "Content containing " + keyword );

        Awaitility.await()
                .atMost( SEARCH_TIMEOUT )
                .pollInterval( POLL_INTERVAL )
                .untilAsserted( () -> {
                    final Map< String, Object > searchResult = mcp.searchPages( keyword );
                    @SuppressWarnings( "unchecked" )
                    final List< Map< String, Object > > results =
                            ( List< Map< String, Object > > ) searchResult.get( "results" );
                    Assertions.assertFalse( results.isEmpty() );

                    final String foundName = ( String ) results.get( 0 ).get( "pageName" );
                    final Map< String, Object > readResult = mcp.readPage( foundName );
                    Assertions.assertEquals( true, readResult.get( "exists" ) );
                    final String content = McpTestClient.normalizeCrlf( ( String ) readResult.get( "content" ) );
                    Assertions.assertTrue( content.contains( keyword ) );
                } );
    }

    @Test
    public void writeCreateLinkThenCheckBacklinks() {
        final String target = uniquePageName( "WFTarget" );
        final String source = uniquePageName( "WFSource" );

        mcp.writePage( target, "Target page content" );
        mcp.writePage( source, "Source links to [" + target + "](" + target + ") here" );

        final Map< String, Object > backlinks = mcp.getBacklinks( target );
        @SuppressWarnings( "unchecked" )
        final List< String > links = ( List< String > ) backlinks.get( "backlinks" );
        Assertions.assertTrue( links.contains( source ),
                "Source page should appear as a backlink for target" );
    }

    @Test
    public void writeMultiplePagesWithMetadataThenQuery() {
        final String suffix = String.valueOf( System.currentTimeMillis() );
        final String typeValue = "wftype-" + suffix;

        for ( int i = 0; i < 3; i++ ) {
            final String pageName = uniquePageName( "WFQuery" + i );
            mcp.writePage( pageName, "Query test page " + i, Map.of( "type", typeValue ) );
        }

        final Map< String, Object > queryResult = mcp.queryMetadataByType( typeValue );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) queryResult.get( "pages" );
        Assertions.assertEquals( 3, pages.size(), "Should find all 3 pages with matching type" );
    }

    @Test
    public void writePageThenConfirmInRecentChangesAndListPages() {
        final String pageName = uniquePageName( "WFConfirm" );
        mcp.writePage( pageName, "Confirm in both tools" );

        // Check recent changes
        final Map< String, Object > recentResult = mcp.recentChanges();
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > changes = ( List< Map< String, Object > > ) recentResult.get( "changes" );
        Assertions.assertTrue( changes.stream().anyMatch( c -> pageName.equals( c.get( "pageName" ) ) ),
                "Should appear in recent changes" );

        // Check list pages
        final Map< String, Object > listResult = mcp.listPages( pageName );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > listed = ( List< Map< String, Object > > ) listResult.get( "pages" );
        Assertions.assertTrue( listed.stream().anyMatch( p -> pageName.equals( p.get( "name" ) ) ),
                "Should appear in list pages" );
    }

    @Test
    public void versionHistoryWorkflow() {
        final String pageName = uniquePageName( "WFVersion" );
        mcp.writePage( pageName, "Version 1 content" );
        mcp.writePage( pageName, "Version 2 content" );

        final Map< String, Object > v1 = mcp.readPage( pageName, 1 );
        final Map< String, Object > v2 = mcp.readPage( pageName, 2 );

        final String v1Body = McpTestClient.normalizeCrlf( ( String ) v1.get( "content" ) );
        final String v2Body = McpTestClient.normalizeCrlf( ( String ) v2.get( "content" ) );

        Assertions.assertTrue( v1Body.contains( "Version 1 content" ) );
        Assertions.assertTrue( v2Body.contains( "Version 2 content" ) );
        Assertions.assertFalse( v1Body.contains( "Version 2" ), "v1 should not contain v2 content" );
    }

    @Test
    public void frontmatterRoundTripThroughCrlfNormalization() {
        final String pageName = uniquePageName( "WFCrlf" );
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "title", "Test Page" );
        metadata.put( "type", "article" );
        metadata.put( "tags", List.of( "java", "wiki", "test" ) );

        final String body = "Line one\nLine two\nLine three";
        mcp.writePage( pageName, body, metadata );

        final Map< String, Object > readResult = mcp.readPage( pageName );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > readMeta = ( Map< String, Object > ) readResult.get( "metadata" );

        Assertions.assertEquals( "Test Page", readMeta.get( "title" ) );
        Assertions.assertEquals( "article", readMeta.get( "type" ) );

        @SuppressWarnings( "unchecked" )
        final List< String > tags = ( List< String > ) readMeta.get( "tags" );
        Assertions.assertEquals( 3, tags.size() );
        Assertions.assertTrue( tags.contains( "java" ) );

        final String readBody = McpTestClient.normalizeCrlf( ( String ) readResult.get( "content" ) );
        Assertions.assertTrue( readBody.contains( "Line one" ) );
        Assertions.assertTrue( readBody.contains( "Line three" ) );
    }
}
