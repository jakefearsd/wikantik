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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wire-level Cargo IT for the drift-dashboard admin endpoints (AdminAuthFilter
 * gates {@code /admin/*}):
 * <ul>
 *   <li>{@code POST /admin/drift/sweep} — manual async trigger; 202
 *       {@code {state:"RUNNING"}} or 409 if a sweep is already in flight.</li>
 *   <li>{@code GET /admin/drift/summary} — latest sweep counts envelope
 *       ({@code sweptAt}, {@code pagesScanned}, {@code counts:[{family, code,
 *       severity, count, delta}]}).</li>
 *   <li>{@code GET /admin/drift/pages?family=F&amp;code=C} — live offender
 *       recomputation.</li>
 *   <li>403 for anonymous callers.</li>
 * </ul>
 * Seeds a page whose frontmatter carries the advisory-warning value
 * {@code status: definitely-not-canonical} (the save itself MUST succeed —
 * field-value checks are warnings, not blockers), then proves the sweep
 * aggregates it into the summary and the live page list names the page.
 */
public class AdminDriftIT {

    private static final Gson GSON = new Gson();

    /** Seed page whose frontmatter drifts from the canonical status vocabulary. */
    private static final String SEED_PAGE = "DriftItSeed";

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    /**
     * The web.xml sets {@code <secure>true</secure>} on the session cookie.
     * Java's {@link java.net.InMemoryCookieStore} filters Secure cookies on plain
     * {@code http://} requests. This wrapper fools the store into treating every
     * URI as HTTPS so the JSESSIONID is always forwarded.
     */
    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri,
                    final Map< String, List< String > > requestHeaders ) throws IOException {
                return cm.get( asHttps( uri ), requestHeaders );
            }

            @Override
            public void put( final URI uri,
                    final Map< String, List< String > > responseHeaders ) throws IOException {
                cm.put( uri, responseHeaders );
            }

            private URI asHttps( final URI uri ) {
                return URI.create( uri.toString().replaceFirst( "^http:", "https:" ) );
            }
        };
    }

    // ---- HTTP helpers ----

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String jsonBody )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > post( final String path, final String jsonBody )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    // ---- Tests ----

    @Test
    void sweepAggregatesSeededDriftAndListsThePage() throws Exception {
        try {
            loginAsAdmin();

            // 1. Seed a page whose frontmatter status drifts from the canonical
            // vocabulary. Field-value checks are advisory warnings, so the save
            // MUST succeed — a 422 here is a real regression, not a test problem.
            final String requestBody = GSON.toJson( Map.of(
                    "content", "Drift IT seed body.",
                    "metadata", Map.of( "type", "article", "status", "definitely-not-canonical" ),
                    "replaceMetadata", true ) );
            final HttpResponse< String > saveResp = put( "/api/pages/" + SEED_PAGE, requestBody );
            assertTrue( saveResp.statusCode() >= 200 && saveResp.statusCode() < 300,
                    "Seed save with a non-canonical status must succeed (advisory warning only); got "
                            + saveResp.statusCode() + ": " + saveResp.body() );

            // 2. Trigger a sweep. A startup/post-rebuild sweep may still be running
            // (409) — wait briefly and retry once.
            HttpResponse< String > sweepResp = post( "/admin/drift/sweep", "{}" );
            if ( sweepResp.statusCode() == 409 ) {
                Thread.sleep( 3000L );
                sweepResp = post( "/admin/drift/sweep", "{}" );
            }
            assertEquals( 202, sweepResp.statusCode(),
                    "POST /admin/drift/sweep must return 202: " + sweepResp.body() );
            final JsonObject sweepBody = JsonParser.parseString( sweepResp.body() ).getAsJsonObject();
            assertEquals( "RUNNING", sweepBody.get( "state" ).getAsString(),
                    "sweep response must carry state=RUNNING: " + sweepResp.body() );

            // 3. Poll the summary until the sweep has landed and the seeded drift
            // is aggregated into the counts.
            String lastSummary = "<no summary fetched>";
            boolean found = false;
            for ( int attempt = 0; attempt < 30 && !found; attempt++ ) {
                Thread.sleep( 2000L );
                final HttpResponse< String > summaryResp = get( "/admin/drift/summary" );
                assertEquals( 200, summaryResp.statusCode(),
                        "GET /admin/drift/summary must return 200: " + summaryResp.body() );
                lastSummary = summaryResp.body();
                final JsonObject summary = JsonParser.parseString( lastSummary ).getAsJsonObject();
                if ( summary.get( "sweptAt" ) == null || summary.get( "sweptAt" ).isJsonNull() ) {
                    continue;
                }
                final JsonArray counts = summary.getAsJsonArray( "counts" );
                for ( final JsonElement el : counts ) {
                    final JsonObject row = el.getAsJsonObject();
                    if ( "status.noncanonical".equals( row.get( "code" ).getAsString() )
                            && row.get( "count" ).getAsInt() >= 1 ) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue( found, "summary never showed a completed sweep with a status.noncanonical "
                    + "count >= 1 after 30 polls; last summary: " + lastSummary );

            // 4. The live page list for the family/code must name the seeded page.
            final HttpResponse< String > pagesResp =
                    get( "/admin/drift/pages?family=frontmatter&code=status.noncanonical" );
            assertEquals( 200, pagesResp.statusCode(),
                    "GET /admin/drift/pages must return 200: " + pagesResp.body() );
            final JsonObject pagesBody = JsonParser.parseString( pagesResp.body() ).getAsJsonObject();
            final JsonArray pages = pagesBody.getAsJsonArray( "pages" );
            boolean seedListed = false;
            for ( final JsonElement el : pages ) {
                if ( SEED_PAGE.equals( el.getAsJsonObject().get( "pageName" ).getAsString() ) ) {
                    seedListed = true;
                    break;
                }
            }
            if ( !seedListed ) {
                fail( "live page list for frontmatter/status.noncanonical must include "
                        + SEED_PAGE + ": " + pagesResp.body() );
            }
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void summaryAnonymousIs403() throws Exception {
        final HttpClient anon = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        final HttpResponse< String > resp = anon.send(
                HttpRequest.newBuilder().uri( URI.create( baseUrl + "/admin/drift/summary" ) )
                        .header( "Accept", "application/json" ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, resp.statusCode(),
                "Anonymous GET to /admin/drift/summary must be 403: " + resp.body() );
    }
}
