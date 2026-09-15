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

import com.wikantik.insights.Opportunity;
import com.wikantik.insights.SuppressedRule;
import com.wikantik.insights.runtime.ContentOpportunityService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ListContentOpportunitiesTool}: parameter mapping into
 * {@link ContentOpportunityService#backlog}, the rendered JSON shape (opportunities, suppressed,
 * uncalibratedTypes, ctrCurveSource), and the two refusal paths (subsystem unavailable, service
 * throws).
 */
class ListContentOpportunitiesToolTest {

    private ContentOpportunityService service;
    private ListContentOpportunitiesTool tool;

    @BeforeEach
    void setUp() {
        service = mock( ContentOpportunityService.class );
        tool = new ListContentOpportunitiesTool( service );
    }

    private static String text( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    private static ContentOpportunityService.BacklogView emptyView() {
        return new ContentOpportunityService.BacklogView(
                List.of(), List.of(), Set.of(), LocalDate.of( 2026, 8, 1 ), "wiki.wikantik.com", "builtin" );
    }

    @Test
    void name_isListContentOpportunities() {
        assertEquals( "list_content_opportunities", tool.name() );
    }

    @Test
    void execute_refusesWhenServiceUnavailable() {
        final ListContentOpportunitiesTool unwired = new ListContentOpportunitiesTool( null );

        final McpSchema.CallToolResult result = unwired.execute( Map.of() );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "not available" ) );
    }

    @Test
    void execute_rendersOpportunitiesAndSuppressedWithFullShape() {
        final Opportunity opp = new Opportunity( "agent_gap", "how do I deploy locally", 6.0,
                Map.of( "occurrences", 3, "distinctSessions", 2 ),
                "Curate the Knowledge Graph relations, or write the missing section.",
                LocalDate.of( 2026, 8, 2 ), false );
        final SuppressedRule suppressed = new SuppressedRule( "engine_divergence", "traffic_gate", 2626.0, 5000.0 );
        final ContentOpportunityService.BacklogView view = new ContentOpportunityService.BacklogView(
                List.of( opp ), List.of( suppressed ), Set.of( "agent_gap", "engine_divergence" ),
                LocalDate.of( 2026, 8, 1 ), "wiki.wikantik.com", "imported:2026-08-14" );
        when( service.backlog( any(), any(), any(), anyInt(), anyBoolean() ) ).thenReturn( view );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertFalse( result.isError() );
        final String json = text( result );
        assertTrue( json.contains( "how do I deploy locally" ) );
        assertTrue( json.contains( "\"count\":1" ) );
        assertTrue( json.contains( "traffic_gate" ) );
        assertTrue( json.contains( "imported:2026-08-14" ) );
        assertTrue( json.contains( "\"calibrated\":false" ) );
    }

    @Test
    void execute_mapsAllArgumentsToBacklogCall() {
        when( service.backlog( any(), any(), any(), anyInt(), anyBoolean() ) ).thenReturn( emptyView() );

        tool.execute( Map.of(
                "type", "stale_high_traffic",
                "site", "example.com",
                "limit", 5,
                "min_priority", 2.5,
                "include_snoozed", true ) );

        verify( service ).backlog( "example.com", "stale_high_traffic", 2.5, 5, true );
    }

    @Test
    void execute_usesDefaultsWhenArgumentsAbsent() {
        when( service.backlog( any(), any(), any(), anyInt(), anyBoolean() ) ).thenReturn( emptyView() );

        tool.execute( Map.of() );

        verify( service ).backlog( ( String ) null, null, null, 20, false );
    }

    @Test
    void execute_parsesLimitFromStringArgument() {
        when( service.backlog( any(), any(), any(), anyInt(), anyBoolean() ) ).thenReturn( emptyView() );

        tool.execute( Map.of( "limit", "7" ) );

        verify( service ).backlog( null, null, null, 7, false );
    }

    @Test
    void execute_returnsErrorEnvelopeWhenServiceThrows() {
        when( service.backlog( any(), any(), any(), anyInt(), anyBoolean() ) )
                .thenThrow( new RuntimeException( "db down" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "Failed to assemble" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void definition_exposesToolNameAndSchema() {
        final McpSchema.Tool definition = tool.definition();

        assertEquals( "list_content_opportunities", definition.name() );
        final Map< String, Object > properties =
                ( Map< String, Object > ) definition.inputSchema().get( "properties" );
        assertTrue( properties.containsKey( "type" ) );
        assertTrue( properties.containsKey( "limit" ) );
        assertTrue( properties.containsKey( "min_priority" ) );
        assertTrue( properties.containsKey( "include_snoozed" ) );
        assertTrue( properties.containsKey( "site" ) );
    }

    @Test
    void execute_fallsBackToDefaultLimitOnNonNumericLimitString() {
        when( service.backlog( any(), any(), any(), anyInt(), anyBoolean() ) ).thenReturn( emptyView() );

        tool.execute( Map.of( "limit", "not-a-number" ) );

        verify( service ).backlog( null, null, null, 20, false );
    }

    @Test
    void execute_rendersNullFirstSeenWhenAbsent() {
        final Opportunity opp = new Opportunity( "vocabulary_gap", "some query", 3.0,
                Map.of(), "Add the missing terminology.", null, true );
        final ContentOpportunityService.BacklogView view = new ContentOpportunityService.BacklogView(
                List.of( opp ), List.of(), Set.of(), LocalDate.of( 2026, 8, 1 ), "wiki.wikantik.com", "builtin" );
        when( service.backlog( any(), any(), any(), anyInt(), anyBoolean() ) ).thenReturn( view );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertTrue( text( result ).contains( "\"firstSeen\":null" ) );
    }
}
