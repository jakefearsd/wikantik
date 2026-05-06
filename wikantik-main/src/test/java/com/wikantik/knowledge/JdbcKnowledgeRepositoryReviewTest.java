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
import com.wikantik.api.knowledge.KgProposalReview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcKnowledgeRepositoryReviewTest {

    private DataSource ds;
    private KgProposalRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        repo = new KgProposalRepository( ds );
        try ( Connection c = ds.getConnection() ) {
            // Order matters: child tables first (FK).
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
        }
    }

    @Test
    void recordReview_appends_audit_row() {
        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "B", "relationship", "rel" ),
            0.7, "reason" );

        repo.recordReview( p.id(), KgProposalReview.REVIEWER_MACHINE,
            "gemma4-assist:latest", "approved", 0.85, "looks legit" );
        repo.recordReview( p.id(), KgProposalReview.REVIEWER_HUMAN,
            "alice", "approved", null, null );

        final List< KgProposalReview > reviews = repo.listReviews( p.id() );
        assertEquals( 2, reviews.size() );
        // listReviews is ordered by created DESC, id DESC — most recent first.
        assertEquals( KgProposalReview.REVIEWER_HUMAN, reviews.get( 0 ).reviewerKind() );
        assertEquals( KgProposalReview.REVIEWER_MACHINE, reviews.get( 1 ).reviewerKind() );
        assertEquals( 0.85, reviews.get( 1 ).confidence() );
        assertNull( reviews.get( 0 ).confidence() );
    }

    @Test
    void getProposalsForJudging_returns_only_unjudged() throws Exception {
        final KgProposal a = repo.insertProposal( "new-edge", "PageA",
            Map.<String, Object>of( "source", "X", "target", "Y", "relationship", "r" ), 0.5, "" );
        final KgProposal b = repo.insertProposal( "new-edge", "PageB",
            Map.<String, Object>of( "source", "X", "target", "Z", "relationship", "r" ), 0.5, "" );
        // Mark `a` as already judged via direct UPDATE.
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute(
                "UPDATE kg_proposals SET machine_status='approved' WHERE id='" + a.id() + "'" );
        }

        final List< KgProposal > batch = repo.getProposalsForJudging( 50 );
        assertTrue( batch.stream().anyMatch( p -> p.id().equals( b.id() ) ) );
        assertFalse( batch.stream().anyMatch( p -> p.id().equals( a.id() ) ) );
    }

    @Test
    void getProposalsForJudging_skips_locked_rows() throws Exception {
        repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "A", "target", "B", "relationship", "r" ), 0.5, "" );

        try ( Connection c1 = ds.getConnection() ) {
            c1.setAutoCommit( false );
            try ( var st = c1.prepareStatement(
                    "SELECT id FROM kg_proposals WHERE machine_status IS NULL FOR UPDATE" ) ) {
                try ( var rs = st.executeQuery() ) {
                    assertTrue( rs.next(), "tx1 must hold the row" );
                    // Now from another connection (createConnection() returns a fresh one),
                    // SKIP LOCKED query must skip it.
                    final List< KgProposal > batch = repo.getProposalsForJudging( 50 );
                    assertTrue( batch.isEmpty(),
                        "SKIP LOCKED must skip the row held by tx1" );
                }
            } finally {
                c1.rollback();
            }
        }
    }
}
