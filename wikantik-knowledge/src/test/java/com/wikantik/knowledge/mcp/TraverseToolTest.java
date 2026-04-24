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
import com.wikantik.api.knowledge.TraversalResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TraverseToolTest {

    @Test
    void name_isTraverse() {
        assertEquals( "traverse",
            new TraverseTool( mock( KnowledgeGraphService.class ) ).name() );
    }

    @Test
    void definition_requiresStartNode() {
        assertTrue( new TraverseTool( mock( KnowledgeGraphService.class ) )
            .definition().inputSchema().required().contains( "start_node" ) );
    }

    @Test
    void execute_returnsJsonWithNodesAndEdges() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final UUID aId = UUID.randomUUID();
        final UUID bId = UUID.randomUUID();
        final KgNode a = new KgNode( aId, "Alpha", "Concept", "AlphaPage",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T10:00:00Z" ),
            Instant.parse( "2026-04-24T10:00:00Z" ) );
        final KgNode b = new KgNode( bId, "Beta", "Concept", "BetaPage",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T10:00:00Z" ),
            Instant.parse( "2026-04-24T10:00:00Z" ) );
        final KgEdge edge = new KgEdge( UUID.randomUUID(), aId, bId, "coMention",
            Provenance.AI_REVIEWED, Map.of( "sharedChunks", 4 ),
            Instant.now(), Instant.now() );
        when( svc.traverseByCoMention( "Alpha", 2, 1 ) )
            .thenReturn( new TraversalResult( List.of( a, b ), List.of( edge ) ) );

        final McpSchema.CallToolResult result =
            new TraverseTool( svc ).execute( Map.of( "start_node", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Alpha" ) );
        assertTrue( text.contains( "Beta" ) );
        assertTrue( text.contains( "coMention" ) );
        assertTrue( text.contains( "sharedChunks" ) );
    }

    @Test
    void execute_passesMaxDepthAndMinSharedChunksDefaults() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.traverseByCoMention( any(), anyInt(), anyInt() ) )
            .thenReturn( new TraversalResult( List.of(), List.of() ) );
        new TraverseTool( svc ).execute( Map.of( "start_node", "Alpha" ) );
        verify( svc ).traverseByCoMention( "Alpha", 2, 1 );
    }

    @Test
    void execute_honorsExplicitMaxDepthAndMinSharedChunks() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.traverseByCoMention( any(), anyInt(), anyInt() ) )
            .thenReturn( new TraversalResult( List.of(), List.of() ) );
        new TraverseTool( svc ).execute( Map.of(
            "start_node", "Alpha", "max_depth", 4, "min_shared_chunks", 3 ) );
        verify( svc ).traverseByCoMention( "Alpha", 4, 3 );
    }

    @Test
    void execute_returnsErrorOnServiceFailure() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.traverseByCoMention( any(), anyInt(), anyInt() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result =
            new TraverseTool( svc ).execute( Map.of( "start_node", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
