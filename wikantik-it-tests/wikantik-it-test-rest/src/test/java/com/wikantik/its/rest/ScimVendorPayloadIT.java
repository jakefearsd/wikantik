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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static com.google.gson.JsonParser.parseString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays real Okta / Microsoft Entra SCIM request bodies (committed under
 * {@code src/test/resources/scim-samples/}) against the live {@code /scim/v2}
 * endpoint, proving the service-provider accepts genuine vendor shapes — Okta's
 * no-{@code path} {@code replace} PATCH and Entra's enterprise-extension create —
 * not merely idealized hand-rolled payloads.
 */
public class ScimVendorPayloadIT {

    private static final String SCIM_TOKEN = "it-scim-token";
    private static final String CT = "application/scim+json";
    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
    }

    private String fixture( final String name ) throws IOException {
        try ( var in = getClass().getResourceAsStream( "/scim-samples/" + name ) ) {
            if ( in == null ) throw new IOException( "missing fixture: " + name );
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    private HttpResponse<String> send( final String method, final String path, final String body )
            throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", CT )
                .header( "Authorization", "Bearer " + SCIM_TOKEN );
        if ( body != null ) {
            b.header( "Content-Type", CT );
            b.method( method, HttpRequest.BodyPublishers.ofString( body ) );
        } else {
            b.method( method, HttpRequest.BodyPublishers.noBody() );
        }
        return client.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private void deleteByUserName( final String userName ) throws IOException, InterruptedException {
        final HttpResponse<String> existing = send( "GET", "/scim/v2/Users?filter="
                + URLEncoder.encode( "userName eq \"" + userName + "\"", StandardCharsets.UTF_8 ), null );
        if ( existing.statusCode() == 200 ) {
            final var arr = parseString( existing.body() ).getAsJsonObject().getAsJsonArray( "Resources" );
            if ( arr != null ) {
                for ( final var el : arr ) {
                    send( "DELETE", "/scim/v2/Users/" + el.getAsJsonObject().get( "id" ).getAsString(), null );
                }
            }
        }
    }

    private String createAndAssert( final String fixtureFile, final String userName ) throws Exception {
        deleteByUserName( userName );
        final HttpResponse<String> resp = send( "POST", "/scim/v2/Users", fixture( fixtureFile ) );
        assertEquals( 201, resp.statusCode(),
                fixtureFile + " should create (201): " + resp.body() );
        final var body = parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.get( "active" ).getAsBoolean(), fixtureFile + " active must be true" );
        return body.get( "id" ).getAsString();
    }

    @Test
    void okta_create_then_no_path_deactivate_patch() throws Exception {
        final String id = createAndAssert( "okta-create-user.json", "okta.sample@example.com" );
        // Okta's no-`path` replace shape must deactivate the user.
        final HttpResponse<String> patch = send( "PATCH", "/scim/v2/Users/" + id,
                fixture( "okta-deactivate-patch.json" ) );
        assertEquals( 200, patch.statusCode(), "okta deactivate PATCH should be 200: " + patch.body() );
        assertFalse( parseString( patch.body() ).getAsJsonObject().get( "active" ).getAsBoolean(),
                "okta no-path PATCH must set active:false" );
        send( "DELETE", "/scim/v2/Users/" + id, null );
    }

    @Test
    void entra_enterprise_extension_create() throws Exception {
        final String id = createAndAssert( "entra-create-user.json", "entra.sample@example.com" );
        send( "DELETE", "/scim/v2/Users/" + id, null );
    }
}
