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

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for the derived-page ingest and reflow endpoints:
 * <ul>
 *   <li>{@code POST /api/ingest} — multipart upload produces a derived page
 *       ({@code status=created}, {@code derived_from} in frontmatter, extracted
 *       text in body).</li>
 *   <li>{@code POST /admin/derived/reflow?page=} — re-extracts from the retained
 *       source attachment; body-independent frontmatter (e.g. hand-added tags)
 *       must survive the reflow.</li>
 *   <li>{@code GET /admin/derived/status} — returns the counts JSON envelope
 *       ({@code derivedTotal >= 1}).</li>
 * </ul>
 *
 * <p>The fixture document ({@code ingest-fixture.txt}) contains the word "hello"
 * and is reliable for Tika plain-text extraction — no PDF/Office parsers required.</p>
 *
 * <p>Tests run in a fixed order because each step depends on the previous one
 * having stored the derived page.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class DerivedIngestIT {

    private static final Gson GSON = new Gson();

    /** Unique suffix so parallel runs don't collide on the page name. */
    private static final String UNIQUE = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 8 );

    /** Fixture filename — page name will be the stem (sans extension), first char uppercased. */
    private static final String FIXTURE_FILENAME = "ingest-it-" + UNIQUE + ".txt";

    /**
     * Expected derived page name: stem of the fixture filename with first char uppercased,
     * matching what {@code DerivedPage.pageNameFor()} produces (and what
     * {@code DefaultAttachmentManager.getAttachmentInfo()} expects after applying
     * {@code MarkupParser.cleanLink()}).
     */
    private static final String EXPECTED_PAGE = "Ingest-it-" + UNIQUE;

    /** The fixture document to ingest — must contain "hello". */
    private static final byte[] FIXTURE_BYTES = (
        "# Ingest IT Fixture\n\n"
        + "hello, this is the ingest integration test fixture document.\n\n"
        + "It is used to verify that POST /api/ingest extracts plain-text content\n"
        + "and stores it as a derived wiki page with derived_from provenance frontmatter.\n"
    ).getBytes( StandardCharsets.UTF_8 );

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
     * Delete the derived page created during the IT so that repeated runs do not
     * leave orphaned pages. Best-effort — a cleanup failure must not fail the suite.
     */
    @AfterAll
    static void cleanUp() {
        try {
            loginAsAdminStatic();
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri( URI.create( baseUrl + "/api/pages/" + EXPECTED_PAGE ) )
                    .header( "Accept", "application/json" )
                    .DELETE()
                    .build();
            client.send( req, HttpResponse.BodyHandlers.ofString() );
        } catch ( final Exception e ) {
            System.err.println( "DerivedIngestIT.cleanUp: best-effort delete failed — " + e );
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

    /**
     * Posts a multipart/form-data request with a single {@code file} part.
     * Constructs the boundary manually — {@code java.net.http.HttpClient} has no
     * built-in multipart support, but building it by hand is straightforward for
     * a single part.
     */
    private HttpResponse< String > postMultipart( final String path,
                                                   final String partName,
                                                   final String filename,
                                                   final byte[] fileBytes )
            throws IOException, InterruptedException {
        final String boundary = "----IngestIT" + Long.toHexString( System.nanoTime() );
        final byte[] CRLF = "\r\n".getBytes( StandardCharsets.UTF_8 );

        // Build the multipart body manually:
        //   --boundary CRLF
        //   Content-Disposition: form-data; name="file"; filename="<filename>" CRLF
        //   Content-Type: text/plain CRLF
        //   CRLF
        //   <file bytes>
        //   CRLF
        //   --boundary-- CRLF
        final byte[] header = (
            "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + partName + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
        ).getBytes( StandardCharsets.UTF_8 );
        final byte[] footer = ( "\r\n--" + boundary + "--\r\n" ).getBytes( StandardCharsets.UTF_8 );

        final byte[] body = new byte[ header.length + fileBytes.length + footer.length ];
        System.arraycopy( header, 0, body, 0, header.length );
        System.arraycopy( fileBytes, 0, body, header.length, fileBytes.length );
        System.arraycopy( footer, 0, body, header.length + fileBytes.length, footer.length );

        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "multipart/form-data; boundary=" + boundary )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofByteArray( body ) )
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
     * Step 1 — Ingest a plain-text fixture as admin.
     * Asserts HTTP 200, {@code status=created}, and a non-blank {@code page} name.
     */
    @Test
    @Order( 1 )
    void ingest_asAdmin_returnsCreatedStatus() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = postMultipart(
                    "/api/ingest", "file", FIXTURE_FILENAME, FIXTURE_BYTES );

            assertEquals( 200, resp.statusCode(),
                    "POST /api/ingest must return 200: " + resp.body() );

            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "status" ),
                    "response must contain 'status': " + resp.body() );
            assertEquals( "created", body.get( "status" ).getAsString(),
                    "ingest status must be 'created': " + resp.body() );
            assertTrue( body.has( "page" ),
                    "response must contain 'page': " + resp.body() );
            assertNotNull( body.get( "page" ).getAsString(),
                    "page name must not be null" );
            assertTrue( !body.get( "page" ).getAsString().isBlank(),
                    "page name must not be blank: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 2 — Verify the derived page has the correct provenance frontmatter
     * and that the extracted body contains the word "hello".
     */
    @Test
    @Order( 2 )
    void ingest_createdPage_hasDerivedFromAndExtractedBody() throws Exception {
        // The page is written synchronously by the ingest endpoint, so it should
        // be available immediately. Poll with a short budget to absorb any lag.
        final JsonObject pageBody = awaitPage( EXPECTED_PAGE, 15 );

        // content must contain the extracted text
        assertTrue( pageBody.has( "content" ),
                "page must have 'content': " + pageBody );
        final String content = pageBody.get( "content" ).getAsString();
        assertTrue( content.toLowerCase().contains( "hello" ),
                "extracted body must contain 'hello': " + content );

        // metadata must carry derived_from provenance
        assertTrue( pageBody.has( "metadata" ),
                "page must have 'metadata': " + pageBody );
        final JsonObject metadata = pageBody.getAsJsonObject( "metadata" );
        assertTrue( metadata.has( "derived_from" ),
                "frontmatter must contain 'derived_from': " + metadata );
        assertNotNull( metadata.get( "derived_from" ),
                "'derived_from' must not be null" );
        assertTrue( !metadata.get( "derived_from" ).isJsonNull(),
                "'derived_from' must not be JSON null: " + metadata );
    }

    /**
     * Step 3 — Add a hand-curated tag to the derived page, then reflow it.
     * Asserts the body was re-extracted (non-empty), and the hand-added tag
     * survived the reflow (body-independent frontmatter must be preserved).
     */
    @Test
    @Order( 3 )
    void reflow_preservesHandCuratedTags() throws Exception {
        try {
            loginAsAdmin();

            // 3a. Read the current page so we can build a valid PUT body.
            final HttpResponse< String > getResp = get( "/api/pages/" + EXPECTED_PAGE );
            assertEquals( 200, getResp.statusCode(),
                    "GET /api/pages/" + EXPECTED_PAGE + " must return 200: " + getResp.body() );
            final JsonObject current = JsonParser.parseString( getResp.body() ).getAsJsonObject();
            final String currentContent = current.has( "content" )
                    ? current.get( "content" ).getAsString() : "";

            // 3b. Save the page with an additional tag in the metadata.
            final String putBody = GSON.toJson( Map.of(
                    "content", currentContent,
                    "metadata", Map.of( "tags", List.of( "ingest-it-curated-tag" ) ),
                    "replaceMetadata", false ) );  // merge — keep existing derived_from etc.
            final HttpResponse< String > putResp = put( "/api/pages/" + EXPECTED_PAGE, putBody );
            assertTrue( putResp.statusCode() >= 200 && putResp.statusCode() < 300,
                    "PUT to add tag must succeed; got " + putResp.statusCode() + ": " + putResp.body() );

            // 3c. Reflow the page — re-extracts from the retained source attachment.
            final HttpResponse< String > reflowResp = post(
                    "/admin/derived/reflow?page=" + EXPECTED_PAGE, "{}" );
            assertEquals( 200, reflowResp.statusCode(),
                    "POST /admin/derived/reflow must return 200: " + reflowResp.body() );
            final JsonObject reflowBody = JsonParser.parseString( reflowResp.body() ).getAsJsonObject();
            assertTrue( reflowBody.has( "status" ),
                    "reflow response must have 'status': " + reflowResp.body() );
            // status == "updated" means the reflow ran and wrote a new version
            final String reflowStatus = reflowBody.get( "status" ).getAsString();
            assertTrue( "updated".equals( reflowStatus ) || "created".equals( reflowStatus ),
                    "reflow status must be 'updated' or 'created': " + reflowResp.body() );

            // 3d. Re-fetch the page and assert:
            //     (i)  the body is still present (re-extracted),
            //     (ii) the hand-added tag survived the reflow.
            final JsonObject reflowed = awaitPage( EXPECTED_PAGE, 15 );

            assertTrue( reflowed.has( "content" ),
                    "reflowed page must have 'content': " + reflowed );
            final String reflowedContent = reflowed.get( "content" ).getAsString();
            assertNotNull( reflowedContent, "reflowed body must not be null" );
            assertTrue( !reflowedContent.isBlank(),
                    "reflowed body must be non-empty (re-extraction ran): " + reflowed );

            final JsonObject reflowedMeta = reflowed.getAsJsonObject( "metadata" );
            assertNotNull( reflowedMeta, "reflowed metadata must not be null" );
            assertTrue( reflowedMeta.has( "tags" ),
                    "reflowed metadata must still contain 'tags' (hand-curated tag must survive reflow): "
                    + reflowedMeta );
            final String tagsStr = reflowedMeta.get( "tags" ).toString();
            assertTrue( tagsStr.contains( "ingest-it-curated-tag" ),
                    "hand-added tag 'ingest-it-curated-tag' must survive reflow: " + reflowedMeta );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 4 — {@code GET /admin/derived/status} returns the counts JSON envelope
     * with {@code derivedTotal >= 1}.
     */
    @Test
    @Order( 4 )
    void derivedStatus_returnsDerivedTotalAtLeastOne() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = get( "/admin/derived/status" );
            assertEquals( 200, resp.statusCode(),
                    "GET /admin/derived/status must return 200: " + resp.body() );

            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "derivedTotal" ),
                    "status must contain 'derivedTotal': " + resp.body() );
            assertTrue( body.has( "staleCount" ),
                    "status must contain 'staleCount': " + resp.body() );
            assertTrue( body.has( "currentExtractorVersion" ),
                    "status must contain 'currentExtractorVersion': " + resp.body() );
            final int total = body.get( "derivedTotal" ).getAsInt();
            assertTrue( total >= 1,
                    "derivedTotal must be >= 1 after ingest; got: " + total );
        } finally {
            logoutAdmin();
        }
    }
}
