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

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * MCP tool that returns full details for a single node: all properties,
 * all edges (inbound and outbound), source page, and provenance.
 */
public class GetNodeTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetNodeTool.class );
    public static final String TOOL_NAME = "get_node";

    private final KnowledgeGraphService service;

    public GetNodeTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "node", Map.of(
                "type", "string",
                "description", "Node name or UUID",
                "examples", List.of( "HybridRetrieval", "8f3c2a1b-7e4d-4f5a-9b6c-1d2e3f4a5b6c" )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "node", Map.of(
                        "id", "8f3c2a1b-7e4d-4f5a-9b6c-1d2e3f4a5b6c",
                        "name", "HybridRetrieval",
                        "node_type", "design_doc",
                        "properties", Map.of(
                                "canonical_id", "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
                                "cluster", "retrieval"
                        ),
                        "provenance", "human-authored"
                ),
                "outbound_edges", List.of( Map.of(
                        "target", "BM25",
                        "relationship", "falls_back_to",
                        "provenance", "human-authored"
                ) ),
                "inbound_edges", List.of( Map.of(
                        "source", "AgentMemory",
                        "relationship", "depends_on",
                        "provenance", "ai-reviewed"
                ) )
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Get full details for a single node: all properties, all edges " +
                        "(inbound and outbound), source page, and provenance." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "node" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String nodeRef = McpToolUtils.getString( arguments, "node" );

            // Try by name first, then by UUID
            KgNode node = service.getNodeByName( nodeRef );
            if ( node == null ) {
                try {
                    final UUID id = UUID.fromString( nodeRef );
                    node = service.getNode( id );
                } catch ( final IllegalArgumentException e ) {
                    LOG.info( "get_node ref '{}' did not resolve by name and is not a UUID — "
                        + "returning not-found: {}", nodeRef, e.getMessage() );
                }
            }

            if ( node == null ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                        "Node not found: " + nodeRef );
            }

            final List< KgEdge > outbound = service.getEdgesForNode( node.id(), "outbound" );
            final List< KgEdge > inbound = service.getEdgesForNode( node.id(), "inbound" );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "node", node );
            result.put( "outbound_edges", outbound );
            result.put( "inbound_edges", inbound );

            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Get node failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
