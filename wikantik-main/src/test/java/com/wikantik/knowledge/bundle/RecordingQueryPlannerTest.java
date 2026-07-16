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
 * proves the bundle's query-decomposition planner gets the same {@code /admin/llm-activity}
 * observability parity as embedding/extraction/judge calls (Task 1.7).
 */
class RecordingQueryPlannerTest {

    @Test
    void recordsOkOnPlan() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final List< String > subs = List.of( "sub one", "sub two" );
        final QueryPlanner delegate = query -> subs;
        final RecordingQueryPlanner rec =
            new RecordingQueryPlanner( delegate, log, "ollama", "gemma4-assist:latest" );

        assertSame( subs, rec.plan( "canary vs blue-green" ) );
        final LlmActivityLog.Snapshot snap = log.snapshot( 10, null, null );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( "QUERY_DECOMPOSITION", snap.calls().get( 0 ).subsystem() );
    }

    @Test
    void recordsErrorAndRethrowsWhenDelegateThrows() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 500 );
        final QueryPlanner delegate = query -> {
            throw new RuntimeException( "planner backend down" );
        };
        final RecordingQueryPlanner rec =
            new RecordingQueryPlanner( delegate, log, "ollama", "gemma4-assist:latest" );

        assertThrows( RuntimeException.class, () -> rec.plan( "q" ) );
        assertEquals( "ERROR", log.snapshot( 10, null, null ).calls().get( 0 ).status() );
    }
}
