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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.MentionIndex;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryNodesToolTest {

    private static KgNode node( final String name, final UUID id ) {
        return new KgNode( id, name, "Concept", name + "Page",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T08:00:00Z" ),
            Instant.parse( "2026-04-24T09:00:00Z" ) );
    }

    @Test
    void name_isQueryNodes() {
        assertEquals( "query_nodes",
            new QueryNodesTool( mock( KnowledgeGraphService.class ) ).name() );
    }

    @Test
    void definition_hasNoRequiredArgs() {
        assertTrue( new QueryNodesTool( mock( KnowledgeGraphService.class ) )
            .definition().inputSchema().required().isEmpty() );
    }

    @Test
    void execute_returnsUnfilteredResultsWhenMentionIndexAbsent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final KgNode a = node( "Alpha", UUID.randomUUID() );
        final KgNode b = node( "Beta", UUID.randomUUID() );
        when( svc.queryNodes( anyMap(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( a, b ) );

        final McpSchema.CallToolResult result = new QueryNodesTool( svc ).execute(
            Map.of( "filters", Map.of( "node_type", "Concept" ) ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Alpha" ) );
        assertTrue( text.contains( "Beta" ) );
    }

    @Test
    void execute_filtersOutUnmentionedNodesWhenIndexPresent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final UUID aId = UUID.randomUUID();
        final UUID bId = UUID.randomUUID();
        when( svc.queryNodes( any(), any(), anyInt(), anyInt() ) )
            .thenReturn( List.of( node( "Alpha", aId ), node( "Beta", bId ) ) );

        final MentionIndex idx = mock( MentionIndex.class );
        when( idx.isMentioned( aId ) ).thenReturn( true );
        when( idx.isMentioned( bId ) ).thenReturn( false );

        final McpSchema.CallToolResult result =
            new QueryNodesTool( svc, idx ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Alpha" ), "mentioned node kept" );
        assertFalse( text.contains( "Beta" ), "unmentioned node filtered out" );
    }

    @Test
    void execute_passesLimitOffsetAndProvenanceFilter() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.queryNodes( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "provenance_filter", List.of( "human-authored" ) );
        args.put( "limit", 12 );
        args.put( "offset", 3 );
        new QueryNodesTool( svc ).execute( args );

        verify( svc ).queryNodes(
            isNull(),
            eq( Set.of( Provenance.HUMAN_AUTHORED ) ),
            eq( 12 ), eq( 3 ) );
    }

    @Test
    void execute_appliesDefaultLimitAndOffsetWhenAbsent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.queryNodes( any(), any(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        new QueryNodesTool( svc ).execute( Map.of() );
        verify( svc ).queryNodes( isNull(), isNull(), eq( 50 ), eq( 0 ) );
    }

    @Test
    void execute_returnsErrorOnServiceFailure() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.queryNodes( any(), any(), anyInt(), anyInt() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result = new QueryNodesTool( svc ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
