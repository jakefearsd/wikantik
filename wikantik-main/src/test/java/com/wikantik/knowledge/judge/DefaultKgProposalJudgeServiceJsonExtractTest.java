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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DefaultKgProposalJudgeService#extractJsonObject(String)},
 * covering the LLM response shapes that trip up a naive Gson parse.
 */
class DefaultKgProposalJudgeServiceJsonExtractTest {

    // -----------------------------------------------------------------------
    // extractJsonObject unit tests
    // -----------------------------------------------------------------------

    @Test
    void extractJsonObject_plain_json_unchanged() {
        final String input = "{\"verdict\":\"approved\",\"confidence\":0.9,\"rationale\":\"clear\"}";
        assertEquals( input, DefaultKgProposalJudgeService.extractJsonObject( input ) );
    }

    @Test
    void extractJsonObject_trailing_tool_call_stripped() {
        final String json    = "{\"verdict\":\"abstain\",\"confidence\":0.0,\"rationale\":\"ambiguous\"}";
        final String tail    = "<tool_call|>The user has provided a structured input for a knowledge-graph fact judge task.";
        final String input   = json + tail;
        assertEquals( json, DefaultKgProposalJudgeService.extractJsonObject( input ) );
    }

    @Test
    void extractJsonObject_leading_prose_stripped() {
        final String json  = "{\"verdict\":\"rejected\",\"confidence\":0.8,\"rationale\":\"wrong\"}";
        final String input = "Here is my response: " + json;
        assertEquals( json, DefaultKgProposalJudgeService.extractJsonObject( input ) );
    }

    @Test
    void extractJsonObject_braces_inside_strings_not_confused() {
        // The rationale contains literal braces — they must not confuse the depth counter.
        final String input = "{\"verdict\":\"approved\",\"confidence\":0.7,\"rationale\":\"use {x} notation here\"}";
        assertEquals( input, DefaultKgProposalJudgeService.extractJsonObject( input ) );
    }

    @Test
    void extractJsonObject_no_braces_returns_input_unchanged() {
        final String input = "error: bad request";
        assertEquals( input, DefaultKgProposalJudgeService.extractJsonObject( input ) );
    }

    @Test
    void extractJsonObject_unbalanced_open_brace_returns_input_unchanged() {
        final String input = "{\"verdict\":\"approved\"";
        assertEquals( input, DefaultKgProposalJudgeService.extractJsonObject( input ) );
    }

    @Test
    void extractJsonObject_null_returns_null() {
        assertNull( DefaultKgProposalJudgeService.extractJsonObject( null ) );
    }

    /** Exact production-failure shape: valid JSON followed by <tool_call|> prose. */
    @Test
    void extractJsonObject_production_failure_example() {
        final String json  = "{\"verdict\":\"abstain\",\"confidence\":0.0,\"rationale\":\"The triple lacks factual grounding.\"}";
        final String prose = "<tool_call|>The user has provided a structured input for a knowledge-graph fact judge task. "
            + "However, the input is incomplete and lacks the necessary components to make a judgment...";
        final String input = json + prose;
        assertEquals( json, DefaultKgProposalJudgeService.extractJsonObject( input ) );
    }

    // -----------------------------------------------------------------------
    // Integration test: parseResponse via judge() — production-failure shape
    // -----------------------------------------------------------------------

    /** Feed the production-failure response through the full parse path. */
    @Test
    void judge_parses_production_failure_response_successfully() throws Exception {
        final String innerJson = "{\"verdict\":\"abstain\",\"confidence\":0.0,\"rationale\":\"The triple lacks factual grounding.\"}";
        final String innerWithTail = innerJson
            + "<tool_call|>The user has provided a structured input for a knowledge-graph fact judge task. "
            + "However, the input is incomplete and lacks the necessary components to make a judgment...";
        // Wrap in the /api/chat envelope
        final String body = "{\"message\":{\"role\":\"assistant\",\"content\":"
            + escapeJsonString( innerWithTail ) + "}}";

        final HttpResponse< String > resp = mockResponse( 200, body );
        final HttpClient http             = httpClientReturning( resp );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        // The verdict object IS valid, so we should get a real abstain from the LLM —
        // NOT a parse-error abstain.
        assertEquals( "abstain", v.verdict() );
        assertEquals( 0.0, v.confidence() );
        assertEquals( "The triple lacks factual grounding.", v.rationale() );
    }

