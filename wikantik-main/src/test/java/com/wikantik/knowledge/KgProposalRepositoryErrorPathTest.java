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

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.SupportEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generic-{@link SQLException} coverage for {@link KgProposalRepository} beyond what
 * {@link JdbcKnowledgeRepositoryPoolClosedTest} already exercises for the "data source is
 * closed" branch. Here the {@link DataSource} fails with an ordinary message so every method
 * takes its plain {@code LOG.warn} + wrap-and-rethrow path (or, for {@code applyMachineVerdict}
 * / {@code applyHumanVerdict}, confirms the {@code isPoolClosed} check correctly falls through
 * to the generic branch when the message doesn't match).
 */
class KgProposalRepositoryErrorPathTest {

    private KgProposalRepository proposals;

    @BeforeEach
    void setUp() throws SQLException {
        final DataSource broken = Mockito.mock( DataSource.class );
        Mockito.when( broken.getConnection() ).thenThrow( new SQLException( "simulated connection failure" ) );
        proposals = new KgProposalRepository( broken );
    }

    @Test
    void insertProposalWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.insertProposal( "new-node", "Page", Map.of(), 0.5, "why" ) );
        assertTrue( ex.getMessage().contains( "Failed to insert proposal" ) );
    }

    @Test
    void getProposalWrapsFailure() {
        assertThrows( RuntimeException.class, () -> proposals.getProposal( UUID.randomUUID() ) );
    }

    @Test
    void listProposalsWrapsFailure() {
        assertThrows( RuntimeException.class, () -> proposals.listProposals( null, null, 10, 0 ) );
    }

    @Test
    void listProposalsFilteredWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.listProposalsFiltered( null, null, null, true, null, 10, 0 ) );
        assertTrue( ex.getMessage().contains( "listProposalsFiltered failed" ) );
    }

    @Test
    void countProposalsFilteredWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.countProposalsFiltered( null, null, null, true, null ) );
        assertTrue( ex.getMessage().contains( "countProposalsFiltered failed" ) );
    }

    @Test
    void updateProposalStatusWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.updateProposalStatus( UUID.randomUUID(), "approved", "alice" ) );
        assertTrue( ex.getMessage().contains( "Failed to update proposal status" ) );
    }

    @Test
    void applyMachineVerdictRejectsUnknownVerdictBeforeTouchingTheDatabase() {
        assertThrows( IllegalArgumentException.class,
                () -> proposals.applyMachineVerdict( UUID.randomUUID(), "maybe", 0.5, "judge" ) );
    }

    @Test
    void applyMachineVerdictWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.applyMachineVerdict( UUID.randomUUID(), "approved", 0.9, "judge" ) );
        assertFalse( ex instanceof PoolClosedException, "a generic failure must not be misreported as pool-closed" );
        assertTrue( ex.getMessage().contains( "applyMachineVerdict failed" ) );
    }

    @Test
    void applyHumanVerdictRejectsUnknownVerdictBeforeTouchingTheDatabase() {
        assertThrows( IllegalArgumentException.class,
                () -> proposals.applyHumanVerdict( UUID.randomUUID(), "maybe", "alice" ) );
    }

    @Test
    void applyHumanVerdictWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.applyHumanVerdict( UUID.randomUUID(), "approved", "alice" ) );
        assertFalse( ex instanceof PoolClosedException );
        assertTrue( ex.getMessage().contains( "applyHumanVerdict failed" ) );
    }

    @Test
    void applyHumanVerdictTranslatesPoolClosed() throws SQLException {
        final DataSource closed = Mockito.mock( DataSource.class );
        Mockito.when( closed.getConnection() ).thenThrow( new SQLException( "Data source is closed" ) );
        final KgProposalRepository repo = new KgProposalRepository( closed );

        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.applyHumanVerdict( UUID.randomUUID(), "approved", "alice" ) );
        assertInstanceOf( PoolClosedException.class, ex );
    }

    @Test
    void updateTierByProvenanceWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.updateTierByProvenance( UUID.randomUUID(), "human" ) );
        assertTrue( ex.getMessage().contains( "updateTierByProvenance failed" ) );
    }

    @Test
    void countPendingBreakdownReturnsEmptyRatherThanThrowingOnFailure() {
        final var breakdown = proposals.countPendingBreakdown();
        assertEquals( 0, breakdown.total() );
        assertEquals( 0, breakdown.newNodes() );
    }

    @Test
    void countPendingUnjudgedProposalsWrapsFailure() {
        assertThrows( RuntimeException.class, proposals::countPendingUnjudgedProposals );
    }

    @Test
    void recordReviewWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.recordReview( UUID.randomUUID(), "human", "alice", "approved", 0.9, "ok" ) );
        assertFalse( ex instanceof PoolClosedException );
        assertTrue( ex.getMessage().contains( "recordReview failed" ) );
    }

    @Test
    void listReviewsWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.listReviews( UUID.randomUUID() ) );
        assertFalse( ex instanceof PoolClosedException );
        assertTrue( ex.getMessage().contains( "listReviews failed" ) );
    }

    @Test
    void getProposalsForJudgingWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.getProposalsForJudging( 10 ) );
        assertFalse( ex instanceof PoolClosedException );
        assertTrue( ex.getMessage().contains( "getProposalsForJudging failed" ) );
    }

    @Test
    void upsertConsolidatedProposalWrapsFailure() {
        final ConsolidatedProposal cp = ConsolidatedProposal.newNode(
                "sig", "Thing", "concept",
                List.of( new SupportEvidence( "Page", "span", 0.5, "ex" ) ), 0.5 );

        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> proposals.upsertConsolidatedProposal( cp ) );
        assertTrue( ex.getMessage().contains( "upsert failed" ) );
    }
}
