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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shared HTTP mechanics for the two Anthropic Messages API callers
 * ({@link ClaudePageExtractor} and {@link ClaudeProposalJudge}): builds the request
 * (model/system/user + auth headers), sends it, unwraps the Anthropic response envelope
 * ({@code { "content": [ { "type":"text", "text":"..." } ] }}), and returns the first text
 * block's raw text.
 *
 * <p>Both callers always catch a malformed-JSON body locally (they differ only in the log
 * wording/identifier), so that catch lives inside this helper too — via the caller-supplied
 * {@code onMalformedJson} callback — unlike {@link OllamaHttpCaller}, whose two callers
 * disagree on whether to catch at all.
 */
final class AnthropicHttpCaller {

    private static final Gson GSON = new Gson();
    private static final String ANTHROPIC_BASE = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private AnthropicHttpCaller() {}

    /**
     * Sends an Anthropic Messages request and returns the first content block's raw
     * {@code text}, or {@code null} on a non-2xx status, a malformed/absent JSON envelope,
     * an empty/non-object {@code content} array, or a {@code null} text field.
     *
     * @param onHttpError invoked with (statusCode, responseBody) on a non-2xx response
     * @param onMalformedJson invoked with the parse-exception message when the response
     *                        body isn't valid JSON
     */
    static String call( final HttpClient httpClient, final String apiKey, final String model, final long timeoutMs,
            final int maxTokens, final String systemPrompt, final String userPrompt,
            final BiConsumer< Integer, String > onHttpError, final Consumer< String > onMalformedJson )
            throws IOException, InterruptedException {
        final Map< String, Object > body = Map.of(
            "model", model,
            "max_tokens", maxTokens,
            "system", systemPrompt,
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
            onHttpError.accept( res.statusCode(), res.body() );
            return null;
        }
        // Anthropic shape: { "content": [ { "type":"text", "text":"..." } ], ... }
        final JsonElement root;
        try {
            root = JsonParser.parseString( res.body() );
        } catch( final RuntimeException e ) {
            onMalformedJson.accept( e.getMessage() );
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
        return text == null || text.isJsonNull() ? null : text.getAsString();
    }
}
