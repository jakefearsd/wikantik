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
 * Integration tests for {@code POST /admin/apikeys/bulk-action} against a
 * Cargo-deployed Tomcat instance.
 *
 * <p>Creates a fresh API key via the existing generate endpoint, then
 * exercises the bulk-revoke endpoint in happy-path and partial-failure modes,
 * and verifies the standard response envelope shape.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class AdminApiKeysBulkActionIT {

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

    /** ID of the key created in @Order(1), consumed in @Order(2). */
    private static String createdKeyId;
    /** ID of a second key for the partial-failure test. */
    private static String createdKeyId2;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    // ---- helpers ----

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

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    /** Creates a key for 'janne' and returns its numeric id as a String. */
    private String createKey( final String label ) throws IOException, InterruptedException {
        final String body = GSON.toJson( Map.of(
                "principalLogin", "janne",
                "label", label,
                "scope", "tools" ) );
        final HttpResponse< String > resp = post( "/admin/apikeys", body );
        assertEquals( 201, resp.statusCode(), "Key creation should return 201: " + resp.body() );
        final JsonObject json = JsonParser.parseString( resp.body() ).getAsJsonObject();
        return String.valueOf( json.get( "id" ).getAsInt() );
    }

    // ---- tests ----

    /**
     * Sets up two keys that will be used by the bulk-action tests.
     * Runs before the actual bulk-action tests so the IDs are available.
     */
    @Test
    @Order( 1 )
    void createKeysForBulkTests() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            createdKeyId = createKey( "bulk-it-key-1" );
            createdKeyId2 = createKey( "bulk-it-key-2" );
            assertNotNull( createdKeyId, "First key id must be set" );
            assertNotNull( createdKeyId2, "Second key id must be set" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Happy path: bulk-revoke a single valid key.
     * Expects 200 with succeeded=[id] and failed=[].
     */
    @Test
    @Order( 2 )
    void bulkRevokeHappyPath() throws IOException, InterruptedException {
        assertNotNull( createdKeyId, "Key id from @Order(1) must not be null" );
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "revoke" );
            final JsonArray ids = new JsonArray();
            ids.add( createdKeyId );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/apikeys/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action should return 200: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            assertEquals( 1, succeeded.size(), "One key should have succeeded" );
            assertEquals( createdKeyId, succeeded.get( 0 ).getAsString() );

            final JsonArray failed = result.getAsJsonArray( "failed" );
            assertEquals( 0, failed.size(), "No failures expected" );

            assertTrue( result.get( "message" ).getAsString().contains( "1 of 1" ),
                    "Message should reflect 1 of 1" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Partial failure: bulk-revoke one valid key plus the already-revoked key
     * from @Order(2). The already-revoked key lands in failed[].
     */
    @Test
    @Order( 3 )
    void bulkRevokePartialFailure() throws IOException, InterruptedException {
        assertNotNull( createdKeyId, "Already-revoked key id must not be null" );
        assertNotNull( createdKeyId2, "Second key id must not be null" );
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "revoke" );
            final JsonArray ids = new JsonArray();
            ids.add( createdKeyId );   // already revoked in @Order(2) → should fail
            ids.add( createdKeyId2 );  // still active → should succeed
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/apikeys/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action should return 200 even on partial failure: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            final JsonArray failed = result.getAsJsonArray( "failed" );

            assertEquals( 1, succeeded.size(), "Second key should succeed" );
            assertEquals( createdKeyId2, succeeded.get( 0 ).getAsString() );

            assertEquals( 1, failed.size(), "Already-revoked key should be in failed" );
            final JsonObject failedItem = failed.get( 0 ).getAsJsonObject();
            assertEquals( createdKeyId, failedItem.get( "id" ).getAsString() );
            assertFalse( failedItem.get( "error" ).getAsString().isBlank(),
                    "Failed item must carry an error message" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Missing action field → 400 Bad Request.
     */
    @Test
    @Order( 4 )
    void bulkRevokeRejectsMissingAction() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            final JsonArray ids = new JsonArray();
            ids.add( "999" );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/apikeys/bulk-action", body.toString() );
            assertEquals( 400, resp.statusCode(),
                    "Missing action should be rejected with 400: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }
}
