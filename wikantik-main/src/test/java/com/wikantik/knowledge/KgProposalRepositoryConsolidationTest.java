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
import com.wikantik.api.knowledge.SchemaDescription;
import com.wikantik.api.knowledge.SupportEvidence;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.extraction.ProposalUpserter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link KgProposalRepository#upsertConsolidatedProposal}, {@link
 * KgProposalRepository#countPendingBreakdown}, and {@link
 * KgProposalRepository#countPendingUnjudgedProposals} — none of which are exercised by
 * {@link KgProposalRepositoryListingTest} or {@link KgProposalRepositoryRollbackTest}.
 */
@RequiresPostgres
class KgProposalRepositoryConsolidationTest {

    private static DataSource dataSource;
    private KgProposalRepository proposals;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestDb.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
        }
        proposals = new KgProposalRepository( dataSource );
    }

    private SupportEvidence evidence( final String page, final double confidence ) {
        return new SupportEvidence( page, "quoted span", confidence, "extractor-v1" );
    }

    @Test
    void upsertConsolidatedProposalInsertsNewNodeProposal() {
        final ConsolidatedProposal cp = ConsolidatedProposal.newNode(
                "sig-node-1", "NewThing", "concept", List.of( evidence( "PageA", 0.8 ) ), 0.8 );

        final ProposalUpserter.Result result = proposals.upsertConsolidatedProposal( cp );

        assertTrue( result.inserted() );
        assertEquals( 1, result.supportCount() );
        assertEquals( 1, proposals.listProposals( "pending", null, 100, 0 ).size() );
    }

    @Test
    void upsertConsolidatedProposalInsertsNewEdgeProposal() {
        final ConsolidatedProposal cp = ConsolidatedProposal.newEdge(
                "sig-edge-1", "SourceThing", "TargetThing", "related_to",
                List.of( evidence( "PageB", 0.7 ) ), 0.7 );

        final ProposalUpserter.Result result = proposals.upsertConsolidatedProposal( cp );

        assertTrue( result.inserted() );
        final var stored = proposals.listProposals( "pending", null, 100, 0 ).get( 0 );
        assertEquals( "new-edge", stored.proposalType() );
        assertEquals( "PageB", stored.sourcePage() );
    }

    @Test
    void upsertConsolidatedProposalMergesSupportOnConflictingSignature() {
        final ConsolidatedProposal first = ConsolidatedProposal.newNode(
                "sig-merge", "Thing", "concept", List.of( evidence( "PageA", 0.6 ) ), 0.6 );
        final ProposalUpserter.Result firstResult = proposals.upsertConsolidatedProposal( first );
        assertTrue( firstResult.inserted() );

        // Second call with the same signature but a different supporting page and higher
        // confidence must merge (not insert a second row) and take the higher confidence.
        final ConsolidatedProposal second = ConsolidatedProposal.newNode(
                "sig-merge", "Thing", "concept", List.of( evidence( "PageB", 0.9 ) ), 0.9 );
        final ProposalUpserter.Result secondResult = proposals.upsertConsolidatedProposal( second );

        assertFalse( secondResult.inserted(), "conflicting signature must merge, not insert" );
        assertEquals( 2, secondResult.supportCount(), "support from both calls must be merged" );
        assertEquals( 1, proposals.listProposals( "pending", null, 100, 0 ).size() );
        assertEquals( 0.9, proposals.listProposals( "pending", null, 100, 0 ).get( 0 ).confidence(), 0.001 );
    }

    @Test
    void countPendingUnjudgedProposalsCountsOnlyPendingWithoutMachineStatus() {
        proposals.insertProposal( "new-node", "A", java.util.Map.of(), 0.5, "r" );
        final var judged = proposals.insertProposal( "new-node", "B", java.util.Map.of(), 0.5, "r" );
        proposals.applyMachineVerdict( judged.id(), "approved", 0.9, "judge-v1" );

        assertEquals( 1L, proposals.countPendingUnjudgedProposals() );
    }

    @Test
    void countPendingBreakdownSplitsByTypeAndMachineStatus() {
        final var newNode = proposals.insertProposal( "new-node", "A", java.util.Map.of(), 0.5, "r" );
        final var newEdge = proposals.insertProposal( "new-edge", "B", java.util.Map.of(), 0.5, "r" );
        proposals.applyMachineVerdict( newNode.id(), "approved", 0.9, "judge-v1" );
        final var abstained = proposals.insertProposal( "new-edge", "C", java.util.Map.of(), 0.5, "r" );
        proposals.applyMachineVerdict( abstained.id(), "abstain", 0.5, "judge-v1" );

        final SchemaDescription.PendingBreakdown breakdown = proposals.countPendingBreakdown();

        assertEquals( 3, breakdown.total() );
        assertEquals( 1, breakdown.newNodes() );
        assertEquals( 2, breakdown.newEdges() );
        assertEquals( 1, breakdown.judgeApproved() );
        assertEquals( 1, breakdown.judgeAbstained() );
        assertEquals( 1, breakdown.unjudged() );
    }

    @Test
    void countPendingBreakdownOnEmptyQueueIsAllZero() {
        final SchemaDescription.PendingBreakdown breakdown = proposals.countPendingBreakdown();
        assertEquals( 0, breakdown.total() );
        assertEquals( 0, breakdown.newNodes() );
        assertEquals( 0, breakdown.newEdges() );
    }
}
