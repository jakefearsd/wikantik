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

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that queries pending or historical knowledge proposals to help
 * agents avoid submitting duplicates.
 */
public class ListProposalsTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( ListProposalsTool.class );
    public static final String TOOL_NAME = "list_proposals";

    private final KnowledgeGraphService service;

    public ListProposalsTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "status", Map.of( "type", "string", "description",
                "Filter by status: pending, approved, or rejected (optional)",
                "enum", List.of( "pending", "approved", "rejected" ) ) );
        properties.put( "source_page", Map.of( "type", "string", "description",
                "Filter by source page name (optional)" ) );
        properties.put( "limit", Map.of( "type", "integer", "description",
                "Maximum number of results (default 50)" ) );
        properties.put( "offset", Map.of( "type", "integer", "description",
                "Number of results to skip (default 0)" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Query knowledge proposals to check for duplicates or review history. " +
                        "Returns proposals with their status, confidence, reasoning, and review details." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String status = McpToolUtils.getString( arguments, "status" );
        final String sourcePage = McpToolUtils.getString( arguments, "source_page" );
        final int limit = McpToolUtils.getInt( arguments, "limit", 50 );
        final int offset = McpToolUtils.getInt( arguments, "offset", 0 );

        try {
            final List< KgProposal > proposals = service.listProposals( status, sourcePage, limit, offset );
            final List< Map< String, Object > > results = proposals.stream().map( p -> {
                final Map< String, Object > map = new LinkedHashMap< String, Object >();
                map.put( "id", p.id().toString() );
                map.put( "proposal_type", p.proposalType() );
                map.put( "source_page", p.sourcePage() );
                map.put( "proposed_data", p.proposedData() );
                map.put( "confidence", p.confidence() );
                map.put( "reasoning", p.reasoning() );
                map.put( "status", p.status() );
                map.put( "reviewed_by", p.reviewedBy() );
                map.put( "created", p.created() != null ? p.created().toString() : null );
                map.put( "reviewed_at", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
                return map;
            } ).toList();

            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, Map.of( "proposals", results ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list proposals: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}
