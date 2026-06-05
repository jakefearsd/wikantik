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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.google.gson.JsonParser.parseString;

/**
 * Proves the enterprise account-disabling contract end-to-end: a SCIM user
 * deactivated via {@code PATCH active:false} can no longer authenticate.
 * The existing {@link ScimUsersIT} asserts the {@code active} flag flips and an
 * audit row is written; this test closes the loop by attempting an actual login.
 */
public class ScimDeactivationAuthIT {

    private static final String SCIM_TOKEN = "it-scim-token";
    private static final String SCIM_CONTENT_TYPE = "application/scim+json";
    private static final String USER_NAME = "scim-deact-user";
    // Must satisfy NIST 800-63B validation and not be on the common-password
    // blocklist. If create returns 400 on the password, swap for another strong
    // non-dictionary value.
    private static final String PASSWORD = "Wk-Sc1m-9173x!";
    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
    }

    private HttpResponse<String> scimPost( final String path, final String body )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", SCIM_CONTENT_TYPE )
                .header( "Accept", SCIM_CONTENT_TYPE )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .POST( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimPatch( final String path, final String body )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", SCIM_CONTENT_TYPE )
                .header( "Accept", SCIM_CONTENT_TYPE )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .method( "PATCH", HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimGet( final String path )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", SCIM_CONTENT_TYPE )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .GET().build(), HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimDelete( final String path )
            throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Authorization", "Bearer " + SCIM_TOKEN )
                .DELETE().build(), HttpResponse.BodyHandlers.ofString() );
    }

    /** Fresh client each time — login must not piggyback an earlier session. */
    private int loginStatus( final String user, final String pw )
            throws IOException, InterruptedException {
        final HttpClient fresh = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL ).build();
        final String body = GSON.toJson( Map.of( "username", user, "password", pw ) );
        return fresh.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                HttpResponse.BodyHandlers.ofString() ).statusCode();
    }

    private void deleteIfExists() throws IOException, InterruptedException {
        final HttpResponse<String> existing = scimGet( "/scim/v2/Users?filter="
                + URLEncoder.encode( "userName eq \"" + USER_NAME + "\"", StandardCharsets.UTF_8 ) );
        if ( existing.statusCode() == 200 ) {
            final var arr = parseString( existing.body() ).getAsJsonObject().getAsJsonArray( "Resources" );
            if ( arr != null ) {
                for ( final var el : arr ) {
                    scimDelete( "/scim/v2/Users/" + el.getAsJsonObject().get( "id" ).getAsString() );
                }
            }
        }
    }

    @Test
    void deactivated_user_cannot_authenticate() throws Exception {
        deleteIfExists();

        // 1. Provision a SCIM user WITH a known password.
        final String createBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"" + USER_NAME + "\","
                + "\"name\":{\"formatted\":\"SCIM Deactivation User\"},"
                + "\"emails\":[{\"value\":\"scim-deact@example.com\",\"primary\":true}],"
                + "\"password\":\"" + PASSWORD + "\","
                + "\"active\":true"
                + "}";
        final HttpResponse<String> create = scimPost( "/scim/v2/Users", createBody );
        assertEquals( 201, create.statusCode(), "create should be 201: " + create.body() );
        final String id = parseString( create.body() ).getAsJsonObject().get( "id" ).getAsString();

        try {
            // 2. Baseline: the active user CAN authenticate.
            assertEquals( 200, loginStatus( USER_NAME, PASSWORD ),
                    "active SCIM user must be able to log in" );

            // 3. Deactivate via SCIM.
            final String patch = "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                    + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}";
            assertEquals( 200, scimPatch( "/scim/v2/Users/" + id, patch ).statusCode(),
                    "deactivate PATCH should be 200" );

            // 4. The deactivated user must NOT be able to authenticate.
            final int after = loginStatus( USER_NAME, PASSWORD );
            assertNotEquals( 200, after, "deactivated user must not be able to log in (got 200)" );
            assertTrue( after == 401 || after == 403,
                    "deactivated login should be 401/403, got: " + after );
        } finally {
            // Cleanup.
            scimDelete( "/scim/v2/Users/" + id );
        }
    }
}
