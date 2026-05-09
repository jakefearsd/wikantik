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
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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

    // ---------------------------------------------------------------------
    // Pre-flight validator — refuse to call the LLM with garbage payloads.
    // ---------------------------------------------------------------------

    private static KgProposal nodeProposal( Map< String, Object > data, String reasoning ) {
        return new KgProposal(
            UUID.randomUUID(), "new-node", "PageA",
            data, 0.7, reasoning,
            "pending", null, Instant.now(), null,
            "none", null, null, null, null );
    }

    private static KgProposal edgeProposal( Map< String, Object > data, String reasoning ) {
        return new KgProposal(
            UUID.randomUUID(), "new-edge", "PageA",
            data, 0.7, reasoning,
            "pending", null, Instant.now(), null,
            "none", null, null, null, null );
    }

    @Test
    void validate_passes_well_formed_edge() {
        final var p = edgeProposal(
            Map.of( "source", "Foo", "target", "Bar", "relationship", "uses" ),
            "extractor reasoning" );
        assertEquals( Optional.empty(), DefaultKgProposalJudgeService.validateProposalForJudgment( p ) );
    }

    @Test
    void validate_passes_well_formed_node() {
        final var p = nodeProposal(
            Map.of( "name", "Bonds", "nodeType", "Concept" ),
            "discussed on the page" );
        assertEquals( Optional.empty(), DefaultKgProposalJudgeService.validateProposalForJudgment( p ) );
    }

    @Test
    void validate_flags_missing_edge_fields() {
        final var p = edgeProposal( Map.of( "source", "Foo" ), "reason" );
        final var result = DefaultKgProposalJudgeService.validateProposalForJudgment( p );
        assertTrue( result.isPresent() );
        assertTrue( result.get().startsWith( "missing_data:" ), result.get() );
        assertTrue( result.get().contains( "target" ) );
        assertTrue( result.get().contains( "relationship" ) );
    }

    @Test
    void validate_flags_blank_edge_field() {
        final var p = edgeProposal(
            Map.of( "source", "Foo", "target", "  ", "relationship", "uses" ),
            "reason" );
        final var result = DefaultKgProposalJudgeService.validateProposalForJudgment( p );
        assertTrue( result.isPresent() );
        assertTrue( result.get().contains( "target" ), result.get() );
    }

    @Test
    void validate_flags_missing_node_name() {
        final var p = nodeProposal( Map.of( "nodeType", "Concept" ), "reason" );
        final var result = DefaultKgProposalJudgeService.validateProposalForJudgment( p );
        assertTrue( result.isPresent() );
        assertTrue( result.get().contains( "name" ), result.get() );
    }

    @Test
    void validate_flags_missing_reasoning() {
        final var p = nodeProposal( Map.of( "name", "Bonds", "nodeType", "Concept" ), "  " );
        final var result = DefaultKgProposalJudgeService.validateProposalForJudgment( p );
        assertTrue( result.isPresent() );
        assertTrue( result.get().contains( "reasoning" ), result.get() );
    }

    @Test
    void validate_flags_unsupported_proposal_type() {
        final var p = new KgProposal(
            UUID.randomUUID(), "new-cluster", "PageA",
            Map.of(), 0.7, "reason",
            "pending", null, Instant.now(), null,
            "none", null, null, null, null );
        final var result = DefaultKgProposalJudgeService.validateProposalForJudgment( p );
        assertTrue( result.isPresent() );
        assertTrue( result.get().startsWith( "unsupported_proposal_type:" ), result.get() );
        assertTrue( result.get().contains( "new-cluster" ) );
    }

    // ---------------------------------------------------------------------
    // judge() short-circuits BEFORE making an HTTP call when validation fails.
    // ---------------------------------------------------------------------

    @Test
    void judge_short_circuits_node_with_missing_name_without_calling_http() throws Exception {
        final HttpClient http = mock( HttpClient.class );
        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final var p = nodeProposal( Map.of( "nodeType", "Concept" ), "reason" );

        final JudgeVerdict v = svc.judge( p );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().startsWith( "missing_data:" ), v.rationale() );
        verify( http, never() ).send( any(), any() );
    }

    @Test
    void judge_short_circuits_edge_with_missing_relationship_without_calling_http() throws Exception {
        final HttpClient http = mock( HttpClient.class );
        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final var p = edgeProposal( Map.of( "source", "Foo", "target", "Bar" ), "reason" );

        final JudgeVerdict v = svc.judge( p );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().contains( "relationship" ) );
        verify( http, never() ).send( any(), any() );
    }

    // ---------------------------------------------------------------------
    // System prompt branch + user-prompt body shape: edge vs node.
    // ---------------------------------------------------------------------

    @SuppressWarnings( "unchecked" )
    @Test
    void judge_node_proposal_uses_node_system_prompt_with_name_and_node_type() throws Exception {
        final String inner = "{\"verdict\":\"approved\",\"confidence\":0.8,\"rationale\":\"clear concept\"}";
        final String body  = "{\"message\":{\"content\":" + escapeJsonString( inner ) + "}}";
        final HttpClient http = mock( HttpClient.class );
        doReturn( mockResponse( 200, body ) ).when( http ).send( any(), any() );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final var p = nodeProposal(
            Map.of( "name", "Bonds", "nodeType", "Concept" ),
            "page introduces fixed-income securities" );

        final JudgeVerdict v = svc.judge( p );
        assertEquals( "approved", v.verdict() );

        // Inspect the request that was actually sent.
        final ArgumentCaptor< HttpRequest > captor = ArgumentCaptor.forClass( HttpRequest.class );
        verify( http ).send( captor.capture(), any() );
        final String sent = readBody( captor.getValue() );
        assertTrue( sent.contains( "node judge" ),
            "expected node-prompt branch in request body but was:\n" + sent );
        assertFalse( sent.contains( "relationship judge" ),
            "node-prompt request must not include the relationship-judge prompt:\n" + sent );
        assertTrue( sent.contains( "NAME: Bonds" ) );
        assertTrue( sent.contains( "NODE TYPE: Concept" ) );
        assertFalse( sent.contains( "RELATIONSHIP:" ),
            "node-prompt user message must not include RELATIONSHIP: line:\n" + sent );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void judge_edge_proposal_uses_edge_system_prompt_with_triple_fields() throws Exception {
        final String inner = "{\"verdict\":\"rejected\",\"confidence\":0.7,\"rationale\":\"ok\"}";
        final String body  = "{\"message\":{\"content\":" + escapeJsonString( inner ) + "}}";
        final HttpClient http = mock( HttpClient.class );
        doReturn( mockResponse( 200, body ) ).when( http ).send( any(), any() );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        svc.judge( edgeProposal(
            Map.of( "source", "Apple", "target", "Fruit", "relationship", "is-a" ),
            "page categorises Apple as a fruit" ) );

        final ArgumentCaptor< HttpRequest > captor = ArgumentCaptor.forClass( HttpRequest.class );
        verify( http ).send( captor.capture(), any() );
        final String sent = readBody( captor.getValue() );
        assertTrue( sent.contains( "relationship judge" ),
            "expected edge-prompt branch:\n" + sent );
        assertTrue( sent.contains( "SOURCE: Apple" ) );
        assertTrue( sent.contains( "TARGET: Fruit" ) );
        assertTrue( sent.contains( "RELATIONSHIP: is-a" ) );
    }

    /** Reads the body bytes the request would publish — used for asserting prompt branch. */
    private static String readBody( HttpRequest req ) {
        final var subscriber = java.net.http.HttpResponse.BodySubscribers.ofString( java.nio.charset.StandardCharsets.UTF_8 );
        req.bodyPublisher().ifPresent( bp -> bp.subscribe( new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe( java.util.concurrent.Flow.Subscription s ) { s.request( Long.MAX_VALUE ); subscriber.onSubscribe( s ); }
            @Override public void onNext( java.nio.ByteBuffer item ) { subscriber.onNext( java.util.List.of( item ) ); }
            @Override public void onError( Throwable t ) { subscriber.onError( t ); }
            @Override public void onComplete() { subscriber.onComplete(); }
        } ) );
        try { return subscriber.getBody().toCompletableFuture().get(); }
        catch ( Exception e ) { throw new RuntimeException( e ); }
    }
}
