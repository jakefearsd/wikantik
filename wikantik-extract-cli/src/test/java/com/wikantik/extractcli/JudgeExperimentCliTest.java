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
package com.wikantik.extractcli;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeExperimentCliTest {

    @Test
    void argsRequireJudge() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse( new String[]{ "--output", "/tmp/x.json" } ) );
        assertTrue( ex.getMessage().contains( "--judge is required" ),
            () -> "unexpected: " + ex.getMessage() );
    }

    @Test
    void argsRequireOutput() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse( new String[]{ "--judge", "ollama" } ) );
        assertTrue( ex.getMessage().contains( "--output is required" ),
            () -> "unexpected: " + ex.getMessage() );
    }

    @Test
    void argsRejectNoneJudge() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse(
                new String[]{ "--judge", "none", "--output", "/tmp/x.json" } ) );
        assertTrue( ex.getMessage().contains( "ollama" ) );
    }

    @Test
    void argsParseOk() {
        final JudgeExperimentCli.Args a = JudgeExperimentCli.Args.parse( new String[]{
            "--judge", "ollama",
            "--judge-model", "qwen3.5:9b",
            "--sample", "25",
            "--output", "/tmp/judge.json"
        } );
        assertEquals( "ollama", a.judge );
        assertEquals( "qwen3.5:9b", a.judgeModel );
        assertEquals( 25, a.sample );
        assertEquals( "/tmp/judge.json", a.output );
    }

    @Test
    void hydrateNodeProposal() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:Kafka:Technology",
            "new-node",
            "{\"name\":\"Kafka\",\"nodeType\":\"Technology\"}",
            "[{\"sourcePage\":\"P1\",\"evidenceSpan\":\"Kafka is fast\",\"confidence\":0.9,\"extractorCode\":\"ollama:gemma\"}]",
            "P1",
            0.9 );
        assertNotNull( p );
        assertEquals( ConsolidatedProposal.Kind.NEW_NODE, p.kind() );
        assertEquals( "Kafka", p.displayName() );
        assertEquals( "Technology", p.type() );
        assertEquals( 1, p.support().size() );
        assertEquals( "P1", p.support().get( 0 ).sourcePage() );
    }

    @Test
    void hydrateEdgeProposal() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "edge:A:B:depends_on",
            "new-edge",
            "{\"source\":\"A\",\"target\":\"B\",\"relationship\":\"depends_on\"}",
            "[{\"sourcePage\":\"P\",\"evidenceSpan\":\"A depends on B\",\"confidence\":0.8,\"extractorCode\":\"ollama:gemma\"}]",
            "P",
            0.8 );
        assertNotNull( p );
        assertEquals( ConsolidatedProposal.Kind.NEW_EDGE, p.kind() );
        assertEquals( "A", p.source() );
        assertEquals( "B", p.target() );
        assertEquals( "depends_on", p.predicate() );
    }

    @Test
    void hydrateSkipsBlankSignature() {
        assertNull( JudgeExperimentCli.hydrate(
            "  ", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            "[]", "P", 0.5 ) );
    }

    @Test
    void hydrateSkipsEdgeMissingFields() {
        assertNull( JudgeExperimentCli.hydrate(
            "edge:bad", "new-edge",
            "{\"source\":\"A\"}",
            "[]", "P", 0.5 ) );
    }

    @Test
    void hydrateFallsBackOnEmptySupport() {
        // Empty support array + a fallback source_page → synthesize one stub
        // SupportEvidence so the proposal is still judgable.
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:X:Concept", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            null, "FallbackPage", 0.5 );
        assertNotNull( p );
        assertEquals( 1, p.support().size() );
        assertEquals( "FallbackPage", p.support().get( 0 ).sourcePage() );
    }

    @Test
    void judgeStatsTracksJudgeFailedAccepts() {
        final JudgeExperimentCli.JudgeStats stats = new JudgeExperimentCli.JudgeStats( "ollama:test" );
        stats.record( new Verdict.Accept( 0.9, "real accept" ) );
        stats.record( new Verdict.Accept( 0.5, "judge_failed: HTTP 500" ) );
        stats.record( new Verdict.Reject( "too_generic", "vague" ) );
        stats.record( new Verdict.Reject( "too_generic", "vague again" ) );
        stats.record( new Verdict.Reject( "weak_support", "thin" ) );
        assertEquals( 2, stats.accepted );
        assertEquals( 1, stats.judgeFailed );
        assertEquals( 3, stats.rejected );
        assertEquals( 2, stats.rejectReasons.get( "too_generic" ) );
        assertEquals( 1, stats.rejectReasons.get( "weak_support" ) );
    }
}
