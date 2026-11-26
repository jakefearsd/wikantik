package com.wikantik.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * An {@link McpTool} that needs the session exchange of the call it serves: who the client is, which session it
 * belongs to, and the transport context of the request (for example the verified API key). The endpoint's call
 * handler invokes {@link #execute(Map, McpSyncServerExchange)} for these tools and {@link #execute(Map)} for all
 * others, so no other tool changes behaviour.
 */
public interface ExchangeAwareMcpTool extends McpTool {

    /** Executes the tool for one call; {@code exchange} may be null in tests and must be tolerated. */
    McpSchema.CallToolResult execute( Map< String, Object > arguments, McpSyncServerExchange exchange );
}
