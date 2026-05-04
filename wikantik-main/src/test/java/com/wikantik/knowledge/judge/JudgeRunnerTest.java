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
package com.wikantik.knowledge.judge;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class JudgeRunnerTest {

    private DataSource ds;
    private JdbcKnowledgeRepository repo;
    private KgMaterializationService mat;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        repo = new JdbcKnowledgeRepository( ds );
        mat = new KgMaterializationService( repo );
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
            c.createStatement().execute( "DELETE FROM kg_rejections" );
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_proposal_reviews" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
            c.createStatement().execute( "DELETE FROM kg_rejections" );
        }
    }

    private static KgJudgeConfig cfg( int maxAttempts, int concurrency ) {
        return new KgJudgeConfig( true, "x", "gemma4-assist:latest",
            false, 5, 50, concurrency, 30, maxAttempts, "30m" );
    }

    @Test
    void runOnce_judges_pending_proposals_records_review_and_materialises_when_approved() {
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "approved", 0.9, "ok", "gemma4-assist:latest" ) );

        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "X", "target", "Y", "relationship", "rel" ),
            0.7, "" );

        final JudgeRunner runner = new JudgeRunner( repo, judge, mat, cfg( 3, 1 ) );
        final int submitted = runner.runOnce();
        assertEquals( 1, submitted );

        assertEquals( "approved", repo.getProposal( p.id() ).machineStatus() );
        assertEquals( "machine", repo.getProposal( p.id() ).tier() );
        assertFalse( repo.listReviews( p.id() ).isEmpty() );
        assertTrue( repo.getAllEdges( Tier.MACHINE ).stream()
            .anyMatch( e -> p.id().equals( e.provenanceProposalId() ) ),
            "approved proposal must be materialised" );
    }

    @Test
    void runOnce_writes_kg_rejections_when_judge_rejects_new_edge() {
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "rejected", 0.95, "no support", "gemma4-assist:latest" ) );

        repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "Q", "target", "R", "relationship", "bad_rel" ),
            0.7, "" );

        new JudgeRunner( repo, judge, mat, cfg( 3, 1 ) ).runOnce();

        assertTrue( repo.isRejected( "Q", "R", "bad_rel" ),
            "hard reject must write kg_rejections" );
    }

    @Test
    void runOnce_skips_proposals_past_max_attempts() {
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "abstain", 0.0, "judge_unavailable: x", "gemma4-assist:latest" ) );

        final KgProposal p = repo.insertProposal( "new-edge", "Page",
            Map.<String, Object>of( "source", "X", "target", "Z", "relationship", "rel" ),
            0.7, "" );
        for ( int i = 0; i < 3; i++ ) {
            repo.recordReview( p.id(), "machine", "gemma", "abstain", 0.0, "boom" );
        }

        new JudgeRunner( repo, judge, mat, cfg( 3, 1 ) ).runOnce();

        verify( judge, never() ).judge( any() );
    }

    @Test
    void runOnce_returns_zero_when_queue_is_empty() {
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        final JudgeRunner runner = new JudgeRunner( repo, judge, mat, cfg( 3, 1 ) );
        assertEquals( 0, runner.runOnce() );
        verifyNoInteractions( judge );
    }
}
