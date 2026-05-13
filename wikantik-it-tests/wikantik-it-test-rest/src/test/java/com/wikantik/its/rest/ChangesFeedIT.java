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
package com.wikantik.its.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for {@code GET /api/changes} — the indexing/sync feed
 * documented in {@code IndexingSupport.md}. Previously the resource was
 * unit-tested only.
 *
 * <p>Each test owns a fresh {@link HttpClient} (no shared cookie jar) — the
 * endpoint is public, no session needed.</p>
 */
public class ChangesFeedIT {

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();
    }

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    // ---- shape ----

    @Test
    void changes_fullExportReturnsEnvelopeShape() throws Exception {
        final HttpResponse< String > resp = get( "/api/changes" );
        assertEquals( 200, resp.statusCode(),
                "GET /api/changes should be 200: " + resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertNotNull( body, "response must be a JSON object" );
        assertTrue( body.has( "since" ),
                "envelope must include 'since' (may be null): " + resp.body() );
        assertTrue( body.has( "generated_at" ),
                "envelope must include 'generated_at': " + resp.body() );
        assertTrue( body.has( "pages" ),
                "envelope must include 'pages': " + resp.body() );
        final JsonArray pages = body.getAsJsonArray( "pages" );
        assertNotNull( pages, "pages must be a JSON array" );
        // No assertion on page count — fresh IT corpus may have zero or many
        // pages, both are valid. Just verify the array shape.
    }

    @Test
    void changes_fullExportPageEntriesHaveRequiredFields() throws Exception {
        final HttpResponse< String > resp = get( "/api/changes" );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final JsonArray pages = body.getAsJsonArray( "pages" );
        if ( pages.isEmpty() ) {
            // Nothing to inspect; the shape test already covered the envelope.
            return;
        }
        final JsonObject first = pages.get( 0 ).getAsJsonObject();
        assertTrue( first.has( "slug" ),
                "each page entry must have 'slug': " + first );
        assertTrue( first.has( "url" ),
                "each page entry must have 'url': " + first );
        // The URL must point at /wiki/<slug> for crawlers — assert prefix.
        assertTrue( first.get( "url" ).getAsString().contains( "/wiki/" ),
                "each entry's url must contain /wiki/: " + first );
    }

    // ---- since= filter ----

    @Test
    void changes_sinceFarFutureReturnsEmptyPages() throws Exception {
        // A timestamp far in the future yields zero results regardless of the
        // corpus, which is the clearest assertion we can make in the IT env.
        final HttpResponse< String > resp = get( "/api/changes?since=2099-01-01T00:00:00Z" );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "since" ),
                "response must echo back the since parameter: " + resp.body() );
        final JsonArray pages = body.getAsJsonArray( "pages" );
        assertEquals( 0, pages.size(),
                "since=2099 must return zero pages: " + resp.body() );
    }

    @Test
    void changes_sinceEpochReturnsAtLeastAsMuchAsFullExport() throws Exception {
        final HttpResponse< String > fullResp = get( "/api/changes" );
        assertEquals( 200, fullResp.statusCode(), fullResp.body() );
        final int fullCount = JsonParser.parseString( fullResp.body() )
                .getAsJsonObject().getAsJsonArray( "pages" ).size();

        final HttpResponse< String > sinceResp = get( "/api/changes?since=1970-01-01T00:00:00Z" );
        assertEquals( 200, sinceResp.statusCode(), sinceResp.body() );
        final int sinceCount = JsonParser.parseString( sinceResp.body() )
                .getAsJsonObject().getAsJsonArray( "pages" ).size();

        // since=epoch must be >= the unbounded recent-changes count (it cannot
        // filter out pages that were modified after 1970). The implementation
        // is permitted to be a no-op for since=epoch but never smaller.
        assertTrue( sinceCount >= fullCount,
                "since=epoch should return >= full export count; full=" + fullCount
                + " since=" + sinceCount );
    }

    // ---- since= validation ----

    @Test
    void changes_invalidSinceReturns400WithExplanatoryError() throws Exception {
        final HttpResponse< String > resp = get( "/api/changes?since=not-a-date" );
        assertEquals( 400, resp.statusCode(),
                "GET /api/changes?since=not-a-date should be 400: " + resp.body() );
        // The error message should cite the bad parameter so callers can fix
        // their request without reading source.
        assertTrue( resp.body().contains( "since" ),
                "400 body should mention the 'since' parameter: " + resp.body() );
    }

    @Test
    void changes_sinceAcceptsBareDateForm() throws Exception {
        // ChangesResource's parser accepts yyyy-MM-dd as one of its patterns.
        // Asserting 200 catches regressions where only the full ISO 8601
        // datetime form is honoured.
        final HttpResponse< String > resp = get( "/api/changes?since=2026-05-01" );
        assertEquals( 200, resp.statusCode(),
                "since=YYYY-MM-DD must be accepted: " + resp.body() );
    }
}
