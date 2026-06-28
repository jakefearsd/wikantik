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
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * MCP tool — assemble a RAG-as-a-Service context bundle for a natural-language query.
 * Returns a ranked, de-duplicated, version-pinned, citation-bearing set of wiki sections
 * for grounding — it does NOT synthesize an answer (ADR-0001).
 */
public class AssembleBundleTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( AssembleBundleTool.class );
    public static final String TOOL_NAME = "assemble_bundle";

    private final BundleAssemblyService service;
    /** Resolved at call time (not construction) so it survives the post-startup wiring order; may yield null. */
    private final Supplier< QueryLogService > queryLog;
    private final PageViewGate viewGate;

    public AssembleBundleTool( final BundleAssemblyService service,
                               final Supplier< QueryLogService > queryLog ) {
        this( service, queryLog, PageViewGate.ALLOW_ALL );
    }

    public AssembleBundleTool( final BundleAssemblyService service,
                               final Supplier< QueryLogService > queryLog,
                               final PageViewGate viewGate ) {
        this.service = service;
        this.queryLog = queryLog;
        this.viewGate = viewGate == null ? PageViewGate.ALLOW_ALL : viewGate;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "query", Map.of(
                "type", "string",
                "description", "Natural-language query to assemble a ranked, cited context bundle for."
        ) );
        props.put( "mode", Map.of(
                "type", "string",
                "description", "Retrieval mode: hybrid (default), dense, or lexical."
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
            final RetrievalMode mode;
            try {
                mode = RetrievalMode.fromWire( McpToolUtils.getString( arguments, "mode" ) );
            } catch ( final IllegalArgumentException e ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
            }
            final ContextBundle bundle = service.assemble( query, mode );
            // Guest view-ACL: the MCP surface has no caller identity, so only publicly-viewable pages are returned (see PageViewGate).
            final List< com.wikantik.api.bundle.BundleSection > filteredSections = bundle.sections().stream()
                    .filter( s -> viewGate.canView( s.slug() ) )
                    .collect( Collectors.toList() );
            final ContextBundle gated = new ContextBundle( bundle.query(), filteredSections );
            final QueryLogService qlog = queryLog == null ? null : queryLog.get();
            if ( qlog != null ) {
                qlog.log( query, ActorType.AGENT, SourceSurface.MCP_ASSEMBLE_BUNDLE, filteredSections.size() );
            }
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, gated );
        } catch ( final Exception e ) {
            LOG.error( "assemble_bundle failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}
