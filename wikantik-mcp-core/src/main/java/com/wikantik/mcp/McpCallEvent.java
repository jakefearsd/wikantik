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

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The one structured event every MCP {@code tools/call} emits, for the observability plane. Fields ride the
 * thread context so the production JSON layout writes them as top-level keys. Never carries arguments, results,
 * search text, the raw session id or an error message.
 */
public final class McpCallEvent {

    /** Logger name the plane filters on. */
    public static final String LOGGER_NAME = "com.wikantik.mcp.events";

    private static final Logger EVENTS = LogManager.getLogger( LOGGER_NAME );
    private static final Logger LOG = LogManager.getLogger( McpCallEvent.class );
    private static final int MAX_CLIENT_NAME = 64;

    private McpCallEvent() {
    }

    /** Emits one {@code mcp_tool_called} event; never throws. */
    public static void emit( final String server, final String tool, final String clientName,
                             final String sessionHash, final boolean success, final long durationNanos,
                             final long responseBytes, final String errorTypeOrNull ) {
        final Map< String, String > fields = new HashMap<>();
        fields.put( "event.action", "mcp_tool_called" );
        fields.put( "event.outcome", success ? "success" : "failure" );
        fields.put( "event.duration", Long.toString( durationNanos ) );
        fields.put( "mcp.server", server );
        fields.put( "mcp.tool", tool );
        fields.put( "mcp.client.name", cut( clientName ) );
        fields.put( "mcp.session.hash", sessionHash );
        fields.put( "mcp.response.bytes", Long.toString( responseBytes ) );
        if ( errorTypeOrNull != null ) {
            fields.put( "error.type", errorTypeOrNull );
        }
        try ( CloseableThreadContext.Instance ignored = CloseableThreadContext.putAll( fields ) ) {
            EVENTS.info( "mcp tool called" );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not emit mcp_tool_called for {}: {}", tool, e.getClass().getSimpleName() );
        }
    }

    /** The client's self-reported name, or {@code "?"} when unavailable. Never throws. */
    public static String clientName( final McpSyncServerExchange exchange ) {
        try {
            final McpSchema.Implementation ci = exchange != null ? exchange.getClientInfo() : null;
            if ( ci != null && ci.name() != null ) {
                return ci.name();
            }
        } catch ( final RuntimeException e ) {
            // exchange.getClientInfo can throw before initialize completes
            LOG.debug( "Client info unavailable for MCP call telemetry; using '?': {}", e.getMessage() );
        }
        return "?";
    }

    /** {@link SessionHash#of} of the exchange's session id, or {@code "none"}. Never throws. */
    public static String sessionHash( final McpSyncServerExchange exchange ) {
        try {
            return SessionHash.of( exchange != null ? exchange.sessionId() : null );
        } catch ( final RuntimeException e ) {
            LOG.debug( "Session id unavailable for MCP call telemetry; using 'none': {}", e.getMessage() );
            return SessionHash.NONE;
        }
    }

    /** UTF-8 byte length of a result's text contents; 0 when it has none. */
    public static long responseBytes( final McpSchema.CallToolResult result ) {
        long bytes = 0;
        if ( result != null && result.content() != null ) {
            for ( final McpSchema.Content c : result.content() ) {
                if ( c instanceof McpSchema.TextContent t && t.text() != null ) {
                    bytes += t.text().getBytes( StandardCharsets.UTF_8 ).length;
                }
            }
        }
        return bytes;
    }

    private static String cut( final String s ) {
        if ( s == null ) {
            return "?";
        }
        return s.length() > MAX_CLIENT_NAME ? s.substring( 0, MAX_CLIENT_NAME ) : s;
    }
}
