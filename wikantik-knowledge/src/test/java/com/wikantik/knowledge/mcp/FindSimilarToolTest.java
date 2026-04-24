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

import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FindSimilarToolTest {

    @Test
    void name_isFindSimilar() {
        assertEquals( "find_similar",
            new FindSimilarTool( mock( NodeMentionSimilarity.class ) ).name() );
    }

    @Test
    void definition_requiresNode() {
        assertTrue( new FindSimilarTool( mock( NodeMentionSimilarity.class ) )
            .definition().inputSchema().required().contains( "node" ) );
    }

    @Test
    void execute_returnsRankedSimilarNodesRoundedToThousandths() {
        final NodeMentionSimilarity sim = mock( NodeMentionSimilarity.class );
        when( sim.isReady() ).thenReturn( true );
        when( sim.similarTo( "Alpha", 10 ) ).thenReturn( List.of(
            new ScoredName( "Beta", 0.87654 ),
            new ScoredName( "Gamma", 0.5 ) ) );

        final McpSchema.CallToolResult result =
            new FindSimilarTool( sim ).execute( Map.of( "node", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"name\":\"Beta\"" ) );
        assertTrue( text.contains( "\"similarity\":0.877" ), "score rounded to 3 decimals" );
        assertTrue( text.contains( "\"name\":\"Gamma\"" ) );
        assertTrue( text.contains( "\"similarity\":0.5" ) );
    }

    @Test
    void execute_honorsLimitArgument() {
        final NodeMentionSimilarity sim = mock( NodeMentionSimilarity.class );
        when( sim.isReady() ).thenReturn( true );
        when( sim.similarTo( any(), anyInt() ) ).thenReturn(
            List.of( new ScoredName( "X", 0.5 ) ) );
        new FindSimilarTool( sim ).execute( Map.of( "node", "Alpha", "limit", 3 ) );
        verify( sim ).similarTo( "Alpha", 3 );
    }

    @Test
    void execute_returnsErrorWhenIndexNotReady() {
        final NodeMentionSimilarity sim = mock( NodeMentionSimilarity.class );
        when( sim.isReady() ).thenReturn( false );
        final McpSchema.CallToolResult result =
            new FindSimilarTool( sim ).execute( Map.of( "node", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "Embedding index not populated" ) );
    }

    @Test
    void execute_returnsErrorWhenNoVectorForNode() {
        final NodeMentionSimilarity sim = mock( NodeMentionSimilarity.class );
        when( sim.isReady() ).thenReturn( true );
        when( sim.similarTo( anyString(), anyInt() ) ).thenReturn( List.of() );
        final McpSchema.CallToolResult result =
            new FindSimilarTool( sim ).execute( Map.of( "node", "Unknown" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "No mention-centroid vector for node" ) );
        assertTrue( text.contains( "Unknown" ) );
    }

    @Test
    void execute_returnsErrorOnRuntimeFromSimilarity() {
        final NodeMentionSimilarity sim = mock( NodeMentionSimilarity.class );
        when( sim.isReady() ).thenReturn( true );
        when( sim.similarTo( anyString(), anyInt() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result =
            new FindSimilarTool( sim ).execute( Map.of( "node", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
