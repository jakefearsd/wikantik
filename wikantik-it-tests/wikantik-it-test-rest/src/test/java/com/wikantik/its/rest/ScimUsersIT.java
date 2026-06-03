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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wire-level integration test for the SCIM 2.0 Users lifecycle.
 *
 * <p>Exercises the full SCIM Users surface against a Cargo-deployed Tomcat:
 * auth enforcement, create, list+filter, deactivate (with audit), reactivate,
 * soft-delete, service-provider discovery, and unsupported-filter rejection.
 *
 * <p>The SCIM bearer token ({@code it-scim-token}) is injected into the Cargo
 * container JVM via {@code -Dwikantik.scim.token=it-scim-token} in
 * {@code wikantik-it-tests/pom.xml}'s {@code cargo.jvmargs} property.
 *
 * <p>Mirror of the {@link AuditLogIT}/{@link KgPolicyAdminIT} harness pattern.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class ScimUsersIT {

    private static final String SCIM_TOKEN = "it-scim-token";
    private static final String SCIM_CONTENT_TYPE = "application/scim+json";

    /** Unique marker so the IT userName does not collide across repeated runs on a persistent DB. */
    private static final String USER_NAME = "scim-it-user";
    private static final String EXTERNAL_ID = "scim-it-ext-001";

    /** Poll ceiling for async audit events. */
    private static final long POLL_TIMEOUT_MS = 10_000L;
    private static final long POLL_INTERVAL_MS = 300L;

    private static final Gson GSON = new Gson();

    private static String baseUrl;

    /** Shared SCIM client (no cookie jar needed — bearer-token auth). */
    private static HttpClient scimClient;

    /** Admin-session client (cookie-jar based, mirrors AuditLogIT). */
    private static HttpClient adminClient;

    /** The SCIM id of the provisioned user, set by the create test and used by later steps. */
    private static String createdUserId;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        scimClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();
        adminClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    /**
     * The web.xml sets {@code <secure>true</secure>} on the session cookie.
     * Java's {@link java.net.InMemoryCookieStore} filters Secure cookies on plain
     * {@code http://} requests. This wrapper fools the store into treating every
     * URI as HTTPS so the JSESSIONID is always forwarded — identical to the
     * pattern in {@link AuditLogIT}.
     */
    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map<String, List<String>> get( final URI uri,
                    final Map<String, List<String>> requestHeaders ) throws IOException {
                return cm.get( asHttps( uri ), requestHeaders );
            }

            @Override
            public void put( final URI uri,
                    final Map<String, List<String>> responseHeaders ) throws IOException {
                cm.put( uri, responseHeaders );
            }

            private URI asHttps( final URI uri ) {
                return URI.create( uri.toString().replaceFirst( "^http:", "https:" ) );
            }
        };
    }

    // -------------------------------------------------------------------------
    // SCIM HTTP helpers (bearer-token auth, application/scim+json)
    // -------------------------------------------------------------------------

    private HttpResponse<String> scimGet( final String path ) throws IOException, InterruptedException {
        return scimGet( path, SCIM_TOKEN );
    }

    private HttpResponse<String> scimGet( final String path, final String token )
            throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", SCIM_CONTENT_TYPE )
                .GET();
        if ( token != null ) b.header( "Authorization", "Bearer " + token );
        return scimClient.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimPost( final String path, final String body )
            throws IOException, InterruptedException {
        return scimClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", SCIM_CONTENT_TYPE )
                        .header( "Accept", SCIM_CONTENT_TYPE )
                        .header( "Authorization", "Bearer " + SCIM_TOKEN )
                        .POST( HttpRequest.BodyPublishers.ofString( body ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimPatch( final String path, final String body )
            throws IOException, InterruptedException {
        return scimClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", SCIM_CONTENT_TYPE )
                        .header( "Accept", SCIM_CONTENT_TYPE )
                        .header( "Authorization", "Bearer " + SCIM_TOKEN )
                        .method( "PATCH", HttpRequest.BodyPublishers.ofString( body ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> scimDelete( final String path )
            throws IOException, InterruptedException {
        return scimClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Authorization", "Bearer " + SCIM_TOKEN )
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    // -------------------------------------------------------------------------
    // Admin HTTP helpers (cookie-session auth, mirrors AuditLogIT)
    // -------------------------------------------------------------------------

    private HttpResponse<String> adminGet( final String path ) throws IOException, InterruptedException {
        return adminClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> adminPost( final String path, final String jsonBody )
            throws IOException, InterruptedException {
        return adminClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /** Authenticates the shared admin {@link #adminClient} cookie jar. */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse<String> resp = adminPost( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        adminPost( "/api/auth/logout", "{}" );
    }

    // -------------------------------------------------------------------------
    // Audit poll helper (mirrors AuditLogIT)
    // -------------------------------------------------------------------------

    /**
     * Polls {@code GET /admin/audit?eventType=<type>&limit=1000} until a row whose
     * {@code targetId} equals {@code targetId} appears, or the timeout elapses.
     * Returns the matching row; fails the test if no row appears within the timeout.
     */
    private JsonObject pollForAuditEvent( final String eventType, final String targetId,
                                          final long timeoutMs )
            throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse<String> resp = adminGet(
                    "/admin/audit?eventType=" + URLEncoder.encode( eventType, StandardCharsets.UTF_8 )
                    + "&limit=1000" );
            assertEquals( 200, resp.statusCode(),
                    "GET /admin/audit should return 200, got: " + resp.body() );
            final JsonArray rows = JsonParser.parseString( resp.body() ).getAsJsonArray();
            for ( final JsonElement el : rows ) {
                final JsonObject row = el.getAsJsonObject();
                final String rowTarget = row.has( "targetId" ) && !row.get( "targetId" ).isJsonNull()
                        ? row.get( "targetId" ).getAsString() : "";
                if ( targetId.equals( rowTarget ) ) {
                    return row;
                }
            }
            Thread.sleep( POLL_INTERVAL_MS );
        }
        fail( "Timed out waiting for audit event eventType=" + eventType + " targetId=" + targetId );
        return null; // unreachable
    }

    // -------------------------------------------------------------------------
    // Test 1: Auth — no/bad token returns 401
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    void no_token_returns_401() throws Exception {
        // No Authorization header at all
        final HttpResponse<String> noAuth = scimGet( "/scim/v2/Users", null );
        assertEquals( 401, noAuth.statusCode(),
                "Request with no token should be 401, got: " + noAuth.body() );

        // Wrong token
        final HttpResponse<String> badAuth = scimGet( "/scim/v2/Users", "wrong-token" );
        assertEquals( 401, badAuth.statusCode(),
                "Request with wrong token should be 401, got: " + badAuth.body() );
    }

    // -------------------------------------------------------------------------
    // Test 2: Create — POST → 201, id, active:true, meta.location
    // -------------------------------------------------------------------------

    @Test
    @Order( 2 )
    void create_user_returns_201_with_id_active_and_location() throws Exception {
        // If user already exists from a previous run, clean up first via SCIM filter
        final HttpResponse<String> existing = scimGet(
                "/scim/v2/Users?filter=" + URLEncoder.encode( "userName eq \"" + USER_NAME + "\"",
                        StandardCharsets.UTF_8 ) );
        if ( existing.statusCode() == 200 ) {
            final JsonObject listBody = JsonParser.parseString( existing.body() ).getAsJsonObject();
            final JsonArray resources = listBody.getAsJsonArray( "Resources" );
            if ( resources != null ) {
                for ( final JsonElement el : resources ) {
                    final String uid = el.getAsJsonObject().get( "id" ).getAsString();
                    scimDelete( "/scim/v2/Users/" + uid );
                }
            }
        }

        final String createBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"" + USER_NAME + "\","
                + "\"externalId\":\"" + EXTERNAL_ID + "\","
                + "\"name\":{\"formatted\":\"SCIM IT User\"},"
                + "\"emails\":[{\"value\":\"scim-it@example.com\",\"primary\":true}]"
                + "}";

        final HttpResponse<String> resp = scimPost( "/scim/v2/Users", createBody );
        assertEquals( 201, resp.statusCode(),
                "POST /scim/v2/Users should return 201: " + resp.body() );

        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "id" ), "Response must have 'id': " + resp.body() );
        assertNotNull( body.get( "id" ).getAsString(), "id must not be null" );
        assertTrue( body.has( "active" ), "Response must have 'active': " + resp.body() );
        assertTrue( body.get( "active" ).getAsBoolean(), "active must be true on create: " + resp.body() );
        assertTrue( body.has( "meta" ), "Response must have 'meta': " + resp.body() );
        final JsonObject meta = body.getAsJsonObject( "meta" );
        assertTrue( meta.has( "location" ) && !meta.get( "location" ).isJsonNull(),
                "meta.location must be present: " + resp.body() );
        assertTrue( meta.get( "location" ).getAsString().contains( "Users" ),
                "meta.location must reference Users: " + resp.body() );

        // Persist id for subsequent tests
        createdUserId = body.get( "id" ).getAsString();
    }

    // -------------------------------------------------------------------------
    // Test 3: List + filter by userName and externalId
    // -------------------------------------------------------------------------

    @Test
    @Order( 3 )
    void list_and_filter_returns_created_user() throws Exception {
        assertNotNull( createdUserId, "createdUserId must be set by test 2" );

        // GET by id
        final HttpResponse<String> byId = scimGet( "/scim/v2/Users/" + createdUserId );
        assertEquals( 200, byId.statusCode(),
                "GET by id should return 200: " + byId.body() );
        final JsonObject byIdBody = JsonParser.parseString( byId.body() ).getAsJsonObject();
        assertEquals( USER_NAME, byIdBody.get( "userName" ).getAsString(),
                "userName must match: " + byId.body() );

        // filter=userName eq "<name>"
        final HttpResponse<String> byName = scimGet(
                "/scim/v2/Users?filter=" + URLEncoder.encode( "userName eq \"" + USER_NAME + "\"",
                        StandardCharsets.UTF_8 ) );
        assertEquals( 200, byName.statusCode(),
                "GET ?filter=userName should return 200: " + byName.body() );
        final JsonObject nameBody = JsonParser.parseString( byName.body() ).getAsJsonObject();
        assertTrue( nameBody.has( "totalResults" ), "Response must have totalResults: " + byName.body() );
        assertEquals( 1, nameBody.get( "totalResults" ).getAsInt(),
                "Exactly 1 result expected for userName filter: " + byName.body() );

        // filter=externalId eq "<ext>"
        final HttpResponse<String> byExt = scimGet(
                "/scim/v2/Users?filter=" + URLEncoder.encode( "externalId eq \"" + EXTERNAL_ID + "\"",
                        StandardCharsets.UTF_8 ) );
        assertEquals( 200, byExt.statusCode(),
                "GET ?filter=externalId should return 200: " + byExt.body() );
        final JsonObject extBody = JsonParser.parseString( byExt.body() ).getAsJsonObject();
        assertTrue( extBody.has( "totalResults" ), "Response must have totalResults: " + byExt.body() );
        assertEquals( 1, extBody.get( "totalResults" ).getAsInt(),
                "Exactly 1 result expected for externalId filter: " + byExt.body() );
    }

    // -------------------------------------------------------------------------
    // Test 4: Deactivate (PATCH active:false) + audit assertion
    // -------------------------------------------------------------------------

    @Test
    @Order( 4 )
    void deactivate_user_emits_audit_event_with_scim_source() throws Exception {
        assertNotNull( createdUserId, "createdUserId must be set by test 2" );

        final String patchBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]"
                + "}";

        final HttpResponse<String> patchResp = scimPatch( "/scim/v2/Users/" + createdUserId, patchBody );
        assertEquals( 200, patchResp.statusCode(),
                "PATCH active:false should return 200: " + patchResp.body() );

        final JsonObject patchBody2 = JsonParser.parseString( patchResp.body() ).getAsJsonObject();
        assertTrue( patchBody2.has( "active" ), "PATCH response must have 'active': " + patchResp.body() );
        assertTrue( !patchBody2.get( "active" ).getAsBoolean(),
                "active should be false after deactivate: " + patchResp.body() );

        // Confirm via GET
        final HttpResponse<String> getResp = scimGet( "/scim/v2/Users/" + createdUserId );
        assertEquals( 200, getResp.statusCode(), "GET after deactivate should return 200: " + getResp.body() );
        final JsonObject getBody = JsonParser.parseString( getResp.body() ).getAsJsonObject();
        assertTrue( !getBody.get( "active" ).getAsBoolean(),
                "GET after deactivate must show active:false: " + getResp.body() );

        // Assert audit event — admin-authed
        try {
            loginAsAdmin();
            final JsonObject auditRow = pollForAuditEvent( "user.deactivate", USER_NAME, POLL_TIMEOUT_MS );
            assertNotNull( auditRow, "Expected user.deactivate audit row" );
            assertEquals( "user.deactivate", auditRow.get( "eventType" ).getAsString() );
            // detail must contain "scim"
            assertTrue( auditRow.has( "detail" ) && !auditRow.get( "detail" ).isJsonNull(),
                    "Audit row must have 'detail': " + auditRow );
            assertTrue( auditRow.get( "detail" ).getAsString().contains( "scim" ),
                    "Audit detail must contain 'scim': " + auditRow );
        } finally {
            logoutAdmin();
        }
    }

    // -------------------------------------------------------------------------
    // Test 5: Reactivate (PATCH active:true) → active:true
    // -------------------------------------------------------------------------

    @Test
    @Order( 5 )
    void reactivate_user_returns_active_true() throws Exception {
        assertNotNull( createdUserId, "createdUserId must be set by test 2" );

        final String patchBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":true}]"
                + "}";

        final HttpResponse<String> patchResp = scimPatch( "/scim/v2/Users/" + createdUserId, patchBody );
        assertEquals( 200, patchResp.statusCode(),
                "PATCH active:true should return 200: " + patchResp.body() );

        final JsonObject body = JsonParser.parseString( patchResp.body() ).getAsJsonObject();
        assertTrue( body.has( "active" ), "PATCH response must have 'active': " + patchResp.body() );
        assertTrue( body.get( "active" ).getAsBoolean(),
                "active must be true after reactivate: " + patchResp.body() );
    }

    // -------------------------------------------------------------------------
    // Test 6: Soft delete (DELETE) → 204; subsequent GET → active:false, row retained
    // -------------------------------------------------------------------------

    @Test
    @Order( 6 )
    void soft_delete_returns_204_and_user_remains_inactive() throws Exception {
        assertNotNull( createdUserId, "createdUserId must be set by test 2" );

        final HttpResponse<String> delResp = scimDelete( "/scim/v2/Users/" + createdUserId );
        assertEquals( 204, delResp.statusCode(),
                "DELETE should return 204: " + delResp.body() );

        // Row is retained — GET still returns 200
        final HttpResponse<String> getResp = scimGet( "/scim/v2/Users/" + createdUserId );
        assertEquals( 200, getResp.statusCode(),
                "GET after DELETE should return 200 (row retained): " + getResp.body() );
        final JsonObject getBody = JsonParser.parseString( getResp.body() ).getAsJsonObject();
        assertTrue( !getBody.get( "active" ).getAsBoolean(),
                "active must be false after soft-delete: " + getResp.body() );
    }

    // -------------------------------------------------------------------------
    // Test 7: ServiceProviderConfig → 200, patch.supported:true
    // -------------------------------------------------------------------------

    @Test
    @Order( 7 )
    void service_provider_config_reports_patch_supported() throws Exception {
        final HttpResponse<String> resp = scimGet( "/scim/v2/ServiceProviderConfig" );
        assertEquals( 200, resp.statusCode(),
                "GET ServiceProviderConfig should return 200: " + resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "patch" ), "ServiceProviderConfig must have 'patch': " + resp.body() );
        final JsonObject patch = body.getAsJsonObject( "patch" );
        assertTrue( patch.has( "supported" ), "'patch' must have 'supported': " + resp.body() );
        assertTrue( patch.get( "supported" ).getAsBoolean(),
                "patch.supported must be true: " + resp.body() );
    }

    // -------------------------------------------------------------------------
    // Test 8: Unsupported filter → 400 invalidFilter
    // -------------------------------------------------------------------------

    @Test
    @Order( 8 )
    void unsupported_filter_returns_400_invalid_filter() throws Exception {
        final HttpResponse<String> resp = scimGet(
                "/scim/v2/Users?filter=" + URLEncoder.encode( "displayName eq \"x\"",
                        StandardCharsets.UTF_8 ) );
        assertEquals( 400, resp.statusCode(),
                "Unsupported filter should return 400: " + resp.body() );

        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        // SCIM error response may carry a scimType or detail indicating invalidFilter
        final String bodyStr = resp.body().toLowerCase();
        assertTrue( bodyStr.contains( "invalidfilter" ) || bodyStr.contains( "not supported" ),
                "Response body should indicate invalidFilter: " + resp.body() );
    }
}
