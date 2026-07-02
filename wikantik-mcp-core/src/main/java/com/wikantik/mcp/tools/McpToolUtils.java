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
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared utilities for MCP tool implementations, reducing boilerplate
 * around result building and argument extraction.
 */
public final class McpToolUtils {

    /** Shared Gson instance — serializes nulls so JSON keys are always present. */
    public static final Gson SHARED_GSON = new GsonBuilder().serializeNulls().create();

    /**
     * Gson instance for KG-aware tools that serialize {@link com.wikantik.api.knowledge.KgNode}
     * records containing {@link Instant} timestamps. Writes {@code Instant} as ISO-8601 strings.
     */
    public static final Gson KG_GSON = new GsonBuilder()
            .serializeNulls()
            .registerTypeAdapter( Instant.class, new TypeAdapter< Instant >() {
                @Override
                public void write( final JsonWriter out, final Instant value ) throws IOException {
                    if ( value == null ) {
                        out.nullValue();
                    } else {
                        out.value( value.toString() );
                    }
                }

                @Override
                public Instant read( final JsonReader in ) throws IOException {
                    final String s = in.nextString();
                    return s == null ? null : Instant.parse( s );
                }
            } )
            .create();

    private McpToolUtils() {
    }

    /**
     * Builds a successful {@link McpSchema.CallToolResult} by JSON-serializing the given data.
     *
     * <p>Sets BOTH {@code content} (text) and {@code structuredContent}. As of MCP SDK
     * 1.1.1, tools that declare a non-empty {@code outputSchema} must include
     * {@code structuredContent} in their response — the SDK validates this on the
     * client side and raises "Response missing structured content" otherwise.</p>
     */
    public static McpSchema.CallToolResult jsonResult( final Gson gson, final Object data ) {
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( gson.toJson( data ) ) ) )
                .structuredContent( data )
                .build();
    }

    /**
     * Builds an error {@link McpSchema.CallToolResult} with the given message.
     * D11: messages are sanitized to drop class names, stack frames, and JDBC URLs
     * so the MCP response never echoes Java internals back to the agent.
     */
    public static McpSchema.CallToolResult errorResult( final Gson gson, final String message ) {
        final Map< String, String > body = Map.of( "error", sanitizeErrorMessage( message ) );
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( gson.toJson( body ) ) ) )
                .structuredContent( body )
                .isError( true )
                .build();
    }

    /**
     * D11: strip Java class names, package prefixes, stack-trace frames, and JDBC URLs
     * out of an error message before it is returned to the MCP caller. The method is
     * conservative — when in doubt, prefer to keep human-meaningful text — and is
     * package-internal-friendly so individual tools can call it directly when they
     * already have a richer context to assemble.
     */
    public static String sanitizeErrorMessage( final String raw ) {
        if ( raw == null || raw.isEmpty() ) {
            return "internal error (see server log)";
        }
        // Drop "at com.example.Foo.bar(Foo.java:123)" stack frame fragments
        String s = raw.replaceAll( "\\s*at\\s+[\\w$.]+\\([\\w$.:]+\\)", "" );
        // Drop fully-qualified Exception class names
        s = s.replaceAll( "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_$]*)+(?:Exception|Error|Throwable)", "error" );
        // Drop JDBC URLs
        s = s.replaceAll( "jdbc:[^\\s'\"]+", "[jdbc-url]" );
        // Collapse whitespace
        s = s.replaceAll( "\\s+", " " ).trim();
        if ( s.isEmpty() ) {
            return "internal error (see server log)";
        }
        return s;
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
                .structuredContent( body )
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
     * Formats a {@link Date} as an ISO-8601 instant string, or returns {@code null} if the date is {@code null}.
     */
    public static String formatTimestamp( final Date date ) {
        return date != null ? date.toInstant().toString() : null;
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
     * D13: tries each {@code keys} in order and returns the first non-null, non-blank
     * String value. Used so MCP tools can accept multiple historical argument names
     * (e.g. {@code slug} and {@code pageName}, {@code id} and {@code canonical_id})
     * without breaking existing callers.
     */
    public static String getStringAny( final Map< String, Object > args, final String... keys ) {
        for ( final String key : keys ) {
            final Object v = args.get( key );
            if ( v instanceof String s && !s.isBlank() ) {
                return s;
            }
        }
        return null;
    }

    /** First list-valued argument among {@code keys}, or null. */
    public static List< ? > firstListArg( final Map< String, Object > args, final String... keys ) {
        if ( args == null ) { return null; }
        for ( final String k : keys ) {
            if ( args.get( k ) instanceof List< ? > l ) { return l; }
        }
        return null;
    }

    /** Canonical singular page identifier: advertises {@code slug}; accepts legacy/guessable aliases. */
    public static String pageSlug( final Map< String, Object > args ) {
        return getStringAny( args, "slug", "pageName", "name", "page" );
    }

    /** Canonical plural page identifiers: first list-valued arg among the accepted keys, else null. */
    public static List< ? > pageSlugs( final Map< String, Object > args ) {
        return firstListArg( args, "slugs", "pageNames", "names", "pages" );
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
     * Builds a paginated tool result for a list-returning tool. Reads {@code limit} (default
     * {@code defaultLimit}, min 1) and {@code offset} (default 0, min 0) from {@code args}, slices
     * {@code all}, and returns a map of {@code {itemsKey: page, count: <total>, returned, offset,
     * limit, hasMore}} so an agent can page without the full (potentially huge) list being dumped.
     */
    public static Map< String, Object > paginate( final String itemsKey, final List< ? > all,
            final Map< String, Object > args, final int defaultLimit ) {
        final int total = all.size();
        final int offset = Math.max( 0, getInt( args, "offset", 0 ) );
        final int limit = Math.max( 1, getInt( args, "limit", defaultLimit ) );
        final int from = Math.min( offset, total );
        final int to = Math.min( from + limit, total );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( itemsKey, all.subList( from, to ) );
        out.put( "count", total );
        out.put( "returned", to - from );
        out.put( "offset", offset );
        out.put( "limit", limit );
        out.put( "hasMore", to < total );
        return out;
    }

    /**
     * Returns {@code o.toString()} or {@code null}. Useful when reading MCP argument
     * values that may be either a String or another type that should be rendered
     * for an error message.
     */
    public static String stringOrNull( final Object o ) {
        return o == null ? null : o.toString();
    }

    /**
     * Parses an {@link java.util.UUID} from any object whose {@code toString()} is a
     * valid UUID string, or returns {@code null} on null/parse failure.
     */
    public static java.util.UUID parseUuid( final Object o ) {
        if ( o == null ) return null;
        try {
            return java.util.UUID.fromString( o.toString() );
        } catch ( final IllegalArgumentException e ) {
            return null;
        }
    }

    /** Unchecked cast helper for inbound MCP argument maps (always {@code Map<String,Object>}). */
    @SuppressWarnings( "unchecked" )
    public static Map< String, Object > castStringKey( final Map< ?, ? > raw ) {
        return ( Map< String, Object > ) raw;
    }

    /**
     * Runs a heterogeneous bulk MCP operation: validates the {@code operations} list,
     * iterates each op through {@code dispatch}, accumulates per-op results into
     * {@code succeeded} / {@code failed} arrays, audits via {@link McpAudit#logBulkWrite},
     * and returns a {@link McpSchema.CallToolResult} whose {@code isError} flag is set
     * when zero ops succeeded. Used by {@code curate_edges} and {@code curate_nodes}.
     *
     * @param toolName tool name passed to the audit logger
     * @param entityLabel singular noun for the {@code message} field (e.g. "edge", "node")
     * @param rawList raw {@code operations} payload (must be a non-empty {@code List<?>})
     * @param bulkLimit per-call cap (e.g. {@code McpConfig#kgCurationBulkLimit()} in admin-mcp)
     * @param defaultAuthor author audit attribute
     * @param dispatch maps the parsed per-op map (with {@code tag} and {@code action} already extracted)
     *                 to either an {@code id} map (success) or an {@code error} map (failure).
     *                 The function receives the full op map; it does NOT need to copy {@code tag}/{@code action} —
     *                 the wrapper adds those itself.
     */
    public static McpSchema.CallToolResult runBulk(
            final String toolName,
            final String entityLabel,
            final Object rawList,
            final int bulkLimit,
            final String defaultAuthor,
            final java.util.function.Function< Map< String, Object >, Map< String, Object > > dispatch ) {
        return runBulk( toolName, entityLabel, rawList, bulkLimit, defaultAuthor, dispatch, true );
    }

    /**
     * Variant of {@link #runBulk(String, String, Object, int, String, java.util.function.Function)} that
     * lets the caller control whether an all-failed bulk envelope is hard-flagged with
     * {@code isError=true}. {@code curate_edges} sets {@code true} (the calling model should treat
     * an all-fail bulk as a tool error and walk {@code failed[].error}); {@code curate_nodes}
     * historically returned {@code isError=false} per-op-only and existing IT tests depend on it.
     */
    public static McpSchema.CallToolResult runBulk(
            final String toolName,
            final String entityLabel,
            final Object rawList,
            final int bulkLimit,
            final String defaultAuthor,
            final java.util.function.Function< Map< String, Object >, Map< String, Object > > dispatch,
            final boolean isErrorOnAllFailed ) {
        if ( !( rawList instanceof List< ? > list ) || list.isEmpty() ) {
            return errorResult( SHARED_GSON,
                    "operations is required and must be a non-empty array" );
        }
        if ( list.size() > bulkLimit ) {
            return errorResult( SHARED_GSON,
                    "bulk limit exceeded: " + list.size() + " > " + bulkLimit );
        }

        final List< Map< String, Object > > succeeded = new java.util.ArrayList<>();
        final List< Map< String, Object > > failed = new java.util.ArrayList<>();

        for ( final Object opEl : list ) {
            if ( !( opEl instanceof Map< ?, ? > opMap ) ) {
                failed.add( Map.of( "error", "operation must be an object" ) );
                continue;
            }
            final Map< String, Object > op = castStringKey( opMap );
            final String tag = stringOrNull( op.get( "tag" ) );
            final String action = stringOrNull( op.get( "action" ) );

            final Map< String, Object > result = dispatch.apply( op );
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "tag", tag );
            entry.put( "action", action );
            entry.putAll( result );
            if ( entry.containsKey( "error" ) ) failed.add( entry );
            else succeeded.add( entry );
        }

        McpAudit.logBulkWrite( toolName, list.size(), succeeded.size(), failed.size(), defaultAuthor );

        final boolean allFailed = succeeded.isEmpty() && !failed.isEmpty();
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "status", allFailed ? "failed" : "completed" );
        out.put( "succeeded", succeeded );
        out.put( "failed", failed );
        out.put( "message", succeeded.size() + " of " + list.size() + " " + entityLabel + " operations applied" );
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( SHARED_GSON.toJson( out ) ) ) )
                .structuredContent( out )
                .isError( isErrorOnAllFailed && allFailed )
                .build();
    }

    /**
     * Parses the {@code provenance_filter} argument from an MCP tool invocation into a
     * {@link Set} of {@link com.wikantik.api.knowledge.Provenance}. Unknown strings are
     * silently skipped. Returns {@code null} when the argument is absent or not a non-empty
     * list — callers pass {@code null} through to the service layer which treats that as
     * "no provenance filter," distinct from "filter to nothing."
     */
    @SuppressWarnings( "unchecked" )
    public static Set< com.wikantik.api.knowledge.Provenance > parseProvenanceFilter(
            final Map< String, Object > arguments ) {
        final Object raw = arguments.get( "provenance_filter" );
        if ( !( raw instanceof List< ? > list ) || list.isEmpty() ) {
            return null;
        }
        final Set< com.wikantik.api.knowledge.Provenance > result = new LinkedHashSet<>();
        for ( final Object item : list ) {
            if ( item instanceof String s ) {
                try {
                    result.add( com.wikantik.api.knowledge.Provenance.fromValue( s ) );
                } catch ( final IllegalArgumentException ignored ) {
                    // silently skip unknown provenance strings
                }
            }
        }
        return result.isEmpty() ? null : result;
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
