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

class DefaultKgProposalJudgeServiceTest {

    private static KgProposal sampleProposal() {
        return new KgProposal(
            UUID.randomUUID(), "new-edge", "PageA",
            Map.<String, Object>of( "source", "Foo", "target", "Bar", "relationship", "uses" ),
            0.7, "extractor reasoning",
            "pending", null, Instant.now(), null,
            "none", null, null, null, null );
    }

    private static KgJudgeConfig cfg() {
        return new KgJudgeConfig( true, "http://localhost:11434",
            "gemma4-assist:latest", false, 5, 50, 2, 30, 3, "30m" );
    }

    @SuppressWarnings( "unchecked" )
    private static HttpResponse< String > mockResponse( int status, String body ) {
        final HttpResponse< String > r = mock( HttpResponse.class );
        when( r.statusCode() ).thenReturn( status );
        when( r.body() ).thenReturn( body );
        return r;
    }

    @SuppressWarnings( "unchecked" )
    private static HttpClient httpClientReturning( HttpResponse< String > resp ) throws Exception {
        final HttpClient http = mock( HttpClient.class );
        doReturn( resp ).when( http ).send( any(), any() );
        return http;
    }

    @Test
    void judge_returns_approved_when_ollama_returns_strict_json() throws Exception {
        final String inner = "{\"verdict\":\"approved\",\"confidence\":0.85,\"rationale\":\"strong evidence\"}";
        // /api/chat wraps in {message:{content:"..."}}
        final String body = "{\"message\":{\"role\":\"assistant\",\"content\":" + escapeJsonString( inner ) + "}}";
        final HttpClient http = httpClientReturning( mockResponse( 200, body ) );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( "approved", v.verdict() );
        assertEquals( 0.85, v.confidence() );
        assertEquals( "gemma4-assist:latest", v.model() );
        assertEquals( "strong evidence", v.rationale() );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void judge_returns_abstain_on_http_error() throws Exception {
        final HttpClient http = mock( HttpClient.class );
        doThrow( new IOException( "connection refused" ) ).when( http ).send( any(), any() );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().contains( "judge_unavailable" ) );
    }

    @Test
    void judge_returns_abstain_on_non_2xx_status() throws Exception {
        final HttpClient http = httpClientReturning( mockResponse( 500, "boom" ) );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().contains( "http 500" ) );
    }

    @Test
    void judge_returns_abstain_on_malformed_inner_json() throws Exception {
        final String body = "{\"message\":{\"content\":\"not-valid-json\"}}";
        final HttpClient http = httpClientReturning( mockResponse( 200, body ) );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().contains( "parse error" ) );
    }

    @Test
    void judge_clamps_confidence_above_one() throws Exception {
        final String inner = "{\"verdict\":\"approved\",\"confidence\":1.5,\"rationale\":\"x\"}";
        final String body = "{\"message\":{\"content\":" + escapeJsonString( inner ) + "}}";
        final HttpClient http = httpClientReturning( mockResponse( 200, body ) );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( sampleProposal() );

        assertEquals( 1.0, v.confidence() );
    }

    private static String escapeJsonString( String s ) {
        return "\"" + s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) + "\"";
    }
}
