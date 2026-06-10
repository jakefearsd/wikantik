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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fresh-install first-login flow: the V002-seeded admin (admin/admin123) is
 * flagged by V039, so its first session is gated to the auth surface until the
 * password is changed; after the change the gate lifts and stays lifted.
 *
 * <p>One test method ensures phase ordering is guaranteed.  The throwaway DB
 * used by this IT module means leaving admin's password as {@link #NEW_PASSWORD}
 * is safe — nothing else in this module relies on the original admin/admin123
 * credentials.
 */
public class MustChangePasswordIT {

    private static final Gson GSON = new Gson();
    private static final String NEW_PASSWORD = "Wk-Adm1n-Fresh-9472!";

    private static String baseUrl;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
    }

    // -----------------------------------------------------------------------
    // Cookie-jar helper — mirrors every other REST IT in this module.
    // The web.xml marks the session cookie Secure; Java's InMemoryCookieStore
    // silently drops Secure cookies on plain http:// requests, so we remap the
    // lookup URI to https:// while still sending the actual request over http.
    // -----------------------------------------------------------------------

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

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    private HttpResponse< String > post( final HttpClient client, final String path,
            final String jsonBody ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > get( final HttpClient client, final String path )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final HttpClient client, final String path,
            final String jsonBody ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    // -----------------------------------------------------------------------
    // Test
    // -----------------------------------------------------------------------

    @Test
    void adminFirstLoginForcesPasswordChange() throws Exception {
        // Cookie-holding client: one session across all phases.
        final HttpClient client = newClient();

        // 1. Login as the freshly-seeded admin: succeeds AND reports the flag.
        final HttpResponse< String > login = post( client, "/api/auth/login",
                GSON.toJson( Map.of( "username", "admin", "password", "admin123" ) ) );
        assertEquals( 200, login.statusCode(), "Login should succeed: " + login.body() );
        final JsonObject loginJson = JsonParser.parseString( login.body() ).getAsJsonObject();
        assertTrue( loginJson.get( "mustChangePassword" ).getAsBoolean(),
                "Login response must carry mustChangePassword=true: " + login.body() );

        // 2. Status probe carries the flag.
        final HttpResponse< String > me = get( client, "/api/auth/user" );
        assertEquals( 200, me.statusCode(), "GET /api/auth/user should be 200: " + me.body() );
        assertTrue( JsonParser.parseString( me.body() ).getAsJsonObject()
                .get( "mustChangePassword" ).getAsBoolean(),
                "GET /api/auth/user must report mustChangePassword=true: " + me.body() );

        // 3. A non-auth API call is gated with the structured code.
        final HttpResponse< String > gated = get( client, "/admin/users" );
        assertEquals( 403, gated.statusCode(),
                "Gated endpoint should return 403 before password change: " + gated.body() );
        assertTrue( gated.body().contains( "PASSWORD_CHANGE_REQUIRED" ),
                "403 body must contain PASSWORD_CHANGE_REQUIRED: " + gated.body() );

        // 4. Self-service password change through the allowlisted endpoint.
        final HttpResponse< String > change = put( client, "/api/auth/profile",
                GSON.toJson( Map.of( "currentPassword", "admin123", "newPassword", NEW_PASSWORD ) ) );
        assertEquals( 200, change.statusCode(),
                "PUT /api/auth/profile should return 200: " + change.body() );

        // 5. Gate lifted in the same session.
        final HttpResponse< String > ungated = get( client, "/admin/users" );
        assertEquals( 200, ungated.statusCode(),
                "Admin endpoint should be accessible after password change: " + ungated.body() );

        // 6. A fresh session with the new password is unflagged.
        final HttpClient fresh = newClient();
        final HttpResponse< String > relogin = post( fresh, "/api/auth/login",
                GSON.toJson( Map.of( "username", "admin", "password", NEW_PASSWORD ) ) );
        assertEquals( 200, relogin.statusCode(),
                "Re-login with new password should succeed: " + relogin.body() );
        assertFalse( JsonParser.parseString( relogin.body() ).getAsJsonObject()
                .get( "mustChangePassword" ).getAsBoolean(),
                "Re-login response must report mustChangePassword=false: " + relogin.body() );
    }
}
