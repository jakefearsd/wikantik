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

import com.wikantik.mcp.tools.McpToolUtils;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkValidationException;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * One tool of the work endpoint. {@link #call} is the only entry: it refuses a call with no actor, and turns the work
 * module's refusals into the one error text each deserves. Hidden and unknown are the same text, {@value #NOT_FOUND},
 * for every tool: it is what {@code WorkNotFoundException} means, and the service throws it for both.
 */
public abstract class WorkMcpTool {

    private static final Logger LOG = LogManager.getLogger( WorkMcpTool.class );

    /** The answer to a call whose key is not a work key (or is no longer one). */
    public static final String NEEDS_KEY = "This endpoint needs a work API key.";

    /** The answer for an item or project that is unknown or hidden from the caller: the two are not told apart. */
    public static final String NOT_FOUND = "Not found.";

    static final String UNAVAILABLE = "Work is unavailable right now.";

    /** Annotations shared by every tool that only reads. */
    protected static final McpSchema.ToolAnnotations READ_ONLY =
        new McpSchema.ToolAnnotations( null, true, false, true, null, null );

    /** The tool name in the MCP protocol. */
    public abstract String name();

    /** The tool's schema and annotations. */
    public abstract McpSchema.Tool definition();

    /** Runs the tool for an actor that is known not to be null. */
    protected abstract McpSchema.CallToolResult run( Actor actor, Map< String, Object > args ) throws SQLException;

    /**
     * For a stale-version refusal, the version the item has now, so the agent can re-read and retry; null when the tool
     * has no item to report.
     */
    protected Integer currentVersion( final Actor actor, final Map< String, Object > args ) {
        return null;
    }

    /** Runs the tool; a null actor is answered with {@link #NEEDS_KEY} and nothing else happens. */
    public final McpSchema.CallToolResult call( final Actor actor, final Map< String, Object > args ) {
        if ( actor == null ) {
            return error( NEEDS_KEY );
        }
        final Map< String, Object > arguments = args == null ? Map.of() : args;
        try {
            return run( actor, arguments );
        } catch ( final WorkNotFoundException e ) {
            LOG.debug( "Work tool {}: not found or hidden", name() );   // expected, caller-caused: no item text
            return error( NOT_FOUND );
        } catch ( final WorkDeniedException e ) {
            LOG.debug( "Work tool {}: denied", name() );
            return error( e.reason() );
        } catch ( final WorkValidationException e ) {
            LOG.debug( "Work tool {}: invalid argument {}", name(), e.field() );
            return error( e.field() + ": " + e.getMessage() );
        } catch ( final WorkConflictException e ) {
            LOG.debug( "Work tool {}: conflict {}", name(), e.reason() );
            final String base = e.reason() + ": " + e.getMessage();
            final Integer current = WorkConflictException.STALE.equals( e.reason() ) ? currentVersion( actor, arguments ) : null;
            return error( current == null ? base : base + " (currentVersion: " + current + ")" );
        } catch ( final SQLException e ) {
            LOG.warn( "Work tool {} failed: {}", name(), e.getMessage(), e );
            return error( UNAVAILABLE );
        } catch ( final RuntimeException e ) {
            // A bug or an unexpected state: log it in full, tell the client nothing of it.
            LOG.warn( "Work tool {} threw {}", name(), e.getClass().getSimpleName(), e );
            return error( UNAVAILABLE );
        }
    }

    /** An error result whose text is exactly {@code text}. */
    static McpSchema.CallToolResult error( final String text ) {
        return McpSchema.CallToolResult.builder().content( List.of( new McpSchema.TextContent( text ) ) )
            .isError( true ).build();
    }

    /** A success result carrying {@code data} as JSON text and as structured content. */
    static McpSchema.CallToolResult ok( final Object data ) {
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, data );
    }

    /** An optional integer argument (a JSON number may arrive as an integer or a double); null when absent. */
    static Integer intArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        if ( v == null ) {
            return null;
        }
        if ( v instanceof Number n ) {
            return n.intValue();
        }
        throw new WorkValidationException( key, "must be a number." );
    }

    /** An optional string argument; null when absent. A value of any other type is a validation error. */
    static String stringArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        if ( v == null ) {
            return null;
        }
        if ( v instanceof String s ) {
            return s;
        }
        throw new WorkValidationException( key, "must be a string." );
    }
}
