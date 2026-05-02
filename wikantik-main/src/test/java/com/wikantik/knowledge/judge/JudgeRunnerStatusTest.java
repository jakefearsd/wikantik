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

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for {@link JudgeRunner#status(long)} and the associated
 * progress instrumentation. No database or Docker container required.
 */
class JudgeRunnerStatusTest {

    private static KgJudgeConfig cfg() {
        return new KgJudgeConfig( true, "x", "test-model",
            false, 5, 50, 1, 30, 3 );
    }

    private static KgProposal proposal() {
        return new KgProposal(
            UUID.randomUUID(), "new-edge", "Page",
            Map.of( "source", "A", "target", "B", "relationship", "rel" ),
            0.8, "reason", "pending", null, null, null, null, null, null, null, null
        );
    }

    @Test
    void status_before_any_run_returns_defaults() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.getProposalsForJudging( anyInt() ) ).thenReturn( List.of() );

        final JudgeRunner runner = new JudgeRunner( repo, mock( KgProposalJudgeService.class ),
            mock( KgMaterializationService.class ), cfg() );

        final JudgeRunner.Status s = runner.status( 7L );

        assertFalse( s.inFlight() );
        assertEquals( 7, s.queueDepth() );
        assertEquals( 0, s.lastRunSubmitted() );
        assertEquals( 0, s.lastRunCompleted() );
        assertNull( s.lastRunStartedAt() );
        assertNull( s.lastRunFinishedAt() );
        assertNull( s.lastRunError() );
    }

    @Test
    void status_after_successful_run_reflects_completions() {
        final KgProposal p = proposal();
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.getProposalsForJudging( anyInt() ) ).thenReturn( List.of( p ) );
        when( repo.listReviews( any() ) ).thenReturn( List.of() );

        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( "abstain", 0.0, "no opinion", "test-model" ) );

        final JudgeRunner runner = new JudgeRunner( repo, judge,
            mock( KgMaterializationService.class ), cfg() );
        runner.runOnce();

        final JudgeRunner.Status s = runner.status( 0L );

        assertFalse( s.inFlight() );
        assertNotNull( s.lastRunFinishedAt() );
        assertNotNull( s.lastRunStartedAt() );
        assertEquals( 1, s.lastRunSubmitted() );
        assertEquals( 1, s.lastRunCompleted() );
        assertNull( s.lastRunError() );
    }

    @Test
    void status_captures_error_and_clears_inFlight_when_runOnce_throws() {
        final JdbcKnowledgeRepository repo = mock( JdbcKnowledgeRepository.class );
        when( repo.getProposalsForJudging( anyInt() ) )
            .thenThrow( new RuntimeException( "db gone" ) );

        final JudgeRunner runner = new JudgeRunner( repo, mock( KgProposalJudgeService.class ),
            mock( KgMaterializationService.class ), cfg() );

        assertThrows( RuntimeException.class, runner::runOnce );

        final JudgeRunner.Status s = runner.status( 5L );

        assertFalse( s.inFlight() );
        assertNotNull( s.lastRunError() );
        assertTrue( s.lastRunError().contains( "db gone" ),
            "error message should contain the cause: " + s.lastRunError() );
        assertNotNull( s.lastRunFinishedAt() );
    }
}
