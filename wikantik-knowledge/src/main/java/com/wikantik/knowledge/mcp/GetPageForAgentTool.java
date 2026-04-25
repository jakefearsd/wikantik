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

import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.ForAgentProjectionService;
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
 * MCP tool — return the {@code /for-agent} projection for a canonical_id.
 * Mirrors {@code GET /api/pages/for-agent/{canonical_id}}: same shape, same
 * graceful-degradation contract, served over Streamable HTTP MCP instead of
 * REST.
 */
public class GetPageForAgentTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetPageForAgentTool.class );
    public static final String TOOL_NAME = "get_page_for_agent";

    private final ForAgentProjectionService service;

    public GetPageForAgentTool( final ForAgentProjectionService service ) {
        this.service = service;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "canonical_id", Map.of(
                "type", "string",
                "description", "26-character ULID canonical identifier for the page.",
                "examples", List.of( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" )
        ) );

        final Map< String, Object > exampleProjection = new LinkedHashMap<>();
        exampleProjection.put( "canonical_id", "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        exampleProjection.put( "slug", "HybridRetrieval" );
        exampleProjection.put( "title", "Hybrid Retrieval" );
        exampleProjection.put( "type", "design" );
        exampleProjection.put( "summary", "BM25 + dense + graph-aware rerank with fail-closed BM25 fallback." );
        exampleProjection.put( "key_facts", List.of(
                "Hybrid retrieval combines BM25 lexical scores with dense-vector cosine similarity.",
                "When the embedding service is unreachable, the system fails closed to BM25-only results."
        ) );
        exampleProjection.put( "headings", List.of( "Failure modes", "Graph rerank step", "Configuration" ) );
        exampleProjection.put( "relations", List.of( Map.of(
                "type", "part-of",
                "target_id", "01H8G3Z2E7FD8R1Q4V9X2T0NMP",
                "target_slug", "RetrievalExperimentHarness"
        ) ) );
        exampleProjection.put( "verification", Map.of(
                "verified_at", "2026-04-25T14:30:00Z",
                "verified_by", "jakefear",
                "confidence", "authoritative"
        ) );
        exampleProjection.put( "recent_changes", List.of( Map.of(
                "version", 7,
                "author", "testbot",
                "lastModified", "2026-04-25T14:30:00Z",
                "changeNote", "fix: typo in BM25 fallback section"
        ) ) );
        exampleProjection.put( "tool_hints", List.of(
                "call get_page for the full body",
                "call traverse_relations to expand the neighborhood"
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleProjection ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Return a token-budgeted projection of a wiki page for agent " +
                        "consumption: summary, key facts, headings outline, typed relations, " +
                        "verification state, recent changes, and MCP tool hints — without " +
                        "the full page body. Prefer this over get_page when the agent only " +
                        "needs to orient itself; follow up with get_page for the full body." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of( "canonical_id" ),
                        null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final Object raw = arguments.get( "canonical_id" );
            if ( !( raw instanceof String s ) || s.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                        "canonical_id argument is required" );
            }
            final Optional< ForAgentProjection > maybe = service.project( s );
            if ( maybe.isEmpty() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                        "no page for canonical_id " + s );
            }
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, maybe.get() );
        } catch ( final Exception e ) {
            LOG.error( "get_page_for_agent failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
