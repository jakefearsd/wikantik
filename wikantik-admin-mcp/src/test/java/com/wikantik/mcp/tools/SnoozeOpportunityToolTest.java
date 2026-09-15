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

import com.wikantik.insights.runtime.ContentOpportunityService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SnoozeOpportunityTool}: required-argument validation ({@code type},
 * {@code target}, {@code reason}, {@code days} range), the happy path (including author
 * attribution via {@link DefaultAuthorTool#setDefaultAuthor}), and the two refusal paths
 * (subsystem unavailable, service throws).
 */
class SnoozeOpportunityToolTest {

    private ContentOpportunityService service;
    private SnoozeOpportunityTool tool;

    @BeforeEach
    void setUp() {
        service = mock( ContentOpportunityService.class );
        tool = new SnoozeOpportunityTool( service );
    }

    private static String text( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    @Test
    void name_isSnoozeOpportunity() {
        assertEquals( "snooze_opportunity", tool.name() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void definition_exposesToolNameSchemaAndRequiredFields() {
        final McpSchema.Tool definition = tool.definition();

        assertEquals( "snooze_opportunity", definition.name() );
        assertEquals( java.util.List.of( "type", "target", "days", "reason" ),
                definition.inputSchema().get( "required" ) );
        final Map< String, Object > properties =
                ( Map< String, Object > ) definition.inputSchema().get( "properties" );
        assertTrue( properties.containsKey( "type" ) );
        assertTrue( properties.containsKey( "target" ) );
        assertTrue( properties.containsKey( "days" ) );
        assertTrue( properties.containsKey( "reason" ) );
    }

    @Test
    void execute_refusesWhenServiceUnavailable() {
        final SnoozeOpportunityTool unwired = new SnoozeOpportunityTool( null );

        final McpSchema.CallToolResult result = unwired.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", 30, "reason", "already fixed" ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "not available" ) );
    }

    @Test
    void execute_refusesWhenTypeOrTargetMissing() {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "days", 30, "reason", "already fixed" ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "required" ) );
        verifyNoInteractions( service );
    }

    @Test
    void execute_refusesWhenReasonMissing() {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", 30 ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "reason" ) );
        verifyNoInteractions( service );
    }

    @Test
    void execute_refusesWhenReasonBlank() {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", 30, "reason", "   " ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "reason" ) );
        verifyNoInteractions( service );
    }

    @Test
    void execute_refusesNonNumericDays() {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", "not-a-number", "reason", "x" ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "whole number" ) );
        verifyNoInteractions( service );
    }

    @Test
    void execute_refusesDaysBelowMinimum() {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", 0, "reason", "x" ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "between 1 and 365" ) );
        verifyNoInteractions( service );
    }

    @Test
    void execute_refusesDaysAboveMaximum() {
        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", 400, "reason", "x" ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "between 1 and 365" ) );
        verifyNoInteractions( service );
    }

    @Test
    void execute_snoozesAndReturnsResult() {
        when( service.snooze( eq( "stale_high_traffic" ), eq( "/SomePage" ), eq( 30 ),
                eq( "already fixed" ), anyString() ) )
                .thenReturn( new ContentOpportunityService.SnoozeResult( LocalDate.of( 2026, 10, 16 ), false ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", 30, "reason", "already fixed" ) );

        assertFalse( result.isError() );
        final String json = text( result );
        assertTrue( json.contains( "2026-10-16" ) );
        assertTrue( json.contains( "\"previouslySnoozed\":false" ) );
    }

    @Test
    void execute_usesConfiguredDefaultAuthor() {
        tool.setDefaultAuthor( "coding-agent" );
        when( service.snooze( anyString(), anyString(), anyInt(), anyString(), eq( "coding-agent" ) ) )
                .thenReturn( new ContentOpportunityService.SnoozeResult( LocalDate.of( 2026, 9, 1 ), true ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "agent_gap", "target", "some query", "days", "5", "reason", "declined" ) );

        assertFalse( result.isError() );
        verify( service ).snooze( "agent_gap", "some query", 5, "declined", "coding-agent" );
    }

    @Test
    void execute_returnsErrorEnvelopeWhenServiceThrows() {
        when( service.snooze( anyString(), anyString(), anyInt(), anyString(), anyString() ) )
                .thenThrow( new RuntimeException( "db down" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
                "type", "stale_high_traffic", "target", "/SomePage", "days", 30, "reason", "x" ) );

        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "Failed to record the snooze" ) );
    }
}
