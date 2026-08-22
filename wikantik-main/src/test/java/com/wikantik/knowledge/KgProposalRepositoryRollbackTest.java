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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@link KgProposalRepository#updateTierByProvenance} rolls back completely when an
 * unchecked exception interrupts the transaction mid-flight.
 *
 * <p>Before the {@code Jdbc.inTransaction} fix, {@code updateTierByProvenance} ran its two
 * UPDATEs (kg_nodes, then kg_edges) on a plain connection with no explicit transaction at all —
 * worse than the classic hand-rolled {@code setAutoCommit(false)}/rollback shape found elsewhere
 * in this codebase, since it never even attempted atomicity. Under the connection's default
 * auto-commit, the first UPDATE committed the instant it executed; a failure before the second
 * UPDATE left the kg_nodes tier permanently changed while kg_edges stayed on the old tier — the
 * two tables silently diverging on partial failure. This test seeds one kg_nodes row and one
 * kg_edges row sharing a {@code provenance_proposal_id}, injects a fault on the second UPDATE
 * (kg_edges), and asserts neither row's tier changed.</p>
 *
 * <p>{@link KgProposalRepository#getProposalsForJudging} was migrated the same way (its own
 * hand-rolled {@code setAutoCommit}/rollback block replaced with {@link
 * com.wikantik.jdbc.Jdbc#inTransaction}), but that method only reads (a locking {@code SELECT ...
 * FOR UPDATE SKIP LOCKED}) — it writes nothing, so there is no "partial rows" scenario to assert
 * against. {@code JdbcKnowledgeRepositoryReviewTest#getProposalsForJudging_skips_locked_rows} and
 * {@code JdbcKnowledgeRepositoryPoolClosedTest#getProposalsForJudging_throwsPoolClosedException_whenDataSourceIsClosed}
 * already cover its locking and pool-closed-exception behaviour and both still pass unchanged
 * after the migration, which is the observable proof that method still behaves identically.</p>
 */
@RequiresPostgres
class KgProposalRepositoryRollbackTest {

    private DataSource ds;
    private KgProposalRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestDb.createDataSource();
        try ( Connection c = ds.getConnection() ) {
            // Order matters: kg_edges first (FK to kg_nodes).
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        repo = new KgProposalRepository( ds );
    }

    private UUID insertNode( final String name, final UUID provenanceProposalId ) throws Exception {
        final UUID id = UUID.randomUUID();
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_nodes (id, name, node_type, tier, provenance_proposal_id) "
                      + "VALUES (?, ?, 'concept', 'machine', ?)" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, name );
            ps.setObject( 3, provenanceProposalId );
            ps.executeUpdate();
        }
        return id;
    }

    private void insertEdge( final UUID sourceId, final UUID targetId, final UUID provenanceProposalId )
            throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_edges (source_id, target_id, relationship_type, tier, provenance_proposal_id) "
                      + "VALUES (?, ?, 'related_to', 'machine', ?)" ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            ps.setObject( 3, provenanceProposalId );
            ps.executeUpdate();
        }
    }

    private String nodeTier( final UUID id ) throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "SELECT tier FROM kg_nodes WHERE id = ?" ) ) {
            ps.setObject( 1, id );
            try ( var rs = ps.executeQuery() ) {
                assertEquals( true, rs.next(), "node must still exist" );
                return rs.getString( 1 );
            }
        }
    }

    private String edgeTier( final UUID sourceId, final UUID targetId ) throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT tier FROM kg_edges WHERE source_id = ? AND target_id = ?" ) ) {
            ps.setObject( 1, sourceId );
            ps.setObject( 2, targetId );
            try ( var rs = ps.executeQuery() ) {
                assertEquals( true, rs.next(), "edge must still exist" );
                return rs.getString( 1 );
            }
        }
    }

    @Test
    void updateTierByProvenanceRollsBackAllWritesOnUncheckedMidTransactionFailure() throws Exception {
        final UUID proposalId = UUID.randomUUID();
        final UUID nodeA = insertNode( "A", proposalId );
        final UUID nodeB = insertNode( "B", proposalId );
        insertEdge( nodeA, nodeB, proposalId );

        // Statement #1 inside updateTierByProvenance is the kg_nodes UPDATE (the first *write*,
        // touching both nodeA and nodeB); #2 is the kg_edges UPDATE. Fail on #2 so the first
        // write has already executed inside the (still open) transaction when the unchecked
        // failure hits.
        final FaultInjectingDataSource faultyDs = new FaultInjectingDataSource( ds );
        faultyDs.failOn( 2, new RuntimeException( "boom: simulated mid-transaction failure" ) );
        final KgProposalRepository faultyRepo = new KgProposalRepository( faultyDs );

        assertThrows( RuntimeException.class,
            () -> faultyRepo.updateTierByProvenance( proposalId, "human" ) );

        // Verify through the clean (non-faulty) DataSource: no partial write from the failed
        // transaction may be visible — both rows must be exactly as they were.
        assertEquals( "machine", nodeTier( nodeA ), "kg_nodes UPDATE must have been rolled back" );
        assertEquals( "machine", nodeTier( nodeB ), "kg_nodes UPDATE must have been rolled back" );
        assertEquals( "machine", edgeTier( nodeA, nodeB ), "kg_edges must never have been reached" );
    }

    @Test
    void updateTierByProvenanceCommitsBothTablesOnSuccess() throws Exception {
        final UUID proposalId = UUID.randomUUID();
        final UUID nodeA = insertNode( "A", proposalId );
        final UUID nodeB = insertNode( "B", proposalId );
        insertEdge( nodeA, nodeB, proposalId );

        final int rows = repo.updateTierByProvenance( proposalId, "human" );

        assertEquals( 3, rows, "2 kg_nodes rows + 1 kg_edges row" );
        assertEquals( "human", nodeTier( nodeA ) );
        assertEquals( "human", nodeTier( nodeB ) );
        assertEquals( "human", edgeTier( nodeA, nodeB ) );
    }
}
