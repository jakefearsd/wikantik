/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class ProposalConflictFlagsTest {

    @Test
    void newNodeProposalSetsNodeExistsTrueWhenNameResolves() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );
        final KgNode existing = Mockito.mock( KgNode.class );
        when( existing.id() ).thenReturn( UUID.randomUUID() );
        when( svc.getNodeByName( "Raft", false ) ).thenReturn( existing );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertEquals( Boolean.TRUE, flags.get( "node_exists" ) );
        assertNotNull( flags.get( "existing_node_id" ) );
    }

    @Test
    void newEdgeProposalSetsPreviouslyRejectedFlagWhenIsRejectedReturnsTrue() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( "new-edge" );
        when( p.proposedData() ).thenReturn( Map.of(
                "source", "A", "target", "B", "relationship", "depends_on" ) );
        when( svc.isRejected( "A", "B", "depends_on" ) ).thenReturn( true );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertEquals( Boolean.TRUE, flags.get( "edge_previously_rejected" ) );
    }

    @Test
    void blankNameSkipsNodeExistsLookup() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( Map.of( "name", "   " ) );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertFalse( flags.containsKey( "node_exists" ) );
        Mockito.verifyNoInteractions( svc );
    }

    @Test
    void unrelatedFlagsAreOmittedNotNull() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );
        when( svc.getNodeByName( "Raft", false ) ).thenReturn( null );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertFalse( flags.containsKey( "edge_previously_rejected" ) );
    }

    @Test
    void forProposalThreadsBypassFlagThroughGetNodeByName() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        Mockito.when( p.proposalType() ).thenReturn( "new-node" );
        Mockito.when( p.proposedData() ).thenReturn( java.util.Map.of( "name", "Raft" ) );

        ProposalConflictFlags.forProposal( svc, p, true );

        Mockito.verify( svc ).getNodeByName( "Raft", true );
        Mockito.verify( svc, Mockito.never() ).getNodeByName( "Raft", false );
    }

    private static KgProposal proposal( final String type, final Map< String, Object > data ) {
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( type );
        when( p.proposedData() ).thenReturn( data );
        return p;
    }

    @Test
    void forProposalsUsesDefaultBatchMethodsAndMatchesPerProposalFlags() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class, Mockito.CALLS_REAL_METHODS );
        final KgNode existing = Mockito.mock( KgNode.class );
        when( existing.id() ).thenReturn( UUID.randomUUID() );
        Mockito.doReturn( existing ).when( svc ).getNodeByName( "Raft", true );
        Mockito.doReturn( true ).when( svc ).isRejected( "A", "B", "uses" );
        Mockito.doReturn( false ).when( svc ).isRejected( "A", "C", "uses" );
        final java.util.List< KgProposal > ps = java.util.List.of(
            proposal( "new-node", Map.of( "name", "Raft" ) ),
            proposal( "new-node", Map.of( "name", "Paxos" ) ),
            proposal( "new-edge", Map.of( "source", "A", "target", "B", "relationship", "uses" ) ),
            proposal( "new-edge", Map.of( "source", "A", "target", "C", "relationship", "uses" ) ),
            proposal( "other", Map.of() ) );

        final java.util.List< Map< String, Object > > batch = ProposalConflictFlags.forProposals( svc, ps, true );

        assertEquals( ps.size(), batch.size() );
        for ( int i = 0; i < ps.size(); i++ ) {
            assertEquals( ProposalConflictFlags.forProposal( svc, ps.get( i ), true ), batch.get( i ) );
        }
        assertEquals( Boolean.TRUE, batch.get( 0 ).get( "node_exists" ) );
        assertEquals( Boolean.FALSE, batch.get( 1 ).get( "node_exists" ) );
        assertEquals( Boolean.TRUE, batch.get( 2 ).get( "edge_previously_rejected" ) );
        assertEquals( Boolean.FALSE, batch.get( 3 ).get( "edge_previously_rejected" ) );
        assertTrue( batch.get( 4 ).isEmpty() );
    }

    @Test
    void forProposalsFallsBackToPerProposalWhenBatchLookupFails() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        when( svc.getNodesByNames( Mockito.anyCollection(), Mockito.anyBoolean() ) )
            .thenThrow( new RuntimeException( "db down" ) );
        final KgNode existing = Mockito.mock( KgNode.class );
        when( existing.id() ).thenReturn( UUID.randomUUID() );
        when( svc.getNodeByName( "Raft", true ) ).thenReturn( existing );

        final java.util.List< Map< String, Object > > batch = ProposalConflictFlags.forProposals( svc,
            java.util.List.of( proposal( "new-node", Map.of( "name", "Raft" ) ) ), true );

        assertEquals( Boolean.TRUE, batch.get( 0 ).get( "node_exists" ) );
    }
}
