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
package org.apache.wiki.its.mcp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import org.apache.wiki.its.environment.Env;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper wrapping {@link McpSyncClient} with typed convenience methods for MCP integration tests.
 */
public class McpTestClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final McpSyncClient client;

    private McpTestClient( final McpSyncClient client ) {
        this.client = client;
    }

    public static McpTestClient create() {
        final String mcpUrl = Env.TESTS_BASE_URL + "/mcp";
        final HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder( mcpUrl )
                        .connectTimeout( Duration.ofSeconds( 10 ) )
                        .build();

        final McpSyncClient syncClient = McpClient.sync( transport )
                .clientInfo( new McpSchema.Implementation( "jspwiki-it-mcp-client", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();

        syncClient.initialize();
        return new McpTestClient( syncClient );
    }

    public McpSyncClient rawClient() {
        return client;
    }

    public Object ping() {
        return client.ping();
    }

    public ListToolsResult listTools() {
        return client.listTools();
    }

    public Map< String, Object > callTool( final String name, final Map< String, Object > args ) {
        final CallToolResult result = client.callTool( new CallToolRequest( name, args ) );
        if ( Boolean.TRUE.equals( result.isError() ) ) {
            throw new McpToolError( name, extractText( result ) );
        }
        return parseJsonResult( result );
    }

    public Map< String, Object > callToolExpectingError( final String name, final Map< String, Object > args ) {
        final CallToolResult result = client.callTool( new CallToolRequest( name, args ) );
        if ( !Boolean.TRUE.equals( result.isError() ) ) {
            throw new AssertionError( "Expected isError=true for tool '" + name + "' but got success: " + extractText( result ) );
        }
        return parseJsonResult( result );
    }

    public Map< String, Object > readPage( final String pageName ) {
        return callTool( "read_page", Map.of( "pageName", pageName ) );
    }

    public Map< String, Object > readPage( final String pageName, final int version ) {
        return callTool( "read_page", Map.of( "pageName", pageName, "version", version ) );
    }

    public Map< String, Object > writePage( final String pageName, final String content ) {
        return callTool( "write_page", Map.of( "pageName", pageName, "content", content ) );
    }

    public Map< String, Object > writePage( final String pageName, final String content, final Map< String, Object > metadata ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        args.put( "pageName", pageName );
        args.put( "content", content );
        args.put( "metadata", metadata );
        return callTool( "write_page", args );
    }

    public Map< String, Object > writePage( final String pageName, final String content, final String changeNote ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        args.put( "pageName", pageName );
        args.put( "content", content );
        args.put( "changeNote", changeNote );
        return callTool( "write_page", args );
    }

    public Map< String, Object > writePageFull( final String pageName, final String content,
                                                 final Map< String, Object > metadata, final String changeNote ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        args.put( "pageName", pageName );
        args.put( "content", content );
        if ( metadata != null ) {
            args.put( "metadata", metadata );
        }
        if ( changeNote != null ) {
            args.put( "changeNote", changeNote );
        }
        return callTool( "write_page", args );
    }

    public Map< String, Object > searchPages( final String query ) {
        return callTool( "search_pages", Map.of( "query", query ) );
    }

    public Map< String, Object > searchPages( final String query, final int maxResults ) {
        return callTool( "search_pages", Map.of( "query", query, "maxResults", maxResults ) );
    }

    public Map< String, Object > listPages() {
        return callTool( "list_pages", Map.of() );
    }

    public Map< String, Object > listPages( final String prefix ) {
        return callTool( "list_pages", Map.of( "prefix", prefix ) );
    }

    public Map< String, Object > listPages( final String prefix, final int limit ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        if ( prefix != null ) {
            args.put( "prefix", prefix );
        }
        args.put( "limit", limit );
        return callTool( "list_pages", args );
    }

    public Map< String, Object > getBacklinks( final String pageName ) {
        return callTool( "get_backlinks", Map.of( "pageName", pageName ) );
    }

    public Map< String, Object > recentChanges() {
        return callTool( "recent_changes", Map.of() );
    }

    public Map< String, Object > recentChanges( final int limit ) {
        return callTool( "recent_changes", Map.of( "limit", limit ) );
    }

    public Map< String, Object > recentChanges( final String since ) {
        return callTool( "recent_changes", Map.of( "since", since ) );
    }

    public Map< String, Object > recentChanges( final String since, final int limit ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        if ( since != null ) {
            args.put( "since", since );
        }
        args.put( "limit", limit );
        return callTool( "recent_changes", args );
    }

    public Map< String, Object > getAttachments( final String pageName ) {
        return callTool( "get_attachments", Map.of( "pageName", pageName ) );
    }

    public Map< String, Object > getAttachmentsExpectingError( final String pageName ) {
        return callToolExpectingError( "get_attachments", Map.of( "pageName", pageName ) );
    }

    public Map< String, Object > queryMetadata( final String field, final String value ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        args.put( "field", field );
        if ( value != null ) {
            args.put( "value", value );
        }
        return callTool( "query_metadata", args );
    }

    public Map< String, Object > queryMetadataByType( final String type ) {
        return callTool( "query_metadata", Map.of( "type", type ) );
    }

    public Map< String, Object > queryMetadataExpectingError( final Map< String, Object > args ) {
        return callToolExpectingError( "query_metadata", args );
    }

    @Override
    public void close() {
        client.close();
    }

    public static String normalizeCrlf( final String text ) {
        return text == null ? null : text.replace( "\r\n", "\n" ).replace( "\r", "\n" );
    }

    private Map< String, Object > parseJsonResult( final CallToolResult result ) {
        final String text = extractText( result );
        return GSON.fromJson( text, new TypeToken< Map< String, Object > >() {}.getType() );
    }

    private String extractText( final CallToolResult result ) {
        final List< McpSchema.Content > content = result.content();
        if ( content == null || content.isEmpty() ) {
            return "{}";
        }
        final McpSchema.Content first = content.get( 0 );
        if ( first instanceof McpSchema.TextContent tc ) {
            return tc.text();
        }
        return "{}";
    }

    public static class McpToolError extends RuntimeException {
        private final String toolName;
        private final String responseText;

        public McpToolError( final String toolName, final String responseText ) {
            super( "MCP tool '" + toolName + "' returned error: " + responseText );
            this.toolName = toolName;
            this.responseText = responseText;
        }

        public String getToolName() { return toolName; }
        public String getResponseText() { return responseText; }
    }
}
