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
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import com.wikantik.api.knowledge.PageExtractor;
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

/**
 * Per-page extractor backed by the Anthropic Messages API. Deliberately mirrors
 * {@link OllamaPageExtractor} — same {@link PageExtractionPromptBuilder} prompts and
 * {@link PageExtractionResponseParser} — so a Claude-vs-Ollama A/B holds everything
 * constant but the model (the apples-to-apples discipline {@link ClaudeProposalJudge}
 * uses for the judge backend). Talks raw HTTP with {@code x-api-key} like the judge,
 * rather than the SDK, to stay consistent with the page-extractor HTTP style.
 *
 * <p>Never throws: a missing key, HTTP error, non-2xx, or malformed body all translate
 * to an empty {@link PageExtractionResult}, matching the fail-open convention every
 * {@link PageExtractor} obeys.
 */
public final class ClaudePageExtractor implements PageExtractor {

    private static final Logger LOG = LogManager.getLogger( ClaudePageExtractor.class );
    private static final Gson GSON = new Gson();
    private static final String ANTHROPIC_BASE = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    // Page-level extraction can emit a larger JSON than the chunk path (whole page →
    // up to maxEntities + maxRelations). 8192 leaves headroom so a full result never
    // truncates into unparseable JSON (which would silently empty the result).
    private static final int MAX_OUTPUT_TOKENS = 8_192;

    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final HttpClient httpClient;
    private final PageExtractionResponseParser parser;

    public ClaudePageExtractor( final String apiKey, final String model, final long timeoutMs,
                                final PageExtractionResponseParser parser ) {
        this( apiKey, model, timeoutMs, HttpClient.newHttpClient(), parser );
    }

    /** Test-visible constructor — inject a mock {@link HttpClient}. */
    ClaudePageExtractor( final String apiKey, final String model, final long timeoutMs,
                         final HttpClient httpClient, final PageExtractionResponseParser parser ) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.httpClient = httpClient;
        this.parser = parser;
    }

    @Override
    public String code() {
        return "claude:" + model.trim();
    }

    @Override
    public PageExtractionResult extract( final Page page, final ExtractionContext context ) {
        final long started = System.nanoTime();
        if( apiKey == null || apiKey.isBlank() ) {
            LOG.warn( "Claude page extract: ANTHROPIC_API_KEY missing for page '{}'", page.name() );
            return PageExtractionResult.empty( code(), page.name(), Duration.ofNanos( System.nanoTime() - started ) );
        }
        try {
            final String raw = callAnthropic( page, context );
            final Duration latency = Duration.ofNanos( System.nanoTime() - started );
            if( raw == null ) {
                return PageExtractionResult.empty( code(), page.name(), latency );
            }
            return parser.parse( raw, code(), page.name(), page.body(), latency );
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            LOG.warn( "Claude page extract interrupted for page '{}': {}", page.name(), ie.getMessage() );
            return PageExtractionResult.empty( code(), page.name(), Duration.ofNanos( System.nanoTime() - started ) );
        } catch( final IOException | RuntimeException e ) {
            LOG.warn( "Claude page extract failed for page '{}': {}", page.name(), e.getMessage() );
            return PageExtractionResult.empty( code(), page.name(), Duration.ofNanos( System.nanoTime() - started ) );
        }
    }

    private String callAnthropic( final Page page, final ExtractionContext ctx )
            throws IOException, InterruptedException {
        final String userPrompt = PageExtractionPromptBuilder.buildUserPrompt( page, ctx );
        final Map< String, Object > body = Map.of(
            "model", model,
            "max_tokens", MAX_OUTPUT_TOKENS,
            "system", PageExtractionPromptBuilder.SYSTEM_PROMPT,
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
            LOG.warn( "Claude page extract HTTP {} for page '{}': {}", res.statusCode(), page.name(), res.body() );
            return null;
        }
        // Anthropic shape: { "content": [ { "type":"text", "text":"..." } ], ... }
        final JsonElement root;
        try {
            root = JsonParser.parseString( res.body() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Claude page extract non-JSON body for '{}': {}", page.name(), e.getMessage() );
            return null;
        }
        if( !root.isJsonObject() ) return null;
        final JsonElement contentArr = root.getAsJsonObject().get( "content" );
        if( contentArr == null || !contentArr.isJsonArray() || contentArr.getAsJsonArray().isEmpty() ) {
            return null;
        }
        final JsonElement first = contentArr.getAsJsonArray().get( 0 );
        if( !first.isJsonObject() ) return null;
        final JsonElement text = first.getAsJsonObject().get( "text" );
        return text == null || text.isJsonNull() ? null : extractJsonObject( text.getAsString() );
    }

    /**
     * Extracts the extraction JSON object from Claude's reply. Claude (unlike Ollama's
     * {@code format:json}) routinely wraps the object in a preamble, a leading reasoning
     * block (which may itself contain {@code { }}), ```json fences, and/or a trailing
     * explanation — gson's strict parser then rejects it and the result silently empties.
     *
     * <p>We anchor on the schema's first key ({@code "entities"}), find the {@code &#123;}
     * that opens its enclosing object, and brace-match forward — respecting string literals
     * and escapes — to the balanced close. That skips any leading brace-block and drops any
     * trailing prose. Returns {@code null} (→ empty result) when no balanced object is found
     * (e.g. a {@code max_tokens} truncation), which the fail-open convention tolerates.
     */
    static String extractJsonObject( final String raw ) {
        if( raw == null ) return null;
        int anchor = raw.indexOf( "\"entities\"" );
        if( anchor < 0 ) anchor = raw.indexOf( '{' );   // fall back to the first object
        if( anchor < 0 ) return null;
        final int open = raw.lastIndexOf( '{', anchor );
        if( open < 0 ) return null;
        final int close = findMatchingClose( raw, open );
        return close < 0 ? null : raw.substring( open, close + 1 );   // -1 = unbalanced (truncated)
    }

    /**
     * Brace-matches forward from {@code open} (which must index a {@code &#123;}), respecting JSON
     * string literals and {@code \}-escapes, and returns the index of the balanced closing
     * {@code &#125;} — or {@code -1} if the object never closes (e.g. a {@code max_tokens} truncation).
     * A {@code &#125;} inside a string value does not terminate the object.
     */
    static int findMatchingClose( final String raw, final int open ) {
        int depth = 0;
        boolean inString = false, escaped = false;
        for( int i = open; i < raw.length(); i++ ) {
            final char c = raw.charAt( i );
            if( inString ) {
                if( escaped ) escaped = false;
                else if( c == '\\' ) escaped = true;
                else if( c == '"' ) inString = false;
            } else if( c == '"' ) {
                inString = true;
            } else if( c == '{' ) {
                depth++;
            } else if( c == '}' ) {
                if( --depth == 0 ) return i;
            }
        }
        return -1;   // unbalanced — truncated output
    }
}
