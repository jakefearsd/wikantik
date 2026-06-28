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
import io.micrometer.core.instrument.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Calls an Ollama-hosted LLM with the judge system prompt. Returns a strict
 * verdict; on any HTTP/parse/timeout failure, returns an abstain verdict with
 * the failure reason in the rationale (caller decides whether to retry).
 *
 * <p>If a {@link KgJudgeTimeoutRepository} is supplied, per-proposal read-timeouts
 * are tracked: each timeout upserts a row keyed by proposal-id with an incremented
 * counter, and any non-timeout HTTP completion clears the row. Subsequent calls
 * for a proposal with prior timeouts use a longer effective timeout —
 * {@code base * min(1 + timeoutCount, 3)} — to give chronically slow content a
 * fairer shot before falling back to admin review.</p>
 */
public class DefaultKgProposalJudgeService implements KgProposalJudgeService {

    private static final Logger LOG = LogManager.getLogger( DefaultKgProposalJudgeService.class );
    private static final Gson GSON = new Gson();

    /** Cap on the timeout multiplier applied to base. count=2 already saturates. */
    public static final int MAX_TIMEOUT_MULTIPLIER = 3;

    /**
     * Edge proposals: a {@code (source, target, relationship)} triple. The judge
     * sees only the extractor's rationale &mdash; <strong>not</strong> the page
     * text &mdash; and decides whether that rationale plausibly supports the
     * triple as a factual relationship.
     */
    static final String SYSTEM_PROMPT_EDGE = """
        You are a knowledge-graph relationship judge. You will receive a relationship
        proposal — a (source, target, relationship) triple extracted from a wiki page —
        plus the source page name, the extractor's confidence, and the extractor's
        rationale describing why the triple holds on that page.

        IMPORTANT: You do NOT have the source page text. Judge ONLY whether the
        extractor's rationale plausibly supports the triple as a factual relationship.
        Do not assume facts about the page beyond what the rationale states.

        Return STRICT JSON with three keys and nothing else:

          {"verdict":"approved|rejected|abstain","confidence":0.0..1.0,"rationale":"..."}

        - approved: the rationale gives clear, on-topic support for the triple as a
                    factual relationship that should join the graph.
        - rejected: the rationale is contradicted, hand-wavy, off-topic, or describes
                    a non-factual relationship (opinion, speculation, hypothetical).
        - abstain:  the rationale is ambiguous or insufficient.

        Confidence is YOUR confidence in the verdict, not the relationship strength.
        Rationale: one or two short sentences. No markdown, no preamble.
        """;

    /**
     * Node proposals: a candidate {@code (name, nodeType)} concept. The judge sees
     * only the extractor's rationale — not the page text — and decides whether the
     * candidate is a meaningful, well-defined concept worth admitting to the graph.
     */
    static final String SYSTEM_PROMPT_NODE = """
        You are a knowledge-graph node judge. You will receive a node proposal — a
        candidate concept extracted from a wiki page — including its proposed name,
        node type, the source page name, the extractor's confidence, and the
        extractor's rationale describing why this concept is notable on that page.

        IMPORTANT: You do NOT have the source page text. Judge ONLY whether the
        proposed (name, nodeType) is a meaningful, well-defined concept based on
        the extractor's rationale. Do not assume facts about the page beyond the
        rationale.

        Return STRICT JSON with three keys and nothing else:

          {"verdict":"approved|rejected|abstain","confidence":0.0..1.0,"rationale":"..."}

        - approved: the name and type identify a clear, well-defined concept that
                    the rationale describes as genuinely introduced or discussed
                    by the source page.
        - rejected: the name is generic boilerplate, a stop-phrase ("the user",
                    "this section"), an ambiguous label that names many different
                    things, or contradicted by its claimed type.
        - abstain:  the rationale is ambiguous or insufficient.

        Confidence is YOUR confidence in the verdict, not the node's importance.
        Rationale: one or two short sentences. No markdown, no preamble.
        """;

    private final HttpClient httpClient;
    private final KgJudgeConfig config;
    private final KgJudgeTimeoutRepository timeoutRepo;

    public DefaultKgProposalJudgeService( final HttpClient httpClient,
                                           final KgJudgeConfig config ) {
        this( httpClient, config, null );
    }

    public DefaultKgProposalJudgeService( final HttpClient httpClient,
                                           final KgJudgeConfig config,
                                           final KgJudgeTimeoutRepository timeoutRepo ) {
        this.httpClient   = Objects.requireNonNull( httpClient, "httpClient" );
        this.config       = Objects.requireNonNull( config, "config" );
        this.timeoutRepo  = timeoutRepo; // optional
    }

