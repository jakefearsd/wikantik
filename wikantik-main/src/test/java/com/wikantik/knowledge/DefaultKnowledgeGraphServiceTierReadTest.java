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

import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.GraphSnapshot;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultKnowledgeGraphServiceTierReadTest {

    private static DefaultKnowledgeGraphService newSvc( final KgNodeRepository nodes,
                                                         final KgEdgeRepository edges ) {
        return new DefaultKnowledgeGraphService(
            nodes, edges,
            mock( KgProposalRepository.class ), mock( KgRejectionRepository.class ),
            null );
    }

    @Test
    void snapshotGraph_default_overload_uses_machine_tier() {
        final KgNodeRepository nodes = mock( KgNodeRepository.class );
        final KgEdgeRepository edges = mock( KgEdgeRepository.class );
        when( nodes.getAllNodes( any( Tier.class ) ) ).thenReturn( List.of() );
        when( edges.getAllEdges( any( Tier.class ) ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = newSvc( nodes, edges );
        svc.snapshotGraph( mock( Session.class ) );

        verify( nodes ).getAllNodes( Tier.MACHINE );
        verify( edges ).getAllEdges( Tier.MACHINE );
    }

    @Test
    void snapshotGraph_strict_overload_uses_human_tier() {
        final KgNodeRepository nodes = mock( KgNodeRepository.class );
        final KgEdgeRepository edges = mock( KgEdgeRepository.class );
        when( nodes.getAllNodes( any( Tier.class ) ) ).thenReturn( List.of() );
        when( edges.getAllEdges( any( Tier.class ) ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = newSvc( nodes, edges );
        svc.snapshotGraph( mock( Session.class ), Tier.HUMAN );

        verify( nodes ).getAllNodes( Tier.HUMAN );
        verify( edges ).getAllEdges( Tier.HUMAN );
    }

    @Test
    void searchKnowledge_default_overload_uses_machine_tier() {
        final KgNodeRepository nodes = mock( KgNodeRepository.class );
        when( nodes.searchNodes( any(), any(), anyInt(), any( Tier.class ) ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = newSvc( nodes, mock( KgEdgeRepository.class ) );
        svc.searchKnowledge( "foo", null, 10 );

        verify( nodes ).searchNodes( eq( "foo" ), any(), eq( 10 ), eq( Tier.MACHINE ) );
    }
}
