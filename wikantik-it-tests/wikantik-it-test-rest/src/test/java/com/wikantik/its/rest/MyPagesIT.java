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

/**
 * Wire-level integration test for {@code GET /api/me/pages} —
 * {@link com.wikantik.rest.MyPagesResource}.
 *
 * <p>Two test scenarios:</p>
 * <ol>
 *   <li><b>Anonymous is 401</b> — a fresh no-cookie client must receive HTTP 401.</li>
 *   <li><b>Authored pages listing</b> — {@code janne} (Admin group) saves a page as
 *       herself (making her the last editor / author), waits for the structural
 *       index to settle, then GETs {@code /api/me/pages?limit=50} and asserts the
 *       saved slug appears in the {@code pages} array. {@code /api/me/pages} lists
 *       pages whose {@code author} matches the caller.</li>
 * </ol>
 *
 * <p>Scaffold copied verbatim from {@link CommentThreadIT}: {@link #secureCookieOverHttp()},
 * {@link #loginAsAdmin()}, {@link #seedPage(String)}, {@link #waitForStructuralIndexUp()},
 * and the instance {@link #get(String)} / {@link #put(String, String)} helpers.</p>
 */
public class MyPagesIT {

    private static final Gson GSON = new Gson();

    /** Slug of the page saved as janne so janne is its author. */
    private static final String PAGE_SLUG = "MyPagesITPage";

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

    /**
     * The web.xml sets {@code <secure>true</secure>} on the session cookie. Java's
     * cookie store filters Secure cookies on {@code http} URIs, so this shim rewrites
     * the scheme to https when reading cookies, ensuring JSESSIONID is always sent.
     * Copied from {@code CommentThreadIT} to keep the two suites self-contained.
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

    // ---- HTTP helpers (copied from CommentThreadIT) ----

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .GET()
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String jsonBody ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > post( final String path, final String jsonBody ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    /**
     * Authenticates the shared {@link #client} cookie jar as {@code janne}
     * (member of the Admin group in the IT XML group DB). Subsequent requests
     * carry the session cookie. Copied from {@code CommentThreadIT}.
     */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    /**
     * Saves a wiki page via {@code PUT /api/pages/{slug}} as the currently
     * authenticated user (i.e. janne, after {@link #loginAsAdmin()}). The save
     * records janne as the page author/last-editor, so the page appears in her
     * {@code /api/me/pages}. Copied from {@code CommentThreadIT}'s inline seed pattern.
     */
    private void seedPage( final String slug ) throws IOException, InterruptedException {
        final HttpResponse< String > resp = put( "/api/pages/" + slug,
                GSON.toJson( Map.of(
                        "content", "Integration test page for MyPagesIT owned by janne.",
                        "changeNote", "seed for MyPagesIT" ) ) );
        assertEquals( 200, resp.statusCode(), "Seeding the page should return 200: " + resp.body() );
    }

    /**
     * Polls {@code /api/health/structural-index} until UP (30s budget).
     * Copied from {@code CommentThreadIT#waitForStructuralIndexUp()}.
     */
    private void waitForStructuralIndexUp() throws Exception {
        for ( int attempt = 0; attempt < 30; attempt++ ) {
            final HttpResponse< String > resp = get( "/api/health/structural-index" );
            if ( resp.statusCode() == 200 && resp.body().matches( "(?s).*\"status\"\\s*:\\s*\"UP\".*" ) ) {
                return;
            }
            Thread.sleep( 1000 );
        }
        throw new AssertionError( "Structural index never reached UP within 30s" );
    }

    // ---- tests ----

    /**
     * A fresh client with no session cookie must receive HTTP 401.
     */
    @Test
    void anonymousIsUnauthorized() throws Exception {
        final HttpClient anonClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();
        final HttpResponse< String > resp = anonClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/me/pages" ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 401, resp.statusCode(),
                "Anonymous GET /api/me/pages must return 401: " + resp.body() );
    }

    /**
     * Authenticate as janne, save a page (making janne the author), wait for the
     * structural index, then assert {@code /api/me/pages} returns the saved slug.
     */
    @Test
    @SuppressWarnings( "unchecked" )
    void listsPagesAuthoredByCaller() throws Exception {
        loginAsAdmin();
        seedPage( PAGE_SLUG );
        waitForStructuralIndexUp();

        final HttpResponse< String > resp = get( "/api/me/pages?limit=50" );
        assertEquals( 200, resp.statusCode(),
                "GET /api/me/pages should return 200 for authenticated janne: " + resp.body() );

        final JsonArray pages = JsonParser.parseString( resp.body() )
                .getAsJsonObject()
                .getAsJsonArray( "pages" );
        assertTrue( pages != null && pages.size() >= 1,
                "pages array must have at least one entry: " + resp.body() );

        boolean found = false;
        for ( final JsonElement element : pages ) {
            final String slug = element.getAsJsonObject().get( "slug" ).getAsString();
            if ( PAGE_SLUG.equals( slug ) ) {
                found = true;
                break;
            }
        }
        assertTrue( found,
                "seeded slug '" + PAGE_SLUG + "' must appear in /api/me/pages response: " + resp.body() );
    }
}
