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

import java.time.Instant;
import java.time.format.DateTimeParseException;
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
            "description", "Natural-language query for BM25 + dense retrieval." ) );
        properties.put( "maxPages", Map.of(
            "type", "integer",
            "description", "Max pages to return (default 5, max 20)." ) );
        properties.put( "chunksPerPage", Map.of(
            "type", "integer",
            "description", "Top contributing chunks per page (default 3, max 5)." ) );
        properties.put( "filters", Map.of(
            "type", "object",
            "description", "Optional pre-filter — cluster, tags, type, modifiedAfter (ISO-8601)." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Retrieve wiki context for a natural-language query. " +
                "Returns {query, pages: [{name, url, score, summary, cluster, tags, " +
                "contributingChunks, relatedPages, author, lastModified}], totalMatched}. " +
                "Primary RAG entry point for agents consuming wiki context." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of( "query" ), null, null, null ) )
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
            asString( filters.get( "cluster" ) ),
            asStringList( filters.get( "tags" ) ),
            asString( filters.get( "type" ) ),
            asString( filters.get( "author" ) ),
            asInstant( filters.get( "modifiedAfter" ) ),
            asInstant( filters.get( "modifiedBefore" ) ),
            50, 0 );
    }

    private static String asString( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > asStringList( final Object o ) {
        if ( !( o instanceof List< ? > ) ) return null;
        return ( (List< Object >) o ).stream().filter( java.util.Objects::nonNull )
            .map( Object::toString ).toList();
    }

    private static Instant asInstant( final Object o ) {
        if ( !( o instanceof String ) || ( (String) o ).isBlank() ) return null;
        try {
            return Instant.parse( (String) o );
        } catch ( final DateTimeParseException e ) {
            throw new IllegalArgumentException( "Invalid ISO-8601 instant: " + o );
        }
    }
}
