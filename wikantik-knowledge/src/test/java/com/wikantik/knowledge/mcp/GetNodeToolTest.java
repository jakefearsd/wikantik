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

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GetNodeToolTest {

    @Test
    void name_isGetNode() {
        assertEquals( "get_node",
            new GetNodeTool( mock( KnowledgeGraphService.class ) ).name() );
    }

    @Test
    void definition_requiresNode() {
        assertTrue( new GetNodeTool( mock( KnowledgeGraphService.class ) )
            .definition().inputSchema().required().contains( "node" ) );
    }

    @Test
    void execute_resolvesByNameAndReturnsEdges() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final UUID id = UUID.randomUUID();
        final KgNode node = new KgNode( id, "Alpha", "Concept", "AlphaPage",
            Provenance.HUMAN_AUTHORED, Map.of( "summary", "summary" ),
            Instant.parse( "2026-04-24T09:00:00Z" ), Instant.parse( "2026-04-24T10:00:00Z" ) );
        when( svc.getNodeByName( "Alpha" ) ).thenReturn( node );
        when( svc.getEdgesForNode( id, "outbound" ) ).thenReturn( List.of(
            new KgEdge( UUID.randomUUID(), id, UUID.randomUUID(), "relatedTo",
                Provenance.AI_REVIEWED, Map.of(), Instant.now(), Instant.now() ) ) );
        when( svc.getEdgesForNode( id, "inbound" ) ).thenReturn( List.of() );

        final McpSchema.CallToolResult result =
            new GetNodeTool( svc ).execute( Map.of( "node", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"outbound_edges\":" ) );
        assertTrue( text.contains( "\"inbound_edges\":" ) );
        assertTrue( text.contains( "relatedTo" ) );
    }

    @Test
    void execute_fallsBackToUuidLookupWhenNameMisses() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final UUID id = UUID.fromString( "11111111-2222-3333-4444-555555555555" );
        final KgNode node = new KgNode( id, "Alpha", "Concept", "AlphaPage",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T09:00:00Z" ), Instant.parse( "2026-04-24T09:00:00Z" ) );
        when( svc.getNodeByName( id.toString() ) ).thenReturn( null );
        when( svc.getNode( id ) ).thenReturn( node );
        when( svc.getEdgesForNode( eq( id ), anyString() ) ).thenReturn( List.of() );

        final McpSchema.CallToolResult result =
            new GetNodeTool( svc ).execute( Map.of( "node", id.toString() ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        verify( svc ).getNode( id );
    }

    @Test
    void execute_returnsNotFoundWhenNeitherNameNorUuidMatches() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.getNodeByName( anyString() ) ).thenReturn( null );
        final McpSchema.CallToolResult result =
            new GetNodeTool( svc ).execute( Map.of( "node", "nonsense-ref" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Node not found" ) );
        assertTrue( text.contains( "nonsense-ref" ) );
        // never asked for getNode(id) because ref is not a valid UUID
        verify( svc, never() ).getNode( any() );
    }

    @Test
    void execute_returnsErrorOnServiceRuntimeException() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.getNodeByName( anyString() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result =
            new GetNodeTool( svc ).execute( Map.of( "node", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
