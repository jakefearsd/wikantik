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
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the policy-exclusion null-guard in
 * {@link KgMaterializationService#materialize}.
 *
 * <p>{@code upsertNodeWithProvenance} writes the row, then reads it back
 * through the inclusion-policy filter. When the source page is excluded
 * from the KG, the read-back returns null. Materialise must skip the
 * edge (no NPE, no edge insert) and log the reason.
 */
class KgMaterializationServiceNullGuardTest {

    @Test
    void materialize_skips_edge_when_target_is_filtered_out() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        final KgNode src = new KgNode( UUID.randomUUID(), "Alpha", "concept", null,
            Provenance.AI_INFERRED, Map.of(), Instant.now(), Instant.now(), "machine", null );
        when( repo.upsertNodeWithProvenance( eq( "Alpha" ), anyString(), any(), any(), any(), anyString(), any() ) )
            .thenReturn( src );
        when( repo.upsertNodeWithProvenance( eq( "Beta" ),  anyString(), any(), any(), any(), anyString(), any() ) )
            .thenReturn( null );

        final KgProposal proposal = new KgProposal(
            UUID.randomUUID(),                  // id
            "new-edge",                         // proposalType
            "Page",                             // sourcePage
            Map.of( "source", "Alpha", "target", "Beta", "relationship", "depends_on" ), // proposedData
            0.8,                                // confidence
            "reason",                           // reasoning
            "pending",                          // status
            null,                               // reviewedBy
            Instant.now(),                      // created
            null,                               // reviewedAt
            "none",                             // tier
            null,                               // machineStatus
            null,                               // machineConfidence
            null,                               // machineJudgedAt
            null );                             // machineModel

        final KgMaterializationService svc = new KgMaterializationService( repo );
        svc.materializeMachine( proposal ); // must not throw

        // Edge upsert must NOT be called when either node was filtered out.
        verify( repo, never() ).upsertEdgeWithProvenance( any(), any(), anyString(),
            any(), any(), anyString(), any() );
    }

    @Test
    void materialize_skips_edge_when_source_is_filtered_out() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        final KgNode tgt = new KgNode( UUID.randomUUID(), "Beta", "concept", null,
            Provenance.AI_INFERRED, Map.of(), Instant.now(), Instant.now(), "machine", null );
        when( repo.upsertNodeWithProvenance( eq( "Alpha" ), anyString(), any(), any(), any(), anyString(), any() ) )
            .thenReturn( null );
        when( repo.upsertNodeWithProvenance( eq( "Beta" ),  anyString(), any(), any(), any(), anyString(), any() ) )
            .thenReturn( tgt );

        final KgProposal proposal = new KgProposal(
            UUID.randomUUID(),                  // id
            "new-edge",                         // proposalType
            "Page",                             // sourcePage
            Map.of( "source", "Alpha", "target", "Beta", "relationship", "depends_on" ), // proposedData
            0.8,                                // confidence
            "reason",                           // reasoning
            "pending",                          // status
            null,                               // reviewedBy
            Instant.now(),                      // created
            null,                               // reviewedAt
            "none",                             // tier
            null,                               // machineStatus
            null,                               // machineConfidence
            null,                               // machineJudgedAt
            null );                             // machineModel

        final KgMaterializationService svc = new KgMaterializationService( repo );
        svc.materializeMachine( proposal ); // must not throw

        verify( repo, never() ).upsertEdgeWithProvenance( any(), any(), anyString(),
            any(), any(), anyString(), any() );
    }
}
