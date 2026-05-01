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

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaProposalJudgeTest {

    private static ConsolidatedProposal sampleProposal() {
        return ConsolidatedProposal.newNode(
            "sig", "Kafka", "Technology",
            List.of(new SupportEvidence("P", "Kafka is fast", 0.9, "ollama:gemma")),
            0.9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesAcceptVerdict() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(
            "{\"message\":{\"content\":\"{\\\"verdict\\\":\\\"accept\\\",\\\"reason_code\\\":\\\"ok\\\",\\\"rationale\\\":\\\"strong evidence\\\"}\"}}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final OllamaProposalJudge j = new OllamaProposalJudge(client, "http://x", "qwen3.5:9b", 60_000L);
        final Verdict v = j.judge(sampleProposal(),
            new JudgeContext(Map.of("P", "Kafka is fast"), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesRejectVerdict() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(
            "{\"message\":{\"content\":\"{\\\"verdict\\\":\\\"reject\\\",\\\"reason_code\\\":\\\"too_generic\\\",\\\"rationale\\\":\\\"too vague\\\"}\"}}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final OllamaProposalJudge j = new OllamaProposalJudge(client, "http://x", "m", 60_000L);
        final ConsolidatedProposal p = ConsolidatedProposal.newNode(
            "sig", "Concept", "Concept",
            List.of(new SupportEvidence("P", "x", 0.5, "ollama:gemma")),
            0.5);
        final Verdict v = j.judge(p, new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Reject.class, v,
            () -> "got " + v + (v instanceof Verdict.Accept a ? " rationale=" + a.rationale() : ""));
        assertEquals("too_generic", ((Verdict.Reject) v).reasonCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unrecognizedRejectReasonCollapsesToWeakSupport() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(
            "{\"message\":{\"content\":\"{\\\"verdict\\\":\\\"reject\\\",\\\"reason_code\\\":\\\"because_i_said_so\\\",\\\"rationale\\\":\\\"meh\\\"}\"}}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final OllamaProposalJudge j = new OllamaProposalJudge(client, "http://x", "m", 60_000L);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Reject.class, v);
        assertEquals("weak_support", ((Verdict.Reject) v).reasonCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedJudgeFailsOpen() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("not json");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final OllamaProposalJudge j = new OllamaProposalJudge(client, "http://x", "m", 60_000L);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        // Fail-open: malformed verdict accepts.
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept) v).rationale().startsWith("judge_failed"),
            () -> "expected judge_failed rationale, got: " + ((Verdict.Accept) v).rationale());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpErrorFailsOpen() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        when(resp.body()).thenReturn("oops");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final OllamaProposalJudge j = new OllamaProposalJudge(client, "http://x", "m", 60_000L);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept) v).rationale().startsWith("judge_failed"));
    }

    @Test
    void codeStripsLatestSuffix() {
        final OllamaProposalJudge j = new OllamaProposalJudge(
            mock(HttpClient.class), "http://x", "qwen3.5:latest", 1_000L);
        assertEquals("ollama:qwen3.5", j.code());
    }

    @Test
    void codeKeepsExplicitTag() {
        final OllamaProposalJudge j = new OllamaProposalJudge(
            mock(HttpClient.class), "http://x", "qwen3.5:9b", 1_000L);
        assertEquals("ollama:qwen3.5:9b", j.code());
    }
}
