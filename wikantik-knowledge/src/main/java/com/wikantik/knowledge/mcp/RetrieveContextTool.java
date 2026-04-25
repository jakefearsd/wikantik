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

import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: run the full retrieval pipeline (BM25 + dense + graph rerank)
 * for a natural-language query. Returns pages with their top contributing
 * chunks and a small list of KG-mention neighbors.
 */
public class RetrieveContextTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( RetrieveContextTool.class );
    public static final String TOOL_NAME = "retrieve_context";

    private final ContextRetrievalService service;

    public RetrieveContextTool( final ContextRetrievalService service ) {
        this.service = service;
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
            "description", "Natural-language query for BM25 + dense retrieval.",
            "examples", List.of( "how does hybrid retrieval handle embedding service outages" )
        ) );
        properties.put( "maxPages", Map.of(
            "type", "integer",
            "description", "Max pages to return (default 5, max 20).",
            "examples", List.of( 5 )
        ) );
        properties.put( "chunksPerPage", Map.of(
            "type", "integer",
            "description", "Top contributing chunks per page (default 3, max 5).",
            "examples", List.of( 3 )
        ) );
        properties.put( "filters", Map.of(
            "type", "object",
            "description", "Optional pre-filter — cluster, tags, type, modifiedAfter (ISO-8601).",
            "examples", List.of( Map.of(
                    "cluster", "retrieval",
                    "tags", List.of( "agents" ),
                    "modifiedAfter", "2026-01-01T00:00:00Z"
            ) )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "query", "how does hybrid retrieval handle embedding service outages",
                "pages", List.of( Map.of(
                        "name", "HybridRetrieval",
                        "url", "https://wiki.example.com/HybridRetrieval",
                        "score", 0.87,
                        "summary", "BM25 + dense + graph-aware rerank with fail-closed BM25 fallback.",
                        "cluster", "retrieval",
                        "tags", List.of( "retrieval", "search" ),
                        "contributingChunks", List.of( Map.of(
                                "headingPath", List.of( "HybridRetrieval", "Failure modes" ),
                                "text", "When the embedding service is unreachable, hybrid degrades to BM25...",
                                "chunkScore", 0.92
                        ) ),
                        "relatedPages", List.of( Map.of(
                                "name", "HandlingEmbeddingServiceOutages",
                                "reason", "co-mentioned in chunks about fail-closed degradation"
                        ) ),
                        "author", "jakefear",
                        "lastModified", "2026-04-25T14:30:00Z"
                ) ),
                "totalMatched", 1
        ) ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Retrieve wiki context for a natural-language query. " +
                "Returns {query, pages: [{name, url, score, summary, cluster, tags, " +
                "contributingChunks, relatedPages, author, lastModified}], totalMatched}. " +
                "Primary RAG entry point for agents consuming wiki context." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of( "query" ), null, null, null ) )
            .outputSchema( outputSchema )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String query = McpToolUtils.getString( arguments, "query" );
            final int maxPages = McpToolUtils.getInt( arguments, "maxPages", 5 );
            final int chunksPerPage = McpToolUtils.getInt( arguments, "chunksPerPage", 3 );
            final PageListFilter filter = parseFilter( arguments );
            final ContextQuery q = new ContextQuery( query, maxPages, chunksPerPage, filter );
            final RetrievalResult result = service.retrieve( q );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final IllegalArgumentException e ) {
            LOG.info( "retrieve_context rejected invalid argument: {}", e.getMessage() );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        } catch ( final RuntimeException e ) {
            LOG.error( "retrieve_context failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    @SuppressWarnings( "unchecked" )
    private PageListFilter parseFilter( final Map< String, Object > arguments ) {
        final Object rawFilters = arguments.get( "filters" );
        if ( !( rawFilters instanceof Map< ?, ? > ) ) return null;
        final Map< String, Object > filters = (Map< String, Object >) rawFilters;
        return new PageListFilter(
            KnowledgeMcpUtils.asString( filters.get( "cluster" ) ),
            KnowledgeMcpUtils.asStringList( filters.get( "tags" ) ),
            KnowledgeMcpUtils.asString( filters.get( "type" ) ),
            KnowledgeMcpUtils.asString( filters.get( "author" ) ),
            KnowledgeMcpUtils.asInstant( filters.get( "modifiedAfter" ) ),
            KnowledgeMcpUtils.asInstant( filters.get( "modifiedBefore" ) ),
            50, 0 );
    }
}
