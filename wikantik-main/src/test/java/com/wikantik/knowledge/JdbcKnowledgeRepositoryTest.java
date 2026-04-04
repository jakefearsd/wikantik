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

import com.wikantik.api.knowledge.*;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JdbcKnowledgeRepositoryTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository repo;

    @BeforeAll
    static void initDataSource() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:knowledge_test;DB_CLOSE_DELAY=-1" );
        dataSource = ds;
        try( final Connection conn = ds.getConnection() ) {
            final String ddl = new String(
                JdbcKnowledgeRepositoryTest.class.getResourceAsStream( "/knowledge-h2.sql" ).readAllBytes() );
            conn.createStatement().execute( ddl );
        }
    }

    @BeforeEach
    void setUp() {
        repo = new JdbcKnowledgeRepository( dataSource );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
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
}
