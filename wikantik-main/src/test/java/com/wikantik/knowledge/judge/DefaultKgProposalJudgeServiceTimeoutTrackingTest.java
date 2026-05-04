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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultKgProposalJudgeServiceTimeoutTrackingTest {

    private static KgProposal proposal( UUID id ) {
        return new KgProposal(
            id, "new-edge", "PageA",
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
    @Test
    void records_timeout_on_http_timeout_exception() throws Exception {
        final HttpClient http = mock( HttpClient.class );
        doThrow( new HttpTimeoutException( "request timed out" ) )
            .when( http ).send( any(), any() );
        final KgJudgeTimeoutRepository repo = mock( KgJudgeTimeoutRepository.class );
        when( repo.find( any( UUID.class ) ) ).thenReturn( Optional.empty() );

        final UUID id = UUID.randomUUID();
        final var svc = new DefaultKgProposalJudgeService( http, cfg(), repo );
        final JudgeVerdict v = svc.judge( proposal( id ) );

        assertEquals( "abstain", v.verdict() );
        assertTrue( v.rationale().contains( "timeout" ) );

        final ArgumentCaptor< Integer > bytesCaptor = ArgumentCaptor.forClass( Integer.class );
        verify( repo ).recordTimeout(
            eq( id ),
            anyString(),
            eq( "PageA" ),
            eq( "new-edge" ),
            eq( "gemma4-assist:latest" ),
            bytesCaptor.capture(),
            anyString(),
            eq( 30 ) );
        assertTrue( bytesCaptor.getValue() > 0 );
        verify( repo, never() ).clear( any( UUID.class ) );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void clears_timeout_on_successful_response() throws Exception {
        final String inner = "{\"verdict\":\"approved\",\"confidence\":0.8,\"rationale\":\"ok\"}";
        final String body = "{\"message\":{\"content\":" + escape( inner ) + "}}";
        final HttpClient http = mock( HttpClient.class );
        doReturn( mockResponse( 200, body ) ).when( http ).send( any(), any() );

        final KgJudgeTimeoutRepository repo = mock( KgJudgeTimeoutRepository.class );
        when( repo.find( any( UUID.class ) ) ).thenReturn( Optional.empty() );

        final UUID id = UUID.randomUUID();
        final var svc = new DefaultKgProposalJudgeService( http, cfg(), repo );
        final JudgeVerdict v = svc.judge( proposal( id ) );

        assertEquals( "approved", v.verdict() );
        verify( repo ).clear( id );
        verify( repo, never() ).recordTimeout(
            any( UUID.class ), anyString(), anyString(), anyString(), anyString(),
            anyInt(), anyString(), anyInt() );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void clears_timeout_on_non_2xx_completed_response() throws Exception {
        // The LLM responded — even with 500 the root cause of any prior
        // timeout has cleared. Drop the tracking row.
        final HttpClient http = mock( HttpClient.class );
        doReturn( mockResponse( 500, "boom" ) ).when( http ).send( any(), any() );

        final KgJudgeTimeoutRepository repo = mock( KgJudgeTimeoutRepository.class );
        when( repo.find( any( UUID.class ) ) ).thenReturn( Optional.empty() );

        final UUID id = UUID.randomUUID();
        final var svc = new DefaultKgProposalJudgeService( http, cfg(), repo );
        svc.judge( proposal( id ) );

        verify( repo ).clear( id );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void does_not_clear_or_record_on_generic_io_failure() throws Exception {
        // Connection refused etc. — not a timeout, not a completion. Leave
        // any existing tracked-timeout row alone.
        final HttpClient http = mock( HttpClient.class );
        doThrow( new IOException( "connection refused" ) ).when( http ).send( any(), any() );

        final KgJudgeTimeoutRepository repo = mock( KgJudgeTimeoutRepository.class );
        when( repo.find( any( UUID.class ) ) ).thenReturn( Optional.empty() );

        final var svc = new DefaultKgProposalJudgeService( http, cfg(), repo );
        svc.judge( proposal( UUID.randomUUID() ) );

        verify( repo, never() ).recordTimeout(
            any( UUID.class ), anyString(), anyString(), anyString(), anyString(),
            anyInt(), anyString(), anyInt() );
        verify( repo, never() ).clear( any( UUID.class ) );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void applies_2x_timeout_when_one_prior_timeout() throws Exception {
        final UUID id = UUID.randomUUID();
        final HttpClient http = mock( HttpClient.class );
        doThrow( new HttpTimeoutException( "still timing out" ) )
            .when( http ).send( any(), any() );

        final KgJudgeTimeoutRepository repo = mock( KgJudgeTimeoutRepository.class );
        when( repo.find( id ) ).thenReturn( Optional.of(
            new KgJudgeTimeoutRepository.TimeoutRow(
                id, "sha", "PageA", "new-edge", "gemma4-assist:latest",
                100, 1, "previous timeout", 30, Instant.now(), Instant.now() ) ) );

        final var svc = new DefaultKgProposalJudgeService( http, cfg(), repo );
        svc.judge( proposal( id ) );

        final ArgumentCaptor< HttpRequest > reqCaptor = ArgumentCaptor.forClass( HttpRequest.class );
        verify( http ).send( reqCaptor.capture(), any() );
        final Optional< Duration > timeout = reqCaptor.getValue().timeout();
        assertTrue( timeout.isPresent() );
        // base 30s * (1 + 1) = 60s
        assertEquals( Duration.ofSeconds( 60 ), timeout.get() );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void caps_multiplier_at_3x() throws Exception {
        final UUID id = UUID.randomUUID();
        final HttpClient http = mock( HttpClient.class );
        doThrow( new HttpTimeoutException( "still" ) ).when( http ).send( any(), any() );

        final KgJudgeTimeoutRepository repo = mock( KgJudgeTimeoutRepository.class );
        // count=10 should still produce 3x, not 11x.
        when( repo.find( id ) ).thenReturn( Optional.of(
            new KgJudgeTimeoutRepository.TimeoutRow(
                id, "sha", "PageA", "new-edge", "gemma4-assist:latest",
                100, 10, "x", 30, Instant.now(), Instant.now() ) ) );

        final var svc = new DefaultKgProposalJudgeService( http, cfg(), repo );
        svc.judge( proposal( id ) );

        final ArgumentCaptor< HttpRequest > reqCaptor = ArgumentCaptor.forClass( HttpRequest.class );
        verify( http ).send( reqCaptor.capture(), any() );
        // base 30s * 3 = 90s
        assertEquals( Duration.ofSeconds( 90 ), reqCaptor.getValue().timeout().orElseThrow() );
    }

    @Test
    void null_repo_no_ops_silently() throws Exception {
        // Existing 2-arg constructor (no repo) must continue to work — no NPEs.
        final HttpClient http = mock( HttpClient.class );
        doThrow( new HttpTimeoutException( "t" ) ).when( http ).send( any(), any() );

        final var svc = new DefaultKgProposalJudgeService( http, cfg() );
        final JudgeVerdict v = svc.judge( proposal( UUID.randomUUID() ) );
        assertEquals( "abstain", v.verdict() );
    }

    private static String escape( String s ) {
        return "\"" + s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) + "\"";
    }
}
