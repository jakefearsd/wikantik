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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProposeKnowledgeToolTest {

    @Test
    void name_isProposeKnowledge() {
        assertEquals( "propose_knowledge",
            new ProposeKnowledgeTool( mock( KnowledgeGraphService.class ) ).name() );
    }

    @Test
    void definition_requiresAllFiveInputs() {
        final var req = new ProposeKnowledgeTool( mock( KnowledgeGraphService.class ) )
            .definition().inputSchema().required();
        assertTrue( req.containsAll( List.of(
            "proposal_type", "proposed_data", "source_page", "confidence", "reasoning" ) ) );
    }

    @Test
    void execute_returnsErrorWhenRequiredArgsMissing() {
        final Map< String, Object > args = new HashMap<>();
        args.put( "proposal_type", "new-node" );
        // proposed_data, source_page, reasoning intentionally absent
        final McpSchema.CallToolResult result =
            new ProposeKnowledgeTool( mock( KnowledgeGraphService.class ) ).execute( args );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Missing required parameters" ) );
    }

    @Test
    void execute_submitsNewNodeProposalAndReturnsShape() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final UUID id = UUID.fromString( "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" );
        final Instant created = Instant.parse( "2026-04-24T09:00:00Z" );
        when( svc.submitProposal( eq( "new-node" ), eq( "Alpha" ), anyMap(),
                eq( 0.9 ), eq( "why" ) ) )
            .thenReturn( new KgProposal( id, "new-node", "Alpha",
                Map.of( "name", "Widget" ), 0.9, "why",
                "pending", null, created, null ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "proposal_type", "new-node" );
        args.put( "proposed_data", Map.of( "name", "Widget" ) );
        args.put( "source_page", "Alpha" );
        args.put( "confidence", 0.9 );
        args.put( "reasoning", "why" );

        final McpSchema.CallToolResult result =
            new ProposeKnowledgeTool( svc ).execute( args );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"id\":\"" + id + "\"" ) );
        assertTrue( text.contains( "\"status\":\"pending\"" ) );
        assertTrue( text.contains( "\"created\":\"2026-04-24T09:00:00Z\"" ) );
    }

    @Test
    void execute_defaultsConfidenceToZeroWhenAbsent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.submitProposal( any(), any(), anyMap(), anyDouble(), any() ) )
            .thenReturn( new KgProposal( UUID.randomUUID(), "new-node", "P",
                Map.of(), 0.0, "why", "pending", null, null, null ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "proposal_type", "new-node" );
        args.put( "proposed_data", Map.of( "name", "N" ) );
        args.put( "source_page", "P" );
        args.put( "reasoning", "why" );
        // confidence absent — should default to 0.0

        new ProposeKnowledgeTool( svc ).execute( args );
        verify( svc ).submitProposal( eq( "new-node" ), eq( "P" ),
            anyMap(), eq( 0.0 ), eq( "why" ) );
    }

    @Test
    void execute_rejectsNewEdgeWhenPreviouslyRejected() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.isRejected( "Alpha", "Beta", "relatedTo" ) ).thenReturn( true );
        when( svc.listRejections( "Alpha", "Beta", "relatedTo" ) ).thenReturn(
            List.of( new KgRejection( UUID.randomUUID(), "Alpha", "Beta", "relatedTo",
                "reviewer", "off-topic", Instant.now() ) ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "proposal_type", "new-edge" );
        args.put( "proposed_data", Map.of(
            "source", "Alpha", "target", "Beta", "relationship", "relatedTo" ) );
        args.put( "source_page", "Alpha" );
        args.put( "confidence", 0.7 );
        args.put( "reasoning", "why" );

        final McpSchema.CallToolResult result =
            new ProposeKnowledgeTool( svc ).execute( args );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "previously rejected" ) );
        assertTrue( text.contains( "off-topic" ) );
        verify( svc, never() ).submitProposal( any(), any(), anyMap(), anyDouble(), any() );
    }

    @Test
    void execute_submitsNewEdgeWhenNotRejected() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.isRejected( any(), any(), any() ) ).thenReturn( false );
        when( svc.submitProposal( any(), any(), anyMap(), anyDouble(), any() ) )
            .thenReturn( new KgProposal( UUID.randomUUID(), "new-edge", "Alpha",
                Map.of(), 0.5, "why", "pending", null, null, null ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "proposal_type", "new-edge" );
        args.put( "proposed_data", Map.of(
            "source", "Alpha", "target", "Beta", "relationship", "relatedTo" ) );
        args.put( "source_page", "Alpha" );
        args.put( "confidence", 0.5 );
        args.put( "reasoning", "why" );

        new ProposeKnowledgeTool( svc ).execute( args );
        verify( svc ).isRejected( "Alpha", "Beta", "relatedTo" );
        verify( svc ).submitProposal( eq( "new-edge" ), eq( "Alpha" ),
            anyMap(), eq( 0.5 ), eq( "why" ) );
    }

    @Test
    void execute_returnsErrorOnServiceFailure() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.submitProposal( any(), any(), anyMap(), anyDouble(), any() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );

        final Map< String, Object > args = new HashMap<>();
        args.put( "proposal_type", "new-node" );
        args.put( "proposed_data", Map.of( "name", "N" ) );
        args.put( "source_page", "P" );
        args.put( "confidence", 0.5 );
        args.put( "reasoning", "why" );

        final McpSchema.CallToolResult result =
            new ProposeKnowledgeTool( svc ).execute( args );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
