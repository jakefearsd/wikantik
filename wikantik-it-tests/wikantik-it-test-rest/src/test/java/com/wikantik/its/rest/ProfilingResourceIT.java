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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for {@code /admin/profiling/jfr/*} against a Cargo-deployed
 * Tomcat. Asserts the happy path (start → list → download → assert JFR magic
 * bytes 'FLR\0' in the output) and the 409 path (a second start while one
 * is running).
 *
 * <p>Uses the same cookie-based admin session pattern as {@code KgPolicyAdminIT}.</p>
 */
public class ProfilingResourceIT {

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    /** See {@link KgPolicyAdminIT#secureCookieOverHttp()} — same trick for cookie-Secure-over-HTTP. */
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

    @Test
    void jfrStartListDownloadHappyPath() throws Exception {
        loginAsAdmin();

        // 1. Start a 2-second recording.
        final HttpResponse< String > start = post( "/admin/profiling/jfr/start",
            GSON.toJson( Map.of( "duration_s", 2, "label", "it-smoke" ) ) );
        assertEquals( 200, start.statusCode(), "start should succeed: " + start.body() );
        final JsonObject startJson = JsonParser.parseString( start.body() ).getAsJsonObject();
        final String id = startJson.get( "recording_id" ).getAsString();
        assertNotNull( id, "start response should carry a recording_id" );
        assertEquals( "RUNNING", startJson.get( "status" ).getAsString() );

        // 2. Wait past the duration so JFR auto-stops and flushes the file.
        TimeUnit.SECONDS.sleep( 3 );

        // 3. Explicit stop — should be idempotent against the already-stopped recording.
        final HttpResponse< String > stop = post( "/admin/profiling/jfr/stop",
            GSON.toJson( Map.of( "recording_id", id ) ) );
        assertEquals( 200, stop.statusCode(), "stop should succeed: " + stop.body() );
        final JsonObject stopJson = JsonParser.parseString( stop.body() ).getAsJsonObject();
        assertEquals( "FINISHED", stopJson.get( "status" ).getAsString() );
        assertTrue( stopJson.get( "size_bytes" ).getAsLong() > 0,
            "stopped recording should have non-zero size_bytes" );

        // 4. List recordings — ours should appear.
        final HttpResponse< String > list = get( "/admin/profiling/jfr/recordings" );
        assertEquals( 200, list.statusCode() );
        assertTrue( list.body().contains( id ),
            "list should include the recording id: " + list.body() );

        // 5. Download the .jfr file. JFR magic bytes are 'F','L','R','\0'.
        final HttpResponse< byte[] > dl = getBytes( "/admin/profiling/jfr/recordings/" + id );
        assertEquals( 200, dl.statusCode() );
        final byte[] bytes = dl.body();
        assertTrue( bytes.length > 0, "downloaded recording must be non-empty" );
        assertEquals( (byte) 'F', bytes[ 0 ], "byte 0 should be 'F'" );
        assertEquals( (byte) 'L', bytes[ 1 ], "byte 1 should be 'L'" );
        assertEquals( (byte) 'R', bytes[ 2 ], "byte 2 should be 'R'" );
        assertEquals( (byte) 0,   bytes[ 3 ], "byte 3 should be the null terminator" );
    }

    @Test
    void jfrConcurrentStartReturns409() throws Exception {
        loginAsAdmin();

        // First start — should succeed.
        final HttpResponse< String > a = post( "/admin/profiling/jfr/start",
            GSON.toJson( Map.of( "duration_s", 5, "label", "first" ) ) );
        assertEquals( 200, a.statusCode(), "first start should succeed: " + a.body() );
        final String aId = JsonParser.parseString( a.body() ).getAsJsonObject()
            .get( "recording_id" ).getAsString();

        try {
            // Second start while the first is running — must be rejected with 409.
            final HttpResponse< String > b = post( "/admin/profiling/jfr/start",
                GSON.toJson( Map.of( "duration_s", 5, "label", "second" ) ) );
            assertEquals( 409, b.statusCode(),
                "second concurrent start must return 409 Conflict: " + b.body() );
        } finally {
            // Cleanup: stop the running recording so the next test starts clean.
            post( "/admin/profiling/jfr/stop",
                GSON.toJson( Map.of( "recording_id", aId ) ) );
        }
    }

    @Test
    void jfrStartInvalidDurationReturns400() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > bad = post( "/admin/profiling/jfr/start",
            GSON.toJson( Map.of( "duration_s", 0, "label", "invalid" ) ) );
        assertEquals( 400, bad.statusCode(),
            "duration_s=0 must return 400 Bad Request: " + bad.body() );
    }

    @Test
    void jfrDownloadUnknownIdReturns404() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = get( "/admin/profiling/jfr/recordings/no-such-id" );
        assertEquals( 404, resp.statusCode(),
            "unknown recording id must return 404 Not Found" );
    }

    // -------------------- helpers --------------------

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
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

    private HttpResponse< byte[] > getBytes( final String path ) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray() );
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
}
