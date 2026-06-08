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
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
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
    void tryUpsertEdgeReportsExplicitRefusalWhenRepositoryRejectsMixedEdge() {
        // KgEdgeRepository.upsertEdge returns null when the mixed page/entity
        // guard rejects the write. Prior to the 2026-05-14 fix this NPE'd at
        // edge.id() and the calling MCP agent saw an opaque internal error
        // instead of the policy reason — so it kept retrying with different
        // predicates between the same endpoints.
        when( kg.upsertEdge( any(), any(), any(), any(), any() ) ).thenReturn( null );

        final KgCurationOps.EdgeResult r = ops.tryUpsertEdge(
                UUID.randomUUID(), UUID.randomUUID(), "depends_on",
                java.util.Map.of(), "alice" );

        assertTrue( r.error().isPresent(), "rejection must yield an error" );
        assertTrue( r.edgeId().isEmpty(), "no id on rejection" );
        final String msg = r.error().get().toLowerCase();
        assertTrue( msg.contains( "page/entity boundary" ) || msg.contains( "mixed page" ),
                "refusal must cite the page/entity boundary policy; got: " + r.error().get() );
    }

    @Test
    void tryUpsertEdgeReportsRelationshipVocabularyViolationCleanly() {
        // KgEdgeRepository catches SQLState 23514 (kg_edges_relationship_type_check
        // violation) and rethrows a RuntimeException with the closed-vocabulary message.
        // DefaultKgCurationOps.wrap() unwraps to the deepest cause-chain message, so the
        // agent sees the actionable hint ("Did you mean ..." / "Allowed: ...") instead of
        // an opaque PSQLException stacktrace. Mirrors the 2026-05-15 catalina.out blocker
        // where Gemini was approving proposals with relationship_type='fixes' / 'plans'.
        when( kg.upsertEdge( any(), any(), any(), any(), any() ) ).thenThrow(
                new RuntimeException(
                    "Relationship type 'fixes' is not in the closed vocabulary."
                    + " Did you mean: contains, mitigates, is_a? Allowed: related_to, part_of, ..." ) );
        final KgCurationOps.EdgeResult r = ops.tryUpsertEdge(
                UUID.randomUUID(), UUID.randomUUID(), "fixes", java.util.Map.of(), "alice" );
        assertTrue( r.error().isPresent(), "vocab violation must yield an error" );
        final String msg = r.error().get();
        assertTrue( msg.contains( "'fixes'" ), "error must cite the offending value; got: " + msg );
        assertTrue( msg.toLowerCase().contains( "closed vocabulary" ),
                "error must label the rule; got: " + msg );
        assertTrue( msg.contains( "Did you mean" ),
                "error must include suggestions; got: " + msg );
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

    private static com.wikantik.api.knowledge.KgNode node( final UUID id, final String name, final String type ) {
        return new com.wikantik.api.knowledge.KgNode( id, name, type, null,
                com.wikantik.api.knowledge.Provenance.HUMAN_CURATED, java.util.Map.of(),
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, "human", null );
    }

    @Test
    void tryUpsertEdgeRejectsOntologyNonConformantEdgeCitingShacl() {
        // person --implements--> concept violates the wk:implements domain shape (subject must be a
        // Technology). The write-time SHACL gate must refuse BEFORE kg.upsertEdge and cite the reason.
        final UUID source = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        when( kg.getNode( source ) ).thenReturn( node( source, "Alice", "person" ) );
        when( kg.getNode( target ) ).thenReturn( node( target, "Raft", "concept" ) );

        final DefaultKgCurationOps gated = new DefaultKgCurationOps(
                kg, pages, saver, null, new com.wikantik.ontology.OntologyShaclValidator() );

        final KgCurationOps.EdgeResult r = gated.tryUpsertEdge( source, target, "implements",
                java.util.Map.of(), "alice" );

        assertTrue( r.error().isPresent(), "non-conformant edge must be refused" );
        assertTrue( r.edgeId().isEmpty(), "no id when refused" );
        final String msg = r.error().get().toLowerCase();
        assertTrue( msg.contains( "ontology" ) || msg.contains( "shacl" ) || msg.contains( "shape" ),
                "refusal must cite the ontology/SHACL reason; got: " + r.error().get() );
        Mockito.verify( kg, Mockito.never() ).upsertEdge( any(), any(), any(), any(), any() );
    }

    @Test
    void tryUpsertEdgeAllowsOntologyConformantEdge() {
        final UUID source = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        final UUID edgeId = UUID.randomUUID();
        when( kg.getNode( source ) ).thenReturn( node( source, "JSPWiki", "technology" ) );
        when( kg.getNode( target ) ).thenReturn( node( target, "Raft", "concept" ) );
        final com.wikantik.api.knowledge.KgEdge edge = Mockito.mock( com.wikantik.api.knowledge.KgEdge.class );
        when( edge.id() ).thenReturn( edgeId );
        when( kg.upsertEdge( eq( source ), eq( target ), eq( "implements" ),
                eq( com.wikantik.api.knowledge.Provenance.HUMAN_CURATED ), any() ) ).thenReturn( edge );

        final DefaultKgCurationOps gated = new DefaultKgCurationOps(
                kg, pages, saver, null, new com.wikantik.ontology.OntologyShaclValidator() );

        final KgCurationOps.EdgeResult r = gated.tryUpsertEdge( source, target, "implements",
                java.util.Map.of(), "alice" );
        assertTrue( r.error().isEmpty(), "conformant edge must pass: " + r.error().orElse( "" ) );
        assertEquals( edgeId, r.edgeId().orElseThrow() );
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

    @Test
    void tryUpsertNodeReturnsIdOnSuccess() {
        final UUID nodeId = UUID.randomUUID();
        final com.wikantik.api.knowledge.KgNode node =
                Mockito.mock( com.wikantik.api.knowledge.KgNode.class );
        when( node.id() ).thenReturn( nodeId );
        when( kg.upsertNode( eq( "Raft" ), eq( "concept" ), eq( "PaxosAndRaft" ),
                eq( com.wikantik.api.knowledge.Provenance.HUMAN_AUTHORED ),
                any() ) ).thenReturn( node );

        final KgCurationOps.NodeResult r = ops.tryUpsertNode( "Raft", "concept", "PaxosAndRaft",
                java.util.Map.of(), "alice" );
        assertEquals( nodeId, r.nodeId().orElseThrow() );
    }

    @Test
    void tryUpsertNodeFilteredByPolicyReportsConflict() {
        when( kg.upsertNode( any(), any(), any(), any(), any() ) ).thenReturn( null );
        final KgCurationOps.NodeResult r = ops.tryUpsertNode( "Raft", "concept", "Excluded",
                java.util.Map.of(), "alice" );
        assertTrue( r.error().isPresent() );
        assertTrue( r.error().get().contains( "not visible after insert" ) );
    }

    @Test
    void tryMergeNodesRejectsSelfMerge() {
        final UUID id = UUID.randomUUID();
        final Optional<String> r = ops.tryMergeNodes( id, id, "alice" );
        assertTrue( r.isPresent() );
        assertTrue( r.get().toLowerCase().contains( "same" ) );
        Mockito.verifyNoInteractions( kg );  // service must not be called
    }

    @Test
    void approveSurfacesKgExcludedPagesWarningWhenPageIsOnExclusionList() {
        final KgExcludedPagesRepository excluded =
                Mockito.mock( KgExcludedPagesRepository.class );
        ops = new DefaultKgCurationOps( kg, pages, saver, excluded );

        final UUID id = UUID.randomUUID();
        final KgProposal approved = Mockito.mock( KgProposal.class );
        when( approved.proposalType() ).thenReturn( "new-node" );
        when( approved.sourcePage() ).thenReturn( "PaxosAndRaft" );
        when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );
        when( excluded.findReason( "PaxosAndRaft" ) )
                .thenReturn( Optional.of( com.wikantik.api.kgpolicy.ExclusionReason.SYSTEM_PAGE ) );

        final KgCurationOps.ApproveOutcome r = ops.tryApprove( id, "alice" );
        assertTrue( r.error().isEmpty() );
        assertEquals( List.of( "source_page is in kg_excluded_pages list" ), r.warnings() );
    }

    @Test
    void approveHasNoWarningsWhenSourcePageNotExcluded() {
        final KgExcludedPagesRepository excluded =
                Mockito.mock( KgExcludedPagesRepository.class );
        ops = new DefaultKgCurationOps( kg, pages, saver, excluded );

        final UUID id = UUID.randomUUID();
        final KgProposal approved = Mockito.mock( KgProposal.class );
        when( approved.proposalType() ).thenReturn( "new-node" );
        when( approved.sourcePage() ).thenReturn( "Active" );
        when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );
        when( excluded.findReason( "Active" ) ).thenReturn( Optional.empty() );

        final KgCurationOps.ApproveOutcome r = ops.tryApprove( id, "alice" );
        assertTrue( r.error().isEmpty() );
        assertTrue( r.warnings().isEmpty() );
    }

    // Covers DefaultKgCurationOps.java:115-118 — tryDeleteEdge happy path
    // returns Optional.empty when the service swallows the call cleanly.
    @Test
    void tryDeleteEdgeReturnsEmptyOnSuccess() {
        final UUID id = UUID.randomUUID();
        Mockito.doNothing().when( kg ).deleteEdge( id );
        assertEquals( Optional.empty(), ops.tryDeleteEdge( id, "alice" ) );
        Mockito.verify( kg ).deleteEdge( id );
    }

    // Covers DefaultKgCurationOps.java:91-97 — tryRejectProposal returns
    // "Not found: ..." when rejectProposal returns null (unknown id).
    @Test
    void tryRejectProposalReturnsNotFoundOnNull() {
        final UUID id = UUID.randomUUID();
        when( kg.rejectProposal( eq( id ), eq( "alice" ), eq( "spam" ) ) )
                .thenReturn( null );

        final Optional< String > r = ops.tryRejectProposal( id, "alice", "spam" );
        assertTrue( r.isPresent() );
        assertTrue( r.get().contains( "Not found" ),
                "rejection-of-missing-proposal must surface a not-found message: " + r.get() );
    }

    // Covers DefaultKgCurationOps.java:156 — tryMergeNodes rejects a null
    // source_id up front, before reaching kg.mergeNodes.
    @Test
    void tryMergeNodesRejectsNullSourceId() {
        final Optional< String > r = ops.tryMergeNodes( null, UUID.randomUUID(), "alice" );
        assertTrue( r.isPresent() );
        assertTrue( r.get().toLowerCase().contains( "required" ),
                "null source_id rejection must cite the requirement: " + r.get() );
        Mockito.verifyNoInteractions( kg );
    }

    // Covers DefaultKgCurationOps.java:156 — tryMergeNodes rejects a null
    // target_id up front, before reaching kg.mergeNodes.
    @Test
    void tryMergeNodesRejectsNullTargetId() {
        final Optional< String > r = ops.tryMergeNodes( UUID.randomUUID(), null, "alice" );
        assertTrue( r.isPresent() );
        assertTrue( r.get().toLowerCase().contains( "required" ),
                "null target_id rejection must cite the requirement: " + r.get() );
        Mockito.verifyNoInteractions( kg );
    }

    // Covers DefaultKgCurationOps.java:215-225 — causeChainMessage walks the
    // cause chain and returns the innermost non-blank message, so JDBC-level
    // "duplicate key" text is not masked by generic outer wrappers. The wrap()
    // helper now routes every try* op through this unwrap (not just
    // tryUpsertEdge — that's the design change documented in the spec).
    @Test
    void causeChainMessageDeepUnwrapPrefersInnermost() {
        final UUID id = UUID.randomUUID();
        final Throwable inner =
                new RuntimeException( "duplicate key value violates unique constraint" );
        final Throwable middle = new RuntimeException( "JDBC error", inner );
        final Throwable outer = new RuntimeException( "outer wrapper", middle );

        // tryConfirmEdge routes through wrap() → tryWithMessage → causeChainMessage.
        Mockito.doThrow( outer ).when( kg ).confirmEdge( eq( id ), any() );

        final Optional< String > r = ops.tryConfirmEdge( id, "alice" );
        assertTrue( r.isPresent() );
        assertEquals( "duplicate key value violates unique constraint", r.get(),
                "deepest cause message must surface, not the outer wrapper text" );
    }
}
