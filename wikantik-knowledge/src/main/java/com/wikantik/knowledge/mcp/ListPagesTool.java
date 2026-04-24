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
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: filter-driven browse over wiki pages. No ranking, no chunks —
 * use retrieve_context for query-driven retrieval.
 */
public class ListPagesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListPagesTool.class );
    public static final String TOOL_NAME = "list_pages";

    private final ContextRetrievalService service;

    public ListPagesTool( final ContextRetrievalService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "cluster", Map.of( "type", "string",
            "description", "Filter by frontmatter cluster value (exact match)." ) );
        properties.put( "tags", Map.of( "type", "array", "items", Map.of( "type", "string" ),
            "description", "Filter to pages that have ALL listed tags." ) );
        properties.put( "type", Map.of( "type", "string",
            "description", "Filter by frontmatter type value." ) );
        properties.put( "author", Map.of( "type", "string",
            "description", "Filter by page author." ) );
        properties.put( "modifiedAfter", Map.of( "type", "string",
            "description", "ISO-8601 instant — include only pages modified after this time." ) );
        properties.put( "modifiedBefore", Map.of( "type", "string",
            "description", "ISO-8601 instant — include only pages modified before this time." ) );
        properties.put( "limit", Map.of( "type", "integer",
            "description", "Max rows (default 50, max 200)." ) );
        properties.put( "offset", Map.of( "type", "integer",
            "description", "Pagination offset (default 0)." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Browse wiki pages by metadata filters. Returns page summaries " +
                "(no chunks, no relatedPages). All filters are optional and combined with AND. " +
                "Use retrieve_context for query-driven retrieval." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final PageListFilter filter = buildFilter( arguments );
            final PageList result = service.listPages( filter );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, result );
        } catch ( final IllegalArgumentException e ) {
            LOG.info( "list_pages rejected invalid argument: {}", e.getMessage() );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        } catch ( final RuntimeException e ) {
            LOG.error( "list_pages failed: {}", e.getMessage(), e );
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
