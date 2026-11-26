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
package com.wikantik.mcp;

import com.wikantik.mcp.tools.McpTool;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The audit line both MCP endpoints write for every {@code tools/call}. */
class AuditedToolCallTest {

    private static final String LOGGER = AuditedToolCall.class.getName();

    private static McpTool tool() {
        final McpTool tool = mock( McpTool.class );
        when( tool.name() ).thenReturn( "read_page" );
        return tool;
    }

    private static McpSyncServerExchange exchange( final String clientName ) {
        final McpSyncServerExchange exchange = mock( McpSyncServerExchange.class );
        when( exchange.getClientInfo() ).thenReturn( new McpSchema.Implementation( clientName, "1.0" ) );
        return exchange;
    }

    private static McpSchema.CallToolResult result( final boolean isError ) {
        return McpSchema.CallToolResult.builder().addTextContent( "x" ).isError( isError ).build();
    }

    /** INFO-and-above messages of the audited-call logger while {@code action} runs. */
    private static List< String > logged( final Runnable action ) {
        final List< String > messages = new CopyOnWriteArrayList<>();
        final AbstractAppender capture = new AbstractAppender( "AuditedToolCallCapture", null, null, true,
                Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent event ) {
                if ( event.getLevel().isMoreSpecificThan( Level.INFO ) ) {
                    messages.add( event.getMessage().getFormattedMessage() );
                }
            }
        };
        final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
        final LoggerConfig own = LoggerConfig.newBuilder().withLoggerName( LOGGER ).withLevel( Level.ALL )
                .withAdditivity( false ).withConfig( ctx.getConfiguration() ).build();
        capture.start();
        own.addAppender( capture, Level.ALL, null );
        ctx.getConfiguration().addLogger( LOGGER, own );
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            ctx.getConfiguration().removeLogger( LOGGER );
            ctx.updateLoggers();
            capture.stop();
        }
        return messages;
    }

    private static void assertLine( final String expectedRegex, final List< String > lines ) {
        assertEquals( 1, lines.size(), () -> "expected one audit line: " + lines );
        assertTrue( Pattern.matches( expectedRegex, lines.get( 0 ) ), lines.get( 0 ) );
    }

    @Test
    void successfulCallWithSurface_logsAdminLine() {
        final McpSchema.CallToolResult ok = result( false );
        final McpSchema.CallToolResult[] returned = new McpSchema.CallToolResult[ 1 ];
        final List< String > lines = logged( () -> returned[ 0 ] = AuditedToolCall.run( "MCP", "admin", tool(),
                exchange( "claude" ), "read", () -> ok ) );
        assertSame( ok, returned[ 0 ] );
        assertLine( "MCP tools/call name=read_page client=claude surface=read durationMs=\\d+ isError=false", lines );
    }

    @Test
    void errorResultWithoutSurface_logsKnowledgeLine() {
        final List< String > lines = logged( () -> AuditedToolCall.run( "Knowledge MCP", "knowledge", tool(),
                exchange( "cursor" ), null, () -> result( true ) ) );
        assertLine( "Knowledge MCP tools/call name=read_page client=cursor durationMs=\\d+ isError=true", lines );
    }

    @Test
    void thrownExceptionWithSurface_logsThrewAndRethrows() {
        final List< String > lines = logged( () -> assertThrows( IllegalStateException.class,
                () -> AuditedToolCall.run( "MCP", "admin", tool(), exchange( "claude" ), "write",
                        ( Supplier< McpSchema.CallToolResult > ) () -> {
                            throw new IllegalStateException( "boom" );
                        } ) ) );
        assertLine( "MCP tools/call name=read_page client=claude surface=write durationMs=\\d+ "
                + "threw=IllegalStateException", lines );
    }

    @Test
    void thrownExceptionWithoutSurface_logsThrewAndRethrows() {
        final List< String > lines = logged( () -> assertThrows( IllegalArgumentException.class,
                () -> AuditedToolCall.run( "Knowledge MCP", "knowledge", tool(), exchange( "cursor" ), null,
                        ( Supplier< McpSchema.CallToolResult > ) () -> {
                            throw new IllegalArgumentException( "boom" );
                        } ) ) );
        assertLine( "Knowledge MCP tools/call name=read_page client=cursor durationMs=\\d+ "
                + "threw=IllegalArgumentException", lines );
    }

    @Test
    void unavailableClientInfo_fallsBackToQuestionMark() {
        final McpSyncServerExchange exchange = mock( McpSyncServerExchange.class );
        when( exchange.getClientInfo() ).thenThrow( new IllegalStateException( "not initialized" ) );
        final List< String > lines = logged( () -> AuditedToolCall.run( "MCP", "admin", tool(), exchange,
                "read", () -> result( false ) ) );
        assertLine( "MCP tools/call name=read_page client=\\? surface=read durationMs=\\d+ isError=false", lines );
    }

    @Test
    void nullExchange_fallsBackToQuestionMark() {
        final List< String > lines = logged( () -> AuditedToolCall.run( "Knowledge MCP", "knowledge", tool(), null,
                null, () -> result( false ) ) );
        assertLine( "Knowledge MCP tools/call name=read_page client=\\? durationMs=\\d+ isError=false", lines );
    }

    private static List< JsonObject > events( final Runnable action ) {
        return McpCallEventTest.rendered( action );
    }

    @Test
    void throwingCallEmitsOneFailureEventAndRethrows() {
        final List< JsonObject > out = events( () -> assertThrows( IllegalStateException.class,
                () -> AuditedToolCall.run( "MCP", "admin", tool(), exchange( "claude" ), "write",
                        ( Supplier< McpSchema.CallToolResult > ) () -> {
                            throw new IllegalStateException( "secret argument text" );
                        } ) ) );
        assertEquals( 1, out.size(), out.toString() );
        assertEquals( "failure", out.get( 0 ).get( "event.outcome" ).getAsString() );
        assertEquals( "IllegalStateException", out.get( 0 ).get( "error.type" ).getAsString() );
        assertEquals( "admin", out.get( 0 ).get( "mcp.server" ).getAsString() );
        assertEquals( "read_page", out.get( 0 ).get( "mcp.tool" ).getAsString() );
        assertFalse( out.get( 0 ).toString().contains( "secret argument text" ) );
    }

    @Test
    void isErrorResultEmitsFailureEvent() {
        final List< JsonObject > out = events( () -> AuditedToolCall.run( "Knowledge MCP", "knowledge", tool(),
                exchange( "cursor" ), null, () -> result( true ) ) );
        assertEquals( 1, out.size() );
        assertEquals( "failure", out.get( 0 ).get( "event.outcome" ).getAsString() );
        assertEquals( "cursor", out.get( 0 ).get( "mcp.client.name" ).getAsString() );
    }

    @Test
    void successEventCountsUtf8ResponseBytes() {
        final McpSchema.CallToolResult r = McpSchema.CallToolResult.builder().addTextContent( "h\u00e9" )
                .isError( false ).build();
        final List< JsonObject > out = events( () -> AuditedToolCall.run( "MCP", "admin", tool(),
                exchange( "c" ), "read", () -> r ) );
        assertEquals( "success", out.get( 0 ).get( "event.outcome" ).getAsString() );
        assertEquals( "3", out.get( 0 ).get( "mcp.response.bytes" ).getAsString() );
    }

    @Test
    void nullExchangeEmitsQuestionMarkClientAndNoneHash() {
        final List< JsonObject > out = events( () -> AuditedToolCall.run( "Knowledge MCP", "knowledge", tool(),
                null, null, () -> result( false ) ) );
        assertEquals( "?", out.get( 0 ).get( "mcp.client.name" ).getAsString() );
        assertEquals( "none", out.get( 0 ).get( "mcp.session.hash" ).getAsString() );
    }
}
