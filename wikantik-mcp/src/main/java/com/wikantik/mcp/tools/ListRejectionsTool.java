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

import com.wikantik.api.knowledge.KgRejection;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that queries the rejection history so agents can avoid re-proposing
 * previously rejected relationships.
 */
public class ListRejectionsTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListRejectionsTool.class );
    public static final String TOOL_NAME = "list_rejections";

    private final KnowledgeGraphService service;

    public ListRejectionsTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "source", Map.of( "type", "string", "description",
                "Filter by source node name (optional)" ) );
        properties.put( "target", Map.of( "type", "string", "description",
                "Filter by target node name (optional)" ) );
        properties.put( "relationship", Map.of( "type", "string", "description",
                "Filter by relationship type (optional)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Query the rejection history for previously rejected knowledge proposals. " +
                        "Use this before proposing new relationships to avoid re-proposing rejected ones." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String source = McpToolUtils.getString( arguments, "source" );
        final String target = McpToolUtils.getString( arguments, "target" );
        final String relationship = McpToolUtils.getString( arguments, "relationship" );

        try {
            final List< KgRejection > rejections = service.listRejections( source, target, relationship );
            final List< Map< String, Object > > results = rejections.stream().map( r -> {
                final Map< String, Object > map = new LinkedHashMap< String, Object >();
                map.put( "id", r.id().toString() );
                map.put( "proposed_source", r.proposedSource() );
                map.put( "proposed_target", r.proposedTarget() );
                map.put( "proposed_relationship", r.proposedRelationship() );
                map.put( "rejected_by", r.rejectedBy() );
                map.put( "reason", r.reason() );
                map.put( "created", r.created() != null ? r.created().toString() : null );
                return map;
            } ).toList();

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of( "rejections", results ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list rejections: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}
