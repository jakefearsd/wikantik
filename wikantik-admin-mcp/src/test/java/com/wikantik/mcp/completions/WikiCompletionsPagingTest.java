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
package com.wikantik.mcp.completions;

import com.wikantik.api.managers.ReferenceManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Prefix matching, ordering and the 20-item cap of the page-name completions, over a fake page list. */
class WikiCompletionsPagingTest {

    private static McpSchema.CompleteResult.CompleteCompletion complete( final List< String > pages, final String prefix ) {
        final ReferenceManager refs = mock( ReferenceManager.class );
        when( refs.findCreated() ).thenReturn( new java.util.HashSet<>( pages ) );
        final McpSchema.CompleteRequest request = new McpSchema.CompleteRequest(
                new McpSchema.PromptReference( "audit-links" ),
                new McpSchema.CompleteRequest.CompleteArgument( "pageName", prefix ) );
        return WikiCompletions.all( refs ).get( 0 ).completionHandler().apply( null, request ).completion();
    }

    private static List< String > numbered( final int n ) {
        return IntStream.rangeClosed( 1, n ).mapToObj( i -> String.format( "Page%02d", i ) ).toList();
    }

    @Test
    void matchingIsCaseInsensitiveAndOnlyOnThePrefix() {
        final var c = complete( List.of( "AlphaPage", "alphaBeta", "BetaAlpha", "Gamma" ), "ALPHA" );
        assertEquals( List.of( "AlphaPage", "alphaBeta" ), c.values() );
        assertEquals( 2, c.total() );
        assertFalse( c.hasMore() );
    }

    @Test
    void resultsAreSortedAlphabetically() {
        assertEquals( List.of( "Apple", "Banana", "Cherry" ), complete( List.of( "Cherry", "Apple", "Banana" ), "" ).values() );
    }

    @Test
    void exactlyTwentyMatchesAreAllReturnedWithNoMore() {
        final var c = complete( numbered( 20 ), "Page" );
        assertEquals( 20, c.values().size() );
        assertEquals( 20, c.total() );
        assertFalse( c.hasMore() );
    }

    @Test
    void twentyOneMatchesAreCappedAtTwentyAndFlaggedAsMore() {
        final var c = complete( numbered( 21 ), "Page" );
        assertEquals( numbered( 20 ), c.values(), "the first twenty alphabetically" );
        assertEquals( 21, c.total() );
        assertTrue( c.hasMore() );
    }

    @Test
    void noMatchesGiveAnEmptyCompletion() {
        final var c = complete( new ArrayList<>( List.of( "Alpha" ) ), "Zzz" );
        assertEquals( List.of(), c.values() );
        assertEquals( 0, c.total() );
        assertFalse( c.hasMore() );
    }
}
