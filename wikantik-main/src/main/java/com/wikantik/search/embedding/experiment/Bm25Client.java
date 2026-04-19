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
package com.wikantik.search.embedding.experiment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Calls the wiki's {@code /api/search} endpoint and returns a ranked list of
 * page names. Page-level output is exactly what the eval needs — dense
 * retrieval produces chunk rankings which are later aggregated to pages via
 * max-score-per-page.
 */
public final class Bm25Client {

    public static final String PROP_BASE_URL = "wikantik.experiment.wiki.base-url";
    public static final String PROP_USER     = "wikantik.experiment.wiki.user";
    public static final String PROP_PASSWORD = "wikantik.experiment.wiki.password";

    public static final String DEFAULT_BASE_URL = "http://localhost:8080";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String basicAuthHeader;
    private final Duration timeout;

    public Bm25Client( final HttpClient httpClient,
                       final String baseUrl,
                       final String user,
                       final String password,
                       final Duration timeout ) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith( "/" ) ? baseUrl.substring( 0, baseUrl.length() - 1 ) : baseUrl;
        this.timeout = timeout;
        if( user != null && password != null ) {
            final String token = Base64.getEncoder().encodeToString(
                ( user + ":" + password ).getBytes( StandardCharsets.UTF_8 ) );
            this.basicAuthHeader = "Basic " + token;
        } else {
            this.basicAuthHeader = null;
        }
    }

    public static Bm25Client fromSystemProperties() {
        final String baseUrl = sysOrDefault( PROP_BASE_URL, DEFAULT_BASE_URL );
        final String user    = System.getProperty( PROP_USER );
        final String pw      = System.getProperty( PROP_PASSWORD );
        return new Bm25Client( HttpClient.newHttpClient(), baseUrl, user, pw, Duration.ofSeconds( 30 ) );
    }

    public record Scored( String name, double score ) {}

    /** Returns page names in BM25 rank order, capped at {@code limit}. */
    public List< String > search( final String query, final int limit ) {
        final List< Scored > s = searchWithScores( query, limit );
        final List< String > out = new ArrayList<>( s.size() );
        for( final Scored r : s ) out.add( r.name );
        return out;
    }

    /** Returns (name, score) pairs in BM25 rank order, capped at {@code limit}. */
    public List< Scored > searchWithScores( final String query, final int limit ) {
        final String url = baseUrl + "/api/search?q=" + URLEncoder.encode( query, StandardCharsets.UTF_8 )
            + "&limit=" + limit;
        final HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri( URI.create( url ) )
            .timeout( timeout )
            .header( "Accept", "application/json" )
            .GET();
        if( basicAuthHeader != null ) req.header( "Authorization", basicAuthHeader );

        final HttpResponse< String > resp;
        try {
            resp = httpClient.send( req.build(), HttpResponse.BodyHandlers.ofString( StandardCharsets.UTF_8 ) );
        } catch( final IOException e ) {
            throw new RuntimeException( "BM25 /api/search call failed: " + e.getMessage(), e );
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new RuntimeException( "BM25 /api/search call interrupted", e );
        }
        if( resp.statusCode() / 100 != 2 ) {
            throw new RuntimeException( "BM25 /api/search returned HTTP " + resp.statusCode()
                + " for query '" + query + "': " + resp.body() );
        }

        final JsonElement root = JsonParser.parseString( resp.body() );
        if( !root.isJsonObject() || !root.getAsJsonObject().has( "results" ) ) {
            throw new RuntimeException( "BM25 /api/search returned unexpected shape: " + resp.body() );
        }
        final JsonArray results = root.getAsJsonObject().getAsJsonArray( "results" );
        final List< Scored > out = new ArrayList<>( results.size() );
        for( final JsonElement e : results ) {
            final JsonObject o = e.getAsJsonObject();
            if( o.has( "name" ) && !o.get( "name" ).isJsonNull() ) {
                final double score = o.has( "score" ) && !o.get( "score" ).isJsonNull()
                    ? o.get( "score" ).getAsDouble() : 0.0;
                out.add( new Scored( o.get( "name" ).getAsString(), score ) );
            }
        }
        return out;
    }

    private static String sysOrDefault( final String key, final String fallback ) {
        final String v = System.getProperty( key );
        return v == null || v.isBlank() ? fallback : v;
    }
}
