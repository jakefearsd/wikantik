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
import com.wikantik.api.knowledge.Provenance;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Admin-context mirror of the /knowledge-mcp {@code query_nodes} tool with the
 * inclusion-filter bypass enabled. Curators can immediately see entities whose
 * source pages are on {@code kg_excluded_pages} — e.g. freshly-created nodes
 * on a page not yet admitted by the cluster inclusion policy.
 *
 * <p>Lives in wikantik-admin-mcp (not wikantik-knowledge) to avoid a cyclic
 * module dependency: wikantik-knowledge already depends on wikantik-admin-mcp
 * for the {@link McpTool} / {@link McpToolUtils} shared types.</p>
 */
public class AdminQueryNodesTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( AdminQueryNodesTool.class );

    /** Tool name matches the /knowledge-mcp equivalent so agents use the same name. */
    public static final String TOOL_NAME = "query_nodes";

    private final KnowledgeGraphService service;

    public AdminQueryNodesTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "filters", Map.of(
                "type", "object",
                "description", "Filter criteria — keys like node_type, source_page, or name",
                "examples", List.of( Map.of( "node_type", "concept" ) )
        ) );
        properties.put( "provenance_filter", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Provenance values to include (e.g. human-authored, ai-reviewed)",
                "examples", List.of( List.of( "human-authored" ) )
        ) );
        properties.put( "limit", Map.of(
                "type", "integer",
                "description", "Maximum number of results (default 50)",
                "examples", List.of( 25 )
        ) );
        properties.put( "offset", Map.of(
                "type", "integer",
                "description", "Offset for pagination (default 0)",
                "examples", List.of( 0 )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "results", List.of( Map.of(
                        "id", "ffffffff-0001-0000-0000-000000000001",
                        "name", "ExampleNode",
                        "node_type", "concept",
                        "provenance", "human-authored"
                ) ),
                "admin_bypass", true
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Admin-context filter+list of KG nodes. Bypasses the inclusion "
                        + "filter so freshly-created entities (whose source pages have not yet "
                        + "been admitted by the cluster inclusion policy) are visible. Use after "
                        + "a curate_nodes upsert to verify the write. Mirrors /knowledge-mcp "
                        + "query_nodes but with admin-bypass on." )
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
            final Set< Provenance > provenanceFilter = parseProvenanceFilter( arguments );
            final int limit = McpToolUtils.getInt( arguments, "limit", 50 );
            final int offset = McpToolUtils.getInt( arguments, "offset", 0 );

            final List< KgNode > nodes = service.queryNodes( filters, provenanceFilter, limit, offset, /*adminBypass=*/ true );
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "results", nodes );
            payload.put( "admin_bypass", true );
            return McpToolUtils.jsonResult( McpToolUtils.KG_GSON, payload );
        } catch ( final Exception e ) {
            LOG.error( "Admin query_nodes failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.KG_GSON, e.getMessage() );
        }
    }

    @SuppressWarnings( "unchecked" )
    private static Set< Provenance > parseProvenanceFilter( final Map< String, Object > arguments ) {
        final Object raw = arguments.get( "provenance_filter" );
        if ( !( raw instanceof List ) ) {
            return Set.of();
        }
        final List< Object > list = ( List< Object > ) raw;
        final Set< Provenance > result = new LinkedHashSet<>();
        for ( final Object item : list ) {
            if ( item instanceof String s ) {
                try {
                    result.add( Provenance.fromValue( s ) );
                } catch ( final IllegalArgumentException ignored ) {
                    // silently skip unknown provenance strings
                }
            }
        }
        return result;
    }
}
