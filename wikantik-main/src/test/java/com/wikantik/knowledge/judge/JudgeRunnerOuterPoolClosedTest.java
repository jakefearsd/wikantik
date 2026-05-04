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

import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.PoolClosedException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Verifies that a {@link PoolClosedException} thrown by
 * {@code getProposalsForJudging} (i.e. at batch-acquisition time, before any
 * worker is spawned) is treated as a graceful-shutdown signal: it must not
 * propagate out of {@link JudgeRunner#runOnceQuietly()} and must not be
 * recorded in {@link JudgeRunner.Status#lastRunError()}.
 */
class JudgeRunnerOuterPoolClosedTest {

    @Test
    void poolClosedAtBatchAcquisitionExitsGracefully() {
        // Repo throws PoolClosedException on getProposalsForJudging itself —
        // simulating webapp shutdown before any workers are spawned.
        final JdbcKnowledgeRepository repo = Mockito.mock( JdbcKnowledgeRepository.class );
        when( repo.getProposalsForJudging( Mockito.anyInt() ) )
            .thenThrow( new PoolClosedException(
                "getProposalsForJudging aborted: pool closed",
                new java.sql.SQLException( "Data source is closed" ) ) );

        final KgProposalJudgeService judge = Mockito.mock( KgProposalJudgeService.class );
        final KgMaterializationService materialization = Mockito.mock( KgMaterializationService.class );
        final KgJudgeConfig config = new KgJudgeConfig(
            true, "endpoint", "model", true, 10, 5, 1, 30, 3, "30m" );

        final JudgeRunner runner = new JudgeRunner( repo, judge, materialization, config );

        // runOnceQuietly must NOT throw.
        runner.runOnceQuietly();

        // lastRunError must remain null — PoolClosedException is not a real error.
        final JudgeRunner.Status status = runner.status( 0 );
        assertNull( status.lastRunError(),
            "PoolClosedException at batch-acquisition should not surface as lastRunError" );
    }
}
