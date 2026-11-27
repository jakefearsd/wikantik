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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The {@code mcp_tool_called} event, rendered through the production JSON layout. */
class McpCallEventTest {

    static final String LOGGER = "com.wikantik.mcp.events";

    /** JSON lines the events logger writes through EcsLayout.json while {@code action} runs. */
    static List< JsonObject > rendered( final Runnable action ) {
        final List< JsonObject > lines = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
        final JsonTemplateLayout layout = JsonTemplateLayout.newBuilder().setConfiguration( ctx.getConfiguration() )
                .setEventTemplateUri( "classpath:EcsLayout.json" ).build();
        final AbstractAppender capture = new AbstractAppender( "McpCallEventCapture", null, layout, true,
                Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent event ) {
                lines.add( JsonParser.parseString( new String( layout.toByteArray( event ),
                        java.nio.charset.StandardCharsets.UTF_8 ) ).getAsJsonObject() );
            }
        };
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
        return lines;
    }

    private static String str( final JsonObject o, final String key ) {
        return o.has( key ) ? o.get( key ).getAsString() : null;
    }

    @Test
    void successEventCarriesTopLevelFields() {
        final List< JsonObject > out = rendered( () -> McpCallEvent.emit( "knowledge", "get_page", "claude-code",
                "abc123def456", true, 18_400_000L, 512L, null ) );
        assertEquals( 1, out.size(), out.toString() );
        final JsonObject j = out.get( 0 );
        assertEquals( "mcp_tool_called", str( j, "event.action" ), j.toString() );
        assertEquals( "success", str( j, "event.outcome" ) );
        assertEquals( "18400000", str( j, "event.duration" ) );
        assertEquals( "knowledge", str( j, "mcp.server" ) );
        assertEquals( "get_page", str( j, "mcp.tool" ) );
        assertEquals( "claude-code", str( j, "mcp.client.name" ) );
        assertEquals( "abc123def456", str( j, "mcp.session.hash" ) );
        assertEquals( "512", str( j, "mcp.response.bytes" ) );
        assertEquals( "mcp tool called", str( j, "message" ) );
        assertNull( str( j, "error.type" ) );
    }

    @Test
    void failureEventCarriesErrorType() {
        final JsonObject j = rendered( () -> McpCallEvent.emit( "admin", "update_page", "x", "none", false, 5L, 0L,
                "IllegalStateException" ) ).get( 0 );
        assertEquals( "failure", str( j, "event.outcome" ) );
        assertEquals( "IllegalStateException", str( j, "error.type" ) );
    }

    @Test
    void clientNameIsCutToSixtyFourChars() {
        final JsonObject j = rendered( () -> McpCallEvent.emit( "work", "t", "n".repeat( 65 ), "none", true, 1L, 0L,
                null ) ).get( 0 );
        assertEquals( "n".repeat( 64 ), str( j, "mcp.client.name" ) );
    }

    @Test
    void threadContextIsCleanAfterEmit() {
        McpCallEvent.emit( "work", "t", "c", "none", true, 1L, 0L, null );
        for ( final String k : List.of( "event.action", "event.outcome", "event.duration", "mcp.server", "mcp.tool",
                "mcp.client.name", "mcp.session.hash", "mcp.response.bytes", "error.type" ) ) {
            assertFalse( ThreadContext.containsKey( k ), k );
        }
    }

    @Test
    void exchangeHelpersAreNullSafe() {
        assertEquals( "?", McpCallEvent.clientName( null ) );
        assertEquals( "none", McpCallEvent.sessionHash( null ) );
        final McpSyncServerExchange ex = mock( McpSyncServerExchange.class );
        when( ex.getClientInfo() ).thenThrow( new IllegalStateException( "not initialized" ) );
        when( ex.sessionId() ).thenThrow( new IllegalStateException( "no session" ) );
        assertEquals( "?", McpCallEvent.clientName( ex ) );
        assertEquals( "none", McpCallEvent.sessionHash( ex ) );
        final McpSyncServerExchange ok = mock( McpSyncServerExchange.class );
        when( ok.getClientInfo() ).thenReturn( new McpSchema.Implementation( "cursor", "1" ) );
        when( ok.sessionId() ).thenReturn( "sid-1" );
        assertEquals( "cursor", McpCallEvent.clientName( ok ) );
        assertEquals( SessionHash.of( "sid-1" ), McpCallEvent.sessionHash( ok ) );
    }

    private static String clientNameEmittedFor( final String client ) {
        return str( rendered( () -> McpCallEvent.emit( "admin", "t", client, "none", true, 1L, 0L, null ) ).get( 0 ),
                "mcp.client.name" );
    }

    @Test
    void clientNameOfExactlySixtyFourCharsIsKeptWhole() {
        final String name = "c".repeat( 64 );
        assertEquals( name, clientNameEmittedFor( name ) );
    }

    @Test
    void clientNameOfSixtyFiveCharsIsCutToSixtyFour() {
        assertEquals( "c".repeat( 64 ), clientNameEmittedFor( "c".repeat( 65 ) ) );
    }

    @Test
    void aNullClientNameIsRecordedAsQuestionMark() {
        assertEquals( "?", clientNameEmittedFor( null ) );
    }
}
