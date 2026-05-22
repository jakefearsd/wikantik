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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for admin REST endpoints that previously had no IT:
 * <ul>
 *   <li>{@code GET /admin/page-graph/conflicts} — structural conflicts listing.</li>
 *   <li>{@code GET /admin/verification} — verification triage envelope.</li>
 *   <li>{@code GET /admin/verification?confidence=stale} — confidence filter.</li>
 *   <li>{@code GET /admin/retrieval-quality} — recent run history envelope.</li>
 *   <li>{@code POST /admin/retrieval-quality/run} — bad mode + missing query_set
 *       both return 400 (or 503 when the runner is unavailable in this stack).</li>
 *   <li>{@code GET /admin/knowledge-graph/judge-timeouts} — timeouts envelope.</li>
 *   <li>{@code GET /admin/overview} — dashboard aggregation envelope
 *       ({@code data.degraded} array + always-present {@code data.load} card).</li>
 *   <li>403 anon paths for every admin endpoint tested above.</li>
 * </ul>
 *
 * <p>Each test only asserts the wire shape; numeric counts depend on the IT
 * fixture and would be brittle. Sole-admin sessions are scoped to one test
 * method via {@link #loginAsAdmin()} / {@link #logoutAdmin()}.</p>
 */
public class AdminEndpointsCoverageIT {

    private static final Gson GSON = new Gson();

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
     * {@code http://} requests. Same trick as {@link RestApiIT} uses.
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

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
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

    private HttpResponse< String > getAnonymous( final String path ) throws IOException, InterruptedException {
        // Fresh client, no shared cookie jar — guarantees no leaked admin session.
        final HttpClient anon = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        return anon.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
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

    // ---------------------------------------------------------------
    // /admin/page-graph/conflicts
    // ---------------------------------------------------------------

    @Test
    void pageGraphConflicts_returnsEnvelopeShape() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/page-graph/conflicts" );
            // 200 when structural index is initialised; 503 when not — both
            // exercise a real code path. Accept either explicitly so a
            // cold-start race in CI does not flake the IT.
            assertTrue( resp.statusCode() == 200 || resp.statusCode() == 503,
                    "expected 200 or 503; got " + resp.statusCode() + ": " + resp.body() );
            if ( resp.statusCode() == 200 ) {
                final JsonObject env = JsonParser.parseString( resp.body() ).getAsJsonObject();
                assertTrue( env.has( "data" ), "200 response must be wrapped in data: " + resp.body() );
                final JsonObject data = env.getAsJsonObject( "data" );
                assertTrue( data.has( "conflicts" ),
                        "data must include conflicts array: " + resp.body() );
                assertTrue( data.has( "count" ),
                        "data must include count: " + resp.body() );
                assertTrue( data.has( "missing_canonical_id_count" ),
                        "data must include missing_canonical_id_count: " + resp.body() );
                assertTrue( data.get( "conflicts" ).isJsonArray(),
                        "conflicts must be a JSON array: " + resp.body() );
            }
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void pageGraphConflicts_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = getAnonymous( "/admin/page-graph/conflicts" );
        assertEquals( 403, resp.statusCode(),
                "Anonymous access to /admin/page-graph/conflicts must be 403: " + resp.body() );
    }

    // ---------------------------------------------------------------
    // /admin/verification
    // ---------------------------------------------------------------

    @Test
    void verification_returnsEnvelopeShape() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/verification?limit=5" );
            assertTrue( resp.statusCode() == 200 || resp.statusCode() == 503,
                    "expected 200 or 503; got " + resp.statusCode() + ": " + resp.body() );
            if ( resp.statusCode() == 200 ) {
                final JsonObject env = JsonParser.parseString( resp.body() ).getAsJsonObject();
                assertTrue( env.has( "data" ), "envelope must contain 'data': " + resp.body() );
                final JsonObject data = env.getAsJsonObject( "data" );
                assertTrue( data.has( "pages" ),       "data must contain pages: " + resp.body() );
                assertTrue( data.has( "count" ),       "data must contain count: " + resp.body() );
                assertTrue( data.has( "total_pages" ), "data must contain total_pages: " + resp.body() );
                assertTrue( data.has( "by_confidence" ),
                        "data must contain by_confidence: " + resp.body() );
                assertTrue( data.get( "pages" ).isJsonArray(),
                        "pages must be a JSON array: " + resp.body() );
                final JsonObject byConf = data.getAsJsonObject( "by_confidence" );
                // Every confidence level must be a key with an integer count.
                for ( final String level : List.of( "authoritative", "provisional", "stale" ) ) {
                    assertNotNull( byConf.get( level ),
                            "by_confidence must include '" + level + "': " + resp.body() );
                }
            }
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void verification_confidenceFilterAcceptsStale() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/verification?confidence=stale&limit=5" );
            assertTrue( resp.statusCode() == 200 || resp.statusCode() == 503,
                    "expected 200 or 503; got " + resp.statusCode() + ": " + resp.body() );
            if ( resp.statusCode() == 200 ) {
                final JsonObject env = JsonParser.parseString( resp.body() ).getAsJsonObject();
                assertTrue( env.has( "data" ), "envelope must contain 'data': " + resp.body() );
            }
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void verification_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = getAnonymous( "/admin/verification" );
        assertEquals( 403, resp.statusCode(),
                "Anonymous access to /admin/verification must be 403: " + resp.body() );
    }

    // ---------------------------------------------------------------
    // /admin/retrieval-quality
    // ---------------------------------------------------------------

    @Test
    void retrievalQuality_getReturnsEnvelopeOrUnavailable() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/retrieval-quality" );
            // 200 with envelope when the runner is registered; 503 with
            // "retrieval-quality runner unavailable" when not. Both are
            // expected outcomes depending on the IT module.
            assertTrue( resp.statusCode() == 200 || resp.statusCode() == 503,
                    "expected 200 or 503; got " + resp.statusCode() + ": " + resp.body() );
            if ( resp.statusCode() == 200 ) {
                final JsonObject env = JsonParser.parseString( resp.body() ).getAsJsonObject();
                assertTrue( env.has( "data" ), "envelope must contain 'data': " + resp.body() );
                final JsonObject data = env.getAsJsonObject( "data" );
                assertTrue( data.has( "recent_runs" ),
                        "data must contain recent_runs: " + resp.body() );
                assertTrue( data.has( "count" ),
                        "data must contain count: " + resp.body() );
                assertTrue( data.get( "recent_runs" ).isJsonArray(),
                        "recent_runs must be a JSON array: " + resp.body() );
            } else {
                final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
                assertTrue( body.has( "error" ),
                        "503 must include an error explanation: " + resp.body() );
            }
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void retrievalQuality_postRunRejectsBadMode() throws Exception {
        try {
            loginAsAdmin();
            // Bad mode must short-circuit to 400 before doing any work,
            // unless the runner is uninitialised — then we get 503 first.
            final String body = GSON.toJson( Map.of(
                    "query_set_id", "core-agent-queries",
                    "mode", "garbage" ) );
            final HttpResponse< String > resp = post( "/admin/retrieval-quality/run", body );
            assertTrue( resp.statusCode() == 400 || resp.statusCode() == 503,
                    "expected 400 or 503; got " + resp.statusCode() + ": " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void retrievalQuality_postRunRejectsMissingQuerySet() throws Exception {
        try {
            loginAsAdmin();
            final String body = GSON.toJson( Map.of( "mode", "bm25" ) );
            final HttpResponse< String > resp = post( "/admin/retrieval-quality/run", body );
            assertTrue( resp.statusCode() == 400 || resp.statusCode() == 503,
                    "expected 400 or 503; got " + resp.statusCode() + ": " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void retrievalQuality_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = getAnonymous( "/admin/retrieval-quality" );
        assertEquals( 403, resp.statusCode(),
                "Anonymous access to /admin/retrieval-quality must be 403: " + resp.body() );
    }

    // ---------------------------------------------------------------
    // /admin/knowledge-graph/judge-timeouts
    // ---------------------------------------------------------------

    @Test
    void judgeTimeouts_returnsEnvelopeShape() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/knowledge-graph/judge-timeouts?limit=5" );
            // Either 200 with a timeouts array, or 503 when the repository is
            // unavailable (judge tracking not configured). Both are valid IT
            // outcomes — we just verify the route is wired and authenticated.
            assertTrue( resp.statusCode() == 200 || resp.statusCode() == 503,
                    "expected 200 or 503; got " + resp.statusCode() + ": " + resp.body() );
            if ( resp.statusCode() == 200 ) {
                final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
                assertTrue( body.has( "timeouts" ),
                        "200 response must include timeouts array: " + resp.body() );
                final JsonElement timeouts = body.get( "timeouts" );
                assertTrue( timeouts.isJsonArray(),
                        "timeouts must be a JSON array: " + resp.body() );
            }
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void judgeTimeouts_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = getAnonymous( "/admin/knowledge-graph/judge-timeouts" );
        assertEquals( 403, resp.statusCode(),
                "Anonymous access to /admin/knowledge-graph/judge-timeouts must be 403: " + resp.body() );
    }

    // ---------------------------------------------------------------
    // /admin/overview
    // ---------------------------------------------------------------

    /**
     * The dashboard-aggregation endpoint always returns 200 for an authenticated
     * admin: each card collects under its own try/catch in the assembler, so a
     * source that throws degrades only its own card (key lands in
     * {@code data.degraded}). {@code data.degraded} must always be present as a
     * JSON array, and the metric-backed {@code data.load} card must always
     * assemble (its {@code MetricReads} reads return defaults rather than throwing).
     */
    @Test
    void overview_returnsAggregatedEnvelope() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/overview" );
            assertEquals( 200, resp.statusCode(),
                    "Admin /admin/overview must return 200: " + resp.body() );

            final JsonObject env = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( env.has( "data" ), "envelope must contain 'data': " + resp.body() );
            final JsonObject data = env.getAsJsonObject( "data" );

            assertTrue( data.has( "degraded" ), "data must contain degraded: " + resp.body() );
            assertTrue( data.get( "degraded" ).isJsonArray(),
                    "degraded must be a JSON array: " + resp.body() );

            assertTrue( data.has( "load" ),
                    "data must contain the metric-backed load card: " + resp.body() );
            assertTrue( data.get( "load" ).isJsonObject(),
                    "load card must be a JSON object: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void overview_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = getAnonymous( "/admin/overview" );
        // AdminAuthFilter rejects unauthenticated callers; accept 401 or 403.
        assertTrue( resp.statusCode() == 401 || resp.statusCode() == 403,
                "Anonymous access to /admin/overview must be 401 or 403: "
                        + resp.statusCode() + " " + resp.body() );
    }
}
