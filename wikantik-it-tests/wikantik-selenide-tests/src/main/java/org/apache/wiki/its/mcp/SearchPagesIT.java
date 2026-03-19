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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integration tests for the {@code search_pages} MCP tool.
 * Uses Awaitility to poll for async search index updates.
 */
public class SearchPagesIT extends WithMcpTestSetup {

    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds( 30 );
    private static final Duration POLL_INTERVAL = Duration.ofSeconds( 2 );

    @Test
    public void searchFindsPreSeededPage() {
        Awaitility.await()
                .atMost( SEARCH_TIMEOUT )
                .pollInterval( POLL_INTERVAL )
                .untilAsserted( () -> {
                    final Map< String, Object > result = mcp.searchPages( "Congratulations" );
                    @SuppressWarnings( "unchecked" )
                    final List< Map< String, Object > > results =
                            ( List< Map< String, Object > > ) result.get( "results" );
                    Assertions.assertFalse( results.isEmpty(), "Should find Main page with 'Congratulations'" );
                    Assertions.assertTrue( results.stream()
                            .anyMatch( r -> "Main".equals( r.get( "pageName" ) ) ) );
                } );
    }

    @Test
    public void searchFindsNewlyWrittenPage() {
        final String keyword = "uniquekeyword" + UUID.randomUUID().toString().replace( "-", "" );
        final String pageName = uniquePageName( "SearchNew" );
        mcp.writePage( pageName, "This page contains " + keyword + " for search testing" );

        Awaitility.await()
                .atMost( SEARCH_TIMEOUT )
                .pollInterval( POLL_INTERVAL )
                .untilAsserted( () -> {
                    final Map< String, Object > result = mcp.searchPages( keyword );
                    @SuppressWarnings( "unchecked" )
                    final List< Map< String, Object > > results =
                            ( List< Map< String, Object > > ) result.get( "results" );
                    Assertions.assertFalse( results.isEmpty(),
                            "Newly written page should be searchable by unique keyword" );
                    Assertions.assertTrue( results.stream()
                            .anyMatch( r -> pageName.equals( r.get( "pageName" ) ) ) );
                } );
    }

    @Test
    public void searchRespectsMaxResults() {
        Awaitility.await()
                .atMost( SEARCH_TIMEOUT )
                .pollInterval( POLL_INTERVAL )
                .untilAsserted( () -> {
                    final Map< String, Object > result = mcp.searchPages( "wiki", 1 );
                    @SuppressWarnings( "unchecked" )
                    final List< Map< String, Object > > results =
                            ( List< Map< String, Object > > ) result.get( "results" );
                    Assertions.assertTrue( results.size() <= 1, "maxResults=1 should limit to at most 1 result" );
                } );
    }

    @Test
    public void searchReturnsEmptyForNoMatch() {
        final String noMatchQuery = UUID.randomUUID().toString();
        final Map< String, Object > result = mcp.searchPages( noMatchQuery );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) result.get( "results" );

        Assertions.assertNotNull( results, "Results should not be null" );
        Assertions.assertTrue( results.isEmpty(), "Random UUID query should return no results" );
    }

    @Test
    public void searchResultsIncludeScoreAndContexts() {
        Awaitility.await()
                .atMost( SEARCH_TIMEOUT )
                .pollInterval( POLL_INTERVAL )
                .untilAsserted( () -> {
                    final Map< String, Object > result = mcp.searchPages( "Congratulations" );
                    @SuppressWarnings( "unchecked" )
                    final List< Map< String, Object > > results =
                            ( List< Map< String, Object > > ) result.get( "results" );
                    Assertions.assertFalse( results.isEmpty() );

                    final Map< String, Object > first = results.get( 0 );
                    final Number score = ( Number ) first.get( "score" );
                    Assertions.assertNotNull( score, "Score should be present" );
                    Assertions.assertTrue( score.doubleValue() > 0, "Score should be positive" );
                    Assertions.assertTrue( first.containsKey( "contexts" ), "Contexts should be present" );
                } );
    }
}
