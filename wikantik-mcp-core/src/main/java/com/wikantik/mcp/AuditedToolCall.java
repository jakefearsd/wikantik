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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * Wraps one MCP {@code tools/call} with an INFO-level audit line so admins can see which tool ran,
 * by which client, how long it took, and whether the tool returned an error envelope
 * ({@code CallToolResult.isError}) or threw — none of which is visible in the access log alone.
 * Shared by the admin and knowledge endpoints.
 */
public final class AuditedToolCall {

    private static final Logger LOG = LogManager.getLogger( AuditedToolCall.class );

    private AuditedToolCall() {
    }

    /**
     * Runs {@code call} and writes the audit line.
     *
     * @param logTag         line prefix, e.g. {@code "MCP"} or {@code "Knowledge MCP"}
     * @param server         the {@code mcp.server} value of the {@code mcp_tool_called} event: {@code "admin"}
     *                       or {@code "knowledge"}
     * @param tool           the tool being called (its name is logged)
     * @param exchange       the session exchange, source of the client name; may be null
     * @param surfaceOrNull  {@code "read"}/{@code "write"} for the admin endpoint; null omits the
     *                       {@code surface=} field
     * @param call           executes the tool; a runtime exception or Error is logged ({@code threw=}) and rethrown
     */
    public static McpSchema.CallToolResult run( final String logTag, final String server,
                                                final McpTool tool,
                                                final McpSyncServerExchange exchange,
                                                final String surfaceOrNull,
                                                final Supplier< McpSchema.CallToolResult > call ) {
        final long t0 = System.nanoTime();
        McpSchema.CallToolResult result = null;
        Throwable thrown = null;
        try {
            result = call.get();
            return result;
        } catch ( final RuntimeException | Error re ) {
            thrown = re;
            throw re;
        } finally {
            final long ms = ( System.nanoTime() - t0 ) / 1_000_000L;
            final String client = McpCallEvent.clientName( exchange );
            final String surface = surfaceOrNull == null ? "" : " surface=" + surfaceOrNull;
            McpCallEvent.emit( server, tool.name(), client, McpCallEvent.sessionHash( exchange ),
                    thrown == null && !( result != null && Boolean.TRUE.equals( result.isError() ) ),
                    System.nanoTime() - t0, McpCallEvent.responseBytes( result ),
                    thrown == null ? null : thrown.getClass().getSimpleName() );
            if ( thrown != null ) {
                LOG.info( "{} tools/call name={} client={}{} durationMs={} threw={}",
                        logTag, tool.name(), client, surface, ms, thrown.getClass().getSimpleName() );
            } else {
                final boolean isError = result != null && Boolean.TRUE.equals( result.isError() );
                LOG.info( "{} tools/call name={} client={}{} durationMs={} isError={}",
                        logTag, tool.name(), client, surface, ms, isError );
            }
        }
    }
}
