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
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.work.mcp.WorkMcpArgs.itemKey;

/**
 * One tool of the work endpoint. {@link #call} is the only entry: it refuses a call with no actor, and turns the work
 * module's refusals into the one error text each deserves. Hidden and unknown are the same text, {@value #NOT_FOUND},
 * for every tool: it is what {@code WorkNotFoundException} means, and the service throws it for both.
 */
public abstract class WorkMcpTool {

    private static final Logger LOG = LogManager.getLogger( WorkMcpTool.class );

    /** The answer to a call whose key is not a work key (or is no longer one). */
    public static final String NEEDS_KEY = "This endpoint needs a work API key.";

    /**
     * The answer to every call made with a valid agent token that stands for no actor: the agent has no sponsor (or
     * its sponsor's account is gone) or it was retired. Only the token's holder ever sees it.
     */
    public static final String AGENT_UNUSABLE = "This agent has no sponsor or was retired, so it can do nothing. "
        + "A wiki admin or a project lead must take it over.";

    /** The answer for an item or project that is unknown or hidden from the caller: the two are not told apart. */
    public static final String NOT_FOUND = "Not found.";

    static final String UNAVAILABLE = "Work is unavailable right now.";

    private final ToolSpec spec;
    private final WorkService versionSource;

    /**
     * For a tool that supplies its own {@link #name()} and {@link #definition()} (a test double). A subclass using
     * this constructor must override both, and {@link #allowed()} too if it reads its arguments; the defaults
     * throw {@link IllegalStateException}.
     */
    protected WorkMcpTool() {
        this( null, null );
    }

    /** A tool declared by {@code spec}; its name, schema, annotations and allowed arguments all come from it. */
    protected WorkMcpTool( final ToolSpec spec ) {
        this( spec, null );
    }

    /**
     * A tool declared by {@code spec} whose stale-version refusal reports the item's current version, read through
     * {@code versionSource} (the tool takes {@code key}).
     */
    protected WorkMcpTool( final ToolSpec spec, final WorkService versionSource ) {
        this.spec = spec;
        this.versionSource = versionSource;
    }

    /** The tool name in the MCP protocol. */
    public String name() {
        return requireSpec( "name()" ).name();
    }

    /** The tool's schema and annotations. */
    public McpSchema.Tool definition() {
        return requireSpec( "definition()" ).definition();
    }

    /** Whether the tool changes something: a refused call of such a tool leaves a line in the security log. */
    protected boolean writes() {
        return spec != null && spec.writes();
    }

    /** The arguments the tool declares, which are the only ones it accepts. */
    protected Set< String > allowed() {
        return requireSpec( "allowed()" ).allowed();
    }

    private ToolSpec requireSpec( final String member ) {
        if ( spec == null ) {
            throw new IllegalStateException( getClass().getName() + " was built without a ToolSpec and does not override "
                + member );
        }
        return spec;
    }

    /** Runs the tool for an actor that is known not to be null. */
    protected abstract McpSchema.CallToolResult run( Actor actor, Map< String, Object > args ) throws SQLException;

    /**
     * For a stale-version refusal, the version the item has now, so the agent can re-read and retry; null when the tool
     * has no item to report.
     */
    protected Integer currentVersion( final Actor actor, final Map< String, Object > args ) {
        return versionSource == null ? null : versionOf( versionSource, actor, args );
    }

    /** Runs the tool; a null actor is answered with {@link #NEEDS_KEY} and nothing else happens. */
    public final McpSchema.CallToolResult call( final Actor actor, final Map< String, Object > args ) {
        final Map< String, Object > arguments = args == null ? Map.of() : args;
        if ( actor == null ) {
            refused( null, arguments, "needs_key" );
            return error( NEEDS_KEY );
        }
        try {
            return run( actor, arguments );
        } catch ( final WorkNotFoundException e ) {
            LOG.debug( "Work tool {}: not found or hidden", name() );   // expected, caller-caused: no item text
            refused( actor, arguments, "not_found" );
            return error( NOT_FOUND );
        } catch ( final WorkDeniedException e ) {
            LOG.debug( "Work tool {}: denied", name() );
            refused( actor, arguments, "denied" );
            return error( "denied: " + e.reason() );
        } catch ( final WorkValidationException e ) {
            LOG.debug( "Work tool {}: invalid argument {}", name(), e.field() );
            refused( actor, arguments, "validation" );
            return error( e.field() + ": " + e.getMessage() );
        } catch ( final WorkConflictException e ) {
            LOG.debug( "Work tool {}: conflict {}", name(), e.reason() );
            refused( actor, arguments, e.reason() );
            final String base = e.reason() + ": " + e.getMessage();
            final Integer current = WorkConflictException.STALE.equals( e.reason() ) ? currentVersion( actor, arguments ) : null;
            return error( current == null ? base : base + " (currentVersion: " + current + ")" );
        } catch ( final SQLException e ) {
            // Never the message: a constraint violation's message carries row values.
            LOG.warn( "Work tool {} failed: {} (SQLState {})", name(), e.getClass().getSimpleName(), e.getSQLState() );
            return error( UNAVAILABLE );
        } catch ( final RuntimeException e ) {
            // A bug or an unexpected state: log it in full, tell the client nothing of it.
            LOG.warn( "Work tool {} threw {}", name(), e.getClass().getSimpleName(), e );
            return error( UNAVAILABLE );
        }
    }

    /**
     * Answers a call whose agent token verified but resolved to no actor with {@link #AGENT_UNUSABLE}; nothing else
     * happens, and a write tool leaves an {@code agent_unusable} refusal line.
     */
    public final McpSchema.CallToolResult refuseUnusableAgent( final Map< String, Object > args ) {
        refused( null, args == null ? Map.of() : args, "agent_unusable" );
        return error( AGENT_UNUSABLE );
    }

    private void refused( final Actor actor, final Map< String, Object > arguments, final String kind ) {
        if ( writes() ) {
            WorkMcpLog.refused( name(), arguments.get( "key" ), actor, kind );
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

    /** The version the item has now, for a stale refusal; null when it cannot be read (the refusal then omits it). */
    private static Integer versionOf( final WorkService svc, final Actor actor, final Map< String, Object > args ) {
        try {
            return svc.item( actor, itemKey( args ) ).item().version();
        } catch ( final SQLException | RuntimeException e ) {
            LOG.warn( "Could not read the current version for a stale refusal: {}", e.getClass().getSimpleName(), e );
            return null;
        }
    }

}
