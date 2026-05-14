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
 * Admin-context mirror of the /knowledge-mcp {@code search_knowledge} tool with the
 * inclusion-filter bypass enabled. Curators can search for entities whose source pages
 * are on {@code kg_excluded_pages} — e.g. to verify a node immediately after a
 * {@code curate_nodes} upsert or {@code review_proposals} approval.
 *
 * <p>Lives in wikantik-admin-mcp (not wikantik-knowledge) to avoid a cyclic module
 * dependency: wikantik-knowledge already depends on wikantik-admin-mcp for the
 * {@link McpTool} / {@link McpToolUtils} shared types.</p>
 */
public class AdminSearchKnowledgeTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( AdminSearchKnowledgeTool.class );

    /** Tool name matches the /knowledge-mcp equivalent so agents use the same name. */
    public static final String TOOL_NAME = "search_knowledge";

    private final KnowledgeGraphService service;

    public AdminSearchKnowledgeTool( final KnowledgeGraphService service ) {
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
                "description", "Full-text search query across node names and properties",
                "examples", List.of( "hybrid retrieval", "knowledge graph" )
        ) );
        properties.put( "provenance_filter", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Provenance values to include (e.g. human-authored, ai-reviewed)",
                "examples", List.of( List.of( "human-authored" ) )
        ) );
        properties.put( "limit", Map.of(
                "type", "integer",
                "description", "Maximum number of results (default 20)",
                "examples", List.of( 10 )
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
                .description( "Admin-context fuzzy search across KG node names and property text. "
                        + "Admin-bypass on — searches entities on excluded source pages too. "
                        + "Use after a curate_nodes or review_proposals approval to find the "
                        + "new entity by name. Mirrors /knowledge-mcp search_knowledge but with "
                        + "admin-bypass on." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "query" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final String query = McpToolUtils.getString( arguments, "query" );
            final Set< Provenance > provenanceFilter = parseProvenanceFilter( arguments );
            final int limit = McpToolUtils.getInt( arguments, "limit", 20 );

            final List< KgNode > nodes = service.searchKnowledge( query, provenanceFilter, limit, /*adminBypass=*/ true );
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "results", nodes );
            payload.put( "admin_bypass", true );
            return McpToolUtils.jsonResult( McpToolUtils.KG_GSON, payload );
        } catch ( final Exception e ) {
            LOG.error( "Admin search_knowledge failed for query: {}", e.getMessage(), e );
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
