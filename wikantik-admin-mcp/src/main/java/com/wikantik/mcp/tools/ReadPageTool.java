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
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.mcp.AbstractMcpTool;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: read the raw Markdown+frontmatter body of a page, along with the
 * content hash and version needed by {@code update_page}'s optimistic-locking
 * check. The existing {@code get_page} on the knowledge endpoint returns a
 * retrieval-shaped summary; this tool exists for edit workflows.
 */
public class ReadPageTool extends AbstractMcpTool {

    public static final String TOOL_NAME = "read_page";

    private final PageManager pageManager;

    public ReadPageTool( final PageManager pageManager ) {
        this.pageManager = pageManager;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "slug", Map.of(
                "type", "string",
                "description", "Name of the page to read (no .md extension).",
                "examples", List.of( "HybridRetrieval" )
        ) );
        properties.put( "version", Map.of(
                "type", "integer",
                "description", "Optional version number. Omit for latest.",
                "examples", List.of( 7 )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "pageName", "HybridRetrieval",
                "exists", true,
                "content", "---\ntitle: Hybrid Retrieval\nsummary: BM25 + dense + graph-aware rerank.\n---\n\n# Hybrid Retrieval\n\n...",
                "contentHash", "sha256:9c3f8a1d4b6e7c2a5f8e1d3b7a9c0e2d4f6a8b1c3e5d7f9a0b2c4d6e8f0a2b4d",
                "version", 7,
                "lastModified", "2026-04-25T14:30:00Z"
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Read the raw Markdown body (including YAML frontmatter) of a page. " +
                        "Returns {exists, pageName, content, contentHash, version, lastModified}. " +
                        "Pass the contentHash back to update_page as expectedContentHash to edit the page." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "slug" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        final String pageName = McpToolUtils.pageSlug( arguments );
        if ( pageName == null || pageName.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "pageName must not be blank" );
        }
        final int version = McpToolUtils.getInt( arguments, "version", PageProvider.LATEST_VERSION );

        // getPageWithoutMetadata, not getPage: only getVersion()/getLastModified() are read below,
        // and both come straight off the provider's page info. getPage() would also run the
        // provider's refreshMetadata(), parsing the page through the markup pipeline for variables
        // this tool never touches; the returned body comes from getPureText().
        final Page page = pageManager.getPageWithoutMetadata( pageName, version );
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "pageName", pageName );
        if ( page == null ) {
            result.put( "exists", false );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        }

        final String text = pageManager.getPureText( pageName, version );
        final String body = text == null ? "" : text;
        result.put( "exists", true );
        result.put( "content", body );
        result.put( "contentHash", McpToolUtils.computeContentHash( body ) );
        result.put( "version", McpToolUtils.normalizeVersion( page.getVersion() ) );
        result.put( "lastModified", McpToolUtils.formatTimestamp( page.getLastModified() ) );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }
}
