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

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tool: filter-driven browse over wiki pages. No ranking, no chunks —
 * use retrieve_context for query-driven retrieval.
 */
public class ListPagesTool extends AbstractKnowledgeMcpTool {

    private static final Logger LOG = LogManager.getLogger( ListPagesTool.class );
    public static final String TOOL_NAME = "list_pages";

    private final ContextRetrievalService service;
    private final PageViewGate viewGate;

    public ListPagesTool( final ContextRetrievalService service ) {
        this( service, PageViewGate.ALLOW_ALL );
    }

    public ListPagesTool( final ContextRetrievalService service, final PageViewGate viewGate ) {
        this.service = service;
        this.viewGate = viewGate == null ? PageViewGate.ALLOW_ALL : viewGate;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "cluster", Map.of( "type", "string",
            "description", "Filter by frontmatter cluster value (exact match).",
            "examples", List.of( "retrieval" ) ) );
        properties.put( "tags", Map.of( "type", "array", "items", Map.of( "type", "string" ),
            "description", "Filter to pages that have ALL listed tags.",
            "examples", List.of( List.of( "agents", "search" ) ) ) );
        properties.put( "type", Map.of( "type", "string",
            "description", "Filter by frontmatter type value.",
            "examples", List.of( "runbook" ) ) );
        properties.put( "author", Map.of( "type", "string",
            "description", "Filter by page author.",
            "examples", List.of( "jakefear" ) ) );
        properties.put( "modifiedAfter", Map.of( "type", "string",
            "description", "ISO-8601 instant — include only pages modified after this time.",
            "examples", List.of( "2026-01-01T00:00:00Z" ) ) );
        properties.put( "modifiedBefore", Map.of( "type", "string",
            "description", "ISO-8601 instant — include only pages modified before this time.",
            "examples", List.of( "2026-04-25T23:59:59Z" ) ) );
        properties.put( "limit", Map.of( "type", "integer",
            "description", "Max rows (default 50, max 200).",
            "examples", List.of( 50 ) ) );
        properties.put( "offset", Map.of( "type", "integer",
            "description", "Pagination offset (default 0).",
            "examples", List.of( 0 ) ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "pages", List.of( Map.of(
                        "name", "HybridRetrieval",
                        "url", "https://wiki.example.com/HybridRetrieval",
                        "summary", "BM25 + dense + graph-aware rerank.",
                        "cluster", "retrieval",
                        "tags", List.of( "retrieval", "search" ),
                        "lastModified", "2026-04-25T14:30:00Z"
                ) ),
                "totalMatched", 1
        ) ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Browse wiki pages by metadata filters. Returns page summaries " +
                "(no chunks, no relatedPages). All filters are optional and combined with AND. " +
                "Use retrieve_context for query-driven retrieval." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null ) )
            .outputSchema( outputSchema )
            .annotations( READ_ONLY_ANNOTATIONS )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        try {
            final PageListFilter filter = buildFilter( arguments );
            final PageList result = service.listPages( filter );
            // Guest view-ACL: the MCP surface has no caller identity, so only publicly-viewable pages are returned (see PageViewGate).
            final List< RetrievedPage > filtered = result.pages().stream()
                    .filter( p -> viewGate.canView( p.name() ) )
                    .collect( Collectors.toList() );
            // Preserve totalMatched's pagination semantic (full match count): only discount the
            // pages actually hidden from this page, never collapse it to the returned count.
            final int hidden = result.pages().size() - filtered.size();
            final int total = Math.max( filtered.size(), result.totalMatched() - hidden );
            final PageList gated = new PageList( filtered, total, result.limit(), result.offset() );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, gated );
        } catch ( final IllegalArgumentException e ) {
            LOG.info( "list_pages rejected invalid argument: {}", e.getMessage() );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    private PageListFilter buildFilter( final Map< String, Object > arguments ) {
        return new PageListFilter(
            KnowledgeMcpUtils.asString( arguments.get( "cluster" ) ),
            KnowledgeMcpUtils.asStringList( arguments.get( "tags" ) ),
            KnowledgeMcpUtils.asString( arguments.get( "type" ) ),
            KnowledgeMcpUtils.asString( arguments.get( "author" ) ),
            KnowledgeMcpUtils.asInstant( arguments.get( "modifiedAfter" ) ),
            KnowledgeMcpUtils.asInstant( arguments.get( "modifiedBefore" ) ),
            McpToolUtils.getInt( arguments, "limit", 50 ),
            McpToolUtils.getInt( arguments, "offset", 0 ) );
    }
}
