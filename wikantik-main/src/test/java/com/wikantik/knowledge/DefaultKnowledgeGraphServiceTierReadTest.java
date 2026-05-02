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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultKnowledgeGraphServiceTierReadTest {

    @Test
    void snapshotGraph_default_overload_uses_machine_tier() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.getAllNodes( any( Tier.class ) ) ).thenReturn( List.of() );
        when( repo.getAllEdges( any( Tier.class ) ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = new DefaultKnowledgeGraphService( repo );
        svc.snapshotGraph( mock( Session.class ) );

        verify( repo ).getAllNodes( Tier.MACHINE );
        verify( repo ).getAllEdges( Tier.MACHINE );
    }

    @Test
    void snapshotGraph_strict_overload_uses_human_tier() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.getAllNodes( any( Tier.class ) ) ).thenReturn( List.of() );
        when( repo.getAllEdges( any( Tier.class ) ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = new DefaultKnowledgeGraphService( repo );
        svc.snapshotGraph( mock( Session.class ), Tier.HUMAN );

        verify( repo ).getAllNodes( Tier.HUMAN );
        verify( repo ).getAllEdges( Tier.HUMAN );
    }

    @Test
    void searchKnowledge_default_overload_uses_machine_tier() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.searchNodes( any(), any(), anyInt(), any( Tier.class ) ) ).thenReturn( List.of() );

        final DefaultKnowledgeGraphService svc = new DefaultKnowledgeGraphService( repo );
        svc.searchKnowledge( "foo", null, 10 );

        verify( repo ).searchNodes( eq( "foo" ), any(), eq( 10 ), eq( Tier.MACHINE ) );
    }
}
