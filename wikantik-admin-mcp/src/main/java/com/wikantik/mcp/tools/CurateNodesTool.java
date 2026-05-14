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
        // Historically curate_nodes returns a non-error envelope even when every op
        // fails — per-op errors live in failed[].error and IT tests rely on this.
        // curate_edges has the opposite behaviour (hard isError on all-failed).
        return McpToolUtils.runBulk( TOOL_NAME, "node", args.get( "operations" ),
                bulkLimit, defaultAuthor, this::dispatch, /*isErrorOnAllFailed*/ false );
    }

    private Map< String, Object > dispatch( final Map< String, Object > op ) {
        final String action = McpToolUtils.stringOrNull( op.get( "action" ) );
        return switch ( action == null ? "" : action ) {
            case "upsert" -> doUpsert( op );
            case "delete" -> doDelete( op );
            case "merge"  -> doMerge( op );
            default       -> Map.of( "error",
                    "Unsupported action '" + action + "' — supported: upsert, delete, merge" );
        };
    }

    private Map< String, Object > doUpsert( final Map< String, Object > op ) {
        if ( op.containsKey( "node" ) ) {
            return Map.of( "error",
                "upsert fields belong at the top level of the operation, not nested under 'node'. "
                + "Expected shape: {action: 'upsert', name: '...', node_type: '...', source_page: '...'}" );
        }
        final String name = McpToolUtils.stringOrNull( op.get( "name" ) );
        if ( name == null || name.isBlank() ) return Map.of( "error", "upsert requires name" );
        final String nodeType = McpToolUtils.stringOrNull( op.get( "node_type" ) );
        final String sourcePage = McpToolUtils.stringOrNull( op.get( "source_page" ) );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > props = op.get( "properties" ) instanceof Map
                ? ( Map< String, Object > ) op.get( "properties" ) : Map.of();
        final KgCurationOps.NodeResult r = ops.tryUpsertNode( name, nodeType, sourcePage, props, defaultAuthor );
        return r.error().isPresent()
                ? Map.of( "error", r.error().get() )
                : Map.of( "id", r.nodeId().get().toString() );
    }

    private Map< String, Object > doDelete( final Map< String, Object > op ) {
        final UUID id = McpToolUtils.parseUuid( op.get( "id" ) );
        if ( id == null ) return Map.of( "error", "delete requires id (UUID)" );
        final Optional< String > err = ops.tryDeleteNode( id, defaultAuthor );
        return err.isEmpty() ? Map.of( "id", id.toString() ) : Map.of( "id", id.toString(), "error", err.get() );
    }

    private Map< String, Object > doMerge( final Map< String, Object > op ) {
        final UUID src = McpToolUtils.parseUuid( op.get( "source_id" ) );
        final UUID tgt = McpToolUtils.parseUuid( op.get( "target_id" ) );
        if ( src == null || tgt == null ) return Map.of( "error", "merge requires source_id and target_id (UUIDs)" );
        final Optional< String > err = ops.tryMergeNodes( src, tgt, defaultAuthor );
        return err.isEmpty()
                ? Map.of( "source_id", src.toString(), "target_id", tgt.toString() )
                : Map.of( "source_id", src.toString(), "target_id", tgt.toString(), "error", err.get() );
    }
}
