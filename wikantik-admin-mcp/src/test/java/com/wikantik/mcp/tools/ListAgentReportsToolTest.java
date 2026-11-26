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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.agentreports.AgentReport;
import com.wikantik.agentreports.AgentReportService;
import com.wikantik.agentreports.ReportQuery;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListAgentReportsToolTest {

    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-26T12:00:00Z" ), ZoneOffset.UTC );

    private AgentReportService service;
    private ListAgentReportsTool tool;

    @BeforeEach
    void setUp() {
        service = mock( AgentReportService.class );
        tool = new ListAgentReportsTool( () -> service, CLOCK );
    }

    private static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    private ReportQuery queryFor( final Map< String, Object > args ) {
        when( service.list( any() ) ).thenReturn( List.of() );
        tool.execute( args );
        final ArgumentCaptor< ReportQuery > c = ArgumentCaptor.forClass( ReportQuery.class );
        verify( service ).list( c.capture() );
        return c.getValue();
    }

    @Test
    void nameIsListAgentReports() {
        assertEquals( "list_agent_reports", tool.name() );
        assertEquals( "list_agent_reports", tool.definition().name() );
    }

    @Test
    void filtersPassThroughToTheQuery() {
        final ReportQuery q = queryFor( Map.of( "since", "2026-11-01", "kind_of_work", "build", "product", "wikantik",
                "client", "claude-code", "skill", "tdd", "outcome", "done", "limit", 5 ) );
        assertEquals( Instant.parse( "2026-11-01T00:00:00Z" ), q.from() );
        assertEquals( "build", q.kindOfWork() );
        assertEquals( "wikantik", q.product() );
        assertEquals( "claude-code", q.client() );
        assertEquals( "tdd", q.skill() );
        assertEquals( "done", q.outcome() );
        assertEquals( 5, q.limit() );
    }

    @Test
    void defaultWindowIsSevenDaysEndingNow() {
        assertEquals( Instant.parse( "2026-11-19T12:00:00Z" ), queryFor( Map.of() ).from() );
    }

    @Test
    void badSinceIsAnErrorNamingIt() {
        final McpSchema.CallToolResult r = tool.execute( Map.of( "since", "yesterday" ) );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "since" ) );
    }

    @Test
    void unavailableStoreIsAClearError() {
        final McpSchema.CallToolResult r = new ListAgentReportsTool( () -> null, CLOCK ).execute( Map.of() );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "report store unavailable" ) );
    }

    @Test
    void rendersReportsAndCount() {
        when( service.list( any() ) ).thenReturn( List.of( new AgentReport( 3L, Instant.parse( "2026-11-25T00:00:00Z" ),
                "claude-code", "h", "jake", "ws", "build", List.of( "wikantik" ), List.of(), null, null, "t", "done",
                List.of(), null, List.of(), null ) ) );
        final JsonObject o = JsonParser.parseString( text( tool.execute( Map.of() ) ) ).getAsJsonObject();
        assertEquals( 1, o.get( "count" ).getAsInt() );
        assertEquals( "build", o.getAsJsonArray( "reports" ).get( 0 ).getAsJsonObject().get( "kind_of_work" ).getAsString() );
    }

    @Test
    void storeFailureIsAnErrorNotAThrow() {
        when( service.list( any() ) ).thenThrow( new IllegalStateException( "db down" ) );
        assertTrue( tool.execute( Map.of() ).isError() );
    }
}
