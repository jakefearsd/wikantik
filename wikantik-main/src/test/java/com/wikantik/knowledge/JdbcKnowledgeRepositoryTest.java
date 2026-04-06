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
import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class JdbcKnowledgeRepositoryTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        repo = new JdbcKnowledgeRepository( dataSource );
    }

    // --- Node tests ---

    @Test
    void upsertNode_createsNewNode() {
        final KgNode node = repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        assertNotNull( node.id() );
        assertEquals( "Order", node.name() );
        assertEquals( "domain-model", node.nodeType() );
        assertEquals( "Order.md", node.sourcePage() );
        assertEquals( Provenance.HUMAN_AUTHORED, node.provenance() );
        assertEquals( "billing", node.properties().get( "domain" ) );
    }

    @Test
    void upsertNode_updatesExistingNode() {
        repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "billing" ) );
        final KgNode updated = repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of( "domain", "shipping" ) );
        assertEquals( "shipping", updated.properties().get( "domain" ) );
    }

    @Test
    void getNodeByName_returnsNode() {
        repo.upsertNode( "Order", "domain-model", "Order.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode found = repo.getNodeByName( "Order" );
        assertNotNull( found );
        assertEquals( "Order", found.name() );
    }

    @Test
    void getNodeByName_returnsNullForMissing() {
        assertNull( repo.getNodeByName( "NonExistent" ) );
    }

    @Test
    void deleteNode_removesNode() {
        final KgNode node = repo.upsertNode( "ToDelete", "test", null,
                Provenance.HUMAN_AUTHORED, Map.of() );
        repo.deleteNode( node.id() );
        assertNull( repo.getNodeByName( "ToDelete" ) );
    }

    // --- Edge tests ---

    @Test
    void upsertEdge_createsEdge() {
        final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgEdge edge = repo.upsertEdge( a.id(), b.id(), "depends-on",
                Provenance.HUMAN_AUTHORED, Map.of() );
        assertNotNull( edge );
        assertEquals( "depends-on", edge.relationshipType() );
        assertEquals( a.id(), edge.sourceId() );
        assertEquals( b.id(), edge.targetId() );
    }

    @Test
    void getEdgesForNode_outbound() {
        final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = repo.upsertNode( "C", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), c.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        final List< KgEdge > edges = repo.getEdgesForNode( a.id(), "outbound" );
        assertEquals( 2, edges.size() );
    }

    @Test
    void diffAndRemoveStaleEdges_removesOldEdges() {
        final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = repo.upsertNode( "C", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), c.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.diffAndRemoveStaleEdges( a.id(), Set.of( Map.entry( "B", "depends-on" ) ) );
        final List< KgEdge > remaining = repo.getEdgesForNode( a.id(), "outbound" );
        assertEquals( 1, remaining.size() );
        assertEquals( "depends-on", remaining.get( 0 ).relationshipType() );
    }

    @Test
    void diffAndRemoveStaleEdges_preservesAiInferredEdges() {
        final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "inferred-rel", Provenance.AI_INFERRED, Map.of() );
        repo.diffAndRemoveStaleEdges( a.id(), Set.of() );
        final List< KgEdge > remaining = repo.getEdgesForNode( a.id(), "outbound" );
        assertEquals( 1, remaining.size() );
    }

    // --- Proposal tests ---

    @Test
    void insertProposal_createsPendingProposal() {
        final KgProposal proposal = repo.insertProposal( "new-edge", "Order.md",
                Map.of( "source", "Order", "target", "Customer", "relationship", "depends-on" ),
                0.85, "Line 47 mentions 'the order references the customer'" );
        assertNotNull( proposal );
        assertEquals( "pending", proposal.status() );
        assertEquals( 0.85, proposal.confidence(), 0.001 );
    }

    @Test
    void updateProposalStatus_approvesProposal() {
        final KgProposal proposal = repo.insertProposal( "new-edge", "Order.md",
                Map.of(), 0.9, "test" );
        repo.updateProposalStatus( proposal.id(), "approved", "admin" );
        final KgProposal updated = repo.getProposal( proposal.id() );
        assertEquals( "approved", updated.status() );
        assertEquals( "admin", updated.reviewedBy() );
        assertNotNull( updated.reviewedAt() );
    }

    @Test
    void isRejected_returnsTrueForRejectedRelationship() {
        repo.insertRejection( "Order", "Inventory", "depends-on", "admin", "Not a real dependency" );
        assertTrue( repo.isRejected( "Order", "Inventory", "depends-on" ) );
        assertFalse( repo.isRejected( "Order", "Customer", "depends-on" ) );
    }

    // --- getNodeNames tests ---

    @Test
    void getNodeNames_resolvesUuidsToNames() {
        final KgNode a = repo.upsertNode( "Alpha", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "Beta", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = repo.upsertNode( "Gamma", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final Map< UUID, String > names = repo.getNodeNames( List.of( a.id(), b.id(), c.id() ) );
        assertEquals( 3, names.size() );
        assertEquals( "Alpha", names.get( a.id() ) );
        assertEquals( "Beta", names.get( b.id() ) );
        assertEquals( "Gamma", names.get( c.id() ) );
    }

    @Test
    void getNodeNames_handlesEmptySet() {
        final Map< UUID, String > names = repo.getNodeNames( List.of() );
        assertTrue( names.isEmpty() );
    }

    // --- Status filter tests ---

    @Test
    void queryNodes_filtersByStatus() {
        repo.upsertNode( "DeployedFeature", "article", "DeployedFeature.md",
                Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
        repo.upsertNode( "DesignedFeature", "article", "DesignedFeature.md",
                Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );
        repo.upsertNode( "ActiveFeature", "article", "ActiveFeature.md",
                Provenance.HUMAN_AUTHORED, Map.of( "status", "active" ) );

        final Map< String, Object > filters = Map.of( "status", "deployed" );
        final List< KgNode > results = repo.queryNodes( filters, null, 50, 0 );
        assertEquals( 1, results.size() );
        assertEquals( "DeployedFeature", results.get( 0 ).name() );
    }

    @Test
    void queryNodes_statusFilterCombinesWithTypeFilter() {
        repo.upsertNode( "A", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
        repo.upsertNode( "B", "hub", null,
                Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
        repo.upsertNode( "C", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );

        final Map< String, Object > filters = Map.of( "status", "deployed", "node_type", "article" );
        final List< KgNode > results = repo.queryNodes( filters, null, 50, 0 );
        assertEquals( 1, results.size() );
        assertEquals( "A", results.get( 0 ).name() );
    }

    // --- queryEdgesWithNames tests ---

    @Test
    void queryEdgesWithNames_returnsEdgesWithNodeNames() {
        final KgNode a = repo.upsertNode( "Customer", "domain", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "Order", "domain", null, Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "has-many", Provenance.HUMAN_AUTHORED, Map.of() );
        final List< Map< String, Object > > edges = repo.queryEdgesWithNames( null, null, 50, 0 );
        assertEquals( 1, edges.size() );
        assertEquals( "Customer", edges.get( 0 ).get( "source_name" ) );
        assertEquals( "Order", edges.get( 0 ).get( "target_name" ) );
        assertEquals( "has-many", edges.get( 0 ).get( "relationship_type" ) );
    }

    @Test
    void queryEdgesWithNames_filtersByRelationshipType() {
        final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        final List< Map< String, Object > > filtered = repo.queryEdgesWithNames( "depends-on", null, 50, 0 );
        assertEquals( 1, filtered.size() );
        assertEquals( "depends-on", filtered.get( 0 ).get( "relationship_type" ) );
    }

    @Test
    void queryEdgesWithNames_searchesByNodeName() {
        final KgNode a = repo.upsertNode( "Customer", "domain", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "Order", "domain", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = repo.upsertNode( "Inventory", "domain", null, Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "has-many", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( b.id(), c.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        final List< Map< String, Object > > results = repo.queryEdgesWithNames( null, "cust", 50, 0 );
        assertEquals( 1, results.size() );
        assertEquals( "Customer", results.get( 0 ).get( "source_name" ) );
    }

    @Test
    void queryEdgesWithNames_paginates() {
        final KgNode a = repo.upsertNode( "A", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode b = repo.upsertNode( "B", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode c = repo.upsertNode( "C", "test", null, Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), b.id(), "r1", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( a.id(), c.id(), "r2", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertEdge( b.id(), c.id(), "r3", Provenance.HUMAN_AUTHORED, Map.of() );
        final List< Map< String, Object > > page1 = repo.queryEdgesWithNames( null, null, 2, 0 );
        final List< Map< String, Object > > page2 = repo.queryEdgesWithNames( null, null, 2, 2 );
        assertEquals( 2, page1.size() );
        assertEquals( 1, page2.size() );
    }
}
