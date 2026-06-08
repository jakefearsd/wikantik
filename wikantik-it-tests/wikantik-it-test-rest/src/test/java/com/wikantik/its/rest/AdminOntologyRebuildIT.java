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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for the ontology admin endpoint wired in Phase 1b:
 * <ul>
 *   <li>{@code POST /admin/ontology/rebuild} — triggers a rebuild; returns 202
 *       (idle) or 409 (a startup-if-empty rebuild is still in flight), both with
 *       a {@code state} field.</li>
 *   <li>{@code GET /admin/ontology/status} — returns the coordinator status
 *       envelope ({@code state}, {@code enabled}, {@code graphCount}).</li>
 *   <li>403 for anonymous callers (AdminAuthFilter covers {@code /admin/*}).</li>
 * </ul>
 * Proves the {@code OntologyRebuildCoordinator} is constructed at boot, registered,
 * and reachable via {@code getSubsystems().pageGraph().ontologyRebuildCoordinator()}.
 */
public class AdminOntologyRebuildIT {

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

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    @Test
    void status_returnsCoordinatorEnvelope() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/ontology/status" );
            assertEquals( 200, resp.statusCode(),
                    "Admin /admin/ontology/status must return 200: " + resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "state" ), "status must include state: " + resp.body() );
            assertTrue( body.has( "enabled" ), "status must include enabled: " + resp.body() );
            assertTrue( body.has( "graphCount" ), "status must include graphCount: " + resp.body() );
            assertTrue( body.get( "enabled" ).getAsBoolean(),
                    "ontology should be enabled by default in the IT stack: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void rebuild_returnsAcceptedOrConflictWithState() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = post( "/admin/ontology/rebuild", "{}" );
            // 202 when idle; 409 when a startup-if-empty rebuild is still in flight.
            // Both prove the coordinator is wired and the endpoint reaches it.
            assertTrue( resp.statusCode() == 202 || resp.statusCode() == 409,
                    "expected 202 or 409; got " + resp.statusCode() + ": " + resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "state" ), "rebuild response must include state: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    void rebuild_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = postAnonymous( "/admin/ontology/rebuild" );
        assertEquals( 403, resp.statusCode(),
                "Anonymous POST to /admin/ontology/rebuild must be 403: " + resp.body() );
    }
}
