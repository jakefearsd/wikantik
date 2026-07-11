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
package com.wikantik.knowledge.bundle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.knowledge.extraction.OllamaChatRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM-backed {@link QueryPlanner} — asks a local Ollama model (via
 * {@link OllamaChatRequest}, {@code think:false}) whether a query is
 * genuinely multi-part and, if so, decomposes it into 2–4 focused
 * sub-queries. Fail-closed: any non-2xx response, I/O failure, interruption,
 * or unparseable/insufficient output falls back to {@code List.of(query)} —
 * this class never throws and never returns an empty list.
 */
final class LlmQueryPlanner implements QueryPlanner {

    private static final Logger LOG = LogManager.getLogger( LlmQueryPlanner.class );
    private static final Gson GSON = new Gson();
    private static final String SYSTEM = """
        You are a query decomposition planner for a document retrieval system. \
        You NEVER answer the question and NEVER generate content. \
        If the user question is comparative or multi-part (asks about two or more \
        distinct topics/entities that live in separate documents), output JSON \
        {"subqueries": ["...", "..."]} with 2 to 4 focused single-topic sub-queries, \
        one per part. If it is a single-intent question, output {"subqueries": []}. \
        Output JSON only.""";

    private final HttpClient http;
    private final BundleDecompositionConfig config;

    LlmQueryPlanner( final HttpClient http, final BundleDecompositionConfig config ) {
        this.http = http;
        this.config = config;
    }

    @Override
    public List< String > plan( final String query ) {
        try {
            final String url = stripTrailingSlash( config.baseUrl() ) + "/api/chat";
            final String body = GSON.toJson( OllamaChatRequest.body( config.model(), SYSTEM, query, null ) );
            final HttpRequest req = HttpRequest.newBuilder( URI.create( url ) )
                .timeout( Duration.ofMillis( config.timeoutMs() ) )
                .header( "Content-Type", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( body ) )
                .build();
            final HttpResponse< String > resp = http.send( req, HttpResponse.BodyHandlers.ofString() );
            if ( resp.statusCode() / 100 != 2 ) {
                LOG.warn( "Query planner non-2xx {} for '{}'; single-pass", resp.statusCode(), query );
                return List.of( query );
            }
            return parse( resp.body(), query );
        } catch ( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            LOG.warn( "Query planner interrupted for '{}'; single-pass", query );
            return List.of( query );
        } catch ( final RuntimeException | java.io.IOException e ) {
            LOG.warn( "Query planner failed for '{}': {}; single-pass", query, e.getMessage() );
            return List.of( query );
        }
    }

    private List< String > parse( final String responseBody, final String query ) {
        try {
            final JsonObject outer = GSON.fromJson( responseBody, JsonObject.class );
            final String content = outer.getAsJsonObject( "message" ).get( "content" ).getAsString();
            final JsonObject inner = GSON.fromJson( content, JsonObject.class );
            final var arr = inner.getAsJsonArray( "subqueries" );
            final List< String > subs = new ArrayList<>();
            if ( arr != null ) {
                for ( final var el : arr ) {
                    final String s = el.getAsString().trim();
                    if ( !s.isBlank() ) subs.add( s );
                }
            }
            if ( subs.size() < 2 ) return List.of( query );
            return subs.size() > config.maxSubqueries() ? subs.subList( 0, config.maxSubqueries() ) : subs;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Query planner unparseable output for '{}': {}; single-pass", query, e.getMessage() );
            return List.of( query );
        }
    }

    private static String stripTrailingSlash( final String s ) {
        return s != null && s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s;
    }
}
