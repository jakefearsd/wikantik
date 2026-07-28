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
import com.wikantik.api.knowledge.KgProposal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the proposal-queue listing/counting queries on
 * {@link KgProposalRepository} — the storage behind the admin curation queue.
 *
 * <p>Two invariants matter most and are asserted throughout:</p>
 * <ul>
 *   <li>{@code countProposalsFiltered} and {@code listProposalsFiltered} must
 *       agree on the WHERE clause, or the UI's "Showing X–Y of Z" footer
 *       silently truncates pages.</li>
 *   <li>Machine-rejected proposals are hidden by default — the reviewer only
 *       sees them when they explicitly opt in.</li>
 * </ul>
 */
@Testcontainers( disabledWithoutDocker = true )
class KgProposalRepositoryListingTest {

    private static DataSource dataSource;
    private KgProposalRepository proposals;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestContainer.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
        }
        proposals = new KgProposalRepository( dataSource );
    }

    // ------------------------------------------------------------------ helpers

    private UUID propose( final String sourcePage ) {
        return proposals.insertProposal( "edge", sourcePage,
                Map.of( "source", "A", "target", "B", "relationship", "related" ),
                0.9, "because" ).id();
    }

    private static List< String > pages( final List< KgProposal > result ) {
        return result.stream().map( KgProposal::sourcePage ).toList();
    }

    /** Asserts the filtered list and the filtered count never disagree. */
    private void assertListAndCountAgree( final String status, final String tier,
                                          final String machineStatus,
                                          final boolean includeMachineRejected,
                                          final String sourcePage, final int expected ) {
        assertEquals( expected, proposals.listProposalsFiltered(
                status, tier, machineStatus, includeMachineRejected, sourcePage, 1000, 0 ).size(),
                "list size" );
        assertEquals( (long) expected, proposals.countProposalsFiltered(
                status, tier, machineStatus, includeMachineRejected, sourcePage ),
                "count must match the list the user sees" );
    }

    // ------------------------------------------------------------------ listProposals

    @Test
    void listProposalsWithoutFiltersReturnsEverything() {
        propose( "PageA" );
        propose( "PageB" );

        assertEquals( 2, proposals.listProposals( null, null, 100, 0 ).size() );
    }

    @Test
    void listProposalsFiltersByStatus() {
        propose( "PageA" );
        final UUID approved = propose( "PageB" );
        proposals.updateProposalStatus( approved, "approved", "alice" );

        assertEquals( List.of( "PageA" ), pages( proposals.listProposals( "pending", null, 100, 0 ) ) );
        assertEquals( List.of( "PageB" ), pages( proposals.listProposals( "approved", null, 100, 0 ) ) );
    }

    @Test
    void listProposalsFiltersBySourcePage() {
        propose( "PageA" );
        propose( "PageB" );

        assertEquals( List.of( "PageA" ), pages( proposals.listProposals( null, "PageA", 100, 0 ) ) );
    }

    @Test
    void listProposalsCombinesStatusAndSourcePage() {
        propose( "PageA" );
        final UUID other = propose( "PageA" );
        proposals.updateProposalStatus( other, "rejected", "alice" );
        propose( "PageB" );

        assertEquals( 1, proposals.listProposals( "pending", "PageA", 100, 0 ).size() );
    }

    @Test
    void listProposalsHonoursLimitAndOffset() {
        propose( "PageA" );
        propose( "PageB" );
        propose( "PageC" );

        assertEquals( 2, proposals.listProposals( null, null, 2, 0 ).size() );
        assertEquals( 1, proposals.listProposals( null, null, 2, 2 ).size() );
        assertTrue( proposals.listProposals( null, null, 10, 99 ).isEmpty() );
    }

    @Test
    void listProposalsOnAnEmptyQueueIsEmpty() {
        assertTrue( proposals.listProposals( null, null, 100, 0 ).isEmpty() );
    }

    // ------------------------------------------------------------------ listProposalsFiltered

    @Test
    void machineRejectedProposalsAreHiddenByDefault() {
        propose( "Kept" );
        proposals.applyMachineVerdict( propose( "Junk" ), "rejected", 0.95, "judge-v1" );

        assertEquals( List.of( "Kept" ),
                pages( proposals.listProposalsFiltered( null, null, null, false, null, 100, 0 ) ) );
        assertListAndCountAgree( null, null, null, false, null, 1 );
    }

    @Test
    void includeMachineRejectedOptsThemBackIn() {
        propose( "Kept" );
        proposals.applyMachineVerdict( propose( "Junk" ), "rejected", 0.95, "judge-v1" );

        assertListAndCountAgree( null, null, null, true, null, 2 );
    }

    @Test
    void tierFilterNarrowsTheQueue() {
        propose( "Untouched" );
        proposals.applyMachineVerdict( propose( "MachineApproved" ), "approved", 0.9, "judge-v1" );

        assertEquals( List.of( "MachineApproved" ),
                pages( proposals.listProposalsFiltered( null, "machine", null, true, null, 100, 0 ) ) );
        assertListAndCountAgree( null, "machine", null, true, null, 1 );
    }

    @Test
    void machineStatusFilterSelectsJudgedProposals() {
        proposals.applyMachineVerdict( propose( "Approved" ), "approved", 0.9, "judge-v1" );
        proposals.applyMachineVerdict( propose( "Abstained" ), "abstain", 0.4, "judge-v1" );

        assertEquals( List.of( "Approved" ),
                pages( proposals.listProposalsFiltered( null, null, "approved", true, null, 100, 0 ) ) );
        assertListAndCountAgree( null, null, "abstain", true, null, 1 );
    }

    @Test
    void nullSentinelSelectsProposalsAwaitingMachineReview() {
        propose( "NotYetJudged" );
        proposals.applyMachineVerdict( propose( "Judged" ), "approved", 0.9, "judge-v1" );

        final String sentinel = KgProposalRepository.MACHINE_STATUS_NULL_SENTINEL;
        assertEquals( List.of( "NotYetJudged" ),
                pages( proposals.listProposalsFiltered( null, null, sentinel, true, null, 100, 0 ) ) );
        assertListAndCountAgree( null, null, sentinel, true, null, 1 );
    }

    @Test
    void statusAndSourcePageFiltersApplyToTheFilteredQueryToo() {
        propose( "PageA" );
        propose( "PageB" );
        proposals.updateProposalStatus( propose( "PageA" ), "approved", "alice" );

        assertListAndCountAgree( "pending", null, null, true, "PageA", 1 );
        assertListAndCountAgree( "approved", null, null, true, "PageA", 1 );
        assertListAndCountAgree( null, null, null, true, "PageB", 1 );
    }

    @Test
    void allFiltersCompose() {
        propose( "PageA" );
        proposals.applyMachineVerdict( propose( "PageA" ), "approved", 0.9, "judge-v1" );
        proposals.applyMachineVerdict( propose( "PageB" ), "approved", 0.9, "judge-v1" );

        assertListAndCountAgree( null, "machine", "approved", false, "PageA", 1 );
    }

    @Test
    void filteredQueueOnAnEmptyTableIsEmptyAndCountsZero() {
        assertListAndCountAgree( null, null, null, true, null, 0 );
        assertEquals( 0L, proposals.countProposalsFiltered( "pending", "machine", "approved", false, "X" ) );
    }

    // ------------------------------------------------------------------ pagination stability

    @Test
    void paginationIsStableAcrossPageBoundariesForSameInstantRows() {
        // Inserted in one burst, so `created` values can collide; the id-DESC
        // tiebreak is what keeps a row from appearing twice or being skipped.
        for ( int i = 0; i < 6; i++ ) {
            propose( "Page" + i );
        }

        final List< UUID > firstPage = proposals
                .listProposalsFiltered( null, null, null, true, null, 3, 0 )
                .stream().map( KgProposal::id ).toList();
        final List< UUID > secondPage = proposals
                .listProposalsFiltered( null, null, null, true, null, 3, 3 )
                .stream().map( KgProposal::id ).toList();

        assertEquals( 3, firstPage.size() );
        assertEquals( 3, secondPage.size() );
        assertTrue( java.util.Collections.disjoint( firstPage, secondPage ),
                "no row may appear on two pages" );
        assertEquals( 6, java.util.stream.Stream.concat( firstPage.stream(), secondPage.stream() )
                .distinct().count(), "every row must appear exactly once across the pages" );
        assertEquals( 6L, proposals.countProposalsFiltered( null, null, null, true, null ) );
    }

    @Test
    void countIsIndependentOfLimitAndOffset() {
        for ( int i = 0; i < 4; i++ ) {
            propose( "Page" + i );
        }

        assertEquals( 2, proposals.listProposalsFiltered( null, null, null, true, null, 2, 0 ).size() );
        assertEquals( 4L, proposals.countProposalsFiltered( null, null, null, true, null ) );
        assertFalse( proposals.listProposalsFiltered( null, null, null, true, null, 2, 0 ).isEmpty() );
    }
}
