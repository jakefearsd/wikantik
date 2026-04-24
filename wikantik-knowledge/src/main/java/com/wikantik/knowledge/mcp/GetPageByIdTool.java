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

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MCP tool — resolves a canonical_id to the current page descriptor. */
public class GetPageByIdTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetPageByIdTool.class );
    public static final String TOOL_NAME = "get_page_by_id";

    private final StructuralIndexService service;
    public GetPageByIdTool( final StructuralIndexService service ) { this.service = service; }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "canonical_id", Map.of(
                "type", "string",
                "description", "26-character ULID canonical identifier for the page."
        ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Resolve a canonical_id to the current page descriptor. Returns " +
                        "{id, slug, title, type, cluster, tags, summary, updated}. Prefer this over " +
                        "get_page when citing sources — the canonical_id is stable across renames." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of( "canonical_id" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String canonicalId = (String) arguments.get( "canonical_id" );
            if ( canonicalId == null || canonicalId.isBlank() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "canonical_id argument is required" );
            }
            final Optional< PageDescriptor > found = service.getByCanonicalId( canonicalId );
            if ( found.isEmpty() ) {
                return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "no page for canonical_id " + canonicalId );
            }
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, found.get() );
        } catch ( final Exception e ) {
            LOG.error( "get_page_by_id failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
