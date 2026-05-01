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

class ClaudeProposalJudgeTest {

    private static ConsolidatedProposal sampleProposal() {
        return ConsolidatedProposal.newNode(
            "sig", "Kafka", "Technology",
            List.of(new SupportEvidence("P", "Kafka is fast", 0.9, "ollama:gemma")),
            0.9);
    }

    @Test
    void noApiKeyFailsOpen() {
        final ClaudeProposalJudge j = new ClaudeProposalJudge(null, "claude-haiku-4-5", 60_000L);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept) v).rationale().contains("ANTHROPIC_API_KEY missing"));
    }

    @Test
    void blankApiKeyFailsOpen() {
        final ClaudeProposalJudge j = new ClaudeProposalJudge("   ", "claude-haiku-4-5", 60_000L);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept) v).rationale().contains("ANTHROPIC_API_KEY missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesAcceptVerdict() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(
            "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"verdict\\\":\\\"accept\\\",\\\"reason_code\\\":\\\"ok\\\",\\\"rationale\\\":\\\"strong\\\"}\"}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final ClaudeProposalJudge j = new ClaudeProposalJudge("sk-test", "claude-haiku-4-5", 60_000L, client);
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
            "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"verdict\\\":\\\"reject\\\",\\\"reason_code\\\":\\\"too_generic\\\",\\\"rationale\\\":\\\"vague\\\"}\"}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final ClaudeProposalJudge j = new ClaudeProposalJudge("sk-test", "claude-haiku-4-5", 60_000L, client);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Reject.class, v);
        assertEquals("too_generic", ((Verdict.Reject) v).reasonCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpErrorFailsOpen() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(401);
        when(resp.body()).thenReturn("{\"error\":\"unauthorized\"}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final ClaudeProposalJudge j = new ClaudeProposalJudge("sk-test", "claude-haiku-4-5", 60_000L, client);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept) v).rationale().startsWith("judge_failed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedTextFailsOpen() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(
            "{\"content\":[{\"type\":\"text\",\"text\":\"not json\"}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final ClaudeProposalJudge j = new ClaudeProposalJudge("sk-test", "claude-haiku-4-5", 60_000L, client);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept) v).rationale().startsWith("judge_failed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyContentArrayFailsOpen() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"content\":[]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        final ClaudeProposalJudge j = new ClaudeProposalJudge("sk-test", "claude-haiku-4-5", 60_000L, client);
        final Verdict v = j.judge(sampleProposal(), new JudgeContext(Map.of(), List.of()));
        assertInstanceOf(Verdict.Accept.class, v);
        assertTrue(((Verdict.Accept) v).rationale().startsWith("judge_failed"));
    }

    @Test
    void codeIncludesModel() {
        final ClaudeProposalJudge j = new ClaudeProposalJudge("k", "claude-haiku-4-5", 1_000L);
        assertEquals("claude:claude-haiku-4-5", j.code());
    }
}
