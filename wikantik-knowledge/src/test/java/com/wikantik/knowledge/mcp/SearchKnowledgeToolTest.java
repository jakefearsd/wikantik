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

class SearchKnowledgeToolTest {

    private static KgNode node( final String name, final UUID id ) {
        return new KgNode( id, name, "Concept", name + "Page",
            Provenance.HUMAN_AUTHORED, Map.of(),
            Instant.parse( "2026-04-24T08:00:00Z" ),
            Instant.parse( "2026-04-24T08:00:00Z" ) );
    }

    @Test
    void name_isSearchKnowledge() {
        assertEquals( "search_knowledge",
            new SearchKnowledgeTool( mock( KnowledgeGraphService.class ) ).name() );
    }

    @Test
    void definition_requiresQuery() {
        assertTrue( new SearchKnowledgeTool( mock( KnowledgeGraphService.class ) )
            .definition().inputSchema().required().contains( "query" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void definition_carriesPhase6WorkedExamples() throws Exception {
        // AG-Phase 6: per-property inputSchema examples + top-level outputSchema examples.
        // The search_knowledge tool is the design doc's canonical specimen — its example
        // shape needs to land in the wire JSON or agents read a typed-only schema.
        final McpSchema.Tool def = new SearchKnowledgeTool( mock( KnowledgeGraphService.class ) ).definition();

        final Map< String, Object > queryProp = (Map< String, Object >) def.inputSchema().properties().get( "query" );
        assertTrue( queryProp.containsKey( "examples" ),
                "input property 'query' must advertise examples" );
        final List< ? > queryExamples = (List< ? >) queryProp.get( "examples" );
        assertFalse( queryExamples.isEmpty() );

        assertNotNull( def.outputSchema(), "outputSchema must be populated for Phase 6" );
        assertTrue( def.outputSchema().containsKey( "examples" ) );
        final List< ? > outExamples = (List< ? >) def.outputSchema().get( "examples" );
        assertFalse( outExamples.isEmpty() );

        // Phase 6 wire-JSON smoke: serialise the tool exactly as the MCP transport would
        // and assert the canonical JSON Schema 'examples' keyword survives both ends.
        final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        final String wireJson = mapper.writeValueAsString( def );
        assertTrue( wireJson.contains( "\"examples\"" ),
                "wire JSON must carry the 'examples' keyword for agents — got: " + wireJson );
        assertTrue( wireJson.contains( "hybrid retrieval" ),
                "wire JSON must include the design-doc canonical example value 'hybrid retrieval'" );
    }

    @Test
    void execute_returnsUnfilteredWhenMentionIndexAbsent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final KgNode a = node( "Alpha", UUID.randomUUID() );
        when( svc.searchKnowledge( eq( "alph" ), any(), anyInt() ) )
            .thenReturn( List.of( a ) );

        final McpSchema.CallToolResult result =
            new SearchKnowledgeTool( svc ).execute( Map.of( "query", "alph" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
    }

    @Test
    void execute_filtersOutUnmentionedNodesWhenIndexPresent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final UUID aId = UUID.randomUUID();
        final UUID bId = UUID.randomUUID();
        when( svc.searchKnowledge( any(), any(), anyInt() ) ).thenReturn(
            List.of( node( "Alpha", aId ), node( "Beta", bId ) ) );
        final MentionIndex idx = mock( MentionIndex.class );
        when( idx.isMentioned( aId ) ).thenReturn( false );
        when( idx.isMentioned( bId ) ).thenReturn( true );

        final McpSchema.CallToolResult result = new SearchKnowledgeTool( svc, idx )
            .execute( Map.of( "query", "q" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( text.contains( "Alpha" ) );
        assertTrue( text.contains( "Beta" ) );
    }

    @Test
    void execute_passesLimitAndProvenanceFilter() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.searchKnowledge( any(), any(), anyInt() ) ).thenReturn( List.of() );

        final Map< String, Object > args = new HashMap<>();
        args.put( "query", "q" );
        args.put( "provenance_filter", List.of( "ai-reviewed" ) );
        args.put( "limit", 7 );
        new SearchKnowledgeTool( svc ).execute( args );
        verify( svc ).searchKnowledge( eq( "q" ), eq( Set.of( Provenance.AI_REVIEWED ) ), eq( 7 ) );
    }

    @Test
    void execute_appliesDefaultLimitWhenAbsent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.searchKnowledge( any(), any(), anyInt() ) ).thenReturn( List.of() );
        new SearchKnowledgeTool( svc ).execute( Map.of( "query", "q" ) );
        verify( svc ).searchKnowledge( eq( "q" ), isNull(), eq( 20 ) );
    }

    @Test
    void execute_returnsErrorOnServiceFailure() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.searchKnowledge( any(), any(), anyInt() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result = new SearchKnowledgeTool( svc )
            .execute( Map.of( "query", "q" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
