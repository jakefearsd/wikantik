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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for the connector admin surface ({@code wikantik-connectors} Phase 2.1,
 * Task 6): proves {@code /admin/connectors/*} works end to end against a live wiki with the
 * filesystem connector enabled.
 *
 * <p>The IT wiki config (shared {@code wikantik-custom.properties}, see
 * {@code wikantik-selenide-tests/src/main/resources/wikantik-custom.properties}) sets
 * {@code wikantik.connectors.enabled=true} and
 * {@code wikantik.connectors.filesystem.itfs.root} to a checked-in fixture directory
 * ({@code wikantik-selenide-tests/src/test/resources/connector-fixture}) containing two
 * markdown files: {@code connector-note-one.md} and {@code connector-note-two.md}.
 *
 * <p>Derived page names follow {@code DerivedPage.pageNameFor()} — the file stem run through
 * {@code MarkupParser.cleanLink()}, which only capitalizes the very first character (hyphens
 * are allowed characters, not word boundaries) — so {@code connector-note-one.md} becomes page
 * {@code Connector-note-one} and {@code connector-note-two.md} becomes {@code Connector-note-two}.
 *
 * <p>Tests run in a fixed order because the sync in step 1 must complete before the derived
 * pages (step 2), status (step 3), and list (step 4) assertions can hold.</p>
 *
 * <p><b>Orders 10-18</b> (connector-admin-ui Task 24) extend this class with the DB-origin CRUD
 * round trip against a {@code feed} connector: create/detail round-trip, {@code PUT} update,
 * {@code sync} + {@code runs}, the unsaved {@code test} dry-run probe, field-keyed validation
 * (reserved id / malformed id / malformed github repo), {@code DELETE}, and anonymous authz. The
 * {@code it-feed} connector points at an unreachable URL (port 1) throughout — CRUD and sync must
 * work fully offline (no egress) since connector construction never makes network calls; only the
 * sync/test probes actually attempt (and fail closed on) egress.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class ConnectorAdminIT {

    private static final Gson GSON = new Gson();

    private static final String CONNECTOR_ID = "itfs";
    private static final String PAGE_ONE = "Connector-note-one";
    private static final String PAGE_TWO = "Connector-note-two";
    private static final String URI_ONE  = "file:connector-note-one.md";
    private static final String URI_TWO  = "file:connector-note-two.md";

    /** DB-origin feed connector created/updated/synced/deleted by the Orders 10-18 scenario. */
    private static final String FEED_CONNECTOR_ID = "it-feed";
    /** Port 1 — nothing listens there, so every fetch fails closed (connection refused) without
     *  ever needing real network reachability or a live remote server. */
    private static final String UNREACHABLE_FEED_URL = "https://localhost:1/nope.xml";

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
     * Best-effort cleanup of the two derived pages so repeated runs against a persistent
     * environment don't leave stale pages behind. A cleanup failure must not fail the suite.
     */
    @AfterAll
    static void cleanUp() {
        try {
            loginAsAdminStatic();
            for ( final String page : List.of( PAGE_ONE, PAGE_TWO ) ) {
                final HttpRequest req = HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/pages/" + page ) )
                        .header( "Accept", "application/json" )
                        .DELETE()
                        .build();
                client.send( req, HttpResponse.BodyHandlers.ofString() );
            }
            // Orders 10-18: the feed connector is normally deleted by deleteFeedConnector_...
            // itself, but clean it up defensively (deletePages=true) so a failed run doesn't leave
            // a stray DB row behind for a repeat run against a persistent environment.
            final HttpRequest deleteFeed = HttpRequest.newBuilder()
                    .uri( URI.create( baseUrl + "/admin/connectors/" + FEED_CONNECTOR_ID + "?deletePages=true" ) )
                    .header( "Accept", "application/json" )
                    .DELETE()
                    .build();
            client.send( deleteFeed, HttpResponse.BodyHandlers.ofString() );
        } catch ( final Exception e ) {
            System.err.println( "ConnectorAdminIT.cleanUp: best-effort delete failed — " + e );
        }
    }

    // -------------------------------------------------------------------------
    // The web.xml sets <secure>true</secure> on the session cookie.
    // Java's InMemoryCookieStore filters Secure cookies on plain http:// requests.
    // This wrapper fools the store into treating every URI as HTTPS so the
    // JSESSIONID cookie is always sent.
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

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

    /** Posts anonymously — a fresh client with no session cookie carried over from admin login. */
    private HttpResponse< String > postAnonymous( final String path ) throws IOException, InterruptedException {
        final HttpClient anon = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        return anon.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( "{}" ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /** Gets anonymously — mirrors {@link #postAnonymous} for GET requests (a fresh client with no
     *  session cookie carried over from admin login). */
    private HttpResponse< String > getAnonymous( final String path ) throws IOException, InterruptedException {
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

    private HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    // -------------------------------------------------------------------------
    // Auth helpers
    // -------------------------------------------------------------------------

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    /** Static variant for @AfterAll cleanup (no instance). */
    private static void loginAsAdminStatic() throws IOException, InterruptedException {
        final String loginBody = new Gson().toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/auth/login" ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( loginBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, resp.statusCode(), "Admin login (cleanup) should succeed: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    // -------------------------------------------------------------------------
    // Wait helpers
    // -------------------------------------------------------------------------

    /**
     * Polls {@code GET /api/pages/{name}} until the page exists (up to
     * {@code maxAttempts} seconds), returning the parsed response body.
     * Throws {@link AssertionError} if the page never appears.
     */
    private JsonObject awaitPage( final String name, final int maxAttempts )
            throws Exception {
        for ( int i = 0; i < maxAttempts; i++ ) {
            final HttpResponse< String > resp = get( "/api/pages/" + name );
            if ( resp.statusCode() == 200 ) {
                final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
                if ( body.has( "name" ) && !body.get( "name" ).isJsonNull() ) {
                    return body;
                }
            }
            Thread.sleep( 1_000 );
        }
        throw new AssertionError( "Page '" + name + "' never appeared within " + maxAttempts + "s" );
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Step 1 — as admin, trigger a sync of the {@code itfs} filesystem connector.
     * Asserts HTTP 200 and a {@link com.wikantik.connectors.SyncReport}-shaped body reporting
     * {@code created >= 2} (the two fixture files).
     */
    @Test
    @Order( 1 )
    void sync_asAdmin_createsDerivedPagesForFixtureFiles() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = post( "/admin/connectors/" + CONNECTOR_ID + "/sync", "{}" );

            assertEquals( 200, resp.statusCode(),
                    "POST /admin/connectors/" + CONNECTOR_ID + "/sync must return 200: " + resp.body() );

            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "created" ), "sync report must contain 'created': " + resp.body() );
            final int created = body.get( "created" ).getAsInt();
            assertTrue( created >= 2,
                    "sync must create at least the 2 fixture files; got created=" + created + ": " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 2 — the two derived pages now exist, each carrying {@code derived_from} provenance
     * frontmatter equal to the {@code file:} source URI of its fixture file.
     */
    @Test
    @Order( 2 )
    void syncedPages_haveDerivedFromFileUri() throws Exception {
        assertDerivedFrom( PAGE_ONE, URI_ONE );
        assertDerivedFrom( PAGE_TWO, URI_TWO );
    }

    private void assertDerivedFrom( final String pageName, final String expectedUri ) throws Exception {
        final JsonObject pageBody = awaitPage( pageName, 15 );

        assertTrue( pageBody.has( "metadata" ), "page '" + pageName + "' must have 'metadata': " + pageBody );
        final JsonObject metadata = pageBody.getAsJsonObject( "metadata" );
        assertTrue( metadata.has( "derived_from" ),
                "frontmatter for '" + pageName + "' must contain 'derived_from': " + metadata );
        assertNotNull( metadata.get( "derived_from" ), "'derived_from' must not be null for " + pageName );
        assertFalse( metadata.get( "derived_from" ).isJsonNull(),
                "'derived_from' must not be JSON null for " + pageName + ": " + metadata );
        assertEquals( expectedUri, metadata.get( "derived_from" ).getAsString(),
                "'derived_from' for '" + pageName + "' must equal the source file: URI: " + metadata );
    }

    /**
     * Step 3 — {@code GET /admin/connectors/itfs/status} reports {@code syncedItemCount} equal
     * to the fixture file count and a non-null {@code lastStatus}.
     */
    @Test
    @Order( 3 )
    void status_returnsSyncedItemCountAndLastStatus() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = get( "/admin/connectors/" + CONNECTOR_ID + "/status" );
            assertEquals( 200, resp.statusCode(),
                    "GET /admin/connectors/" + CONNECTOR_ID + "/status must return 200: " + resp.body() );

            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "connectorId" ), "status must contain 'connectorId': " + resp.body() );
            assertEquals( CONNECTOR_ID, body.get( "connectorId" ).getAsString() );

            assertTrue( body.has( "syncedItemCount" ), "status must contain 'syncedItemCount': " + resp.body() );
            assertEquals( 2, body.get( "syncedItemCount" ).getAsInt(),
                    "syncedItemCount must equal the fixture file count: " + resp.body() );

            assertTrue( body.has( "lastStatus" ), "status must contain 'lastStatus': " + resp.body() );
            assertNotNull( body.get( "lastStatus" ), "'lastStatus' must not be null: " + resp.body() );
            assertFalse( body.get( "lastStatus" ).isJsonNull(),
                    "'lastStatus' must not be JSON null: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 4 — {@code GET /admin/connectors} lists every registered connector under the
     * {@code connectors} array of the enriched envelope ({@code { syncingEnabled,
     * credentialStoreEnabled, connectors: [...] }}, connector-admin-UI Task 10); the array must
     * contain the {@code itfs} filesystem connector wired for this IT.
     */
    @Test
    @Order( 4 )
    void list_containsItfsConnector() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = get( "/admin/connectors" );
            assertEquals( 200, resp.statusCode(), "GET /admin/connectors must return 200: " + resp.body() );

            final JsonObject envelope = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( envelope.has( "connectors" ), "GET /admin/connectors must have 'connectors': " + resp.body() );
            final JsonArray json = envelope.getAsJsonArray( "connectors" );
            boolean found = false;
            for ( final var el : json ) {
                final JsonObject o = el.getAsJsonObject();
                if ( o.has( "id" ) && CONNECTOR_ID.equals( o.get( "id" ).getAsString() ) ) {
                    found = true;
                    break;
                }
            }
            assertTrue( found, "GET /admin/connectors must list '" + CONNECTOR_ID + "': " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 5 — a non-admin (anonymous, no session) request to trigger a sync must be refused by
     * {@code AdminAuthFilter} with 403, independent of login state.
     */
    @Test
    @Order( 5 )
    void sync_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = postAnonymous( "/admin/connectors/" + CONNECTOR_ID + "/sync" );
        assertEquals( 403, resp.statusCode(),
                "Anonymous POST to /admin/connectors/" + CONNECTOR_ID + "/sync must be 403: " + resp.body() );
    }

    // -------------------------------------------------------------------------
    // Orders 10-18 — connector-admin-ui Task 24: DB-origin CRUD/sync/validation/authz round trip
    // -------------------------------------------------------------------------

    /**
     * Steps 1+2 — as admin, confirm the list envelope reports {@code credentialStoreEnabled} (this
     * IT environment need not configure a credential-store crypto key — no assertion in this class
     * depends on it, since a {@code feed} connector carries no secrets), then create a DB-origin
     * {@code feed} connector against an unreachable URL and confirm the detail payload round-trips
     * the submitted config. CRUD must work fully offline: {@link
     * com.wikantik.derived.ConnectorAssembler#build} never makes a network call for a {@code feed}
     * connector — only {@code poll()} (triggered by sync/test) does.
     */
    @Test
    @Order( 10 )
    void createFeedConnector_returns201AndRoundTripsConfig() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > listResp = get( "/admin/connectors" );
            assertEquals( 200, listResp.statusCode(), "GET /admin/connectors must return 200: " + listResp.body() );
            final JsonObject envelope = JsonParser.parseString( listResp.body() ).getAsJsonObject();
            assertTrue( envelope.has( "credentialStoreEnabled" ),
                    "list envelope must report 'credentialStoreEnabled': " + listResp.body() );

            final HttpResponse< String > createResp = post( "/admin/connectors", feedConnectorBody(
                    FEED_CONNECTOR_ID, UNREACHABLE_FEED_URL, false, null ) );
            assertEquals( 201, createResp.statusCode(),
                    "POST /admin/connectors (feed) must return 201: " + createResp.body() );
            final JsonObject created = JsonParser.parseString( createResp.body() ).getAsJsonObject();
            assertEquals( FEED_CONNECTOR_ID, created.get( "id" ).getAsString() );
            assertEquals( "feed", created.get( "type" ).getAsString() );
            assertEquals( "db", created.get( "origin" ).getAsString() );

            final HttpResponse< String > detailResp = get( "/admin/connectors/" + FEED_CONNECTOR_ID );
            assertEquals( 200, detailResp.statusCode(), "GET detail must return 200: " + detailResp.body() );
            final JsonObject detailConfig = JsonParser.parseString( detailResp.body() )
                    .getAsJsonObject().getAsJsonObject( "config" );
            assertEquals( UNREACHABLE_FEED_URL, detailConfig.getAsJsonArray( "feed_urls" ).get( 0 ).getAsString(),
                    "config.feed_urls must round-trip: " + detailResp.body() );
            assertFalse( detailConfig.get( "fetch_full_articles" ).getAsBoolean(),
                    "config.fetch_full_articles must round-trip as false: " + detailResp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 3 — {@code PUT} replaces the connector's whole config (not a partial merge), so the
     * request must resend {@code feed_urls} alongside the changed {@code max_items}; the response
     * detail payload reflects the new value immediately.
     */
    @Test
    @Order( 11 )
    void updateFeedConnector_changesMaxItemsAndReflects() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = put( "/admin/connectors/" + FEED_CONNECTOR_ID,
                    feedConnectorBody( null, UNREACHABLE_FEED_URL, false, 42 ) );
            assertEquals( 200, resp.statusCode(),
                    "PUT /admin/connectors/" + FEED_CONNECTOR_ID + " must return 200: " + resp.body() );
            final JsonObject detail = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( 42, detail.getAsJsonObject( "config" ).get( "max_items" ).getAsInt(),
                    "config.max_items must reflect the update: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 4 — triggering a sync against the unreachable feed URL never throws (fail-closed inside
     * {@link com.wikantik.connectors.web.FeedSourceConnector#poll} — the underlying {@link
     * com.wikantik.connectors.web.HttpPageFetcher} never throws either): the resulting {@code
     * SyncReport} is all zeros, and exactly one {@code ok} run is recorded.
     */
    @Test
    @Order( 12 )
    void syncFeedConnector_returnsZeroCountReportAndOneOkRun() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > syncResp = post( "/admin/connectors/" + FEED_CONNECTOR_ID + "/sync", "{}" );
            assertEquals( 200, syncResp.statusCode(), "POST sync must return 200: " + syncResp.body() );
            final JsonObject report = JsonParser.parseString( syncResp.body() ).getAsJsonObject();
            assertEquals( 0, report.get( "created" ).getAsInt(), "sync report: " + syncResp.body() );
            assertEquals( 0, report.get( "updated" ).getAsInt(), "sync report: " + syncResp.body() );
            assertEquals( 0, report.get( "unchanged" ).getAsInt(), "sync report: " + syncResp.body() );
            assertEquals( 0, report.get( "deleted" ).getAsInt(), "sync report: " + syncResp.body() );
            assertEquals( 0, report.get( "failed" ).getAsInt(), "sync report: " + syncResp.body() );

            final HttpResponse< String > runsResp = get( "/admin/connectors/" + FEED_CONNECTOR_ID + "/runs" );
            assertEquals( 200, runsResp.statusCode(), "GET runs must return 200: " + runsResp.body() );
            final JsonArray runs = JsonParser.parseString( runsResp.body() ).getAsJsonObject().getAsJsonArray( "runs" );
            assertEquals( 1, runs.size(), "exactly one run expected after the single sync above: " + runsResp.body() );
            final JsonObject run = runs.get( 0 ).getAsJsonObject();
            assertEquals( "ok", run.get( "status" ).getAsString(), "run status: " + runsResp.body() );
            assertEquals( 0, run.get( "created" ).getAsInt() );
            assertEquals( 0, run.get( "failed" ).getAsInt() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 5 — {@code POST /admin/connectors/test} probes an <em>unsaved</em> payload without
     * persisting anything (the wizard's Test step). A {@code feed} connector's {@code poll()}
     * always reports {@code complete=true} (design: a rolling-window source never derives
     * tombstones, see {@link com.wikantik.connectors.web.FeedSourceConnector#reflectsFullCorpus}),
     * so an unreachable feed URL alone cannot demonstrate {@code ok=false} through this route.
     * {@code webcrawler} is used instead — it marks its batch incomplete on a genuine fetch failure
     * (see {@link com.wikantik.connectors.web.WebCrawlerSourceConnector#poll}), the same case
     * {@code ConnectorTestServiceTest.unreachableSourceFails} exercises at the unit level. This
     * still proves the dry-run path end to end: an unreachable source never throws ({@code 200},
     * not a 500) and is reported {@code ok=false}.
     */
    @Test
    @Order( 13 )
    void testUnsavedProbe_reportsOkFalseForUnreachableSource() throws Exception {
        try {
            loginAsAdmin();

            final JsonObject config = new JsonObject();
            final JsonArray seeds = new JsonArray();
            seeds.add( "https://localhost:1/nope" );
            config.add( "seeds", seeds );
            final JsonObject body = new JsonObject();
            body.addProperty( "type", "webcrawler" );
            body.add( "config", config );

            final HttpResponse< String > resp = post( "/admin/connectors/test", body.toString() );
            assertEquals( 200, resp.statusCode(), "POST /admin/connectors/test must return 200: " + resp.body() );
            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertFalse( result.get( "ok" ).getAsBoolean(), "unreachable source must report ok=false: " + resp.body() );
            assertEquals( 0, result.get( "found" ).getAsInt() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * {@code "test"} is a reserved connector id — it would otherwise be shadowed by the {@code POST
     * /admin/connectors/test} dry-run route — so creation is rejected with a field-keyed {@code
     * connector_id} validation error before any type/config validation runs.
     */
    @Test
    @Order( 14 )
    void createConnector_reservedIdIsRejected() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = post( "/admin/connectors",
                    feedConnectorBody( "test", UNREACHABLE_FEED_URL, true, null ) );
            assertEquals( 422, resp.statusCode(), "reserved id 'test' must be rejected: " + resp.body() );
            final JsonObject errors = JsonParser.parseString( resp.body() ).getAsJsonObject().getAsJsonObject( "errors" );
            assertTrue( errors.has( "connector_id" ), "errors must key on 'connector_id': " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 6a — an id outside {@code [a-z0-9-]{1,64}} (uppercase letters, a dot) is rejected with a
     * field-keyed {@code connector_id} validation error.
     */
    @Test
    @Order( 15 )
    void createConnector_invalidIdIsRejected() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = post( "/admin/connectors",
                    feedConnectorBody( "Bad.Id", "https://example.com/feed.xml", true, null ) );
            assertEquals( 422, resp.statusCode(), "id 'Bad.Id' must fail [a-z0-9-]{1,64}: " + resp.body() );
            final JsonObject errors = JsonParser.parseString( resp.body() ).getAsJsonObject().getAsJsonObject( "errors" );
            assertTrue( errors.has( "connector_id" ), "errors must key on 'connector_id': " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 6b — a {@code github} {@code repo} not shaped {@code "owner/name"} is rejected with a
     * field-keyed {@code repo} validation error.
     */
    @Test
    @Order( 16 )
    void createConnector_invalidGithubRepoIsRejected() throws Exception {
        try {
            loginAsAdmin();

            final JsonObject config = new JsonObject();
            config.addProperty( "repo", "invalid-repo-no-slash" );
            final JsonObject body = new JsonObject();
            body.addProperty( "id", "it-github-validation" );
            body.addProperty( "type", "github" );
            body.add( "config", config );

            final HttpResponse< String > resp = post( "/admin/connectors", body.toString() );
            assertEquals( 422, resp.statusCode(), "malformed github repo must be rejected: " + resp.body() );
            final JsonObject errors = JsonParser.parseString( resp.body() ).getAsJsonObject().getAsJsonObject( "errors" );
            assertTrue( errors.has( "repo" ), "errors must key on 'repo': " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 7 — {@code deletePages=false} keeps every page the connector synced (stamped {@code
     * derived_orphaned: true} instead of hard-deleted); since the sync in {@link
     * #syncFeedConnector_returnsZeroCountReportAndOneOkRun} never created any pages (unreachable
     * URL), {@code pagesKept} is 0. The connector then 404s on detail and drops out of the list.
     */
    @Test
    @Order( 17 )
    void deleteFeedConnector_keepsPagesAndBecomesAbsent() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > deleteResp = delete(
                    "/admin/connectors/" + FEED_CONNECTOR_ID + "?deletePages=false" );
            assertEquals( 200, deleteResp.statusCode(), "DELETE must return 200: " + deleteResp.body() );
            final JsonObject result = JsonParser.parseString( deleteResp.body() ).getAsJsonObject();
            assertEquals( 0, result.get( "pagesKept" ).getAsInt(), "delete result: " + deleteResp.body() );
            assertEquals( 0, result.get( "pagesDeleted" ).getAsInt(), "delete result: " + deleteResp.body() );

            final HttpResponse< String > detailResp = get( "/admin/connectors/" + FEED_CONNECTOR_ID );
            assertEquals( 404, detailResp.statusCode(), "deleted connector must 404 on detail: " + detailResp.body() );

            final HttpResponse< String > listResp = get( "/admin/connectors" );
            assertEquals( 200, listResp.statusCode(), "GET /admin/connectors must return 200: " + listResp.body() );
            final JsonArray connectors = JsonParser.parseString( listResp.body() ).getAsJsonObject().getAsJsonArray( "connectors" );
            for ( final var el : connectors ) {
                assertFalse( FEED_CONNECTOR_ID.equals( el.getAsJsonObject().get( "id" ).getAsString() ),
                        "deleted connector must not remain listed: " + listResp.body() );
            }
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 8 — an anonymous (no session) client is refused by {@code AdminAuthFilter} the same way
     * {@link #sync_anonymousIs403} proves for POST: 403, independent of login state.
     */
    @Test
    @Order( 18 )
    void list_anonymousIsForbidden() throws Exception {
        final HttpResponse< String > resp = getAnonymous( "/admin/connectors" );
        assertEquals( 403, resp.statusCode(), "Anonymous GET /admin/connectors must be 403: " + resp.body() );
    }

    /**
     * Builds a {@code feed} connector create/update body. {@code id} is omitted from the JSON
     * (needed for {@code PUT}, which addresses the connector by path segment, never by body field)
     * when {@code null}; {@code maxItems} is omitted (letting the codec default apply) when
     * {@code null}.
     */
    private static String feedConnectorBody( final String id, final String feedUrl,
            final boolean fetchFullArticles, final Integer maxItems ) {
        final JsonObject config = new JsonObject();
        final JsonArray feedUrls = new JsonArray();
        feedUrls.add( feedUrl );
        config.add( "feed_urls", feedUrls );
        config.addProperty( "fetch_full_articles", fetchFullArticles );
        if ( maxItems != null ) config.addProperty( "max_items", maxItems );

        final JsonObject body = new JsonObject();
        if ( id != null ) body.addProperty( "id", id );
        body.addProperty( "type", "feed" );
        body.addProperty( "syncIntervalHours", 0 );
        body.add( "config", config );
        return body.toString();
    }
}
