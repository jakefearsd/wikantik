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
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: fetch a single page by name for pinned-context flows. Returns
 * {name, url, score=0, summary, cluster, tags, author, lastModified} or
 * {exists:false, pageName} if the page does not exist.
 */
public class GetPageTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetPageTool.class );
    public static final String TOOL_NAME = "get_page";

    private final ContextRetrievalService service;
    private final PageViewGate viewGate;

    public GetPageTool( final ContextRetrievalService service ) {
        this( service, PageViewGate.ALLOW_ALL );
    }

    public GetPageTool( final ContextRetrievalService service, final PageViewGate viewGate ) {
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
        // Canonical name is `slug`. Legacy/guessable names (pageName, name, page) are still
        // accepted at execute() via McpToolUtils.pageSlug — we just don't advertise them.
        properties.put( "slug", Map.of(
            "type", "string",
            "description", "Name (slug) of the wiki page to fetch.",
            "examples", List.of( "HybridRetrieval" )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "name", "HybridRetrieval",
                "url", "https://wiki.example.com/HybridRetrieval",
                "score", 0,
                "summary", "BM25 + dense + graph-aware rerank with fail-closed BM25 fallback.",
                "cluster", "retrieval",
                "tags", List.of( "retrieval", "search" ),
                "author", "jakefear",
                "lastModified", "2026-04-25T14:30:00Z"
        ) ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Fetch a single wiki page by name. Use for pinned-context flows " +
                "when you already know which page to load. Returns the page's frontmatter " +
                "metadata and a URL. Use retrieve_context instead when querying by topic." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null ) )
            .outputSchema( outputSchema )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            // Canonical key is `slug`; accept common synonyms an agent may guess so a reasonable
            // call doesn't hard-fail (the cross-surface naming-consistency ask).
            final String pageName = McpToolUtils.pageSlug( arguments );
            if ( pageName == null || pageName.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                        "a page identifier is required (one of: slug, pageName, name)" );
            }
            final RetrievedPage page = service.getPage( pageName );
            if ( page == null ) {
                final Map< String, Object > missing = new LinkedHashMap<>();
                missing.put( "exists", false );
                missing.put( "pageName", pageName );
                return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, missing );
            }
            // Guest view-ACL: the MCP surface has no caller identity, so only publicly-viewable pages are returned (see PageViewGate).
            if ( !viewGate.canView( pageName ) ) {
                final Map< String, Object > missing = new LinkedHashMap<>();
                missing.put( "exists", false );
                missing.put( "pageName", pageName );
                return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, missing );
            }
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, page );
        } catch ( final RuntimeException e ) {
            LOG.error( "get_page failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
