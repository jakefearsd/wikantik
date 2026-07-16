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
package com.wikantik.knowledge.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wikantik.llm.activity.LlmActivityLog;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code RecordingEmbeddingClientTest} / {@code RecordingKgProposalJudgeServiceTest}:
 * proves the bundle's listwise LLM reranker gets the same {@code /admin/llm-activity}
 * observability parity as embedding/extraction/judge calls (Task 1.7).
 */
class RecordingSectionRerankerTest {

    private static CandidateSection sec( final String head ) {
        return new CandidateSection( "p", List.of( head ), head + " text", 0.5 );
    }

    @Test
    void recordsOkOnRerank() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final List< CandidateSection > in = List.of( sec( "A" ), sec( "B" ) );
        final List< CandidateSection > out = List.of( sec( "B" ), sec( "A" ) );
        final SectionReranker delegate = ( query, sections ) -> out;
        final RecordingSectionReranker rec =
            new RecordingSectionReranker( delegate, log, "ollama", "gemma4:e4b" );

        assertSame( out, rec.rerank( "q", in ) );
        final LlmActivityLog.Snapshot snap = log.snapshot( 10, null, null );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( "SECTION_RERANK", snap.calls().get( 0 ).subsystem() );
    }

    @Test
    void recordsErrorAndRethrowsOnFailure() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final SectionReranker delegate = ( query, sections ) -> {
            throw new RuntimeException( "rerank backend down" );
        };
        final RecordingSectionReranker rec =
            new RecordingSectionReranker( delegate, log, "ollama", "gemma4:e4b" );

        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertThrows( RuntimeException.class, () -> rec.rerank( "q", in ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }
}
