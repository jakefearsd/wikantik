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

import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.SchemaDescription;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that returns the current shape of the knowledge base: node types,
 * relationship types, property keys with cardinalities and sample values,
 * and aggregate statistics.
 */
public class DiscoverSchemaTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( DiscoverSchemaTool.class );
    public static final String TOOL_NAME = "discover_schema";

    private final KnowledgeGraphService service;
    private final MentionIndex mentionIndex;

    public DiscoverSchemaTool( final KnowledgeGraphService service ) {
        this( service, null );
    }

    public DiscoverSchemaTool( final KnowledgeGraphService service,
                                final MentionIndex mentionIndex ) {
        this.service = service;
        this.mentionIndex = mentionIndex;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "schema", Map.of(
                        "node_types", List.of( "design_doc", "runbook", "concept", "person" ),
                        "relationship_types", List.of( "part_of", "supersedes", "implements", "falls_back_to" ),
                        "property_keys", Map.of(
                                "canonical_id", Map.of( "cardinality", 412, "samples", List.of( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ) ),
                                "cluster", Map.of( "cardinality", 14, "samples", List.of( "retrieval", "agents", "ops" ) )
                        ),
                        "totals", Map.of( "nodes", 1287, "edges", 3104 )
                ),
                "mentionedNodeCount", 962
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Returns the current shape of the knowledge base: node types, " +
                        "relationship types, property keys with cardinalities and sample values, " +
                        "and aggregate statistics. Use this first to understand what's in this " +
                        "knowledge base before querying." )
                .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final SchemaDescription schema = service.discoverSchema();
            if ( mentionIndex == null ) {
                return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, schema );
            }
            final Map< String, Object > payload = new LinkedHashMap<>();
            payload.put( "schema", schema );
            payload.put( "mentionedNodeCount", mentionIndex.getMentionedIds().size() );
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, payload );
        } catch ( final Exception e ) {
            LOG.error( "Discover schema failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }
}
