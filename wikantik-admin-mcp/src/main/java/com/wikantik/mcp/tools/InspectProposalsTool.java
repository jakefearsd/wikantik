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
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.ProposalConflictFlags;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP tool providing deep-dive bulk read of 1..bulkLimit proposals.
 * Returns full proposal details, conflict flags, prior review history,
 * and any linked entity snapshot. Unknown or invalid ids land in missing[].
 */
public class InspectProposalsTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( InspectProposalsTool.class );
    public static final String TOOL_NAME = "inspect_proposals";

    private final KnowledgeGraphService service;
    private final int bulkLimit;

    public InspectProposalsTool( final KnowledgeGraphService service, final int bulkLimit ) {
        this.service = service;
        this.bulkLimit = bulkLimit;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "ids", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Proposal UUIDs to inspect (1.." + bulkLimit + ")",
                "examples", List.of( List.of( "8f3c2a1b-7e4d-4f5a-9b6c-1d2e3f4a5b6c" ) )
        ) );

        final Map< String, Object > exampleOut = new LinkedHashMap<>();
        exampleOut.put( "proposals", List.of( Map.of(
                "id", "8f3c2a1b-...",
                "proposal", Map.of( "proposal_type", "new-edge", "status", "pending" ),
                "conflicts", Map.of( "edge_previously_rejected", true ),
                "prior_reviews", List.of(),
                "linked_entity", Map.of() ) ) );
        exampleOut.put( "missing", List.of() );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleOut ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Bulk deep-dive read of 1.." + bulkLimit + " proposals. " +
                        "Returns full proposal, conflict flags, prior reviews, and any linked entity snapshot. " +
                        "Unknown ids land in `missing[]`." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "ids" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final Object raw = arguments.get( "ids" );
        if ( !( raw instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "ids is required and must be a non-empty array" );
        }
        if ( rawList.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + rawList.size() + " > " + bulkLimit );
        }

        final List< Map< String, Object > > proposals = new ArrayList<>();
        final List< String > missing = new ArrayList<>();

        for ( final Object idEl : rawList ) {
            final String idStr = idEl == null ? null : idEl.toString();
            UUID id;
            try {
                id = UUID.fromString( idStr );
            } catch ( final IllegalArgumentException e ) {
                missing.add( idStr );
                continue;
            }

            final KgProposal p = service.getProposal( id );
            if ( p == null ) {
                missing.add( idStr );
                continue;
            }

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "id", id.toString() );
            entry.put( "proposal", proposalToMap( p ) );
            entry.put( "conflicts", ProposalConflictFlags.forProposal( service, p, true ) );
            entry.put( "prior_reviews", reviewsToMaps( service.listReviews( id ) ) );
            entry.put( "linked_entity", linkedEntity( p ) );
            proposals.add( entry );
        }

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "proposals", proposals );
        out.put( "missing", missing );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }

    private Map< String, Object > proposalToMap( final KgProposal p ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "proposal_type", p.proposalType() );
        m.put( "source_page", p.sourcePage() );
        m.put( "proposed_data", p.proposedData() );
        m.put( "confidence", p.confidence() );
        m.put( "reasoning", p.reasoning() );
        m.put( "status", p.status() );
        m.put( "reviewed_by", p.reviewedBy() );
        m.put( "created", p.created() != null ? p.created().toString() : null );
        m.put( "reviewed_at", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
        return m;
    }

    private List< Map< String, Object > > reviewsToMaps( final List< KgProposalReview > reviews ) {
        if ( reviews == null ) return List.of();
        final List< Map< String, Object > > out = new ArrayList<>( reviews.size() );
        for ( final KgProposalReview r : reviews ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "reviewer_kind", r.reviewerKind() );
            m.put( "reviewer_id", r.reviewerId() );
            m.put( "verdict", r.verdict() );
            m.put( "confidence", r.confidence() );
            m.put( "rationale", r.rationale() );
            m.put( "created", r.created() != null ? r.created().toString() : null );
            out.add( m );
        }
        return out;
    }

    private Map< String, Object > linkedEntity( final KgProposal p ) {
        if ( "new-node".equals( p.proposalType() ) && p.proposedData() != null ) {
            final Object name = p.proposedData().get( "name" );
            if ( name instanceof String s ) {
                final KgNode existing = service.getNodeByName( s, true );
                if ( existing != null ) {
                    return Map.of( "kind", "node",
                            "id", existing.id().toString(),
                            "name", existing.name(),
                            "type", existing.nodeType() );
                }
            }
        }
        return Map.of();
    }
}
