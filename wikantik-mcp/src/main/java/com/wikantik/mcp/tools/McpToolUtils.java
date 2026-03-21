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

import com.google.gson.Gson;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utilities for MCP tool implementations, reducing boilerplate
 * around result building and argument extraction.
 */
public final class McpToolUtils {

    /** Shared Gson instance for tools that don't need special serialization config. */
    public static final Gson SHARED_GSON = new Gson();

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

    /**
     * Checks whether a content string looks like a serialized read_page JSON response
     * (e.g. {@code {"exists":true,"pageName":"...","content":"..."}}) that was accidentally
     * passed as page content instead of plain Markdown.  Returns an error result if detected,
     * or {@code null} if the content looks fine.
     */
    public static McpSchema.CallToolResult checkForSerializedResponse( final String content ) {
        if ( content != null && content.length() > 20 ) {
            final String trimmed = content.stripLeading();
            if ( trimmed.startsWith( "{" )
                    && ( trimmed.contains( "\"exists\"" ) || trimmed.contains( "\"pageName\"" ) )
                    && trimmed.contains( "\"content\"" ) ) {
                return errorResult( SHARED_GSON,
                        "Content appears to be a serialized JSON response (contains exists/pageName/content fields). " +
                        "Pass only the Markdown body text in the content parameter, not the full read_page response object.",
                        "Extract the 'content' field from the read_page response and pass that string as content." );
            }
        }
        return null;
    }

    /**
     * Computes a SHA-256 hex digest of the given content string.
     */
    public static String computeContentHash( final String content ) {
        try {
            final MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            final byte[] hash = digest.digest( content.getBytes( StandardCharsets.UTF_8 ) );
            return HexFormat.of().formatHex( hash );
        } catch ( final NoSuchAlgorithmException e ) {
            throw new RuntimeException( "SHA-256 not available", e );
        }
    }

    /**
     * Checks optimistic locking via expectedVersion and/or expectedContentHash.
     * Returns an error result if there is a conflict, or {@code null} if OK.
     */
    public static McpSchema.CallToolResult checkVersionOrHash( final PageManager pageManager,
                                                                 final String pageName,
                                                                 final int expectedVersion,
                                                                 final String expectedContentHash,
                                                                 final Gson gson ) {
        if ( expectedVersion <= 0 && expectedContentHash == null ) {
            return null;
        }

        final Page currentPage = pageManager.getPage( pageName );
        if ( currentPage == null ) {
            return null; // new page — no conflict possible
        }

        if ( expectedVersion > 0 ) {
            final int currentVersion = normalizeVersion( currentPage.getVersion() );
            if ( currentVersion != expectedVersion ) {
                return errorResult( gson,
                        "Version conflict: page '" + pageName + "' is at version " + currentVersion +
                                " but expectedVersion was " + expectedVersion,
                        "Read the page again with read_page to get the current version and content, then retry." );
            }
        }

        if ( expectedContentHash != null ) {
            final String currentText = pageManager.getPureText( pageName, -1 );
            final String currentHash = computeContentHash( currentText != null ? currentText : "" );
            if ( !currentHash.equals( expectedContentHash ) ) {
                return errorResult( gson,
                        "Content hash conflict: page '" + pageName + "' content has changed since you last read it.",
                        "Read the page again with read_page to get the current contentHash and content, then retry." );
            }
        }

        return null;
    }

}
