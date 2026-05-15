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
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingKgProposalJudgeServiceTest {

    private static KgProposal proposal() {
        return new KgProposal( UUID.randomUUID(), "NEW_NODE", "PageA",
            Map.of( "name", "Docker" ), 0.8, "reasoning",
            "pending", null, null, null, "none", null, null, null, null );
    }

    @Test
    void recordsOkOnVerdict() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final JudgeVerdict verdict = new JudgeVerdict( JudgeVerdict.APPROVED, 0.9, "looks good", "gemma" );
        final KgProposalJudgeService delegate = p -> verdict;
        final RecordingKgProposalJudgeService rec =
            new RecordingKgProposalJudgeService( delegate, log, "ollama", "gemma" );

        assertSame( verdict, rec.judge( proposal() ) );
        assertEquals( "OK", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
        assertEquals( "PROPOSAL_JUDGE", log.snapshot( 10, null, null ).calls().get( 0 ).subsystem() );
    }

    @Test
    void recordsErrorAndRethrowsWhenDelegateThrows() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final KgProposalJudgeService delegate = p -> { throw new RuntimeException( "judge down" ); };
        final RecordingKgProposalJudgeService rec =
            new RecordingKgProposalJudgeService( delegate, log, "ollama", "gemma" );

        assertThrows( RuntimeException.class, () -> rec.judge( proposal() ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }
}
