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

import com.wikantik.knowledge.EmbeddingService;
import com.wikantik.knowledge.EmbeddingService.ContentSimilarity;
import com.wikantik.knowledge.ComplExModel.Prediction;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * MCP tool that finds structurally similar nodes using ComplEx KGE embeddings.
 */
public class FindSimilarTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( FindSimilarTool.class );
    public static final String TOOL_NAME = "find_similar";

    private final EmbeddingService embeddingService;

    public FindSimilarTool( final EmbeddingService embeddingService ) {
        this.embeddingService = embeddingService;
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
            "description", "Node name to find similar nodes for"
        ) );
        properties.put( "limit", Map.of(
            "type", "integer",
            "description", "Maximum number of similar nodes to return (default: 10)"
        ) );
        properties.put( "type", Map.of(
            "type", "string",
            "description", "Similarity type: 'structural' (graph topology), 'content' (text similarity), or 'both' (default: structural)",
            "enum", List.of( "structural", "content", "both" )
        ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Find similar nodes using knowledge graph embeddings. " +
                "Supports structural similarity (graph topology), content similarity " +
                "(TF-IDF text analysis), or both." )
            .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "node" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            if ( !embeddingService.isReady() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "Embedding model not trained yet. Ask an admin to trigger retraining." );
            }

            final String node = McpToolUtils.getString( arguments, "node" );
            final int limit = McpToolUtils.getInt( arguments, "limit", 10 );
            final String type = McpToolUtils.getString( arguments, "type", "structural" );

            final Map< String, Object > response = new LinkedHashMap<>();

            if ( "structural".equals( type ) || "both".equals( type ) ) {
                final List< Prediction > structural = embeddingService.getSimilarNodes( node, limit );
                response.put( "structural", structural.stream().map( p -> {
                    final Map< String, Object > m = new LinkedHashMap<>();
                    m.put( "name", p.entityName() );
                    m.put( "similarity", Math.round( p.score() * 1000.0 ) / 1000.0 );
                    return m;
                } ).toList() );
            }

            if ( "content".equals( type ) || "both".equals( type ) ) {
                final List< ContentSimilarity > content = embeddingService.getContentSimilarNodes( node, limit );
                response.put( "content", content.stream().map( cs -> {
                    final Map< String, Object > m = new LinkedHashMap<>();
                    m.put( "name", cs.name() );
                    m.put( "similarity", Math.round( cs.similarity() * 1000.0 ) / 1000.0 );
                    return m;
                } ).toList() );
            }

            if ( response.isEmpty() || response.values().stream().allMatch( v ->
                    v instanceof List< ? > l && l.isEmpty() ) ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "Node not found in embedding model: " + node );
            }

            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, response );
        } catch ( final Exception e ) {
            LOG.warn( "find_similar failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
