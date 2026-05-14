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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListProposalsToolTest {

    @Test
    void name_isListProposals() {
        assertEquals( "list_proposals",
            new ListProposalsTool( mock( KnowledgeGraphService.class ) ).name() );
    }

    @Test
    void definition_hasNoRequiredArgs() {
        final McpSchema.Tool def = new ListProposalsTool(
            mock( KnowledgeGraphService.class ) ).definition();
        assertTrue( def.inputSchema().required().isEmpty(),
            "all arguments are optional filters" );
    }

    @Test
    void execute_returnsShapedJson() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final UUID id = UUID.fromString( "11111111-2222-3333-4444-555555555555" );
        final Instant created = Instant.parse( "2026-04-24T10:00:00Z" );
        final Instant reviewed = Instant.parse( "2026-04-24T11:00:00Z" );
        when( svc.listProposals( eq( "pending" ), isNull(), eq( 50 ), eq( 0 ) ) )
            .thenReturn( List.of( new KgProposal(
                id, "new-edge", "Alpha",
                Map.< String, Object >of( "relationship", "relatedTo" ),
                0.82, "co-occurrence in section 2",
                "pending", null, created, reviewed,
                null, null, null, null, null ) ) );

        final McpSchema.CallToolResult result =
            new ListProposalsTool( svc ).execute( Map.of( "status", "pending" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"id\":\"" + id + "\"" ) );
        assertTrue( text.contains( "\"proposal_type\":\"new-edge\"" ) );
        assertTrue( text.contains( "\"source_page\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"confidence\":0.82" ) );
        assertTrue( text.contains( "\"status\":\"pending\"" ) );
        assertTrue( text.contains( "\"created\":\"2026-04-24T10:00:00Z\"" ) );
    }

    @Test
    void execute_appliesDefaultLimitAndOffset() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.listProposals( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        new ListProposalsTool( svc ).execute( Map.of() );

        verify( svc ).listProposals( isNull(), isNull(), eq( 50 ), eq( 0 ) );
    }

    @Test
    void execute_passesSourcePageAndPagination() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.listProposals( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        new ListProposalsTool( svc ).execute( Map.of(
            "source_page", "KGDocs",
            "limit", 10,
            "offset", 5 ) );

        verify( svc ).listProposals( isNull(), eq( "KGDocs" ), eq( 10 ), eq( 5 ) );
    }

    @Test
    void execute_passesNullInstantsThrough() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.listProposals( any(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( new KgProposal(
                UUID.randomUUID(), "new-node", "Beta",
                Map.of(), 0.5, "why", "approved", "alice", null, null,
                null, null, null, null, null ) ) );

        final McpSchema.CallToolResult result =
            new ListProposalsTool( svc ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"created\":null" ) );
        assertTrue( text.contains( "\"reviewed_at\":null" ) );
    }

    @Test
    void execute_returnsErrorResultOnServiceFailure() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.listProposals( any(), any(), anyInt(), anyInt() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result =
            new ListProposalsTool( svc ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }

    @Test
    void executeAddsNodeExistsFlagForNewNodeProposal() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.id() ).thenReturn( UUID.randomUUID() );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( java.util.Map.of( "name", "Raft" ) );
        when( p.status() ).thenReturn( "pending" );
        when( svc.listProposals( "pending", null, 50, 0 ) ).thenReturn( java.util.List.of( p ) );
        when( svc.getNodeByName( "Raft", true ) ).thenReturn( Mockito.mock( com.wikantik.api.knowledge.KgNode.class ) );

        final var result = new ListProposalsTool( svc ).execute( java.util.Map.of( "status", "pending" ) );
        final String body = ( ( io.modelcontextprotocol.spec.McpSchema.TextContent )
                result.content().get( 0 ) ).text();
        org.junit.jupiter.api.Assertions.assertTrue( body.contains( "\"node_exists\":true" ),
                "Expected node_exists flag in payload: " + body );
    }
}
