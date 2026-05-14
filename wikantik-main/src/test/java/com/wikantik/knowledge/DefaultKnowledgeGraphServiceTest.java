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

import com.wikantik.PostgresTestContainer;
import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class DefaultKnowledgeGraphServiceTest {

    private static DataSource dataSource;
    private static TestEngine engine;
    private DefaultKnowledgeGraphService service;

    @BeforeAll
    static void initDataSource() throws Exception {
        dataSource = PostgresTestContainer.createDataSource();
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        service = new DefaultKnowledgeGraphService(
            new KgNodeRepository( dataSource ),
            new KgEdgeRepository( dataSource ),
            new KgProposalRepository( dataSource ),
            new KgRejectionRepository( dataSource ),
            dataSource, engine );
    }

    private Session adminSession() throws Exception {
        return WikiSessionTest.adminSession( engine );
    }

    @Test
    void discoverSchema_returnsCorrectCounts() {
        service.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        service.upsertNode( "Customer", "domain-model", "Customer.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        final SchemaDescription schema = service.discoverSchema();
        assertTrue( schema.nodeTypes().contains( "domain-model" ) );
        assertEquals( 2, schema.stats().nodes() );
    }

    @Test
    void discoverSchema_pendingBreakdown_splitsByTypeAndJudgeStatus() throws Exception {
        service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "Inventory", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );

        service.submitProposal( "new-node", "Order.md",
            Map.of( "name", "Region", "node_type", "concept" ), 0.6, "test" );
        service.submitProposal( "new-node", "Order.md",
            Map.of( "name", "Currency", "node_type", "concept" ), 0.7, "test" );
        service.submitProposal( "new-edge", "Order.md",
            Map.of( "source", "Order", "target", "Inventory", "relationship", "depends-on" ),
            0.8, "test" );

        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute(
                "UPDATE kg_proposals SET machine_status = 'approved' " +
                "WHERE proposed_data::text LIKE '%Region%'" );
            conn.createStatement().execute(
                "UPDATE kg_proposals SET machine_status = 'abstain' " +
                "WHERE proposed_data::text LIKE '%Currency%'" );
        }

        final SchemaDescription.PendingBreakdown b =
            service.discoverSchema().stats().pendingBreakdown();
        assertEquals( 3, b.total(), "three pending overall" );
        assertEquals( 2, b.newNodes(), "two new-node proposals" );
        assertEquals( 1, b.newEdges(), "one new-edge proposal" );
        assertEquals( 1, b.judgeApproved(), "Region was judge-approved" );
        assertEquals( 1, b.judgeAbstained(), "Currency was judge-abstained" );
        assertEquals( 1, b.unjudged(), "the edge proposal hasn't been judged" );
    }

    @Test
    void traverse_findsConnectedNodes() {
        final KgNode order = service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode customer = service.upsertNode( "Customer", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode address = service.upsertNode( "Address", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( order.id(), customer.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( customer.id(), address.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        final TraversalResult result = service.traverse( "Order", "outbound",
                Set.of(), 3, Set.of( Provenance.HUMAN_AUTHORED ) );
        assertEquals( 3, result.nodes().size() );
        assertEquals( 2, result.edges().size() );
    }

    @Test
    void traverse_respectsMaxDepth() {
        final KgNode a = service.upsertNode( "A", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = service.upsertNode( "B", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = service.upsertNode( "C", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "r", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( b.id(), c.id(), "r", Provenance.HUMAN_AUTHORED, Map.of() );
        final TraversalResult result = service.traverse( "A", "outbound", Set.of(), 1, null );
        assertEquals( 2, result.nodes().size() ); // A and B only
        assertEquals( 1, result.edges().size() );
    }

    @Test
    void traverse_filtersRelationshipTypes() {
        final KgNode a = service.upsertNode( "A", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = service.upsertNode( "B", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = service.upsertNode( "C", "t", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), c.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        final TraversalResult result = service.traverse( "A", "outbound",
                Set.of( "depends-on" ), 5, null );
        assertEquals( 2, result.nodes().size() ); // A and B only
    }

    @Test
    void approveProposal_returnsNullForMissingId() {
        // Approving a non-existent UUID must return null cleanly, not throw a
        // FK violation. The MCP facade converts null to a per-id "Not found" error.
        final java.util.UUID missing = java.util.UUID.fromString( "00000000-0000-0000-0000-000000000000" );
        assertNull( service.approveProposal( missing, "admin" ),
                "approveProposal with unknown id should return null, not throw" );
    }

    @Test
    void rejectProposal_returnsNullForMissingId() {
        // Rejecting a non-existent UUID must return null cleanly.
        final java.util.UUID missing = java.util.UUID.fromString( "00000000-0000-0000-0000-000000000000" );
        assertNull( service.rejectProposal( missing, "admin", "no reason needed" ),
                "rejectProposal with unknown id should return null, not throw" );
    }

    @Test
    void approveProposal_throwsWhenAlreadyApproved() {
        // Spec §6: approving an already-approved proposal must throw
        // IllegalStateException with the exact message wording.
        final KgProposal proposal = service.submitProposal( "new-node", "Test.md",
                Map.of( "name", "DoubleApproveNode", "nodeType", "concept" ), 0.8, "test" );
        // First approval — succeeds
        service.approveProposal( proposal.id(), "admin" );
        // Second approval on the now-approved proposal — must throw
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> service.approveProposal( proposal.id(), "admin" ),
                "approveProposal on already-approved proposal should throw IllegalStateException" );
        assertTrue( ex.getMessage().contains( "proposal already reviewed: status=approved" ),
                "Exception message must match spec wording, got: " + ex.getMessage() );
    }

    @Test
    void approveProposal_throwsWhenAlreadyRejected() {
        final KgProposal proposal = service.submitProposal( "new-node", "Test.md",
                Map.of( "name", "ApproveAfterRejectNode", "nodeType", "concept" ), 0.8, "test" );
        service.rejectProposal( proposal.id(), "admin", "not valid" );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> service.approveProposal( proposal.id(), "admin" ),
                "approveProposal on already-rejected proposal should throw IllegalStateException" );
        assertTrue( ex.getMessage().contains( "proposal already reviewed: status=rejected" ),
                "Exception message must match spec wording, got: " + ex.getMessage() );
    }

    @Test
    void rejectProposal_throwsWhenAlreadyRejected() {
        final KgProposal proposal = service.submitProposal( "new-node", "Test.md",
                Map.of( "name", "DoubleRejectNode", "nodeType", "concept" ), 0.8, "test" );
        service.rejectProposal( proposal.id(), "admin", "first rejection" );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> service.rejectProposal( proposal.id(), "admin", "second attempt" ),
                "rejectProposal on already-rejected proposal should throw IllegalStateException" );
        assertTrue( ex.getMessage().contains( "proposal already reviewed: status=rejected" ),
                "Exception message must match spec wording, got: " + ex.getMessage() );
    }

    @Test
    void rejectProposal_throwsWhenAlreadyApproved() {
        final KgProposal proposal = service.submitProposal( "new-node", "Test.md",
                Map.of( "name", "RejectAfterApproveNode", "nodeType", "concept" ), 0.8, "test" );
        service.approveProposal( proposal.id(), "admin" );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> service.rejectProposal( proposal.id(), "admin", "too late" ),
                "rejectProposal on already-approved proposal should throw IllegalStateException" );
        assertTrue( ex.getMessage().contains( "proposal already reviewed: status=approved" ),
                "Exception message must match spec wording, got: " + ex.getMessage() );
    }

    // --- mergeNodes existence-check tests ---

    @Test
    void mergeNodes_throwsWhenSourceMissing() {
        // Source UUID does not exist; target does.
        final java.util.UUID source = java.util.UUID.fromString( "00000000-dead-0000-0000-000000000001" );
        final KgNode target = service.upsertNode( "MergeTargetNode", "concept", "Test.md",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final IllegalStateException e = assertThrows( IllegalStateException.class,
                () -> service.mergeNodes( source, target.id() ) );
        assertTrue( e.getMessage().contains( "merge source not found" ), e.getMessage() );
    }

    @Test
    void mergeNodes_throwsWhenTargetMissing() {
        // Source exists; target UUID does not.
        final KgNode source = service.upsertNode( "MergeSourceNode", "concept", "Test.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final java.util.UUID target = java.util.UUID.fromString( "00000000-dead-0000-0000-000000000002" );

        final IllegalStateException e = assertThrows( IllegalStateException.class,
                () -> service.mergeNodes( source.id(), target ) );
        assertTrue( e.getMessage().contains( "merge target not found" ), e.getMessage() );
    }

    @Test
    void mergeNodes_succeedsWhenBothPresent() {
        // Both nodes exist; merge should transfer edges and delete source.
        final KgNode source = service.upsertNode( "MergeSrcNode2", "concept", "Test.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode target = service.upsertNode( "MergeTgtNode2", "concept", "Test.md",
                Provenance.HUMAN_AUTHORED, Map.of() );

        assertDoesNotThrow( () -> service.mergeNodes( source.id(), target.id() ) );
        // After merge, source node should no longer exist.
        assertNull( service.getNode( source.id() ),
                "Source node should be deleted after successful merge" );
    }

    @Test
    void approveProposal_happyPath_pendingProposalSucceeds() {
        final KgProposal proposal = service.submitProposal( "new-node", "Test.md",
                Map.of( "name", "HappyApproveNode", "nodeType", "concept" ), 0.9, "test" );
        final KgProposal approved = service.approveProposal( proposal.id(), "admin" );
        assertNotNull( approved, "approveProposal on pending proposal should return non-null" );
        assertEquals( "approved", approved.status(), "approved proposal status should be 'approved'" );
    }

    @Test
    void rejectProposal_happyPath_pendingProposalSucceeds() {
        final KgProposal proposal = service.submitProposal( "new-node", "Test.md",
                Map.of( "name", "HappyRejectNode", "nodeType", "concept" ), 0.9, "test" );
        final KgProposal rejected = service.rejectProposal( proposal.id(), "admin", "not valid" );
        assertNotNull( rejected, "rejectProposal on pending proposal should return non-null" );
        assertEquals( "rejected", rejected.status(), "rejected proposal status should be 'rejected'" );
    }

    @Test
    void submitProposal_rejectedIfPreviouslyRejected() {
        service.upsertNode( "Order", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertNode( "Inventory", "dm", null, Provenance.HUMAN_AUTHORED, Map.of() );
        // First proposal, then reject it
        final KgProposal first = service.submitProposal( "new-edge", "Order.md",
            Map.of( "source", "Order", "target", "Inventory", "relationship", "depends-on" ),
            0.7, "test" );
        service.rejectProposal( first.id(), "admin", "Not real" );
        // Second proposal for same relationship should return null
        assertNull( service.submitProposal( "new-edge", "Order.md",
            Map.of( "source", "Order", "target", "Inventory", "relationship", "depends-on" ),
            0.8, "test again" ) );
    }

    // --- snapshotGraph tests ---

    @Test
    void snapshotGraph_emptyGraph_returnsEmptyCollections() throws Exception {
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        assertEquals( 0, snap.nodeCount() );
        assertEquals( 0, snap.edgeCount() );
        assertTrue( snap.nodes().isEmpty() );
        assertTrue( snap.edges().isEmpty() );
        assertNotNull( snap.generatedAt() );
        assertEquals( 10, snap.hubDegreeThreshold() );
    }

    @Test
    void snapshotGraph_classifiesOrphanWithZeroDegree() throws Exception {
        service.upsertNode( "Orphan", "page", "Orphan",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        assertEquals( 1, snap.nodeCount() );
        final SnapshotNode node = snap.nodes().get( 0 );
        assertEquals( "orphan", node.role() );
        assertEquals( 0, node.degreeIn() );
        assertEquals( 0, node.degreeOut() );
    }

    @Test
    void snapshotGraph_classifiesStubFromNullSourcePage() throws Exception {
        service.upsertNode( "StubTarget", "page", null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode node = snap.nodes().get( 0 );
        assertEquals( "stub", node.role() );
    }

    @Test
    void snapshotGraph_classifiesHubAtThreshold() throws Exception {
        final var hub = service.upsertNode( "Hub", "page", "Hub",
                Provenance.HUMAN_AUTHORED, Map.of() );
        for ( int i = 0; i < 12; i++ ) {
            final var target = service.upsertNode( "Target" + i, "page", "Target" + i,
                    Provenance.HUMAN_AUTHORED, Map.of() );
            service.upsertEdge( hub.id(), target.id(), "links_to",
                    Provenance.HUMAN_AUTHORED, Map.of() );
        }
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode hubNode = snap.nodes().stream()
                .filter( n -> "Hub".equals( n.name() ) ).findFirst().orElseThrow();
        assertEquals( "hub", hubNode.role() );
    }

    @Test
    void snapshotGraph_hubThresholdFloorIsTen() throws Exception {
        service.upsertNode( "A", "page", "A", Provenance.HUMAN_AUTHORED, Map.of() );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        assertTrue( snap.hubDegreeThreshold() >= 10 );
    }

    @Test
    void snapshotGraph_degreeCountsBothDirections() throws Exception {
        final var a = service.upsertNode( "A", "page", "A",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var b = service.upsertNode( "B", "page", "B",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "links_to",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode nodeA = snap.nodes().stream()
                .filter( n -> "A".equals( n.name() ) ).findFirst().orElseThrow();
        final SnapshotNode nodeB = snap.nodes().stream()
                .filter( n -> "B".equals( n.name() ) ).findFirst().orElseThrow();
        assertEquals( 0, nodeA.degreeIn() );
        assertEquals( 1, nodeA.degreeOut() );
        assertEquals( 1, nodeB.degreeIn() );
        assertEquals( 0, nodeB.degreeOut() );
    }

    @Test
    void snapshotGraph_edgesReturnedWithCorrectFields() throws Exception {
        final var a = service.upsertNode( "A", "page", "A",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var b = service.upsertNode( "B", "page", "B",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "related_to",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        assertEquals( 1, snap.edgeCount() );
        final var edge = snap.edges().get( 0 );
        assertEquals( a.id(), edge.source() );
        assertEquals( b.id(), edge.target() );
        assertEquals( "related_to", edge.relationshipType() );
        assertEquals( Provenance.HUMAN_AUTHORED, edge.provenance() );
    }

    @Test
    void snapshotGraph_exposesClusterTagsStatusFromProperties() throws Exception {
        service.upsertNode( "LinearAlgebra", "article", "LinearAlgebra.md",
                Provenance.HUMAN_AUTHORED, Map.of(
                        "cluster", "mathematics",
                        "tags", java.util.List.of( "linear-algebra", "vectors" ),
                        "status", "active" ) );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode node = snap.nodes().get( 0 );
        assertEquals( "mathematics", node.cluster() );
        assertEquals( java.util.List.of( "linear-algebra", "vectors" ), node.tags() );
        assertEquals( "active", node.status() );
    }

    @Test
    void snapshotGraph_filtersNonStringEntriesFromTags() throws Exception {
        service.upsertNode( "MixedTags", "article", "MixedTags.md",
                Provenance.HUMAN_AUTHORED, Map.of(
                        "tags", java.util.List.of( "alpha", 42, "beta", true ) ) );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode node = snap.nodes().get( 0 );
        assertEquals( java.util.List.of( "alpha", "beta" ), node.tags() );
    }

    @Test
    void snapshotGraph_invalidatesCacheOnUpsertNode() throws Exception {
        service.upsertNode( "First", "article", "First.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final GraphSnapshot before = service.snapshotGraph( adminSession() );
        assertEquals( 1, before.nodeCount() );

        service.upsertNode( "Second", "article", "Second.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final GraphSnapshot after = service.snapshotGraph( adminSession() );
        assertEquals( 2, after.nodeCount(), "Cache should be invalidated after upsertNode" );
    }

    @Test
    void snapshotGraph_invalidatesCacheOnDeleteNode() throws Exception {
        final KgNode n = service.upsertNode( "Temp", "article", "Temp.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        assertEquals( 1, service.snapshotGraph( adminSession() ).nodeCount() );
        service.deleteNode( n.id() );
        assertEquals( 0, service.snapshotGraph( adminSession() ).nodeCount(),
                "Cache should be invalidated after deleteNode" );
    }

    @Test
    void snapshotGraph_acceptsAnonymousViewer() {
        // D27: knowledge graph reads are now public — anonymous viewers must not be
        // rejected. The redaction step still hides per-page restricted content.
        assertDoesNotThrow( () -> service.snapshotGraph( null ) );
    }

    @Test
    void snapshotGraph_nullPropertiesYieldNullClusterAndEmptyTags() throws Exception {
        service.upsertNode( "Orphan", "article", "Orphan.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final GraphSnapshot snap = service.snapshotGraph( adminSession() );
        final SnapshotNode node = snap.nodes().get( 0 );
        assertNull( node.cluster() );
        assertTrue( node.tags().isEmpty() );
        assertNull( node.status() );
    }

    // --- node_type vocabulary regex (Task 6) ---

    @Test
    void upsertNode_rejectsTrailingCommaTypo() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> service.upsertNode( "RaftCommaTest_" + System.currentTimeMillis(),
                        "concept,",
                        "TestPage", Provenance.HUMAN_AUTHORED, java.util.Map.of() ) );
        assertTrue( e.getMessage().contains( "invalid node_type" ), e.getMessage() );
    }

    @Test
    void upsertNode_rejectsEmptyString() {
        assertThrows( IllegalArgumentException.class,
                () -> service.upsertNode( "EmptyTest_" + System.currentTimeMillis(),
                        "",
                        "TestPage", Provenance.HUMAN_AUTHORED, java.util.Map.of() ) );
    }

    @Test
    void upsertNode_rejectsUppercaseFirstLetter() {
        assertThrows( IllegalArgumentException.class,
                () -> service.upsertNode( "UppercaseTest_" + System.currentTimeMillis(),
                        "Product",
                        "TestPage", Provenance.HUMAN_AUTHORED, java.util.Map.of() ) );
    }

    @Test
    void upsertNode_allowsHyphenatedTypes() {
        assertDoesNotThrow( () -> service.upsertNode( "HyphenTest_" + System.currentTimeMillis(),
                "implementation-plan",
                "TestPage", Provenance.HUMAN_AUTHORED, java.util.Map.of() ) );
    }

    @Test
    void upsertNode_allowsLowercaseAlphanumericUnderscore() {
        assertDoesNotThrow( () -> service.upsertNode( "SnakeTest_" + System.currentTimeMillis(),
                "concept",
                "TestPage", Provenance.HUMAN_AUTHORED, java.util.Map.of() ) );
    }

    @Test
    void upsertNode_allowsNullNodeType() {
        assertDoesNotThrow( () -> service.upsertNode( "NullTest_" + System.currentTimeMillis(),
                null,
                "TestPage", Provenance.HUMAN_AUTHORED, java.util.Map.of() ) );
    }
}
