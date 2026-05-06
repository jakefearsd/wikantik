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
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.SnapshotNode;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultKnowledgeGraphServiceSnapshotTierTest {

    @Test
    void snapshotPopulatesTierOnEachNode() {
        final UUID id = UUID.randomUUID();
        final KgNode kgNode = new KgNode(
            id, "AlanTuring", "person",
            "AlanTuring",
            Provenance.AI_INFERRED,
            Map.of(),
            Instant.now(), Instant.now(),
            "human",
            null
        );

        final KgNodeRepository nodes = mock( KgNodeRepository.class );
        final KgEdgeRepository edges = mock( KgEdgeRepository.class );
        when( nodes.getAllNodes( any( Tier.class ) ) ).thenReturn( List.of( kgNode ) );
        when( edges.getAllEdges( any( Tier.class ) ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = new DefaultKnowledgeGraphService(
            nodes, edges, mock( KgProposalRepository.class ), mock( KgRejectionRepository.class ), null );
        final GraphSnapshot snap = svc.snapshotGraph( null, Tier.HUMAN );

        assertEquals( 1, snap.nodes().size() );
        final SnapshotNode out = snap.nodes().get( 0 );
        assertNotNull( out.tier() );
        assertEquals( "human", out.tier() );
    }
}
