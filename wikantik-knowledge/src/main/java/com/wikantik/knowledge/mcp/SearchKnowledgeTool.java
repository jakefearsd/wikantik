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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * MCP tool for full-text search across node names and properties.
 * Bridges the gap between "I don't know the exact name" and the
 * structured query tools.
 */
public class SearchKnowledgeTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( SearchKnowledgeTool.class );
    public static final String TOOL_NAME = "search_knowledge";

    private final KnowledgeGraphService service;
    private final MentionIndex mentionIndex;

    public SearchKnowledgeTool( final KnowledgeGraphService service ) {
        this( service, null );
    }

    public SearchKnowledgeTool( final KnowledgeGraphService service,
                                 final MentionIndex mentionIndex ) {
        this.service = service;
        this.mentionIndex = mentionIndex;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "query", Map.of(
                "type", "string",
                "description", "Full-text search query across node names and properties",
                "examples", List.of( "hybrid retrieval", "graph traversal" )
        ) );
        properties.put( "provenance_filter", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Provenance values to include (e.g. human-authored, ai-reviewed, ai-inferred)",
                "examples", List.of( List.of( "human-authored", "ai-reviewed" ) )
        ) );
        properties.put( "limit", Map.of(
                "type", "integer",
                "description", "Maximum number of results (default 20)",
                "examples", List.of( 5 )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "results", List.of(
                        Map.of(
                                "id", "8f3c2a1b-7e4d-4f5a-9b6c-1d2e3f4a5b6c",
                                "name", "HybridRetrieval",
                                "node_type", "design_doc",
                                "properties", Map.of(
                                        "canonical_id", "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
                                        "summary", "BM25 + dense + graph-aware rerank with fail-closed BM25 fallback."
                                ),
                                "provenance", "human-authored"
                        ),
                        Map.of(
                                "id", "9a4d3b2c-8f5e-4c6b-ad7d-2e3f4a5b6c7d",
                                "name", "RetrievalExperimentHarness",
                                "node_type", "design_doc",
                                "properties", Map.of(
                                        "canonical_id", "01H8G3Z2E7FD8R1Q4V9X2T0NMP",
                                        "summary", "Offline harness for replaying canned queries against snapshots."
                                ),
                                "provenance", "human-authored"
                        )
                ),
                "scope", "knowledge_graph_nodes_only"
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Full-text search across knowledge-graph node names and properties. " +
                        "Bridges 'I don't know the exact name' and the structured query tools. " +
                        "Results are filtered to nodes the entity extractor has actually found in " +
                        "wiki content; nodes present only from legacy frontmatter/link projection " +
                        "are hidden. " +
                        "D29: NOTE — this searches the knowledge-graph node table only. For " +
                        "page-body content searches use retrieve_context (hybrid BM25+dense) instead." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "query" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String query = McpToolUtils.getString( arguments, "query" );
            final Set< Provenance > provenanceFilter = KnowledgeMcpUtils.parseProvenanceFilter( arguments );
            final int limit = McpToolUtils.getInt( arguments, "limit", 20 );

            final List< KgNode > nodes = service.searchKnowledge( query, provenanceFilter, limit );
            final List< KgNode > filtered = filterToMentioned( nodes );
            // D29: surface the scope-difference up-front so callers know to fall back to
            // retrieve_context for body-content matches when the KG returned nothing.
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "results", filtered );
            payload.put( "scope", "knowledge_graph_nodes_only" );
            if ( filtered.isEmpty() ) {
                payload.put( "hint", "search_knowledge searches knowledge-graph nodes only; "
                        + "if you expected page-body matches, call retrieve_context with the same query." );
            }
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, payload );
        } catch ( final Exception e ) {
            LOG.error( "Search knowledge failed for query: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    private List< KgNode > filterToMentioned( final List< KgNode > nodes ) {
        if ( mentionIndex == null ) return nodes;
        final List< KgNode > out = new ArrayList<>( nodes.size() );
        for ( final KgNode n : nodes ) {
            if ( mentionIndex.isMentioned( n.id() ) ) {
                out.add( n );
            }
        }
        return out;
    }
}
