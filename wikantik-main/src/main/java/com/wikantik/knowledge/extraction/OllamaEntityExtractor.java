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
import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
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
 * Extractor backed by a local Ollama model. Uses the {@code /api/chat}
 * endpoint with {@code format: "json"} to coerce structured output — models
 * that ignore the flag and free-form respond are handled by the common JSON
 * fence/extraction logic in {@link ExtractionResponseParser}.
 *
 * <p>Intentionally thin: no circuit breaker (the listener already drops
 * failures and the whole pipeline is opt-in), no retries (poisoned chunks are
 * better skipped than re-sent). A bad Ollama day degrades the KG the same
 * way it already degrades search — proposals simply stop arriving.
 */
public class OllamaEntityExtractor implements EntityExtractor {

    private static final Logger LOG = LogManager.getLogger( OllamaEntityExtractor.class );
    private static final Gson GSON = new Gson();

    public static final String CODE = EntityExtractorConfig.BACKEND_OLLAMA;

    private final HttpClient httpClient;
    private final EntityExtractorConfig config;

    public OllamaEntityExtractor( final HttpClient httpClient, final EntityExtractorConfig config ) {
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ExtractionResult extract( final ExtractionChunk chunk, final ExtractionContext context ) {
        final long started = System.nanoTime();
        try {
            final String raw = callOllama( chunk, context );
            final Duration latency = Duration.ofNanos( System.nanoTime() - started );
            if( raw == null ) {
                return ExtractionResult.empty( CODE, latency );
            }
            return ExtractionResponseParser.parse(
                raw, chunk, context, CODE, latency, config.confidenceThreshold() );
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            LOG.warn( "Ollama extraction interrupted for chunk {}", chunk.id() );
            return ExtractionResult.empty( CODE, Duration.ofNanos( System.nanoTime() - started ) );
        } catch( final RuntimeException | IOException e ) {
            LOG.warn( "Ollama extraction failed for chunk {}: {}", chunk.id(), e.getMessage() );
            return ExtractionResult.empty( CODE, Duration.ofNanos( System.nanoTime() - started ) );
        }
    }

    private String callOllama( final ExtractionChunk chunk, final ExtractionContext context )
            throws IOException, InterruptedException {
        final Map< String, Object > body = Map.of(
            "model", config.ollamaModel(),
            "stream", false,
            "format", "json",
            "messages", List.of(
                Map.of( "role", "system", "content", ExtractionPromptBuilder.SYSTEM_PROMPT ),
                Map.of( "role", "user", "content",
                        ExtractionPromptBuilder.buildUserPrompt( chunk, context, config.maxExistingNodes() ) )
            )
        );

        final String url = stripTrailingSlash( config.ollamaBaseUrl() ) + "/api/chat";
        final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
            .timeout( Duration.ofMillis( config.timeoutMs() ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();

        final HttpResponse< String > res = httpClient.send( req, HttpResponse.BodyHandlers.ofString() );
        if( res.statusCode() / 100 != 2 ) {
            LOG.warn( "Ollama extract HTTP {} for chunk {}", res.statusCode(), chunk.id() );
            return null;
        }

        final JsonElement root = JsonParser.parseString( res.body() );
        if( !root.isJsonObject() ) {
            return null;
        }
        final JsonObject obj = root.getAsJsonObject();
        final JsonElement message = obj.get( "message" );
        if( message == null || !message.isJsonObject() ) {
            return null;
        }
        final JsonElement content = message.getAsJsonObject().get( "content" );
        return content == null || content.isJsonNull() ? null : content.getAsString();
    }

    private static String stripTrailingSlash( final String s ) {
        if( s == null || s.isEmpty() ) {
            return "";
        }
        return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }
}
