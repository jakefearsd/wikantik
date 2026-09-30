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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for the Obsidian vault export endpoints:
 * <ul>
 *   <li>{@code GET /api/export/preview?cluster=} — anonymous is rejected (401); an
 *       authenticated admin sees the correct page count for the seeded cluster.</li>
 *   <li>{@code GET /api/export?cluster=} — streams a zip whose entries reproduce the vault
 *       layout ({@code <cluster>/<Page>.md}), with a wikilink in the hub page resolving to
 *       an actual file in the vault, and the {@code .wikantik/manifest.json} companion
 *       present.</li>
 * </ul>
 *
 * <p>Fixture: a two-page cluster (a {@code type: hub} page linking to a child page via a
 * normal markdown link) seeded over REST in {@link #setUp()}, cleaned up in
 * {@link #tearDown()}. The structural index may lag page creation (see
 * {@code reference_it_structural_index_seed_lag}), so the preview is polled for up to 30s
 * before asserting on its page count.</p>
 *
 * <p>Copied cookie-handling / login / logout scaffolding from {@code DerivedIngestIT}.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class ExportIT {

    private static final Gson GSON = new Gson();

    private static final String CLUSTER = "export-it-" + UUID.randomUUID().toString().substring( 0, 8 );
    private static final String HUB_PAGE = "ExportItHub";
    private static final String CHILD_PAGE = "ExportItChild";

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();

        loginAsAdmin();
        try {
            final String childBody = GSON.toJson( Map.of(
                    "content", "# Export Child\n\nThis page is a member of the " + CLUSTER + " cluster.\n",
                    "metadata", Map.of( "cluster", CLUSTER ) ) );
            final HttpResponse< String > childResp = put( "/api/pages/" + CHILD_PAGE, childBody );
            assertEquals( 200, childResp.statusCode(), "Seeding " + CHILD_PAGE + " must succeed: " + childResp.body() );

            final String hubBody = GSON.toJson( Map.of(
                    "content", "# Export Hub\n\nLinks to [Child](" + CHILD_PAGE + ").\n",
                    "metadata", Map.of( "type", "hub", "cluster", CLUSTER ) ) );
            final HttpResponse< String > hubResp = put( "/api/pages/" + HUB_PAGE, hubBody );
            assertEquals( 200, hubResp.statusCode(), "Seeding " + HUB_PAGE + " must succeed: " + hubResp.body() );
        } finally {
            logoutAdmin();
        }
    }

    @AfterAll
    static void tearDown() {
        try {
            loginAsAdmin();
            try {
                delete( "/api/pages/" + HUB_PAGE );
                delete( "/api/pages/" + CHILD_PAGE );
            } finally {
                logoutAdmin();
            }
        } catch ( final Exception e ) {
            System.err.println( "ExportIT.tearDown: best-effort cleanup failed — " + e );
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

    private static HttpResponse< String > getString( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< byte[] > getBytes( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray() );
    }

    private static HttpResponse< String > put( final String path, final String jsonBody )
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

    private static HttpResponse< String > post( final String path, final String jsonBody )
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

    private static HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
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

    private static void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private static void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    // -------------------------------------------------------------------------
    // Zip helper
    // -------------------------------------------------------------------------

    /** Unzips the given bytes into a map of zip-entry name to entry content. */
    private static Map< String, byte[] > unzip( final byte[] zipBytes ) throws IOException {
        final Map< String, byte[] > entries = new HashMap<>();
        try ( ZipInputStream zis = new ZipInputStream( new ByteArrayInputStream( zipBytes ) ) ) {
            ZipEntry entry;
            while ( ( entry = zis.getNextEntry() ) != null ) {
                if ( !entry.isDirectory() ) {
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zis.transferTo( out );
                    entries.put( entry.getName(), out.toByteArray() );
                }
                zis.closeEntry();
            }
        }
        return entries;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    void anonymousIsRejected() throws Exception {
        final HttpResponse< String > r = getString( "/api/export/preview?cluster=" + CLUSTER );
        assertEquals( 401, r.statusCode(), r.body() );
    }

    @Test
    @Order( 2 )
    void previewAndDownloadProduceAWorkingVault() throws Exception {
        loginAsAdmin();
        try {
            final JsonObject preview = awaitPreviewWithTwoPages();
            assertEquals( 2, preview.get( "pages" ).getAsInt(), preview.toString() );

            final HttpResponse< byte[] > zip = getBytes( "/api/export?cluster=" + CLUSTER );
            assertEquals( 200, zip.statusCode() );
            assertEquals( "application/zip", zip.headers().firstValue( "Content-Type" ).orElse( "" ) );

            final Map< String, byte[] > entries = unzip( zip.body() );
            final String hub = new String( entries.get( CLUSTER + "/" + HUB_PAGE + ".md" ), StandardCharsets.UTF_8 );
            final Matcher m = Pattern.compile( "\\[\\[([^\\]|#]+)" ).matcher( hub );
            assertTrue( m.find(), hub );
            final String target = m.group( 1 );
            assertTrue( entries.keySet().stream().anyMatch( k -> k.endsWith( "/" + target + ".md" ) ),
                    "wikilink [[" + target + "]] must resolve to a file in the vault: " + entries.keySet() );
            assertTrue( entries.containsKey( ".wikantik/manifest.json" ), entries.keySet().toString() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Polls {@code GET /api/export/preview} for up to 30s until it reports 2 pages for
     * {@link #CLUSTER} — the structural index lags page creation, so an immediate call can
     * observe a stale (smaller) count. Fails with the last observed body if it never converges.
     */
    private JsonObject awaitPreviewWithTwoPages() throws Exception {
        JsonObject last = null;
        for ( int attempt = 0; attempt < 30; attempt++ ) {
            final HttpResponse< String > resp = getString( "/api/export/preview?cluster=" + CLUSTER );
            assertEquals( 200, resp.statusCode(), resp.body() );
            last = JsonParser.parseString( resp.body() ).getAsJsonObject();
            if ( last.has( "pages" ) && last.get( "pages" ).getAsInt() == 2 ) {
                return last;
            }
            Thread.sleep( 1_000 );
        }
        throw new AssertionError( "Export preview for cluster " + CLUSTER
                + " never reported 2 pages within 30s; last body: " + last );
    }
}
