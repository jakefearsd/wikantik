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

import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogQuery;
import com.wikantik.api.querylog.QueryLogReader;
import com.wikantik.api.querylog.SourceSurface;
import com.wikantik.mcp.ToolSchemas;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ListRetrievalQueriesToolTest {

    @Test
    void mapsArgumentsToFilterAndRendersRows() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        when( reader.topQueries( any() ) ).thenReturn( List.of(
                new AggregatedQuery( "how do I deploy locally", 3, 0.67, 2, Instant.EPOCH ) ) );

        final var tool = new ListRetrievalQueriesTool( reader );
        final var result = tool.execute( Map.of(
                "since_days", 7, "actor", "agent", "max_avg_results", 1, "limit", 25 ) );

        final ArgumentCaptor< QueryLogQuery > cap = ArgumentCaptor.forClass( QueryLogQuery.class );
        verify( reader ).topQueries( cap.capture() );
        assertEquals( ActorType.AGENT, cap.getValue().actor() );
        assertEquals( 1, cap.getValue().maxAvgResultCount() );
        assertEquals( 25, cap.getValue().limit() );
        assertFalse( result.isError() );

        final String json = ( ( io.modelcontextprotocol.spec.McpSchema.TextContent )
                result.content().get( 0 ) ).text();
        assertTrue( json.contains( "how do I deploy locally" ) );
        assertTrue( json.contains( "zeroResultCount" ) );
    }

    @Test
    void rejectsUnknownSurface() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        final var result = new ListRetrievalQueriesTool( reader )
                .execute( Map.of( "surface", "not_a_surface" ) );
        assertTrue( result.isError() );
        verifyNoInteractions( reader );
    }

    @Test
    void rejectsUnknownActor() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        final var result = new ListRetrievalQueriesTool( reader )
                .execute( Map.of( "actor", "bogus_actor" ) );
        assertTrue( result.isError() );
        verifyNoInteractions( reader );
    }

    @Test
    void nameIsListRetrievalQueries() {
        assertEquals( "list_retrieval_queries", new ListRetrievalQueriesTool( mock( QueryLogReader.class ) ).name() );
    }

    @Test
    void definitionExposesTheFilterParameters() {
        final var def = new ListRetrievalQueriesTool( mock( QueryLogReader.class ) ).definition();
        final Map< String, Object > props = ToolSchemas.properties( def.inputSchema() );
        assertTrue( props.containsKey( "since_days" ) );
        assertTrue( props.containsKey( "actor" ) );
        assertTrue( props.containsKey( "surface" ) );
        assertTrue( props.containsKey( "max_avg_results" ) );
        assertNotNull( def.outputSchema() );
    }

    @Test
    void validSurfaceIsParsedAndPassedToTheQuery() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        when( reader.topQueries( any() ) ).thenReturn( List.of() );

        new ListRetrievalQueriesTool( reader ).execute( Map.of( "surface", "api_bundle" ) );

        final ArgumentCaptor< QueryLogQuery > cap = ArgumentCaptor.forClass( QueryLogQuery.class );
        verify( reader ).topQueries( cap.capture() );
        assertEquals( SourceSurface.API_BUNDLE, cap.getValue().surface() );
    }

    @Test
    void readerFailureIsReportedAsAnMcpError() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        when( reader.topQueries( any() ) ).thenThrow( new RuntimeException( "db down" ) );

        final var result = new ListRetrievalQueriesTool( reader ).execute( Map.of() );

        assertTrue( result.isError() );
        final String text = ( ( io.modelcontextprotocol.spec.McpSchema.TextContent )
                result.content().get( 0 ) ).text();
        assertTrue( text.contains( "retrieval query log" ), text );
    }

    @Test
    void nonNumericSinceDaysFallsBackToDefault() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        when( reader.topQueries( any() ) ).thenReturn( List.of() );

        new ListRetrievalQueriesTool( reader ).execute( Map.of( "since_days", "not-a-number" ) );

        final ArgumentCaptor< QueryLogQuery > cap = ArgumentCaptor.forClass( QueryLogQuery.class );
        verify( reader ).topQueries( cap.capture() );
        // default lookback is 30 days; assert the resolved "since" is close to that, not the parse failure's bound.
        final long daysBack = java.time.Duration.between( cap.getValue().since(), Instant.now() ).toDays();
        assertEquals( 30, daysBack );
    }

    private static QueryLogQuery queryFor( final Map< String, Object > args ) {
        final QueryLogReader reader = mock( QueryLogReader.class );
        when( reader.topQueries( any() ) ).thenReturn( List.of() );
        new ListRetrievalQueriesTool( reader ).execute( args );
        final ArgumentCaptor< QueryLogQuery > cap = ArgumentCaptor.forClass( QueryLogQuery.class );
        verify( reader ).topQueries( cap.capture() );
        return cap.getValue();
    }

    @Test
    void defaultsAreFiftyQueriesWithOneOccurrenceAndNoFilters() {
        final QueryLogQuery q = queryFor( Map.of() );
        assertEquals( 50, q.limit() );
        assertEquals( 1, q.minOccurrences() );
        assertNull( q.actor() );
        assertNull( q.surface() );
        assertNull( q.maxAvgResultCount() );
    }

    @Test
    void numericStringsAreParsedAndGarbageFallsBackToTheDefaults() {
        final QueryLogQuery parsed = queryFor( Map.of( "limit", " 12 ", "min_occurrences", "3", "max_avg_results", "2" ) );
        assertEquals( 12, parsed.limit() );
        assertEquals( 3, parsed.minOccurrences() );
        assertEquals( 2, parsed.maxAvgResultCount() );

        final QueryLogQuery garbage = queryFor( Map.of( "limit", "lots", "min_occurrences", "x", "max_avg_results", "y" ) );
        assertEquals( 50, garbage.limit() );
        assertEquals( 1, garbage.minOccurrences() );
        assertEquals( 0, garbage.maxAvgResultCount(), "a present-but-unparseable max_avg_results is 0, not unset" );
    }

    @Test
    void sinceDaysSetsTheWindowStart() {
        final Instant before = Instant.now();
        final QueryLogQuery q = queryFor( Map.of( "since_days", 7 ) );
        final long days = java.time.Duration.between( q.since(), before ).toDays();
        assertTrue( days == 7 || days == 6, "window starts about 7 days ago, was " + days );
    }

    @Test
    void aBlankActorOrSurfaceMeansNoFilterAndTheUnknownActorIsAccepted() {
        assertNull( queryFor( Map.of( "actor", "  ", "surface", " " ) ).actor() );
        assertEquals( ActorType.UNKNOWN, queryFor( Map.of( "actor", " unknown " ) ).actor() );
    }

    @Test
    void renderedRowsCarryEveryAggregateAndATimestamp() {
        final QueryLogReader reader = mock( QueryLogReader.class );
        when( reader.topQueries( any() ) ).thenReturn( List.of(
                new AggregatedQuery( "q1", 4, 1.5, 2, Instant.parse( "2026-11-20T05:00:00Z" ) ),
                new AggregatedQuery( "q2", 1, 0.0, 1, null ) ) );

        final com.google.gson.JsonObject out = com.google.gson.JsonParser.parseString(
                ( ( io.modelcontextprotocol.spec.McpSchema.TextContent ) new ListRetrievalQueriesTool( reader )
                        .execute( Map.of() ).content().get( 0 ) ).text() ).getAsJsonObject();

        assertEquals( 2, out.get( "count" ).getAsInt() );
        final var first = out.getAsJsonArray( "queries" ).get( 0 ).getAsJsonObject();
        assertEquals( "q1", first.get( "query" ).getAsString() );
        assertEquals( 4, first.get( "occurrences" ).getAsInt() );
        assertEquals( 1.5, first.get( "avgResultCount" ).getAsDouble() );
        assertEquals( 2, first.get( "zeroResultCount" ).getAsInt() );
        assertEquals( "2026-11-20T05:00:00Z", first.get( "lastSeen" ).getAsString() );
        final var second = out.getAsJsonArray( "queries" ).get( 1 ).getAsJsonObject();
        assertTrue( !second.has( "lastSeen" ) || second.get( "lastSeen" ).isJsonNull() );
    }
}