    @Test
    void judge_fenced_json_response_parses_successfully() throws Exception {
        // LLM wraps the verdict object in ```json fences — extractJsonObject must still recover it.
        final String inner = "```json\n"
            + "{\"verdict\":\"approved\",\"confidence\":0.9,\"rationale\":\"Well grounded.\"}\n```";
        final String body = "{\"message\":{\"role\":\"assistant\",\"content\":"
            + escapeJsonString( inner ) + "}}";
        final var svc = new DefaultKgProposalJudgeService( httpClientReturning( mockResponse( 200, body ) ), cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );
        assertEquals( "approved", v.verdict() );
        assertEquals( 0.9, v.confidence() );
        assertEquals( "Well grounded.", v.rationale() );
    }

    @Test
    void judge_truncated_response_degrades_to_transient_abstain() throws Exception {
        // Truncated mid-object (unbalanced braces) — must NOT crash; must be a transient abstain.
        final String inner = "{\"verdict\":\"approved\",\"confidence\":0.9,\"rationale\":\"Well groun";
        final String body = "{\"message\":{\"role\":\"assistant\",\"content\":"
            + escapeJsonString( inner ) + "}}";
        final var svc = new DefaultKgProposalJudgeService( httpClientReturning( mockResponse( 200, body ) ), cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );
        assertEquals( JudgeVerdict.ABSTAIN, v.verdict() );
        assertTrue( v.rationale().startsWith( "judge_unavailable:" ),
            "truncation must yield a transient (judge_unavailable:) abstain, got: " + v.rationale() );
    }

    @Test
    void judge_message_without_content_key_is_transient_abstain_not_npe() throws Exception {
        // message object present but no "content" — previously NPE'd into a generic parse error.
        final String body = "{\"message\":{\"role\":\"assistant\"}}";
        final var svc = new DefaultKgProposalJudgeService( httpClientReturning( mockResponse( 200, body ) ), cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );
        assertEquals( JudgeVerdict.ABSTAIN, v.verdict() );
        assertEquals( "judge_unavailable: response missing content", v.rationale() );
    }

    @Test
    void judge_message_with_null_content_is_transient_abstain_not_npe() throws Exception {
        // "content" present but JSON null (non-primitive) — guard's second arm must catch it.
        final String body = "{\"message\":{\"role\":\"assistant\",\"content\":null}}";
        final var svc = new DefaultKgProposalJudgeService( httpClientReturning( mockResponse( 200, body ) ), cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );
        assertEquals( JudgeVerdict.ABSTAIN, v.verdict() );
        assertEquals( "judge_unavailable: response missing content", v.rationale() );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static KgJudgeConfig cfg() {
        return new KgJudgeConfig( true, "http://localhost:11434",
            "gemma4-assist:latest", false, 5, 50, 2, 30, 3, "30m" );
    }

    private static com.wikantik.api.knowledge.KgProposal sampleProposal() {
        return new com.wikantik.api.knowledge.KgProposal(
            UUID.randomUUID(), "new-edge", "PageA",
            Map.< String, Object >of( "source", "Foo", "target", "Bar", "relationship", "uses" ),
            0.7, "extractor reasoning",
            "pending", null, Instant.now(), null,
            "none", null, null, null, null );
    }

    @SuppressWarnings( "unchecked" )
    private static HttpResponse< String > mockResponse( final int status, final String body ) {
        final HttpResponse< String > r = mock( HttpResponse.class );
        when( r.statusCode() ).thenReturn( status );
        when( r.body() ).thenReturn( body );
        return r;
    }

    @SuppressWarnings( "unchecked" )
    private static HttpClient httpClientReturning( final HttpResponse< String > resp ) throws Exception {
        final HttpClient http = mock( HttpClient.class );
        doReturn( resp ).when( http ).send( any(), any() );
        return http;
    }

    private static String escapeJsonString( final String s ) {
        return "\"" + s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) + "\"";
    }
}
