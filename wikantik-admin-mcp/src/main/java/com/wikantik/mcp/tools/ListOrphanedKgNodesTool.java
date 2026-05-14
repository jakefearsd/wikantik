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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only tool that lists Knowledge Graph nodes with no incident edges
 * (degree-0 nodes). Surfaces the disconnected-entity backlog so curators can
 * triage and either anchor or delete at scale, instead of sampling
 * {@code query_nodes} and walking each result with {@code get_node} to check
 * {@code incident_edges} (the workaround that motivated this tool).
 *
 * <p>Always runs with the {@code kg_excluded_pages} filter bypassed — the same
 * admin-bypass posture as {@link AdminQueryNodesTool} — so curators can find
 * orphans on pages that have not yet been admitted by the cluster inclusion
 * policy.</p>
 *
 * <p>Three-state {@code source_page_excluded} filter lets curators distinguish
 * "policy-hidden but anchored to a page" from "truly disconnected." Stubs
 * (nodes with no {@code source_page}) are returned regardless of the filter
 * direction, since they cannot be classified either way.</p>
 */
public class ListOrphanedKgNodesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListOrphanedKgNodesTool.class );

    public static final String TOOL_NAME = "list_orphaned_kg_nodes";

    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_OFFSET = 0;

    private final KnowledgeGraphService service;

    public ListOrphanedKgNodesTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "filters", Map.of(
                "type", "object",
                "description", "Optional filters: node_type (exact), source_page (exact), "
                        + "source_page_excluded (true=only excluded pages, false=only "
                        + "non-excluded pages, omitted=both). Stub nodes (no source_page) are "
                        + "always returned regardless of source_page_excluded.",
                "examples", List.of(
                        Map.of( "node_type", "concept" ),
                        Map.of( "source_page_excluded", true ) )
        ) );
        properties.put( "limit", Map.of(
                "type", "integer",
                "description", "Maximum number of results (default 50)",
                "examples", List.of( 50 )
        ) );
        properties.put( "offset", Map.of(
                "type", "integer",
                "description", "Pagination offset (default 0)",
                "examples", List.of( 0 )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "results", List.of( Map.of(
                        "id", "ffffffff-0001-0000-0000-000000000001",
                        "name", "OrphanConcept",
                        "node_type", "concept",
                        "source_page", "ExcludedPage",
                        "provenance", "human-authored"
                ) ),
                "count", 1,
                "admin_bypass", true
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Admin-only listing of Knowledge Graph nodes with no incident "
                        + "edges. Returns degree-0 nodes (neither source nor target of any "
                        + "kg_edges row). Bypasses the kg_excluded_pages filter so curators "
                        + "can triage orphans regardless of cluster inclusion policy. "
                        + "Filters: node_type, source_page, source_page_excluded (three-state). "
                        + "Pair with get_node, curate_edges, or curate_nodes to anchor or delete "
                        + "the disconnected entity." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final Map< String, Object > filters = ( Map< String, Object > ) arguments.get( "filters" );
            final int limit = McpToolUtils.getInt( arguments, "limit", DEFAULT_LIMIT );
            final int offset = McpToolUtils.getInt( arguments, "offset", DEFAULT_OFFSET );

            final List< KgNode > orphans = service.listOrphanedNodes( filters, limit, offset );
            final long total = service.countOrphanedNodes( filters );

            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "results", orphans );
            payload.put( "count", total );
            payload.put( "admin_bypass", true );
            return McpToolUtils.jsonResult( McpToolUtils.KG_GSON, payload );
        } catch ( final Exception e ) {
            LOG.error( "list_orphaned_kg_nodes failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.KG_GSON, e.getMessage() );
        }
    }
}
