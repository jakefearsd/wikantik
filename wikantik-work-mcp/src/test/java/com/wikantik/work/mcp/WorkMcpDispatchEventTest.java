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
package com.wikantik.work.mcp;

import com.wikantik.work.Actor;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkStatus;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What one dispatched tool call reports about itself: the {@code mcp_tool_called} event on the success path (the
 * failure path is pinned in {@link WorkMcpLoggingTest}), and the one-line call log (who, how long, whether it
 * failed).
 */
class WorkMcpDispatchEventTest {

    private static final String EVENTS = "com.wikantik.mcp.events";

    private static String callLine( final List< LogEvent > events ) {
        final List< String > lines = events.stream().map( e -> e.getMessage().getFormattedMessage() )
            .filter( m -> m.startsWith( "Work MCP tools/call" ) ).toList();
        assertEquals( 1, lines.size(), lines.toString() );
        return lines.get( 0 );
    }

    private static long durationMs( final String line ) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile( "durationMs=(\\d+)" ).matcher( line );
        assertTrue( m.find(), line );
        return Long.parseLong( m.group( 1 ) );
    }

    @Test
    void aSuccessfulCallEmitsASuccessEventWithItsSizeAndNoErrorType() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForApiKey( 9, "lena" ) ).thenReturn( w.agent );
        when( w.svc.item( any(), any() ) ).thenReturn(
            MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 2, null, null ) ) );
        final McpSchema.CallToolResult[] result = new McpSchema.CallToolResult[ 1 ];
        final List< LogEvent > events = WorkMcpLoggingTest.logged( EVENTS, () -> result[ 0 ] =
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, WorkMcpLoggingTest.keyed(),
                Map.of( "key", "PLAT-1" ), "claude-code", "abc123def456" ) );
        assertFalse( Boolean.TRUE.equals( result[ 0 ].isError() ), WorkMcpFixture.text( result[ 0 ] ) );
        assertEquals( 1, events.size(), events.toString() );
        final var ctx = events.get( 0 ).getContextData();
        assertEquals( "success", ctx.getValue( "event.outcome" ) );
        assertNull( ctx.getValue( "error.type" ), "a success carries no error type" );
        assertEquals( Long.toString( WorkMcpFixture.text( result[ 0 ] ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length ),
            ctx.getValue( "mcp.response.bytes" ) );
        final long nanos = Long.parseLong( ctx.getValue( "event.duration" ) );
        assertTrue( nanos >= 0 && nanos < TimeUnit.SECONDS.toNanos( 30 ), "a duration, not a sum of clock readings: " + nanos );
    }

    @Test
    void aCallRefusedForWantOfAKeyIsAFailureEvent() throws Exception {
        final MockWork w = new MockWork();
        final McpSchema.CallToolResult[] result = new McpSchema.CallToolResult[ 1 ];
        final List< LogEvent > events = WorkMcpLoggingTest.logged( EVENTS, () -> result[ 0 ] =
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, McpTransportContext.EMPTY,
                Map.of( "key", "PLAT-1" ), "c", "none" ) );
        assertEquals( WorkMcpTool.NEEDS_KEY, WorkMcpFixture.text( result[ 0 ] ) );
        assertEquals( "failure", events.get( 0 ).getContextData().getValue( "event.outcome" ) );
        assertEquals( "tool_error", events.get( 0 ).getContextData().getValue( "error.type" ) );
    }

    @Test
    void theCallLineNamesAnAgentByIdAndShowsAFastCallAsAFewMilliseconds() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForApiKey( 9, "lena" ) ).thenReturn( w.agent );
        when( w.svc.item( any(), any() ) ).thenReturn(
            MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 2, null, null ) ) );
        final List< LogEvent > events = WorkMcpLoggingTest.logged( WorkMcpInitializer.class.getName(), () ->
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, WorkMcpLoggingTest.keyed(),
                Map.of( "key", "PLAT-1" ) ) );
        final String line = callLine( events );
        assertTrue( line.contains( "caller=agent:7 " ), line );
        assertTrue( line.contains( "isError=false" ), line );
        assertTrue( durationMs( line ) < 30_000, "milliseconds, not a sum or a product of nanoseconds: " + line );
    }

    @Test
    void theCallLineNamesAPersonKeyAsAPersonAndNeverByLogin() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForApiKey( 9, "lena" ) ).thenReturn( Actor.human( "lena", java.util.Set.of(), false ) );
        when( w.svc.item( any(), any() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        final List< LogEvent > events = WorkMcpLoggingTest.logged( WorkMcpInitializer.class.getName(), () ->
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, WorkMcpLoggingTest.keyed(),
                Map.of( "key", "PLAT-1" ) ) );
        final String line = callLine( events );
        assertTrue( line.contains( "caller=person " ), line );
        assertFalse( line.contains( "lena" ), line );
        assertTrue( line.contains( "isError=true" ), line );
    }

    @Test
    void theCallLineSaysNoneWhenNobodyIsCalling() throws Exception {
        final MockWork w = new MockWork();
        final List< LogEvent > events = WorkMcpLoggingTest.logged( WorkMcpInitializer.class.getName(), () ->
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, McpTransportContext.EMPTY,
                Map.of( "key", "PLAT-1" ) ) );
        final String line = callLine( events );
        assertTrue( line.contains( "caller=none " ), line );
        assertEquals( Level.DEBUG, events.stream()
            .filter( e -> e.getMessage().getFormattedMessage().startsWith( "Work MCP tools/call" ) )
            .findFirst().orElseThrow().getLevel() );
    }
}
