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

    public GetPageTool( final ContextRetrievalService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        // D13: canonical name is `slug`. Accept the legacy `pageName` for callers that
        // were generated from older tool definitions.
        properties.put( "slug", Map.of(
            "type", "string",
            "description", "Name (slug) of the wiki page to fetch." ) );
        properties.put( "pageName", Map.of(
            "type", "string",
            "description", "Deprecated alias for `slug`. Prefer `slug` for new code." ) );

        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Fetch a single wiki page by name. Use for pinned-context flows " +
                "when you already know which page to load. Returns the page's frontmatter " +
                "metadata and a URL. Use retrieve_context instead when querying by topic." )
            .inputSchema( new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null ) )
            .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
            .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            // D13: accept both `slug` (canonical) and `pageName` (deprecated alias).
            final String pageName = McpToolUtils.getStringAny( arguments, "slug", "pageName" );
            if ( arguments.containsKey( "pageName" ) && !arguments.containsKey( "slug" ) ) {
                LOG.warn( "get_page called with deprecated argument 'pageName'; prefer 'slug'" );
            }
            if ( pageName == null || pageName.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "slug must not be blank" );
            }
            final RetrievedPage page = service.getPage( pageName );
            if ( page == null ) {
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
