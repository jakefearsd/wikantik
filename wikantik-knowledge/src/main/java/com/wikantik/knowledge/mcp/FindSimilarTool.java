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

import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that finds similar nodes by cosine similarity over the unified
 * mention-centroid embedding space ({@link NodeMentionSimilarity}). Each node's
 * vector is the centroid of the chunks that mention it, drawn from the same
 * Ollama-backed content_chunk_embeddings table as hybrid search.
 */
public class FindSimilarTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( FindSimilarTool.class );
    public static final String TOOL_NAME = "find_similar";

    private final NodeMentionSimilarity similarity;

    public FindSimilarTool( final NodeMentionSimilarity similarity ) {
        this.similarity = similarity;
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

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Find similar nodes by cosine similarity over the unified "
                + "mention-centroid embedding space. Each node's vector is the centroid "
                + "of the chunks that mention it; results are ordered by descending "
                + "similarity to the input node." )
            .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "node" ), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            if ( !similarity.isReady() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "Embedding index not populated yet. Ask an admin to wait for the chunk embedding indexer to run." );
            }

            final String node = McpToolUtils.getString( arguments, "node" );
            final int limit = McpToolUtils.getInt( arguments, "limit", 10 );

            final List< ScoredName > ranked = similarity.similarTo( node, limit );
            if ( ranked.isEmpty() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "No mention-centroid vector for node (either unknown or without chunk mentions): " + node );
            }
            final Map< String, Object > response = new LinkedHashMap<>();
            response.put( "similar", ranked.stream().map( s -> {
                final Map< String, Object > m = new LinkedHashMap<>();
                m.put( "name", s.name() );
                m.put( "similarity", Math.round( s.score() * 1000.0 ) / 1000.0 );
                return m;
            } ).toList() );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, response );
        } catch ( final Exception e ) {
            LOG.warn( "find_similar failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
