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
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that when the judge service returns a transient-unavailable
 * verdict (HTTP timeout, connection refused, malformed response), the runner
 * does NOT persist a machine review or stamp machine_status. The proposal
 * stays at machine_status=NULL so the next cron pass naturally retries it.
 *
 * <p>This is the self-healing replacement for the
 * {@code reset_judge_timeout_abstains.sh} one-shot — pre-existing data may
 * still need that script, but new timeouts no longer pollute review history
 * or count against {@code max_attempts}.
 */
class JudgeRunnerTransientUnavailableTest {

    private static KgJudgeConfig cfg() {
        return new KgJudgeConfig( true, "x", "test-model",
            false, 5, 50, 1, 30, 3, "30m" );
    }

    private static KgProposal proposal() {
        return new KgProposal(
            UUID.randomUUID(), "new-edge", "Page",
            Map.of( "source", "A", "target", "B", "relationship", "depends_on" ),
            0.8, "reason", "pending", null,
            Instant.now(), null, "none", null, null, null, null );
    }

    @Test
    void transient_unavailable_verdict_is_not_persisted() {
        final KgProposalRepository proposals = mock( KgProposalRepository.class );
        final KgRejectionRepository rejections = mock( KgRejectionRepository.class );
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        final KgMaterializationService materialization = mock( KgMaterializationService.class );

        final KgProposal p = proposal();
        when( proposals.getProposalsForJudging( anyInt() ) ).thenReturn( List.of( p ) );
        when( proposals.listReviews( any() ) ).thenReturn( List.of() );
        when( judge.judge( any() ) ).thenReturn( new JudgeVerdict(
            JudgeVerdict.ABSTAIN, 0.0,
            "judge_unavailable: request timed out",
            "test-model" ) );

        final JudgeRunner runner = new JudgeRunner( proposals, rejections, judge, materialization, cfg() );
        runner.runOnce();

        // The judge WAS called.
        verify( judge, atLeast( 1 ) ).judge( any() );
        // But NEITHER the verdict stamp NOR the review row was written.
        verify( proposals, never() ).applyMachineVerdict( any(), anyString(), anyDouble(), anyString() );
        verify( proposals, never() ).recordReview( any(), anyString(), anyString(), anyString(),
            any(), any() );
        // And neither materialise path fired.
        verify( materialization, never() ).materializeMachine( any() );
    }

    @Test
    void real_abstain_IS_persisted() {
        // Sanity check: real model abstentions still flow through normally.
        final KgProposalRepository proposals = mock( KgProposalRepository.class );
        final KgRejectionRepository rejections = mock( KgRejectionRepository.class );
        final KgProposalJudgeService judge = mock( KgProposalJudgeService.class );
        final KgMaterializationService materialization = mock( KgMaterializationService.class );

        final KgProposal p = proposal();
        when( proposals.getProposalsForJudging( anyInt() ) ).thenReturn( List.of( p ) );
        when( proposals.listReviews( any() ) ).thenReturn( List.of() );
        when( judge.judge( any() ) ).thenReturn( new JudgeVerdict(
            JudgeVerdict.ABSTAIN, 0.4,
            "evidence is ambiguous between depends_on and uses",
            "test-model" ) );

        final JudgeRunner runner = new JudgeRunner( proposals, rejections, judge, materialization, cfg() );
        runner.runOnce();

        verify( proposals ).applyMachineVerdict( any(), anyString(), anyDouble(), anyString() );
        verify( proposals ).recordReview( any(), anyString(), anyString(), anyString(), any(), any() );
    }
}
