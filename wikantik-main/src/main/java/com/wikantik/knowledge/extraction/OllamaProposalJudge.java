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
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;import java.util.Locale;
import java.util.Map;

/**
 * Opt-in proposal judge backed by an Ollama /api/chat endpoint with
 * {@code format: "json"}. Same wire shape as {@link OllamaPageExtractor}; the
 * judge runs after consolidation and returns one of {@link Verdict.Accept},
 * {@link Verdict.Reject}, or {@link Verdict.Rewrite}. Malformed or otherwise
 * failed responses fail open as
 * {@code Accept(p.aggregateConfidence(), "judge_failed: ...")} so a
 * misbehaving judge can never silently drop proposals.
 */
public final class OllamaProposalJudge implements ProposalJudge {

    private static final Logger LOG = LogManager.getLogger( OllamaProposalJudge.class );
    private static final Gson GSON = new Gson();

    /** System prompt shared with {@link ClaudeProposalJudge} so A/B comparisons are apples-to-apples. */
    static final String SYSTEM_PROMPT = """
        You are a strict reviewer for a small, curated knowledge graph. Reject anything
        that fails ANY of these tests:

        1. Ungrounded: the evidence_span doesn't actually support the claim.
        2. Too generic: the entity/predicate is so general it adds no graph value
           (Concept, Agent, System, Software, "is_related_to").
        3. Redundant: a near-identical node already exists in the dictionary.
        4. Wrong type: the type doesn't match the entity (e.g. "Kafka" typed as Person).
        5. Weak support: only one weak quote and aggregate_confidence < 0.55.

        When the entity is right but the form is wrong (e.g. "GitHub Inc." but "GitHub"
        exists), rewrite to the canonical form rather than reject.

        Output strict JSON: { "verdict": "accept"|"reject"|"rewrite",
                              "reason_code": str, "rationale": <=30 words,
                              "rewritten": { ...same shape as input... } | null }
        """;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final long timeoutMs;

    public OllamaProposalJudge( final HttpClient httpClient, final String baseUrl,
                                final String model, final long timeoutMs ) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String code() {
        final String trimmed = model.trim();
        return "ollama:" + ( trimmed.endsWith( ":latest" )
            ? trimmed.substring( 0, trimmed.length() - ":latest".length() )
            : trimmed );
    }

    @Override
    public Verdict judge( final ConsolidatedProposal proposal, final JudgeContext context ) {
        try {
            final String raw = callOllama( proposal, context );
            return ProposalVerdictParser.parse( raw, proposal );
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: interrupted" );
        } catch( final IOException | RuntimeException e ) {
            LOG.warn( "OllamaProposalJudge failed for {}: {}", proposal.signature(), e.getMessage() );
            return new Verdict.Accept( proposal.aggregateConfidence(), "judge_failed: " + e.getMessage() );
        }
    }

    private String callOllama( final ConsolidatedProposal p, final JudgeContext c )
            throws IOException, InterruptedException {
        final String userPrompt = buildUserPrompt( p, c );
        final Map< String, Object > body = OllamaChatRequest.body( model, SYSTEM_PROMPT, userPrompt, null );
        final String url = stripTrailingSlash( baseUrl ) + "/api/chat";
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .timeout( Duration.ofMillis( timeoutMs ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();
        final HttpResponse< String > res = httpClient.send( req, HttpResponse.BodyHandlers.ofString() );
        if( res.statusCode() / 100 != 2 ) {
            LOG.warn( "OllamaProposalJudge HTTP {} for sig {}", res.statusCode(), p.signature() );
            return null;
        }
        final JsonElement root;
        try {
            root = JsonParser.parseString( res.body() );
        } catch( final RuntimeException e ) {
            LOG.warn( "OllamaProposalJudge non-JSON body for sig {}: {}", p.signature(), e.getMessage() );
            return null;
        }
        if( !root.isJsonObject() ) return null;
        final JsonElement message = root.getAsJsonObject().get( "message" );
        if( message == null || !message.isJsonObject() ) return null;
        final JsonElement content = message.getAsJsonObject().get( "content" );
        return content == null || content.isJsonNull() ? null : content.getAsString();
    }

    /** Build the user-side payload. Package-private so {@link ClaudeProposalJudge} can reuse it. */
    static String buildUserPrompt( final ConsolidatedProposal p, final JudgeContext c ) {
        final StringBuilder user = new StringBuilder( 2048 );
        user.append( "Candidate: " ).append( GSON.toJson( p ) ).append( '\n' );
        if( !c.sourcePageBodies().isEmpty() ) {
            user.append( "Supporting page excerpts:\n" );
            for( final Map.Entry< String, String > entry : c.sourcePageBodies().entrySet() ) {
                final String body = entry.getValue() == null ? "" : entry.getValue();
                final String excerpt = body.length() > 500 ? body.substring( 0, 500 ) : body;
                user.append( "  " ).append( entry.getKey() ).append( ": " ).append( excerpt ).append( '\n' );
            }
        }
        if( !c.neighborhoodNodes().isEmpty() ) {
            user.append( "Nearby existing nodes:\n" );
            for( final KgNode n : c.neighborhoodNodes() ) {
                user.append( "  - " ).append( n.name() ).append( " :: " )
                    .append( n.nodeType() == null ? "concept" : n.nodeType().toLowerCase( Locale.ROOT ) ).append( '\n' );
            }
        }
        user.append( "\nReturn ONLY the JSON object." );
        return user.toString();
    }

    private static String stripTrailingSlash( final String s ) {
        if( s == null || s.isEmpty() ) return "";
        return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }

}
