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
import com.wikantik.api.knowledge.TraversalResult;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * MCP tool that traverses the co-mention graph from a seed node via BFS.
 * Nodes are connected when they appear together in the same content chunk
 * as recorded by the entity extractor.
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
            "description", "Name of the seed node to traverse from.",
            "examples", List.of( "HybridRetrieval" ) ) );
        properties.put( "max_depth", Map.of(
            "type", "integer",
            "description", "BFS depth limit (default 2; 1 = direct co-mentions only).",
            "examples", List.of( 2 ) ) );
        properties.put( "min_shared_chunks", Map.of(
            "type", "integer",
            "description", "Minimum shared-chunk count required to follow an edge (default 1).",
            "examples", List.of( 2 ) ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "nodes", List.of(
                        Map.of( "name", "HybridRetrieval", "depth", 0 ),
                        Map.of( "name", "BM25", "depth", 1 ),
                        Map.of( "name", "VectorEmbeddings", "depth", 1 )
                ),
                "edges", List.of(
                        Map.of( "source", "HybridRetrieval", "target", "BM25", "sharedChunks", 6 ),
                        Map.of( "source", "HybridRetrieval", "target", "VectorEmbeddings", "sharedChunks", 4 )
                )
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Walk the co-mention graph from a seed node. Nodes are connected " +
                        "when they appear in the same chunk per the entity extractor. Returns " +
                        "{nodes, edges} with each edge carrying its 'sharedChunks' count." )
                .inputSchema( new McpSchema.JsonSchema(
                    "object", properties, List.of( "start_node" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String startNode = McpToolUtils.getString( arguments, "start_node" );
            final int maxDepth = McpToolUtils.getInt( arguments, "max_depth", 2 );
            final int minSharedChunks = McpToolUtils.getInt( arguments, "min_shared_chunks", 1 );

            final TraversalResult result = service.traverseByCoMention(
                startNode, maxDepth, minSharedChunks );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Traverse failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
