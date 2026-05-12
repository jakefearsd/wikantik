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
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class DefaultKnowledgeGraphServiceEdgeCurationTest {

    private static DataSource dataSource;
    private KgNodeRepository nodes;
    private KgEdgeRepository edgeRepo;
    private DefaultKnowledgeGraphService service;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edge_audit" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        nodes    = new KgNodeRepository( dataSource );
        edgeRepo = new KgEdgeRepository( dataSource );
        service  = new DefaultKnowledgeGraphService(
                nodes, edgeRepo,
                new KgProposalRepository( dataSource ),
                new KgRejectionRepository( dataSource ),
                dataSource );
    }

    // --- tests ---

    @Test
    void countEdgesDelegatesToRepository() {
        seedEdges();
        assertEquals( 2L, service.countEdges( "related", null ) );
        assertEquals( 3L, service.countEdges( null, null ) );
    }

    @Test
    void getEdgeReturnsEdgeOrNull() throws Exception {
        seedEdges();
        final UUID id = lookupOneEdgeId();
        final KgEdge e = service.getEdge( id );
        assertNotNull( e );
        assertEquals( id, e.id() );
        assertNull( service.getEdge( UUID.randomUUID() ) );
    }

    @Test
    void bulkDeleteEdgesRespectsExpectedCount() {
        seedEdges();
        assertEquals( 2, service.bulkDeleteEdges( "related", null, 2 ) );
        assertEquals( 1L, service.countEdges( null, null ) );
    }

    @Test
    void bulkDeleteEdgesThrowsOnCountMismatch() {
        seedEdges();
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> service.bulkDeleteEdges( "related", null, 99 ) );
        assertTrue( ex.getMessage().contains( "expected 99" ) );
        assertTrue( ex.getMessage().contains( "found 2" ) );
        // No deletions happened
        assertEquals( 3L, service.countEdges( null, null ) );
    }

    @Test
    void deleteEdgeAndRecordRejectionDelegates() throws Exception {
        seedEdges();
        final UUID id = lookupOneEdgeId();
        service.deleteEdgeAndRecordRejection( id, "carol", "wrong" );
        assertNull( service.getEdge( id ) );
    }

    @Test
    void confirmEdgeElevatesMachineEdgeToHumanCuratedAndWritesAudit() throws Exception {
        // A machine-tier AI_INFERRED edge — exactly the kind of row a curator
        // hits "Confirm" on to elevate without otherwise editing.
        final UUID a = nodes.upsertNode( "SvcA", "concept", null,
            Provenance.AI_INFERRED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "SvcB", "concept", null,
            Provenance.AI_INFERRED, Map.of() ).id();
        edgeRepo.upsertEdgeWithProvenance(
            a, b, "related_to", Provenance.AI_INFERRED, Map.of(),
            "machine", null );
        final UUID edgeId = lookupOneEdgeId();

        final KgEdge after = service.confirmEdge( edgeId, "carol" );

        assertNotNull( after );
        assertEquals( Provenance.HUMAN_CURATED, after.provenance() );
        assertEquals( "human", after.tier() );

        // Audit row recorded the before -> after transition.
        final List< Map< String, Object > > audit = service.getEdgeAudit( edgeId, 10 );
        assertEquals( 1, audit.size() );
        assertEquals( "CONFIRM", audit.get( 0 ).get( "action" ) );
        assertEquals( "carol", audit.get( 0 ).get( "actor" ) );
    }

    @Test
    void confirmEdgeReturnsNullForUnknownId() {
        assertNull( service.confirmEdge( UUID.randomUUID(), "carol" ) );
    }

    @Test
    void confirmEdgeIsIdempotentOnAlreadyCuratedEdge() throws Exception {
        seedEdges();
        final UUID id = lookupOneEdgeId();
        // Pre-condition: seeded edges are already HUMAN_CURATED.
        final KgEdge first = service.confirmEdge( id, "carol" );
        final KgEdge second = service.confirmEdge( id, "carol" );
        assertNotNull( first );
        assertNotNull( second );
        assertEquals( Provenance.HUMAN_CURATED, second.provenance() );
        // Two audit rows even though the state didn't change — operator
        // intent is what gets recorded, not the diff.
        assertEquals( 2, service.getEdgeAudit( id, 10 ).size() );
    }

    @Test
    void getEdgeAuditReturnsAuditRowsForEdge() {
        final UUID edgeId = UUID.randomUUID();
        service.getEdgeAuditRepository().insert( edgeId, "CREATE", null,
                Map.of( "rel", "related" ), "alice", null );
        final List< Map< String, Object > > rows = service.getEdgeAudit( edgeId, 10 );
        assertEquals( 1, rows.size() );
        assertEquals( "CREATE", rows.get( 0 ).get( "action" ) );
    }

    // --- helpers ---

    private void seedEdges() {
        final UUID a = nodes.upsertNode( "SvcA", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "SvcB", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID c = nodes.upsertNode( "SvcC", "concept", null, Provenance.HUMAN_AUTHORED, Map.of() ).id();
        edgeRepo.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        edgeRepo.upsertEdge( a, c, "related", Provenance.HUMAN_CURATED, Map.of() );
        edgeRepo.upsertEdge( b, c, "depends_on", Provenance.HUMAN_CURATED, Map.of() );
    }

    private UUID lookupOneEdgeId() throws Exception {
        try ( Connection conn = dataSource.getConnection();
              Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery( "SELECT id FROM kg_edges LIMIT 1" ) ) {
            assertTrue( rs.next() );
            return rs.getObject( 1, UUID.class );
        }
    }
}