    @Override
    public JudgeVerdict judge( final KgProposal proposal ) {
        // Pre-flight: refuse to call the LLM with a payload it can't sensibly
        // judge. Catches the (huge) class of bug where a node proposal gets
        // sent through the edge-shaped prompt with null source/target/rel —
        // which looked like 7300 abstains in the historical data, none of
        // them carrying any useful signal.
        final Optional< String > shortCircuit = validateProposalForJudgment( proposal );
        if ( shortCircuit.isPresent() ) {
            final String reason = shortCircuit.get();
            LOG.info( "judge short-circuit for proposal {} ({}): {}",
                proposal.id(), proposal.proposalType(), reason );
            Metrics.counter( "wikantik.kg_judge.short_circuit_total",
                "reason", metricReasonTag( reason ) ).increment();
            return abstain( reason );
        }

        final String systemPrompt = systemPromptFor( proposal.proposalType() );
        final String userPrompt   = buildUserPrompt( proposal );

        // keep_alive holds the model resident on the Ollama side longer than
        // the cron interval — without this the model auto-unloads between
        // batches (default 5m) and every batch's first request cold-loads,
        // typically blowing past the request timeout. Setting it just longer
        // than the cron interval keeps the model warm across batches.
        final Map< String, Object > body = Map.of(
            "model", config.model(),
            "stream", false,
            "format", "json",
            "keep_alive", config.keepAlive(),
            "messages", List.of(
                Map.of( "role", "system", "content", systemPrompt ),
                Map.of( "role", "user",   "content", userPrompt )
            )
        );

        final int baseTimeout      = config.timeoutSeconds();
        final int multiplier       = computeMultiplier( proposal );
        final int effectiveTimeout = baseTimeout * multiplier;
        if ( multiplier > 1 ) {
            LOG.info( "judge applying {}x timeout ({}s) for proposal {} due to prior timeouts",
                multiplier, effectiveTimeout, proposal.id() );
            Metrics.counter( "wikantik.kg_judge.timeout_multiplier_applied",
                "multiplier", multiplier + "x" ).increment();
        }

        final String url = stripTrailingSlash( config.endpoint() ) + "/api/chat";
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .timeout( Duration.ofSeconds( effectiveTimeout ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();

        try {
            final HttpResponse< String > resp = httpClient.send( req, HttpResponse.BodyHandlers.ofString() );
            // The LLM responded — root cause of any prior timeout has cleared
            // for this proposal. Drop the tracking row regardless of status.
            clearTrackedTimeout( proposal.id() );
            if ( resp.statusCode() / 100 != 2 ) {
                LOG.debug( "judge HTTP {} for proposal {}", resp.statusCode(), proposal.id() );
                return abstain( "judge_unavailable: http " + resp.statusCode() );
            }
            return parseResponse( resp.body() );
        } catch ( final HttpTimeoutException e ) {
            recordTrackedTimeout( proposal, userPrompt, e.getMessage(), baseTimeout );
            LOG.debug( "judge timeout for proposal {} (effective={}s, base={}s): {}",
                proposal.id(), effectiveTimeout, baseTimeout, e.getMessage() );
            Metrics.counter( "wikantik.kg_judge.timeouts" ).increment();
            return abstain( "judge_unavailable: timeout after " + effectiveTimeout + "s" );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            LOG.warn( "judge interrupted for proposal {}: {}", proposal.id(), e.getMessage() );
            return abstain( "judge_unavailable: interrupted" );
        } catch ( final IOException e ) {
            LOG.debug( "judge HTTP failure for proposal {}: {}", proposal.id(), e.getMessage() );
            return abstain( "judge_unavailable: " + e.getMessage() );
        }
    }

    private int computeMultiplier( final KgProposal proposal ) {
        if ( timeoutRepo == null ) return 1;
        try {
            final Optional< KgJudgeTimeoutRepository.TimeoutRow > row = timeoutRepo.find( proposal.id() );
            if ( row.isEmpty() ) return 1;
            return Math.min( 1 + row.get().timeoutCount(), MAX_TIMEOUT_MULTIPLIER );
        } catch ( final RuntimeException e ) {
            LOG.warn( "judge timeout multiplier lookup failed for {}: {}", proposal.id(), e.getMessage() );
            return 1;
        }
    }

    private void recordTrackedTimeout( final KgProposal p,
                                        final String userPrompt,
                                        final String errorMessage,
                                        final int baseTimeoutSeconds ) {
        if ( timeoutRepo == null ) return;
        try {
            final byte[] bytes = userPrompt.getBytes( StandardCharsets.UTF_8 );
            timeoutRepo.recordTimeout(
                p.id(),
                sha256Hex( bytes ),
                p.sourcePage(),
                p.proposalType(),
                config.model(),
                bytes.length,
                errorMessage,
                baseTimeoutSeconds );
        } catch ( final RuntimeException e ) {
            LOG.warn( "judge timeout record failed for {}: {}", p.id(), e.getMessage() );
        }
    }

    private void clearTrackedTimeout( final java.util.UUID proposalId ) {
        if ( timeoutRepo == null ) return;
        try {
            timeoutRepo.clear( proposalId );
        } catch ( final RuntimeException e ) {
            LOG.warn( "judge timeout clear failed for {}: {}", proposalId, e.getMessage() );
        }
    }

    private static String sha256Hex( final byte[] bytes ) {
        try {
            final MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            final byte[] digest = md.digest( bytes );
            final StringBuilder sb = new StringBuilder( 64 );
            for ( final byte b : digest ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            // SHA-256 is mandatory in every Java SE implementation.
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    /**
     * Returns the {@link #SYSTEM_PROMPT_EDGE} or {@link #SYSTEM_PROMPT_NODE} for
     * the given proposal type. Callers must run {@link #validateProposalForJudgment}
     * first; an unsupported type would have already short-circuited there, so
     * any call that reaches this method has a known type.
     */
    static String systemPromptFor( final String proposalType ) {
        return switch ( proposalType ) {
            case "new-edge" -> SYSTEM_PROMPT_EDGE;
            case "new-node" -> SYSTEM_PROMPT_NODE;
            default -> throw new IllegalStateException(
                "systemPromptFor reached for unsupported type — validate first: " + proposalType );
        };
    }

    private String buildUserPrompt( final KgProposal p ) {
        return switch ( p.proposalType() ) {
            case "new-edge" -> buildEdgeUserPrompt( p );
            case "new-node" -> buildNodeUserPrompt( p );
            default -> throw new IllegalStateException(
                "buildUserPrompt reached for unsupported type — validate first: " + p.proposalType() );
        };
    }

    private static String buildEdgeUserPrompt( final KgProposal p ) {
        final Map< String, Object > data = p.proposedData();
        return String.format(
            "PROPOSAL TYPE: %s%nSOURCE PAGE: %s%nSOURCE: %s%nTARGET: %s%nRELATIONSHIP: %s%n" +
            "EXTRACTOR CONFIDENCE: %.2f%nEXTRACTOR RATIONALE: %s%n",
            p.proposalType(), p.sourcePage(),
            data.get( "source" ), data.get( "target" ), data.get( "relationship" ),
            p.confidence(), p.reasoning() == null ? "" : p.reasoning() );
    }

    private static String buildNodeUserPrompt( final KgProposal p ) {
        final Map< String, Object > data = p.proposedData();
        return String.format(
            "PROPOSAL TYPE: %s%nSOURCE PAGE: %s%nNAME: %s%nNODE TYPE: %s%n" +
            "EXTRACTOR CONFIDENCE: %.2f%nEXTRACTOR RATIONALE: %s%n",
            p.proposalType(), p.sourcePage(),
            data.get( "name" ), data.get( "nodeType" ),
            p.confidence(), p.reasoning() == null ? "" : p.reasoning() );
    }

    /**
     * Pre-flight check that refuses to send the proposal to the LLM if it lacks
     * the fields the prompt needs. Returns the abstain rationale to use, or
     * {@link Optional#empty()} when the proposal is well-formed.
     *
     * <p>Synthetic abstain rationales are prefixed {@code "missing_data:"} or
     * {@code "unsupported_proposal_type:"} so operators can grep for them and
     * the {@code wikantik.kg_judge.short_circuit_total} metric carries the
     * same first-token reason as a tag.
     */
    static Optional< String > validateProposalForJudgment( final KgProposal p ) {
        final String type = p.proposalType();
        if ( type == null || type.isBlank() ) {
            return Optional.of( "missing_data: proposal_type is null" );
        }
        final Map< String, Object > data = p.proposedData() == null ? Map.of() : p.proposedData();
        final List< String > missing = new ArrayList<>();
        switch ( type ) {
            case "new-edge" -> {
                if ( isBlank( data.get( "source" ) ) )       missing.add( "source" );
                if ( isBlank( data.get( "target" ) ) )       missing.add( "target" );
                if ( isBlank( data.get( "relationship" ) ) ) missing.add( "relationship" );
            }
            case "new-node" -> {
                if ( isBlank( data.get( "name" ) ) )     missing.add( "name" );
                if ( isBlank( data.get( "nodeType" ) ) ) missing.add( "nodeType" );
            }
            default -> {
                return Optional.of( "unsupported_proposal_type: " + type );
            }
        }
        if ( p.reasoning() == null || p.reasoning().isBlank() ) {
            missing.add( "reasoning" );
        }
        if ( missing.isEmpty() ) return Optional.empty();
        return Optional.of( "missing_data: " + String.join( ",", missing ) );
    }

    private static boolean isBlank( final Object value ) {
        return !( value instanceof String s ) || s.isBlank();
    }

    /** Extracts the leading classifier token from a synthetic-abstain rationale for metric tagging. */
    private static String metricReasonTag( final String rationale ) {
        if ( rationale == null ) return "unknown";
        final int colon = rationale.indexOf( ':' );
        return colon < 0 ? rationale : rationale.substring( 0, colon );
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
