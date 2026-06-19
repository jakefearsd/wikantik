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
        assertFalse( result.isError() != null && result.isError() );

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
}
