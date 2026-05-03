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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.JudgeVerdict;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calls an Ollama-hosted LLM with the judge system prompt. Returns a strict
 * verdict; on any HTTP/parse/timeout failure, returns an abstain verdict with
 * the failure reason in the rationale (caller decides whether to retry).
 */
public class DefaultKgProposalJudgeService implements KgProposalJudgeService {

    private static final Logger LOG = LogManager.getLogger( DefaultKgProposalJudgeService.class );
    private static final Gson GSON = new Gson();

    static final String SYSTEM_PROMPT = """
        You are a knowledge-graph fact judge. You are given an extracted relationship
        proposal: a (source, target, relationship) triple, the source page name, the
        extractor's confidence score, and the extractor's free-text reasoning.

        Decide whether the proposed triple is well-supported as a factual relationship
        on the source page. Return STRICT JSON with three keys and nothing else:

          {"verdict":"approved|rejected|abstain","confidence":0.0..1.0,"rationale":"..."}

        - approved: clear factual support; the triple should join the graph.
        - rejected: clearly unsupported, contradicted, or nonsensical.
        - abstain:  evidence is ambiguous or insufficient to commit either way.

        Confidence is YOUR confidence in the verdict, not the relationship strength.
        Rationale: one or two short sentences. No markdown, no preamble.
        """;

    private final HttpClient httpClient;
    private final KgJudgeConfig config;

    public DefaultKgProposalJudgeService( final HttpClient httpClient,
                                           final KgJudgeConfig config ) {
        this.httpClient = Objects.requireNonNull( httpClient, "httpClient" );
        this.config     = Objects.requireNonNull( config, "config" );
    }

    @Override
    public JudgeVerdict judge( final KgProposal proposal ) {
        final String userPrompt = buildUserPrompt( proposal );
        final Map< String, Object > body = Map.of(
            "model", config.model(),
            "stream", false,
            "format", "json",
            "messages", List.of(
                Map.of( "role", "system", "content", SYSTEM_PROMPT ),
                Map.of( "role", "user",   "content", userPrompt )
            )
        );

        final String url = stripTrailingSlash( config.endpoint() ) + "/api/chat";
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .timeout( Duration.ofSeconds( config.timeoutSeconds() ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();

        try {
            final HttpResponse< String > resp = httpClient.send( req, HttpResponse.BodyHandlers.ofString() );
            if ( resp.statusCode() / 100 != 2 ) {
                LOG.warn( "judge HTTP {} for proposal {}", resp.statusCode(), proposal.id() );
                return abstain( "judge_unavailable: http " + resp.statusCode() );
            }
            return parseResponse( resp.body() );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            LOG.warn( "judge interrupted for proposal {}: {}", proposal.id(), e.getMessage() );
            return abstain( "judge_unavailable: interrupted" );
        } catch ( final IOException e ) {
            LOG.warn( "judge HTTP failure for proposal {}: {}", proposal.id(), e.getMessage() );
            return abstain( "judge_unavailable: " + e.getMessage() );
        }
    }

    private String buildUserPrompt( final KgProposal p ) {
        final Map< String, Object > data = p.proposedData();
        return String.format(
            "PROPOSAL TYPE: %s%nSOURCE PAGE: %s%nSOURCE: %s%nTARGET: %s%nRELATIONSHIP: %s%n" +
            "EXTRACTOR CONFIDENCE: %.2f%nEXTRACTOR REASONING: %s%n",
            p.proposalType(), p.sourcePage(),
            data.get( "source" ), data.get( "target" ), data.get( "relationship" ),
            p.confidence(), p.reasoning() == null ? "" : p.reasoning() );
    }

    private JudgeVerdict parseResponse( final String body ) {
        try {
            final JsonElement outer = JsonParser.parseString( body );
            if ( !outer.isJsonObject() ) {
                return abstain( "judge_unavailable: response not a JSON object" );
            }
            // /api/chat shape: {message: {content: "..."}}
            final JsonObject outerObj = outer.getAsJsonObject();
            final String inner;
            if ( outerObj.has( "message" ) && outerObj.get( "message" ).isJsonObject() ) {
                inner = outerObj.getAsJsonObject( "message" ).get( "content" ).getAsString();
            } else if ( outerObj.has( "response" ) ) {
                // /api/generate fallback
                inner = outerObj.get( "response" ).getAsString();
            } else {
                return abstain( "judge_unavailable: response missing message/response key" );
            }
            final JsonObject verdictObj = JsonParser.parseString( extractJsonObject( inner ) ).getAsJsonObject();
            final String verdict = verdictObj.get( "verdict" ).getAsString();
            final double rawConf = verdictObj.has( "confidence" ) ? verdictObj.get( "confidence" ).getAsDouble() : 0.0;
            final double clamped = Math.max( 0.0, Math.min( 1.0, rawConf ) );
            final String rationale = verdictObj.has( "rationale" ) ? verdictObj.get( "rationale" ).getAsString() : "";
            return new JudgeVerdict( verdict, clamped, rationale, config.model() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "judge response parse failure: {}", e.getMessage() );
            return abstain( "judge_unavailable: parse error" );
        }
    }

    private JudgeVerdict abstain( final String reason ) {
        return new JudgeVerdict( JudgeVerdict.ABSTAIN, 0.0, reason, config.model() );
    }

    private static String stripTrailingSlash( final String s ) {
        return s != null && s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }

    /**
     * Extracts the first balanced top-level JSON object from {@code input}.
     * <p>
     * Handles three common LLM response shapes:
     * <ul>
     *   <li>Plain JSON — returned unchanged.</li>
     *   <li>JSON with trailing prose / tool-call wrapper — leading object extracted.</li>
     *   <li>JSON with leading prose — scans forward to the first '{', extracts the object.</li>
     * </ul>
     * If no balanced object is found (no '{', unbalanced braces) the original string
     * is returned so the caller's JSON parser fails as before.
     * <p>
     * String literals are respected: '{' and '}' inside {@code "..."} do not affect
     * the depth counter.
     *
     * @param input raw LLM content, may be {@code null}
     * @return extracted JSON object substring, or {@code input} if extraction is not possible
     */
    static String extractJsonObject( final String input ) {
        if ( input == null ) {
            return null;
        }
        final int start = input.indexOf( '{' );
        if ( start < 0 ) {
            return input;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape   = false;
        for ( int i = start; i < input.length(); i++ ) {
            final char c = input.charAt( i );
            if ( inString ) {
                if ( escape ) {
                    escape = false;
                } else if ( c == '\\' ) {
                    escape = true;
                } else if ( c == '"' ) {
                    inString = false;
                }
            } else if ( c == '"' ) {
                inString = true;
            } else if ( c == '{' ) {
                depth++;
            } else if ( c == '}' ) {
                depth--;
                if ( depth == 0 ) {
                    return input.substring( start, i + 1 );
                }
            }
        }
        // No balanced closing brace found — return original so the caller's parser fails.
        return input;
    }
}
