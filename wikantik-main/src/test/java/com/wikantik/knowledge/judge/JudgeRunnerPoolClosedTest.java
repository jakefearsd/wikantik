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
import com.wikantik.knowledge.PoolClosedException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JudgeRunnerPoolClosedTest {

    @Test
    void poolClosedExceptionDoesNotPropagateAsWarn() {
        // Arrange — synthetic proposal + verdict; repo throws PoolClosedException
        // on applyMachineVerdict to simulate webapp shutdown mid-pass.
        final KgProposal proposal = Mockito.mock( KgProposal.class );
        when( proposal.id() ).thenReturn( UUID.randomUUID() );

        final KgProposalRepository proposals = Mockito.mock( KgProposalRepository.class );
        final KgRejectionRepository rejections = Mockito.mock( KgRejectionRepository.class );
        when( proposals.getProposalsForJudging( Mockito.anyInt() ) )
            .thenReturn( List.of( proposal ) );
        when( proposals.listReviews( any() ) ).thenReturn( List.of() );
        Mockito.doThrow( new PoolClosedException( "applyMachineVerdict aborted: pool closed",
                new java.sql.SQLException( "Data source is closed" ) ) )
            .when( proposals ).applyMachineVerdict( any(), anyString(), anyDouble(), anyString() );

        final KgProposalJudgeService judge = Mockito.mock( KgProposalJudgeService.class );
        when( judge.judge( any() ) ).thenReturn(
            new JudgeVerdict( JudgeVerdict.APPROVED, 0.9, "ok", "test-model" ) );

        final KgMaterializationService materialization = Mockito.mock( KgMaterializationService.class );
        final KgJudgeConfig config = new KgJudgeConfig( true, "endpoint", "model",
            true, 10, 5, 1, 30, 3, "30m" );

        final JudgeRunner runner = new JudgeRunner( proposals, rejections, judge, materialization, config );

        // Act
        final int submitted = runner.runOnce();

        // Assert — the worker exited gracefully on PoolClosedException.
        // No recordReview, no materializeMachine. No throw out of runOnce.
        verify( proposals, never() ).recordReview( any(), anyString(), anyString(), anyString(),
            anyDouble(), any() );
        verify( materialization, never() ).materializeMachine( any() );
        // submitted reflects dispatched workers, not completed
        assertEquals( 1, submitted );
    }
}
