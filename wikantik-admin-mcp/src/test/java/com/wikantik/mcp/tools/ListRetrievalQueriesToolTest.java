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
}
