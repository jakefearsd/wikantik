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

import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkNotFoundException;
import io.modelcontextprotocol.common.McpTransportContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.modelcontextprotocol.spec.McpSchema;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** What the endpoint writes to its own logs: no row values from a database error, reads only at DEBUG. */
class WorkMcpLoggingTest {

    /** Every event of the named logger at {@code Level.ALL} while {@code action} runs. */
    private static List< LogEvent > logged( final String logger, final ThrowingRunnable action ) throws Exception {
        final List< LogEvent > events = new CopyOnWriteArrayList<>();
        final AbstractAppender capture = new AbstractAppender( "WorkMcpLoggingCapture", null, null, true,
            Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent event ) {
                events.add( event.toImmutable() );
            }
        };
        final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
        final LoggerConfig own = LoggerConfig.newBuilder().withLoggerName( logger ).withLevel( Level.ALL )
            .withAdditivity( false ).withConfig( ctx.getConfiguration() ).build();
        capture.start();
        own.addAppender( capture, Level.ALL, null );
        ctx.getConfiguration().addLogger( logger, own );
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            ctx.getConfiguration().removeLogger( logger );
            ctx.updateLoggers();
            capture.stop();
        }
        return events;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void aDatabaseErrorIsLoggedByStateAndClassNeverByItsMessage() throws Exception {
        final MockWork w = new MockWork();
        when( w.svc.item( any(), any() ) ).thenThrow( new SQLException(
            "duplicate key value violates unique constraint; Key (title)=(secret row value) already exists", "23505" ) );
        final List< LogEvent > events = logged( WorkMcpTool.class.getName(),
            () -> new GetItemTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1" ) ) );
        final LogEvent warn = events.stream().filter( e -> e.getLevel() == Level.WARN ).findFirst().orElseThrow();
        final String line = warn.getMessage().getFormattedMessage();
        assertTrue( line.contains( "23505" ) && line.contains( "SQLException" ), line );
        assertFalse( line.contains( "secret row value" ), line );
        assertNull( warn.getThrown(), "no stack: its first line would print the message" );
    }

    private static McpTransportContext keyed() {
        return McpTransportContext.create( Map.of( WorkMcpActors.KEY_RECORD, new ApiKeyService.Record( 9, "h", "lena",
            "k", ApiKeyService.Scope.WORK, Instant.EPOCH, "admin", null, null, null, false ) ) );
    }

    @Test
    void aReadToolCallIsLoggedAtDebugAndAWriteAtInfo() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForApiKey( 9, "lena" ) ).thenReturn( w.agent );
        when( w.svc.item( any(), any() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        when( w.claims.release( any(), any(), any() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        final List< LogEvent > events = logged( WorkMcpInitializer.class.getName(), () -> {
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, keyed(), Map.of( "key", "PLAT-1" ) );
            WorkMcpInitializer.dispatch( new ReleaseItemTool( w.svc ), w.svc, keyed(), Map.of( "key", "PLAT-1" ) );
        } );
        final List< LogEvent > calls = events.stream()
            .filter( e -> e.getMessage().getFormattedMessage().startsWith( "Work MCP tools/call" ) ).toList();
        assertEquals( 2, calls.size(), calls.toString() );
        assertEquals( Level.DEBUG, calls.get( 0 ).getLevel(), "a read" );
        assertTrue( calls.get( 0 ).getMessage().getFormattedMessage().contains( "name=get_item" ) );
        assertEquals( Level.INFO, calls.get( 1 ).getLevel(), "a write" );
    }

    @Test
    void aKeyLookupFailureIsLoggedWithoutItsMessage() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForAgent( 9 ) ).thenThrow( new SQLException( "row (lena, secret)", "08006" ) );
        final io.modelcontextprotocol.common.McpTransportContext agentCall =
            io.modelcontextprotocol.common.McpTransportContext.create( Map.of( WorkMcpActors.AGENT_REF,
                new com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef( 1, 9 ) ) );
        final List< LogEvent > events = logged( WorkMcpInitializer.class.getName(), () ->
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, agentCall, Map.of( "key", "P-1" ) ) );
        final LogEvent warn = events.stream().filter( e -> e.getLevel() == Level.WARN ).findFirst().orElseThrow();
        assertTrue( warn.getMessage().getFormattedMessage().contains( "08006" ) );
        assertFalse( warn.getMessage().getFormattedMessage().contains( "secret" ) );
        assertNull( warn.getThrown() );
    }

    @Test
    void anActorIsUnusedHereButTheReadRefusalWritesNoSecurityLine() throws Exception {
        // h24: a refused READ tool logs no MCP_WORK_REFUSED line (only write tools do).
        final MockWork w = new MockWork();
        when( w.svc.item( any(), any() ) ).thenThrow( new WorkNotFoundException( "hidden" ) );
        final Actor agent = w.agent;
        final List< String > lines = WriteToolsAuditTest.audited( () ->
            new GetItemTool( w.svc, w.directory ).call( agent, Map.of( "key", "HID-1" ) ) );
        assertTrue( lines.isEmpty(), lines.toString() );
        final List< String > keyless = WriteToolsAuditTest.audited( () ->
            new GetItemTool( w.svc, w.directory ).call( null, Map.of( "key", "HID-1" ) ) );
        assertTrue( keyless.isEmpty(), keyless.toString() );
    }

    @Test
    void aToolCallDuringADatabaseOutageFailsSoftAndTheNextOneRecovers() throws Exception {
        final MockWork w = new MockWork();
        when( w.svc.item( any(), any() ) ).thenThrow( new SQLException( "connection lost", "08006" ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", com.wikantik.work.WorkStatus.READY, 2, null, null ) ) );
        final GetItemTool tool = new GetItemTool( w.svc, w.directory );
        final var down = tool.call( w.agent, Map.of( "key", "PLAT-1" ) );
        assertTrue( Boolean.TRUE.equals( down.isError() ) );
        assertEquals( WorkMcpTool.UNAVAILABLE, WorkMcpFixture.text( down ) );
        final var up = tool.call( w.agent, Map.of( "key", "PLAT-1" ) );
        assertFalse( Boolean.TRUE.equals( up.isError() ), WorkMcpFixture.text( up ) );
        assertTrue( WorkMcpFixture.text( up ).contains( "PLAT-1" ) );
    }

    @Test
    void everyDispatchEmitsOneMcpToolCalledEventWithoutArguments() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForApiKey( 9, "lena" ) ).thenReturn( w.agent );
        when( w.svc.item( any(), any() ) ).thenThrow( new WorkNotFoundException( "SECRET-ARG" ) );
        final List< LogEvent > events = logged( "com.wikantik.mcp.events", () ->
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, keyed(),
                Map.of( "key", "SECRET-ARG" ), "claude-code", "abc123def456" ) );
        assertEquals( 1, events.size(), events.toString() );
        final var ctx = events.get( 0 ).getContextData();
        assertEquals( "mcp_tool_called", ctx.getValue( "event.action" ) );
        assertEquals( "work", ctx.getValue( "mcp.server" ) );
        assertEquals( "get_item", ctx.getValue( "mcp.tool" ) );
        assertEquals( "claude-code", ctx.getValue( "mcp.client.name" ) );
        assertEquals( "abc123def456", ctx.getValue( "mcp.session.hash" ) );
        assertEquals( "failure", ctx.getValue( "event.outcome" ), "an isError envelope is a failure" );
        assertEquals( "tool_error", ctx.getValue( "error.type" ) );
        assertFalse( ctx.toMap().toString().contains( "SECRET-ARG" ) );
    }

    private void assertThrowsAsFailureEvent( final Throwable toThrow, final Class< ? extends Throwable > type )
        throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForApiKey( 9, "lena" ) ).thenReturn( w.agent );
        when( w.svc.item( any(), any() ) ).thenAnswer( inv -> {
            throw toThrow;
        } );
        final List< LogEvent > events = logged( "com.wikantik.mcp.events", () ->
            org.junit.jupiter.api.Assertions.assertSame( toThrow, org.junit.jupiter.api.Assertions.assertThrows( type,
                () -> WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, keyed(),
                    Map.of( "key", "P-1" ), "c", "none" ) ) ) );
        assertEquals( 1, events.size(), events.toString() );
        assertEquals( "failure", events.get( 0 ).getContextData().getValue( "event.outcome" ) );
        assertEquals( type.getSimpleName(), events.get( 0 ).getContextData().getValue( "error.type" ) );
    }

    /** The tool's own contract turns a RuntimeException into an error envelope: one failure event, no rethrow. */
    @Test
    void aRuntimeExceptionInsideAToolBecomesAnErrorEnvelopeAndOneFailureEvent() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.actorForApiKey( 9, "lena" ) ).thenReturn( w.agent );
        when( w.svc.item( any(), any() ) ).thenThrow( new IllegalStateException( "SECRET-ARG" ) );
        final McpSchema.CallToolResult[] returned = new McpSchema.CallToolResult[ 1 ];
        final List< LogEvent > events = logged( "com.wikantik.mcp.events", () -> returned[ 0 ] =
            WorkMcpInitializer.dispatch( new GetItemTool( w.svc, w.directory ), w.svc, keyed(),
                Map.of( "key", "P-1" ), "c", "none" ) );
        assertTrue( Boolean.TRUE.equals( returned[ 0 ].isError() ) );
        assertEquals( 1, events.size(), events.toString() );
        assertEquals( "failure", events.get( 0 ).getContextData().getValue( "event.outcome" ) );
        assertFalse( events.get( 0 ).getContextData().toMap().toString().contains( "SECRET-ARG" ) );
    }

    @Test
    void aThrownErrorEmitsOneFailureEventAndPropagates() throws Exception {
        assertThrowsAsFailureEvent( new StackOverflowError(), StackOverflowError.class );
    }
}
