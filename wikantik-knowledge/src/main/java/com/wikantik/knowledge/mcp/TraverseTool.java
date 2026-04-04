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

import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.TraversalResult;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * MCP tool that traverses the knowledge graph from a starting node,
 * following relationships outward, inward, or both.
 */
public class TraverseTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( TraverseTool.class );
    public static final String TOOL_NAME = "traverse";

    private final KnowledgeGraphService service;

    public TraverseTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "start_node", Map.of(
                "type", "string",
                "description", "Name of the node to start traversal from"
        ) );
        properties.put( "direction", Map.of(
                "type", "string",
                "description", "Traversal direction: outbound, inbound, or both (default outbound)",
                "enum", List.of( "outbound", "inbound", "both" )
        ) );
        properties.put( "relationship_types", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Only follow edges of these relationship types (omit for all)"
        ) );
        properties.put( "max_depth", Map.of(
                "type", "integer",
                "description", "Maximum traversal depth (default 3)"
        ) );
        properties.put( "provenance_filter", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Provenance values to include (e.g. human-authored, ai-reviewed, ai-inferred)"
        ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Traverse the knowledge graph from a starting node, following " +
                        "relationships outward, inward, or both." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "start_node" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String startNode = McpToolUtils.getString( arguments, "start_node" );
            final String direction = McpToolUtils.getString( arguments, "direction", "outbound" );
            final int maxDepth = McpToolUtils.getInt( arguments, "max_depth", 3 );
            final Set< Provenance > provenanceFilter = KnowledgeMcpUtils.parseProvenanceFilter( arguments );

            Set< String > relationshipTypes = null;
            final Object rawRelTypes = arguments.get( "relationship_types" );
            if ( rawRelTypes instanceof List ) {
                relationshipTypes = new HashSet<>( ( List< String > ) rawRelTypes );
            }

            final TraversalResult result = service.traverse( startNode, direction,
                    relationshipTypes, maxDepth, provenanceFilter );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Traversal failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
