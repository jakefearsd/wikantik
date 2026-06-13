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

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool — assemble a RAG-as-a-Service context bundle for a natural-language query.
 * Returns a ranked, de-duplicated, version-pinned, citation-bearing set of wiki sections
 * for grounding — it does NOT synthesize an answer (ADR-0001).
 */
public class AssembleBundleTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( AssembleBundleTool.class );
    public static final String TOOL_NAME = "assemble_bundle";

    private final BundleAssemblyService service;

    public AssembleBundleTool( final BundleAssemblyService service ) {
        this.service = service;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "query", Map.of(
                "type", "string",
                "description", "Natural-language query to assemble a ranked, cited context bundle for."
        ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Assemble a RAG-as-a-Service context bundle: a ranked, de-duplicated, "
                        + "version-pinned, citation-bearing set of wiki sections for a query. Returns evidence "
                        + "to ground on — it does NOT synthesize an answer." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of( "query" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String query = McpToolUtils.getString( arguments, "query" );
            if ( query == null || query.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "query argument is required" );
            }
            final ContextBundle bundle = service.assemble( query );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, bundle );
        } catch ( final Exception e ) {
            LOG.error( "assemble_bundle failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}
