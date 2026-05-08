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
 * Integration tests for {@code POST /admin/users/bulk-action} against a
 * Cargo-deployed Tomcat instance.
 *
 * <p>Creates throwaway users, exercises lock and delete bulk actions in
 * happy-path and partial-failure modes, and verifies the standard response
 * envelope shape matches the API Keys bulk-action contract.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class AdminUserBulkActionIT {

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

    /** Login name of user created in @Order(1), consumed in lock test. */
    private static String userA;
    /** Login name of a second user, consumed in partial-failure test. */
    private static String userB;

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

    private HttpResponse< String > delete( final String path )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .DELETE()
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

    /** Creates a throwaway user and returns the loginName. */
    private String createUser( final String loginName ) throws IOException, InterruptedException {
        final String body = GSON.toJson( Map.of(
                "loginName", loginName,
                "fullName", "IT Bulk Test " + loginName,
                "email", loginName + "@it-test.example.com",
                "password", "ItBulkTest1!" ) );
        final HttpResponse< String > resp = post( "/admin/users", body );
        assertEquals( 201, resp.statusCode(),
                "User creation should return 201: " + resp.body() );
        return loginName;
    }

    // ---- tests ----

    /**
     * Creates two users that will be consumed by subsequent bulk tests.
     */
    @Test
    @Order( 1 )
    void createUsersForBulkTests() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            userA = createUser( "bulk-it-userA-" + System.currentTimeMillis() );
            userB = createUser( "bulk-it-userB-" + System.currentTimeMillis() );
            assertNotNull( userA, "userA login must be set" );
            assertNotNull( userB, "userB login must be set" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Happy path: bulk-lock a single valid user.
     * Expects 200 with succeeded=[loginName] and failed=[].
     */
    @Test
    @Order( 2 )
    void bulkLockHappyPath() throws IOException, InterruptedException {
        assertNotNull( userA, "userA from @Order(1) must not be null" );
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "lock" );
            final JsonArray ids = new JsonArray();
            ids.add( userA );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/users/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action lock should return 200: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            assertEquals( 1, succeeded.size(), "One user should have succeeded" );
            assertEquals( userA, succeeded.get( 0 ).getAsString() );

            final JsonArray failed = result.getAsJsonArray( "failed" );
            assertEquals( 0, failed.size(), "No failures expected" );

            assertTrue( result.get( "message" ).getAsString().contains( "1 of 1" ),
                    "Message should reflect 1 of 1" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Happy path: bulk-delete userA (already locked) and userB.
     * Both should end up in succeeded[].
     */
    @Test
    @Order( 3 )
    void bulkDeleteHappyPath() throws IOException, InterruptedException {
        assertNotNull( userA, "userA from @Order(1) must not be null" );
        assertNotNull( userB, "userB from @Order(1) must not be null" );
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "delete" );
            final JsonArray ids = new JsonArray();
            ids.add( userA );
            ids.add( userB );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/users/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action delete should return 200: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            assertEquals( 2, succeeded.size(), "Both users should succeed" );

            final JsonArray failed = result.getAsJsonArray( "failed" );
            assertEquals( 0, failed.size(), "No failures expected" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Partial failure: bulk-lock a non-existent user alongside a newly created one.
     * The non-existent user ends up in failed[], the real user in succeeded[].
     */
    @Test
    @Order( 4 )
    void bulkLockPartialFailure() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            // Create a fresh user for this test
            final String freshUser = createUser( "bulk-it-partial-" + System.currentTimeMillis() );

            final JsonObject body = new JsonObject();
            body.addProperty( "action", "lock" );
            final JsonArray ids = new JsonArray();
            ids.add( "nonexistent_user_xyz_it_99999" );
            ids.add( freshUser );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/users/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action should return 200 even on partial failure: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            final JsonArray failed = result.getAsJsonArray( "failed" );

            assertEquals( 1, succeeded.size(), "Fresh user should succeed" );
            assertEquals( freshUser, succeeded.get( 0 ).getAsString() );

            assertEquals( 1, failed.size(), "Nonexistent user should be in failed" );
            final JsonObject failedItem = failed.get( 0 ).getAsJsonObject();
            assertEquals( "nonexistent_user_xyz_it_99999", failedItem.get( "id" ).getAsString() );
            assertFalse( failedItem.get( "error" ).getAsString().isBlank(),
                    "Failed item must carry an error message" );

            // Clean up
            delete( "/admin/users/" + freshUser );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Missing action field → 400 Bad Request.
     */
    @Test
    @Order( 5 )
    void bulkActionRejectsMissingAction() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            final JsonArray ids = new JsonArray();
            ids.add( "somebody" );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/users/bulk-action", body.toString() );
            assertEquals( 400, resp.statusCode(),
                    "Missing action should be rejected with 400: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Non-existent group for add-to-group → 400 Bad Request (whole-call failure,
     * not a per-id failure).
     */
    @Test
    @Order( 6 )
    void addToGroupNonExistentGroupReturns400() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "add-to-group" );
            body.addProperty( "group", "totally-nonexistent-group-xyz-99999" );
            final JsonArray ids = new JsonArray();
            ids.add( "janne" );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post( "/admin/users/bulk-action", body.toString() );
            assertEquals( 400, resp.statusCode(),
                    "Non-existent group should return 400: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }
}
