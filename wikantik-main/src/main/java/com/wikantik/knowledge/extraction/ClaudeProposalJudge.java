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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mirror of {@link OllamaProposalJudge} that talks to the Anthropic Messages
 * API. Reuses {@link OllamaProposalJudge#SYSTEM_PROMPT} so A/B comparisons
 * between the two backends stay apples-to-apples. Missing API keys, HTTP
 * errors, and malformed responses fail open as
 * {@code Accept(judge_failed: ...)}.
 *
 * <p>Production gating lives at the CLI level
 * ({@code -Dwikantik.kg.judge.allow_claude=true}); the class itself is
 * callable from tests without the gate.
 */
public final class ClaudeProposalJudge implements ProposalJudge {

    private static final Logger LOG = LogManager.getLogger( ClaudeProposalJudge.class );
    private static final Gson GSON = new Gson();
    private static final String ANTHROPIC_BASE = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Set< String > ALLOWED_REASONS = Set.of(
        "ungrounded", "redundant_with_existing_node", "wrong_type", "too_generic", "weak_support" );

    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final HttpClient httpClient;

    public ClaudeProposalJudge( final String apiKey, final String model, final long timeoutMs ) {
        this( apiKey, model, timeoutMs, HttpClient.newHttpClient() );
    }

    /** Test-visible constructor — inject a mock {@link HttpClient}. */
    ClaudeProposalJudge( final String apiKey, final String model, final long timeoutMs,
                         final HttpClient httpClient ) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.httpClient = httpClient;
    }

    @Override
    public String code() {
        return "claude:" + model;
    }

    @Override
    public Verdict judge( final ConsolidatedProposal proposal, final JudgeContext context ) {
        if( apiKey == null || apiKey.isBlank() ) {
            return new Verdict.Accept( proposal.aggregateConfidence(),
                "judge_failed: ANTHROPIC_API_KEY missing" );
        }
        try {
            final String raw = callAnthropic( proposal, context );
            return parseVerdict( raw, proposal );
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: interrupted" );
        } catch( final IOException | RuntimeException e ) {
            LOG.warn( "ClaudeProposalJudge failed for {}: {}", proposal.signature(), e.getMessage() );
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: " + e.getMessage() );
        }
    }

    private String callAnthropic( final ConsolidatedProposal p, final JudgeContext c )
            throws IOException, InterruptedException {
        final String userPrompt = OllamaProposalJudge.buildUserPrompt( p, c );
        final Map< String, Object > body = Map.of(
            "model", model,
            "max_tokens", 1024,
            "system", OllamaProposalJudge.SYSTEM_PROMPT,
            "messages", List.of( Map.of( "role", "user", "content", userPrompt ) )
        );
        final HttpRequest req = HttpRequest.newBuilder( URI.create( ANTHROPIC_BASE ) )
            .timeout( Duration.ofMillis( timeoutMs ) )
            .header( "Content-Type", "application/json" )
            .header( "x-api-key", apiKey )
            .header( "anthropic-version", ANTHROPIC_VERSION )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();
        final HttpResponse< String > res = httpClient.send( req, HttpResponse.BodyHandlers.ofString() );
        if( res.statusCode() / 100 != 2 ) {
            LOG.warn( "ClaudeProposalJudge HTTP {} for sig {}: {}",
                res.statusCode(), p.signature(), res.body() );
            return null;
        }
        // Anthropic shape: { "content": [ { "type":"text", "text":"..." } ], ... }
        final JsonElement root;
        try {
            root = JsonParser.parseString( res.body() );
        } catch( final RuntimeException e ) {
            LOG.warn( "ClaudeProposalJudge non-JSON body for sig {}: {}", p.signature(), e.getMessage() );
            return null;
        }
        if( !root.isJsonObject() ) return null;
        final JsonElement contentArr = root.getAsJsonObject().get( "content" );
        if( contentArr == null || !contentArr.isJsonArray() || contentArr.getAsJsonArray().size() == 0 ) {
            return null;
        }
        final JsonElement first = contentArr.getAsJsonArray().get( 0 );
        if( !first.isJsonObject() ) return null;
        final JsonElement text = first.getAsJsonObject().get( "text" );
        return text == null || text.isJsonNull() ? null : text.getAsString();
    }

    private Verdict parseVerdict( final String raw, final ConsolidatedProposal proposal ) {
        if( raw == null ) {
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: empty response" );
        }
        try {
            final JsonObject obj = JsonParser.parseString( raw ).getAsJsonObject();
            final String verdict = obj.get( "verdict" ).getAsString().toLowerCase( Locale.ROOT );
            final String reason  = obj.has( "reason_code" )
                ? obj.get( "reason_code" ).getAsString() : "ok";
            final String rationale = obj.has( "rationale" )
                ? obj.get( "rationale" ).getAsString() : "";
            return switch( verdict ) {
                case "accept"  -> new Verdict.Accept( proposal.aggregateConfidence(), rationale );
                case "reject"  -> new Verdict.Reject(
                    ALLOWED_REASONS.contains( reason ) ? reason : "weak_support", rationale );
                case "rewrite" -> new Verdict.Accept( proposal.aggregateConfidence(),
                    "judge_failed: rewrite parsing not implemented yet" );
                default -> new Verdict.Accept( proposal.aggregateConfidence(),
                    "judge_failed: unknown verdict " + verdict );
            };
        } catch( final RuntimeException e ) {
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: " + e.getMessage() );
        }
    }
}
