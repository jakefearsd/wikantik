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
        service = new DefaultKnowledgeGraphService( new JdbcKnowledgeRepository( dataSource ), engine );
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
}
