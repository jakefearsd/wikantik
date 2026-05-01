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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.SupportEvidence;
import com.wikantik.api.knowledge.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NoOpProposalJudgeTest {

    @Test
    void nodeProposalAccepted() {
        final NoOpProposalJudge judge = new NoOpProposalJudge();
        final SupportEvidence e = new SupportEvidence("P", "x is a y", 0.9, "ollama:gemma4");
        final ConsolidatedProposal p = ConsolidatedProposal.newNode(
            "sig-1", "X", "Concept", List.of(e), 0.9);
        final Verdict v = judge.judge(p, new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertEquals(0.9, ((Verdict.Accept) v).finalConfidence(), 1e-9);
    }

    @Test
    void edgeProposalAcceptedAtAggregateConfidence() {
        final NoOpProposalJudge judge = new NoOpProposalJudge();
        final SupportEvidence e = new SupportEvidence("P", "X relates to Y", 0.42, "ollama:gemma4");
        final ConsolidatedProposal p = ConsolidatedProposal.newEdge(
            "sig-2", "X", "Y", "relates_to", List.of(e), 0.42);
        final Verdict v = judge.judge(p, new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertEquals(0.42, ((Verdict.Accept) v).finalConfidence(), 1e-9);
    }

    @Test
    void rationaleMentionsNoOp() {
        final NoOpProposalJudge judge = new NoOpProposalJudge();
        final ConsolidatedProposal p = ConsolidatedProposal.newNode(
            "sig-3", "X", "Concept",
            List.of(new SupportEvidence("P", "x", 1.0, "ollama:gemma4")),
            1.0);
        final Verdict v = judge.judge(p, new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertNotNull(((Verdict.Accept) v).rationale());
        assertFalse(((Verdict.Accept) v).rationale().isBlank());
    }

    @Test
    void codeIsNoop() {
        assertEquals("noop", new NoOpProposalJudge().code());
    }
}
