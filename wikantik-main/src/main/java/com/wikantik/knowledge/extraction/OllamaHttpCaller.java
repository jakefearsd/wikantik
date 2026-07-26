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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Shared HTTP mechanics for the two Ollama {@code /api/chat} callers
 * ({@link OllamaEntityExtractor} and {@link OllamaPageExtractor}): builds the
 * request body via {@link OllamaChatRequest#body}, sends it, and handles the
 * HTTP status check. Returns the raw response body text on success, or
 * {@code null} on a non-2xx status (after invoking {@code onHttpError} with
 * the status code so each caller can log its own wording/identifier).
 *
 * <p>Deliberately does <b>not</b> parse the returned body — each caller
 * unwraps the {@code message.content} field itself, since the two callers
 * differ in whether a malformed-JSON response is caught locally (and logged
 * with a caller-specific message) or left to propagate to their outer
 * {@code catch} block. Keeping that parsing at the call site preserves each
 * caller's existing exception semantics exactly.
 */
final class OllamaHttpCaller {

    private static final Gson GSON = new Gson();

    private OllamaHttpCaller() {}

    /**
     * Sends an Ollama {@code /api/chat} request built from the given model/prompts and
     * returns the raw response body text, or {@code null} if the HTTP status was not 2xx.
     *
     * @param keepAlive passed straight through to {@link OllamaChatRequest#body} —
     *                  {@code null} to omit the {@code keep_alive} field
     * @param onHttpError invoked with the status code on a non-2xx response, before this
     *                     method returns {@code null}, so each caller can log its own wording
     */
    static String call( final HttpClient httpClient, final String baseUrl, final long timeoutMs,
            final String model, final String systemPrompt, final String userPrompt, final String keepAlive,
            final IntConsumer onHttpError ) throws IOException, InterruptedException {
        final Map< String, Object > body = OllamaChatRequest.body( model, systemPrompt, userPrompt, keepAlive );
        final String url = stripTrailingSlash( baseUrl ) + "/api/chat";
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .timeout( Duration.ofMillis( timeoutMs ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();

        final HttpResponse< String > res = httpClient.send( req, HttpResponse.BodyHandlers.ofString() );
        if( res.statusCode() / 100 != 2 ) {
            onHttpError.accept( res.statusCode() );
            return null;
        }
        return res.body();
    }

    private static String stripTrailingSlash( final String s ) {
        if( s == null || s.isEmpty() ) {
            return "";
        }
        return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }
}
