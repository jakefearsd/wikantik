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
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class InspectProposalsToolTest {

    private KnowledgeGraphService svc;
    private InspectProposalsTool tool;

    @BeforeEach void setUp() {
        svc = Mockito.mock( KnowledgeGraphService.class );
        tool = new InspectProposalsTool( svc, 50 );
    }

    @Test
    void capExceededReturnsTopLevelError() {
        final List< String > ids = new ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ids.add( UUID.randomUUID().toString() );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", ids ) );
        assertTrue( r.isError() );
    }

    @Test
    void unknownIdLandsInMissingArray() {
        final UUID id = UUID.randomUUID();
        when( svc.getProposal( id ) ).thenReturn( null );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", List.of( id.toString() ) ) );
        assertFalse( r.isError() );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"missing\":[\"" + id + "\"]" ),
                "Expected unknown id in missing[]: " + body );
    }

    @Test
    void resolvedProposalIncludesConflictsAndPriorReviews() {
        final UUID id = UUID.randomUUID();
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.id() ).thenReturn( id );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );
        when( svc.getProposal( id ) ).thenReturn( p );
        when( svc.listReviews( id ) ).thenReturn( List.of() );
        when( svc.getNodeByName( "Raft", true ) ).thenReturn( null );

        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", List.of( id.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"node_exists\":false" ), body );
        assertTrue( body.contains( "\"prior_reviews\":[]" ), body );
    }

    @Test
    void invalidUuidLandsInMissingArrayNotTopLevel() {
        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", List.of( "not-a-uuid" ) ) );
        assertFalse( r.isError() );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"missing\":[\"not-a-uuid\"]" ), body );
    }
}
