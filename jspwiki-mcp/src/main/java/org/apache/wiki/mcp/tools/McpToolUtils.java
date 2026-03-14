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
package org.apache.wiki.mcp.tools;

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for MCP tool implementations, reducing boilerplate
 * around result building and argument extraction.
 */
public final class McpToolUtils {

    private McpToolUtils() {
    }

    /**
     * Builds a successful {@link McpSchema.CallToolResult} by JSON-serializing the given data.
     */
    public static McpSchema.CallToolResult jsonResult( final Gson gson, final Object data ) {
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( gson.toJson( data ) ) ) )
                .build();
    }

    /**
     * Builds an error {@link McpSchema.CallToolResult} with the given message.
     */
    public static McpSchema.CallToolResult errorResult( final Gson gson, final String message ) {
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( gson.toJson( Map.of( "error", message ) ) ) ) )
                .isError( true )
                .build();
    }

    /**
     * Builds an error {@link McpSchema.CallToolResult} with a message and actionable suggestion.
     */
    public static McpSchema.CallToolResult errorResult( final Gson gson, final String message, final String suggestion ) {
        final Map< String, String > body = new LinkedHashMap<>();
        body.put( "error", message );
        body.put( "suggestion", suggestion );
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( gson.toJson( body ) ) ) )
                .isError( true )
                .build();
    }

    /**
     * Normalizes a page version number for API responses. Internal sentinel values
     * like {@code PageProvider.LATEST_VERSION} ({@code -1}) are replaced with {@code 1}
     * as a minimum, since clients should never see negative version numbers.
     */
    public static int normalizeVersion( final int version ) {
        return Math.max( version, 1 );
    }

    /**
     * Extracts a String argument, returning {@code null} if absent.
     */
    public static String getString( final Map< String, Object > args, final String key ) {
        return ( String ) args.get( key );
    }

    /**
     * Extracts a String argument with a default value.
     */
    public static String getString( final Map< String, Object > args, final String key, final String defaultVal ) {
        return ( String ) args.getOrDefault( key, defaultVal );
    }

    /**
     * Extracts an integer argument, returning {@code defaultVal} if absent.
     */
    public static int getInt( final Map< String, Object > args, final String key, final int defaultVal ) {
        final Object val = args.get( key );
        if ( val instanceof Number ) {
            return ( ( Number ) val ).intValue();
        }
        return defaultVal;
    }

    /**
     * Extracts a boolean argument, returning {@code false} if absent.
     */
    public static boolean getBoolean( final Map< String, Object > args, final String key ) {
        return Boolean.TRUE.equals( args.get( key ) );
    }

}
