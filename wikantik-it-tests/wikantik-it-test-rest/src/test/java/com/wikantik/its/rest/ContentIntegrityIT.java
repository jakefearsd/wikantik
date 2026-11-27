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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guard for a content-destroying defect found by mutation testing (2026-11-27) and fixed with a unit test
 * first: a rename that rewrites referrers must keep a link's anchor and attachment path exactly once.
 *
 * <p>The companion provider fix (validate custom {@code @} properties before writing) has no test here: a REST save
 * whose body sets an {@code @} attribute does not reach the provider's custom-property check, so that path is pinned
 * by the provider unit tests only.
 */
class ContentIntegrityIT {

    private static final Gson GSON = new Gson();
    private static final String S = UUID.randomUUID().toString().substring( 0, 6 );
    private static final String OLD = "CiOld" + S;
    private static final String NEW = "CiNew" + S;
    private static final String REFERRER = "CiReferrer" + S;

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

    @AfterAll
    static void tearDown() {
        try {
            loginAsAdmin();
            try {
                for ( final String p : List.of( OLD, NEW, REFERRER ) ) {
                    delete( "/api/pages/" + p );
                }
            } finally {
                logoutAdmin();
            }
        } catch ( final Exception e ) {
            System.err.println( "ContentIntegrityIT.tearDown: best-effort cleanup failed - " + e );
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

    private static JsonObject json( final HttpResponse< String > r ) {
        return JsonParser.parseString( r.body() ).getAsJsonObject();
    }

    private static void save( final String name, final String content ) throws Exception {
        final HttpResponse< String > r = put( "/api/pages/" + name, GSON.toJson( Map.of( "content", content ) ) );
        assertEquals( 200, r.statusCode(), "Saving " + name + ": " + r.body() );
    }

    private static JsonObject page( final String path ) throws Exception {
        final HttpResponse< String > r = getString( path );
        assertEquals( 200, r.statusCode(), path + ": " + r.body() );
        return json( r );
    }

    @Test
    void renameKeepsALinksAnchorAndAttachmentPathExactlyOnce() throws Exception {
        loginAsAdmin();
        try {
            save( OLD, "The page being renamed.\n" );
            save( REFERRER, "Plain [p](" + OLD + "), anchored [a](" + OLD + "#intro), attachment [f](" + OLD
                    + "/file.png#frag), slashed anchor [s](" + OLD + "#a/b).\n" );

            final HttpResponse< String > r = post( "/api/pages/" + OLD + "/rename", GSON.toJson( Map.of( "newName", NEW ) ) );
            assertEquals( 200, r.statusCode(), r.body() );

            final String content = page( "/api/pages/" + REFERRER ).get( "content" ).getAsString();
            assertTrue( content.contains( "[p](" + NEW + ")" ), content );
            assertTrue( content.contains( "[a](" + NEW + "#intro)" ), content );
            assertTrue( content.contains( "[f](" + NEW + "/file.png#frag)" ),
                    "the attachment path and the anchor each appear once: " + content );
            assertTrue( content.contains( "[s](" + NEW + "#a/b)" ), "a slash inside the anchor is renamed too: " + content );
            assertFalse( content.contains( OLD ), "no link still names the old page: " + content );
        } finally {
            logoutAdmin();
        }
    }

}
