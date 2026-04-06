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
import com.wikantik.knowledge.EmbeddingService.EdgePrediction;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * MCP tool that predicts missing edges using ComplEx KGE link prediction.
 */
public class PredictEdgesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( PredictEdgesTool.class );
    public static final String TOOL_NAME = "predict_edges";

    private final EmbeddingService embeddingService;

    public PredictEdgesTool( final EmbeddingService embeddingService ) {
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
            "description", "Optional: predict edges for a specific node. If omitted, returns global top-K predictions."
        ) );
        properties.put( "limit", Map.of(
            "type", "integer",
            "description", "Maximum number of predictions to return (default: 10)"
        ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Predict missing edges in the knowledge graph using structural embeddings. " +
                "Returns the highest-scoring absent triples — relationships that the graph topology suggests " +
                "should exist but are not yet recorded." )
            .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
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

            final String node = McpToolUtils.getString( arguments, "node", null );
            final int limit = McpToolUtils.getInt( arguments, "limit", 10 );

            final List< EdgePrediction > predictions;
            if ( node != null && !node.isEmpty() ) {
                predictions = embeddingService.predictEdgesForNode( node, limit );
            } else {
                predictions = embeddingService.predictMissingEdges( limit );
            }

            final List< Map< String, Object > > results = predictions.stream().map( ep -> {
                final Map< String, Object > m = new LinkedHashMap<>();
                m.put( "source", ep.sourceName() );
                m.put( "relationship_type", ep.relationshipType() );
                m.put( "target", ep.targetName() );
                m.put( "score", Math.round( ep.score() * 1000.0 ) / 1000.0 );
                return m;
            } ).toList();

            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON,
                Map.of( "predictions", results, "count", results.size() ) );
        } catch ( final Exception e ) {
            LOG.error( "predict_edges failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
