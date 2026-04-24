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

import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.SchemaDescription;
import com.wikantik.knowledge.MentionIndex;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoverSchemaToolTest {

    @Test
    void name_isDiscoverSchema() {
        assertEquals( "discover_schema",
            new DiscoverSchemaTool( mock( KnowledgeGraphService.class ) ).name() );
    }

    @Test
    void definition_requiresNothing() {
        assertTrue( new DiscoverSchemaTool( mock( KnowledgeGraphService.class ) )
            .definition().inputSchema().required().isEmpty() );
    }

    @Test
    void execute_returnsBareSchemaWhenMentionIndexAbsent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.discoverSchema() ).thenReturn( new SchemaDescription(
            List.of( "Concept" ),
            List.of( "relatedTo" ),
            List.of( "active" ),
            Map.of(), new SchemaDescription.Stats( 5, 3, 0 ) ) );

        final McpSchema.CallToolResult result =
            new DiscoverSchemaTool( svc ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"nodeTypes\"" ) );
        assertTrue( text.contains( "Concept" ) );
        assertFalse( text.contains( "mentionedNodeCount" ),
            "no mention index → no coverage stat emitted" );
    }

    @Test
    void execute_wrapsSchemaAndAppendsMentionCoverageWhenIndexPresent() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.discoverSchema() ).thenReturn( new SchemaDescription(
            List.of( "Concept" ), List.of(), List.of(),
            Map.of(), new SchemaDescription.Stats( 1, 0, 0 ) ) );
        final MentionIndex idx = mock( MentionIndex.class );
        when( idx.getMentionedIds() ).thenReturn( Set.of( UUID.randomUUID(), UUID.randomUUID() ) );

        final McpSchema.CallToolResult result =
            new DiscoverSchemaTool( svc, idx ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"schema\":" ) );
        assertTrue( text.contains( "\"mentionedNodeCount\":2" ) );
    }

    @Test
    void execute_returnsErrorOnServiceRuntimeException() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.discoverSchema() ).thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result =
            new DiscoverSchemaTool( svc ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }
}
