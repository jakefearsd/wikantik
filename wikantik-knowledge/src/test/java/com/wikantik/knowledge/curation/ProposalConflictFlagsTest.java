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
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
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
        when( svc.getNodeByName( "Raft" ) ).thenReturn( existing );

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
        when( svc.getNodeByName( "Raft" ) ).thenReturn( null );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertFalse( flags.containsKey( "edge_previously_rejected" ) );
    }
}
