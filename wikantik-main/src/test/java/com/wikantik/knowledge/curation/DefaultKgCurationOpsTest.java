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
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class DefaultKgCurationOpsTest {

    private KnowledgeGraphService kg;
    private PageManager pages;
    private PageSaveHelper saver;
    private DefaultKgCurationOps ops;

    @BeforeEach
    void setUp() {
        kg = Mockito.mock( KnowledgeGraphService.class );
        pages = Mockito.mock( PageManager.class );
        saver = Mockito.mock( PageSaveHelper.class );
        ops = new DefaultKgCurationOps( kg, pages, saver );
    }

    @Test
    void approveReturnsEmptyOptionalOnSuccess() {
        final UUID id = UUID.randomUUID();
        final KgProposal approved = Mockito.mock( KgProposal.class );
        when( approved.proposalType() ).thenReturn( "new-node" );
        when( kg.approveProposal( eq( id ), eq( "alice" ) ) ).thenReturn( approved );

        assertEquals( Optional.empty(), ops.tryApproveProposal( id, "alice" ) );
    }

    @Test
    void approveReturnsNotFoundWhenServiceReturnsNull() {
        final UUID id = UUID.randomUUID();
        when( kg.approveProposal( eq( id ), eq( "alice" ) ) ).thenReturn( null );

        final Optional<String> result = ops.tryApproveProposal( id, "alice" );
        assertTrue( result.isPresent() );
        assertTrue( result.get().contains( "Not found" ) );
    }

    @Test
    void approveSurfacesServiceExceptionMessage() {
        final UUID id = UUID.randomUUID();
        when( kg.approveProposal( eq( id ), any() ) )
                .thenThrow( new RuntimeException( "constraint violation" ) );

        final Optional<String> result = ops.tryApproveProposal( id, "alice" );
        assertTrue( result.isPresent() );
        assertEquals( "constraint violation", result.get() );
    }

    @Test
    void approvingNewEdgeWritesBackToSourcePageFrontmatter() throws Exception {
        final UUID id = UUID.randomUUID();
        final KgProposal approved = Mockito.mock( KgProposal.class );
        when( approved.proposalType() ).thenReturn( "new-edge" );
        when( approved.sourcePage() ).thenReturn( "HybridRetrieval" );
        when( approved.proposedData() ).thenReturn( java.util.Map.of(
                "target", "BM25", "relationship", "falls_back_to" ) );
        when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );
        when( pages.getPureText( eq( "HybridRetrieval" ), Mockito.anyInt() ) )
                .thenReturn( "---\ntitle: Hybrid Retrieval\n---\nbody" );

        ops.tryApproveProposal( id, "alice" );

        Mockito.verify( saver ).saveText( eq( "HybridRetrieval" ), Mockito.contains( "falls_back_to" ),
                any( com.wikantik.api.pages.SaveOptions.class ) );
    }

    @Test
    void approvingNonEdgeProposalDoesNotTouchFrontmatter() {
        final UUID id = UUID.randomUUID();
        final KgProposal approved = Mockito.mock( KgProposal.class );
        when( approved.proposalType() ).thenReturn( "new-node" );
        when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );

        ops.tryApproveProposal( id, "alice" );

        Mockito.verifyNoInteractions( saver );
    }

    @Test
    void tryUpsertEdgeReturnsIdOnSuccess() {
        final UUID source = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        final UUID edgeId = UUID.randomUUID();
        final com.wikantik.api.knowledge.KgEdge edge = Mockito.mock( com.wikantik.api.knowledge.KgEdge.class );
        when( edge.id() ).thenReturn( edgeId );
        when( kg.upsertEdge( eq( source ), eq( target ), eq( "depends_on" ),
                eq( com.wikantik.api.knowledge.Provenance.HUMAN_CURATED ),
                any() ) ).thenReturn( edge );

        final KgCurationOps.EdgeResult r = ops.tryUpsertEdge( source, target, "depends_on",
                java.util.Map.of(), "alice" );
        assertTrue( r.error().isEmpty() );
        assertEquals( edgeId, r.edgeId().orElseThrow() );
    }

    @Test
    void tryUpsertEdgeReportsDuplicateKeyAsErrorMessage() {
        when( kg.upsertEdge( any(), any(), any(), any(), any() ) )
                .thenThrow( new RuntimeException( "duplicate key value violates unique constraint" ) );
        final KgCurationOps.EdgeResult r = ops.tryUpsertEdge(
                UUID.randomUUID(), UUID.randomUUID(), "rel", java.util.Map.of(), "alice" );
        assertTrue( r.error().isPresent() );
        assertTrue( r.error().get().toLowerCase().contains( "duplicate" ) );
    }

    @Test
    void tryConfirmEdgeReturnsNotFoundWhenServiceReturnsNull() {
        final UUID id = UUID.randomUUID();
        when( kg.confirmEdge( eq( id ), any() ) ).thenReturn( null );
        assertTrue( ops.tryConfirmEdge( id, "alice" ).isPresent() );
    }

    @Test
    void tryDeleteAndRejectEdgeSucceedsWhenServiceReturnsCleanly() {
        final UUID id = UUID.randomUUID();
        Mockito.doNothing().when( kg ).deleteEdgeAndRecordRejection( eq( id ), eq( "alice" ), eq( "spurious" ) );
        assertEquals( Optional.empty(), ops.tryDeleteAndRejectEdge( id, "alice", "spurious" ) );
    }
}
