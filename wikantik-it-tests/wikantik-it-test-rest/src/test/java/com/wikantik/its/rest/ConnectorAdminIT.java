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
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class ConnectorAdminIT {

    private static final Gson GSON = new Gson();

    private static final String CONNECTOR_ID = "itfs";
    private static final String PAGE_ONE = "Connector-note-one";
    private static final String PAGE_TWO = "Connector-note-two";
    private static final String URI_ONE  = "file:connector-note-one.md";
    private static final String URI_TWO  = "file:connector-note-two.md";

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
}
