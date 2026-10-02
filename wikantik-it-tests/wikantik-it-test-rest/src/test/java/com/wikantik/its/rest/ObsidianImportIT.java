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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Cargo IT for the Obsidian vault import: builds a compact vault zip in code,
 * plans it, applies it, polls the job, and asserts pages, frontmatter, a native {@code [[ ]]}
 * backlink, a stored+served attachment, a generated-or-folder-note hub, idempotent re-import,
 * stale plan-hash rejection and zip-slip rejection.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class ObsidianImportIT {

    private static final Gson GSON = new Gson();
    private static final String U = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 8 );
    private static final String FOLDER = "Imp" + U;
    private static final String TARGET = "Target " + U;
    private static final String SOURCE = "Source " + U;
    private static final String PIC = "pic" + U + ".png";
    /** A lone top-level folder is treated as the vault wrapper and stripped, so nest the cluster folder in one. */
    private static final String WRAPPER = "Vault" + U;
    private static final String CLUSTER = "imp" + U;
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

    private static String baseUrl;
    private static HttpClient client;
    private static String planHash;
    private static byte[] vault;

    @BeforeAll
    static void setUp() throws IOException {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() ).build();
        final Map< String, byte[] > entries = new LinkedHashMap<>();
        entries.put( WRAPPER + "/" + FOLDER + "/" + FOLDER + ".md", ( "# Folder hub " + U + "\n" ).getBytes( StandardCharsets.UTF_8 ) );
        entries.put( WRAPPER + "/" + FOLDER + "/" + TARGET + ".md", "# Target\n".getBytes( StandardCharsets.UTF_8 ) );
        entries.put( WRAPPER + "/" + FOLDER + "/" + SOURCE + ".md",
                ( "# Source\nSee [[" + TARGET + "]].\n![[" + PIC + "]]\n" ).getBytes( StandardCharsets.UTF_8 ) );
        entries.put( WRAPPER + "/" + FOLDER + "/" + PIC, PNG );
        vault = zip( entries );
    }

    /** Best-effort cleanup of every page the import created. */
    @AfterAll
    static void cleanUp() {
        try {
            login();
            for ( final String page : List.of( SOURCE, TARGET, FOLDER ) ) {
                try {
                    client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + "/api/pages/" + enc( page ) ) )
                            .header( "Accept", "application/json" ).DELETE().build(),
                            HttpResponse.BodyHandlers.ofString() );
                } catch ( final Exception e ) {
                    System.err.println( "ObsidianImportIT.cleanUp: delete of " + page + " failed - " + e );
                }
            }
        } catch ( final Exception e ) {
            System.err.println( "ObsidianImportIT.cleanUp: best-effort cleanup failed - " + e );
        }
    }

    // ---- tests -------------------------------------------------------------------------------

    @Test
    @Order( 1 )
    void planReportsExpectedTotals() throws Exception {
        login();
        final HttpResponse< String > r = postMultipart( "/api/import/obsidian/plan", vault, Map.of( "clusterMode", "folders" ) );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject plan = json( r );
        final JsonObject totals = plan.getAsJsonObject( "totals" );
        assertEquals( 3, totals.get( "pagesNew" ).getAsInt(), r.body() );
        assertEquals( 1, totals.get( "attachments" ).getAsInt(), r.body() );
        planHash = plan.get( "planHash" ).getAsString();
        assertTrue( !planHash.isBlank() );
    }

    @Test
    @Order( 2 )
    void applyImportsAndJobCompletes() throws Exception {
        final String jobId = apply( planHash );
        final JsonObject job = awaitJob( jobId );
        assertEquals( "DONE", job.get( "state" ).getAsString(), job.toString() );
        final JsonObject summary = job.getAsJsonObject( "summary" );
        assertEquals( 3, summary.get( "pagesCreated" ).getAsInt(), job.toString() );
        assertEquals( 1, summary.get( "attachmentsCreated" ).getAsInt(), job.toString() );
    }

    @Test
    @Order( 3 )
    void importedPageKeepsWikilink() throws Exception {
        final HttpResponse< String > r = get( "/api/pages/" + enc( SOURCE ) );
        assertEquals( 200, r.statusCode(), r.body() );
        assertTrue( json( r ).get( "content" ).getAsString().contains( "[[" + TARGET + "]]" ), r.body() );
    }

    @Test
    @Order( 4 )
    void backlinkAppearsOnTarget() throws Exception {
        pollUntil( "backlinks of " + TARGET + " to include " + SOURCE, 60,
                () -> uncheckedGet( "/api/backlinks/" + enc( TARGET ) ),
                r -> r.statusCode() == 200 && r.body().contains( "\"" + SOURCE + "\"" ) );
    }

    @Test
    @Order( 5 )
    void attachmentIsStoredAndServed() throws Exception {
        final HttpResponse< String > list = get( "/api/attachments/" + enc( SOURCE ) );
        assertEquals( 200, list.statusCode(), list.body() );
        assertTrue( list.body().contains( PIC ), list.body() );
        final HttpResponse< byte[] > file = client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/attach/" + enc( SOURCE ) + "/" + PIC ) ).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray() );
        assertEquals( 200, file.statusCode() );
        assertEquals( PNG.length, file.body().length );
    }

    @Test
    @Order( 6 )
    void hubDeclaresCluster() throws Exception {
        final HttpResponse< String > r = get( "/api/pages/" + enc( FOLDER ) );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject meta = json( r ).getAsJsonObject( "metadata" );
        assertTrue( meta.has( "type" ), "hub metadata: " + meta + " page: " + r.body() );
        assertEquals( "hub", meta.get( "type" ).getAsString(), meta.toString() );
        assertEquals( CLUSTER, meta.get( "cluster" ).getAsString(), meta.toString() );
        final JsonObject srcMeta = json( get( "/api/pages/" + enc( SOURCE ) ) ).getAsJsonObject( "metadata" );
        assertEquals( CLUSTER, srcMeta.get( "cluster" ).getAsString(), srcMeta.toString() );
    }

    @Test
    @Order( 7 )
    void staleHashIsRejectedWith409() throws Exception {
        final HttpResponse< String > r = postMultipart( "/api/import/obsidian/apply", vault,
                Map.of( "clusterMode", "folders", "planHash", planHash ) );
        assertEquals( 409, r.statusCode(), r.body() );
    }

    @Test
    @Order( 8 )
    void reimportSkipsEverything() throws Exception {
        final HttpResponse< String > plan = postMultipart( "/api/import/obsidian/plan", vault, Map.of( "clusterMode", "folders" ) );
        assertEquals( 200, plan.statusCode(), plan.body() );
        final JsonObject totals = json( plan ).getAsJsonObject( "totals" );
        assertEquals( 0, totals.get( "pagesNew" ).getAsInt(), plan.body() );
        assertEquals( 3, totals.get( "pagesSkippedExisting" ).getAsInt(), plan.body() );
        final JsonObject job = awaitJob( apply( json( plan ).get( "planHash" ).getAsString() ) );
        assertEquals( "DONE", job.get( "state" ).getAsString(), job.toString() );
        assertEquals( 0, job.getAsJsonObject( "summary" ).get( "pagesCreated" ).getAsInt(), job.toString() );
        assertEquals( 3, job.getAsJsonObject( "summary" ).get( "pagesSkipped" ).getAsInt(), job.toString() );
    }

    @Test
    @Order( 9 )
    void zipSlipEntryIsRejectedWith400() throws Exception {
        final Map< String, byte[] > evil = new LinkedHashMap<>();
        evil.put( "../evil" + U + ".md", "# x\n".getBytes( StandardCharsets.UTF_8 ) );
        final HttpResponse< String > r = postMultipart( "/api/import/obsidian/plan", zip( evil ),
                Map.of( "clusterMode", "folders" ) );
        assertEquals( 400, r.statusCode(), r.body() );
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String enc( final String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 ).replace( "+", "%20" );
    }

    private static byte[] zip( final Map< String, byte[] > entries ) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try ( ZipOutputStream zos = new ZipOutputStream( bos ) ) {
            for ( final Map.Entry< String, byte[] > e : entries.entrySet() ) {
                zos.putNextEntry( new ZipEntry( e.getKey() ) );
                zos.write( e.getValue() );
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static JsonObject json( final HttpResponse< String > r ) {
        return JsonParser.parseString( r.body() ).getAsJsonObject();
    }

    private String apply( final String hash ) throws Exception {
        final HttpResponse< String > r = postMultipart( "/api/import/obsidian/apply", vault,
                Map.of( "clusterMode", "folders", "planHash", hash ) );
        assertEquals( 202, r.statusCode(), r.body() );
        final JsonElement id = json( r ).get( "jobId" );
        assertNotNull( id, r.body() );
        return id.getAsString();
    }

    private JsonObject awaitJob( final String jobId ) throws Exception {
        for ( int i = 0; i < 120; i++ ) {
            final HttpResponse< String > r = get( "/api/import/obsidian/jobs/" + jobId );
            assertEquals( 200, r.statusCode(), r.body() );
            final JsonObject job = json( r );
            if ( !"RUNNING".equals( job.get( "state" ).getAsString() ) ) {
                return job;
            }
            Thread.sleep( 500 );
        }
        throw new AssertionError( "import job " + jobId + " still RUNNING after 60s" );
    }

    private static HttpResponse< String > pollUntil( final String what, final int seconds,
            final Supplier< HttpResponse< String > > call, final Predicate< HttpResponse< String > > ok )
            throws InterruptedException {
        HttpResponse< String > last = null;
        for ( int i = 0; i < seconds; i++ ) {
            last = call.get();
            if ( ok.test( last ) ) {
                return last;
            }
            Thread.sleep( 1_000 );
        }
        throw new AssertionError( "Timed out waiting for " + what + "; last="
                + ( last == null ? null : last.statusCode() + " " + last.body() ) );
    }

    private static HttpResponse< String > uncheckedGet( final String path ) {
        try {
            return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Accept", "application/json" ).GET().build(), HttpResponse.BodyHandlers.ofString() );
        } catch ( final IOException e ) {
            throw new IllegalStateException( e );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException( e );
        }
    }

    private static HttpResponse< String > get( final String path ) {
        return uncheckedGet( path );
    }

    private static void login() throws IOException, InterruptedException {
        final HttpResponse< String > r = client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString(
                        GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ) ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, r.statusCode(), "Admin login should succeed: " + r.body() );
    }

    /** Hand-built multipart body: text fields plus one {@code file} part. */
    private static HttpResponse< String > postMultipart( final String path, final byte[] zipBytes,
            final Map< String, String > fields ) throws IOException, InterruptedException {
        final String boundary = "----ObsidianIT" + Long.toHexString( System.nanoTime() );
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for ( final Map.Entry< String, String > f : fields.entrySet() ) {
            bos.write( ( "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey()
                    + "\"\r\n\r\n" + f.getValue() + "\r\n" ).getBytes( StandardCharsets.UTF_8 ) );
        }
        bos.write( ( "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"vault.zip\"\r\n"
                + "Content-Type: application/zip\r\n\r\n" ).getBytes( StandardCharsets.UTF_8 ) );
        bos.write( zipBytes );
        bos.write( ( "\r\n--" + boundary + "--\r\n" ).getBytes( StandardCharsets.UTF_8 ) );
        return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "multipart/form-data; boundary=" + boundary )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofByteArray( bos.toByteArray() ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /** The session cookie is Secure; present every URI as https to the cookie store. */
    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > h )
                    throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), h );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > h ) throws IOException {
                cm.put( uri, h );
            }
        };
    }
}
