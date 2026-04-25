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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.structure.RelationDirection;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TraversalSpec;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP tool — bounded BFS traversal of the typed-relation graph rooted at a
 * canonical_id. Returns edges with resolved target slug+title plus the depth
 * at which each was reached, so agents can render the result without a second
 * lookup.
 */
public class TraverseRelationsTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( TraverseRelationsTool.class );
    public static final String TOOL_NAME = "traverse_relations";

    private final StructuralIndexService service;

    public TraverseRelationsTool( final StructuralIndexService service ) {
        this.service = service;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        // D13: canonical name is `id`. Accept the legacy `from` form too.
        props.put( "id", Map.of(
                "type", "string",
                "description", "Canonical id (ULID) of the page to traverse from."
        ) );
        props.put( "from", Map.of(
                "type", "string",
                "description", "Deprecated alias for `id`."
        ) );
        props.put( "direction", Map.of(
                "type", "string",
                "description", "out (default) | in | both."
        ) );
        props.put( "type_filter", Map.of(
                "type", "string",
                "description", "Optional relation type: part-of, example-of, prerequisite-for, " +
                        "supersedes, contradicts, implements, derived-from."
        ) );
        props.put( "depth_cap", Map.of(
                "type", "integer",
                "description", "Maximum BFS depth, clamped to 1..5 (default 1)."
        ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Walk the typed-relation graph from a canonical_id. Returns edges " +
                        "with resolved target slug+title and the depth at which each was discovered. " +
                        "Use this to expand a known page into its declared neighborhood (parts, examples, " +
                        "prerequisites, supersedes chains) without paying for full-text search." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            // D13: accept both `id` (canonical) and `from` (deprecated alias).
            final String from = McpToolUtils.getStringAny( arguments, "id", "from" );
            if ( arguments.containsKey( "from" ) && !arguments.containsKey( "id" ) ) {
                LOG.warn( "traverse_relations called with deprecated argument 'from'; prefer 'id'" );
            }
            if ( from == null || from.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "id (or from) argument is required" );
            }
            final RelationDirection direction = RelationDirection.fromString(
                    (String) arguments.get( "direction" ) );
            final Optional< RelationType > typeFilter = Optional.ofNullable(
                    (String) arguments.get( "type_filter" ) ).flatMap( RelationType::fromWire );
            final int depthCap = arguments.get( "depth_cap" ) instanceof Number n ? n.intValue() : 1;

            final List< RelationEdge > edges = service.traverse(
                    from, new TraversalSpec( direction, typeFilter, depthCap ) );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON,
                    Map.of( "edges", edges, "count", edges.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "traverse_relations failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
