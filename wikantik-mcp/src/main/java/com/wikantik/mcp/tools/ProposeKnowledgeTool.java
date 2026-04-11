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
import com.wikantik.api.knowledge.KgRejection;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool that allows external agents to submit knowledge graph proposals.
 * Checks the rejection history before accepting; if the proposed relationship
 * was previously rejected, the proposal is declined with an explanation.
 */
public class ProposeKnowledgeTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( ProposeKnowledgeTool.class );
    public static final String TOOL_NAME = "propose_knowledge";

    private final KnowledgeGraphService service;
    private String defaultAuthor = "MCP";

    public ProposeKnowledgeTool( final KnowledgeGraphService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public void setDefaultAuthor( final String author ) {
        this.defaultAuthor = author;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "proposal_type", Map.of( "type", "string", "description",
                "Type of proposal: new-node, new-edge, new-property, or modify-property",
                "enum", List.of( "new-node", "new-edge", "new-property", "modify-property" ) ) );
        properties.put( "proposed_data", Map.of( "type", "object", "description",
                "The full proposal data — node definition, edge definition, or property change. " +
                "For new-edge: {source, target, relationship}. For new-node: {name, node_type, properties}. " +
                "For new-property/modify-property: {node_name, key, value}." ) );
        properties.put( "source_page", Map.of( "type", "string", "description",
                "The wiki page that motivated this proposal" ) );
        properties.put( "confidence", Map.of( "type", "number", "description",
                "Agent's self-assessed confidence (0.0 to 1.0)" ) );
        properties.put( "reasoning", Map.of( "type", "string", "description",
                "Why the agent believes this is correct, citing specific evidence from page content" ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Submit a knowledge graph proposal for human review. " +
                        "Proposals are queued for approval by a knowledge administrator. " +
                        "If the proposed relationship was previously rejected, the submission is " +
                        "declined with the rejection reason included in the error." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "proposal_type", "proposed_data", "source_page", "confidence", "reasoning" ),
                        null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, false, null, null ) )
                .build();
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final String proposalType = McpToolUtils.getString( arguments, "proposal_type" );
        final String sourcePage = McpToolUtils.getString( arguments, "source_page" );
        final String reasoning = McpToolUtils.getString( arguments, "reasoning" );
        final double confidence = arguments.containsKey( "confidence" )
                ? ( ( Number ) arguments.get( "confidence" ) ).doubleValue() : 0.0;
        final Map< String, Object > proposedData = ( Map< String, Object > ) arguments.get( "proposed_data" );

        if ( proposalType == null || proposedData == null || sourcePage == null || reasoning == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Missing required parameters: proposal_type, proposed_data, source_page, reasoning" );
        }

        try {
            // Check rejection history for edge proposals
            if ( "new-edge".equals( proposalType ) ) {
                final String source = ( String ) proposedData.get( "source" );
                final String target = ( String ) proposedData.get( "target" );
                final String relationship = ( String ) proposedData.get( "relationship" );
                if ( source != null && target != null && relationship != null
                        && service.isRejected( source, target, relationship ) ) {
                    final List< KgRejection > rejections =
                            service.listRejections( source, target, relationship );
                    final String reason = rejections.isEmpty() ? "no reason recorded"
                            : rejections.get( 0 ).reason();
                    return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                            "This relationship was previously rejected: " + source + " --[" +
                            relationship + "]--> " + target + ". Reason: " + reason,
                            "Propose a different relationship, or adjust your reasoning." );
                }
            }

            final KgProposal proposal = service.submitProposal(
                    proposalType, sourcePage, proposedData, confidence, reasoning );

            McpAudit.logWrite( TOOL_NAME, "proposed-" + proposalType, sourcePage, defaultAuthor );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "id", proposal.id().toString() );
            result.put( "proposal_type", proposal.proposalType() );
            result.put( "source_page", proposal.sourcePage() );
            result.put( "status", proposal.status() );
            result.put( "confidence", proposal.confidence() );
            result.put( "created", proposal.created() != null ? proposal.created().toString() : null );
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
        } catch ( final Exception e ) {
            LOG.error( "Failed to submit proposal: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }
}
