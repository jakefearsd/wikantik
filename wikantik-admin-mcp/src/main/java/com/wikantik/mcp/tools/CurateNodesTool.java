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

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class CurateNodesTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( CurateNodesTool.class );
    public static final String TOOL_NAME = "curate_nodes";

    private final KgCurationOps ops;
    private final int bulkLimit;
    private volatile String defaultAuthor = "admin";

    public CurateNodesTool( final KgCurationOps ops, final int bulkLimit ) {
        this.ops = ops;
        this.bulkLimit = bulkLimit;
    }

    @Override public String name() { return TOOL_NAME; }
    @Override public void setDefaultAuthor( final String author ) { this.defaultAuthor = author; }

    @Override public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "operations", Map.of(
                "type", "array",
                "description", "1.." + bulkLimit + " heterogeneous node ops. Each item has `action` " +
                        "(upsert|delete|merge) plus action-specific fields and an optional `tag`.",
                "items", Map.of( "type", "object" )
        ) );

        final Map< String, Object > exampleOut = Map.of(
                "status", "completed",
                "succeeded", List.of( Map.of( "tag", "node-1", "action", "upsert",
                        "id", "8f3c2a1b-..." ) ),
                "failed", List.of(),
                "message", "1 of 1 node operations applied" );
        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleOut ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Bulk heterogeneous node curation. Actions: " +
                        "`upsert` (HUMAN_AUTHORED), `delete`, `merge` (source → target, frontmatter rewritten)." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "operations" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, true, null, null ) )
                .build();
    }

    @Override public McpSchema.CallToolResult execute( final Map< String, Object > args ) {
        final Object raw = args.get( "operations" );
        if ( !( raw instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "operations is required and must be a non-empty array" );
        }
        if ( rawList.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + rawList.size() + " > " + bulkLimit );
        }

        final List< Map< String, Object > > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();

        for ( final Object opEl : rawList ) {
            if ( !( opEl instanceof Map< ?, ? > opMap ) ) {
                failed.add( Map.of( "error", "operation must be an object" ) );
                continue;
            }
            final Map< String, Object > op = castStringKey( opMap );
            final String tag = stringOrNull( op.get( "tag" ) );
            final String action = stringOrNull( op.get( "action" ) );

            final Map< String, Object > result;
            switch ( action == null ? "" : action ) {
                case "upsert" -> result = doUpsert( op );
                case "delete" -> result = doDelete( op );
                case "merge"  -> result = doMerge( op );
                default       -> result = Map.of( "error",
                        "Unsupported action '" + action + "' — supported: upsert, delete, merge" );
            }

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "tag", tag );
            entry.put( "action", action );
            entry.putAll( result );
            if ( entry.containsKey( "error" ) ) failed.add( entry );
            else succeeded.add( entry );
        }

        McpAudit.logBulkWrite( TOOL_NAME, rawList.size(), succeeded.size(), failed.size(), defaultAuthor );

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "status", "completed" );
        out.put( "succeeded", succeeded );
        out.put( "failed", failed );
        out.put( "message", succeeded.size() + " of " + rawList.size() + " node operations applied" );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }

    private Map< String, Object > doUpsert( final Map< String, Object > op ) {
        if ( op.containsKey( "node" ) ) {
            return Map.of( "error",
                "upsert fields belong at the top level of the operation, not nested under 'node'. "
                + "Expected shape: {action: 'upsert', name: '...', node_type: '...', source_page: '...'}" );
        }
        final String name = stringOrNull( op.get( "name" ) );
        if ( name == null || name.isBlank() ) return Map.of( "error", "upsert requires name" );
        final String nodeType = stringOrNull( op.get( "node_type" ) );
        final String sourcePage = stringOrNull( op.get( "source_page" ) );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > props = op.get( "properties" ) instanceof Map
                ? ( Map< String, Object > ) op.get( "properties" ) : Map.of();
        final KgCurationOps.NodeResult r = ops.tryUpsertNode( name, nodeType, sourcePage, props, defaultAuthor );
        return r.error().isPresent()
                ? Map.of( "error", r.error().get() )
                : Map.of( "id", r.nodeId().get().toString() );
    }

    private Map< String, Object > doDelete( final Map< String, Object > op ) {
        final UUID id = parseUuid( op.get( "id" ) );
        if ( id == null ) return Map.of( "error", "delete requires id (UUID)" );
        final Optional< String > err = ops.tryDeleteNode( id, defaultAuthor );
        return err.isEmpty() ? Map.of( "id", id.toString() ) : Map.of( "id", id.toString(), "error", err.get() );
    }

    private Map< String, Object > doMerge( final Map< String, Object > op ) {
        final UUID src = parseUuid( op.get( "source_id" ) );
        final UUID tgt = parseUuid( op.get( "target_id" ) );
        if ( src == null || tgt == null ) return Map.of( "error", "merge requires source_id and target_id (UUIDs)" );
        final Optional< String > err = ops.tryMergeNodes( src, tgt, defaultAuthor );
        return err.isEmpty()
                ? Map.of( "source_id", src.toString(), "target_id", tgt.toString() )
                : Map.of( "source_id", src.toString(), "target_id", tgt.toString(), "error", err.get() );
    }

    private static String stringOrNull( final Object o ) { return o == null ? null : o.toString(); }
    private static UUID parseUuid( final Object o ) {
        if ( o == null ) return null;
        try { return UUID.fromString( o.toString() ); }
        catch ( final IllegalArgumentException e ) { return null; }
    }
    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > castStringKey( final Map< ?, ? > raw ) { return ( Map< String, Object > ) raw; }
}
