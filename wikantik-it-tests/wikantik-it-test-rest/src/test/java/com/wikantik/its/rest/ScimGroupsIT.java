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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wire-level integration test for the SCIM 2.0 Groups lifecycle.
 *
 * <p>Exercises the full SCIM Groups surface against a Cargo-deployed Tomcat:
 * auth enforcement (step 1), group create (step 3), GET (step 4), PATCH add
 * member (step 5), PATCH remove member via value-path (step 6), PUT replace
 * (step 7), filter by displayName (step 8), admin-role invariant (step 9),
 * hard DELETE + 404 (step 10), and audit-event assertion (step 11).
 *
 * <p>The SCIM bearer token ({@code it-scim-token}) is injected into the Cargo
 * container JVM via {@code -Dwikantik.scim.token=it-scim-token} in
 * {@code wikantik-it-tests/pom.xml}'s {@code cargo.jvmargs} property.
 *
 * <p><b>Admin-role invariant:</b> Step 9 creates a SCIM group named
 * {@code ScimAdminTest<marker>} and adds a member user provisioned via SCIM
 * Users.  It then asserts the member user has <em>no</em> {@code Admin} role
 * by opening a JDBC connection (same pattern as {@link AuditLogIT} step 9) to
 * the IT PostgreSQL instance and asserting
 * {@code SELECT count(*) FROM roles WHERE login_name=? AND role='Admin'} is 0.
 * The member's {@code login_name} is resolved via
 * {@code GET /scim/v2/Users/{uid}} → {@code userName}.
 *
 * <p>Mirror of the {@link ScimUsersIT}/{@link AuditLogIT} harness pattern.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class ScimGroupsIT {

    private static final String SCIM_TOKEN = "it-scim-token";
    private static final String SCIM_CONTENT_TYPE = "application/scim+json";

    /**
     * Run-unique marker so repeated executions against a persistent DB do not collide.
     * Uses the lower bits of the current time in milliseconds.
     */
    private static final String MARKER = String.valueOf( System.currentTimeMillis() % 1_000_000L );

    /** Primary group name used throughout the lifecycle tests. */
    private static final String GROUP_NAME = "ITEngineers" + MARKER;

    /** Group used for the admin-role invariant check. */
    private static final String ADMIN_INVARIANT_GROUP = "ScimAdminTest" + MARKER;

    /** First member user provisioned via SCIM Users. */
    private static final String USER1_NAME = "scim-grp-u1-" + MARKER;
    /** Second member user provisioned via SCIM Users. */
    private static final String USER2_NAME = "scim-grp-u2-" + MARKER;
    /** Third member user used for the admin-role invariant check. */
    private static final String USER3_NAME = "scim-grp-u3-" + MARKER;

    /** Poll ceiling for async audit events. */
    private static final long POLL_TIMEOUT_MS = 10_000L;
    private static final long POLL_INTERVAL_MS = 300L;

    private static final Gson GSON = new Gson();

    private static String baseUrl;

    /** Shared SCIM client (no cookie jar needed — bearer-token auth). */
    private static HttpClient scimClient;

    /** Admin-session client (cookie-jar based, mirrors ScimUsersIT). */
    private static HttpClient adminClient;

    // -------------------------------------------------------------------------
    // State shared across ordered tests
    // -------------------------------------------------------------------------

    private static String uid1;
    private static String uid2;
    private static String uid3;

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
     * {@code http://} requests.  This wrapper fools the store into treating every
     * URI as HTTPS so the JSESSIONID is always forwarded — identical to the
     * pattern in {@link ScimUsersIT}.
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

    private HttpResponse<String> scimPut( final String path, final String body )
            throws IOException, InterruptedException {
        return scimClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", SCIM_CONTENT_TYPE )
                        .header( "Accept", SCIM_CONTENT_TYPE )
                        .header( "Authorization", "Bearer " + SCIM_TOKEN )
                        .PUT( HttpRequest.BodyPublishers.ofString( body ) )
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
    // Admin HTTP helpers (cookie-session auth, mirrors ScimUsersIT)
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
    // Audit poll helper (mirrors ScimUsersIT)
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
    // Helper: provision a SCIM user and return their uid
    // -------------------------------------------------------------------------

    private String provisionScimUser( final String userName ) throws IOException, InterruptedException {
        // Clean up any pre-existing user from a prior run
        final HttpResponse<String> existing = scimGet(
                "/scim/v2/Users?filter=" + URLEncoder.encode( "userName eq \"" + userName + "\"",
                        StandardCharsets.UTF_8 ) );
        if ( existing.statusCode() == 200 ) {
            final JsonObject listBody = JsonParser.parseString( existing.body() ).getAsJsonObject();
            final JsonArray resources = listBody.getAsJsonArray( "Resources" );
            if ( resources != null ) {
                for ( final JsonElement el : resources ) {
                    scimDelete( "/scim/v2/Users/" + el.getAsJsonObject().get( "id" ).getAsString() );
                }
            }
        }

        // Use userName as the formatted name so each user gets a unique wiki_name
        // (wiki_name is derived by stripping whitespace from fullname; a shared
        // formatted string like "SCIM Group IT User" would cause a duplicate-key
        // violation on the users_wiki_name_uniq constraint when provisioning
        // multiple users in the same test run).
        final String createBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],"
                + "\"userName\":\"" + userName + "\","
                + "\"name\":{\"formatted\":\"" + userName + "\"},"
                + "\"emails\":[{\"value\":\"" + userName + "@example.com\",\"primary\":true}]"
                + "}";

        final HttpResponse<String> resp = scimPost( "/scim/v2/Users", createBody );
        assertEquals( 201, resp.statusCode(),
                "POST /scim/v2/Users for " + userName + " should return 201: " + resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "id" ), "User create response must have 'id': " + resp.body() );
        return body.get( "id" ).getAsString();
    }

    // -------------------------------------------------------------------------
    // Helper: clean up any pre-existing group from a prior run
    // -------------------------------------------------------------------------

    private void deleteGroupIfExists( final String groupName )
            throws IOException, InterruptedException {
        final HttpResponse<String> existing = scimGet( "/scim/v2/Groups/" + groupName );
        if ( existing.statusCode() == 200 ) {
            scimDelete( "/scim/v2/Groups/" + groupName );
        }
    }

    // -------------------------------------------------------------------------
    // Step 1: Auth — no/bad token returns 401
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    void no_token_returns_401() throws Exception {
        // No Authorization header at all
        final HttpResponse<String> noAuth = scimGet( "/scim/v2/Groups", null );
        assertEquals( 401, noAuth.statusCode(),
                "Request with no token should be 401, got: " + noAuth.body() );

        // Wrong token
        final HttpResponse<String> badAuth = scimGet( "/scim/v2/Groups", "wrong-token" );
        assertEquals( 401, badAuth.statusCode(),
                "Request with wrong token should be 401, got: " + badAuth.body() );
    }

    // -------------------------------------------------------------------------
    // Step 2: Provision member users
    // -------------------------------------------------------------------------

    @Test
    @Order( 2 )
    void provision_member_users() throws Exception {
        uid1 = provisionScimUser( USER1_NAME );
        uid2 = provisionScimUser( USER2_NAME );
        uid3 = provisionScimUser( USER3_NAME );
        assertNotNull( uid1, "uid1 must be set" );
        assertNotNull( uid2, "uid2 must be set" );
        assertNotNull( uid3, "uid3 must be set" );
        System.out.println( "[ScimGroupsIT] Provisioned users: uid1=" + uid1
                + " uid2=" + uid2 + " uid3=" + uid3 );
    }

    // -------------------------------------------------------------------------
    // Step 3: Create group → 201; verify id, displayName, members
    // -------------------------------------------------------------------------

    @Test
    @Order( 3 )
    void create_group_returns_201_with_id_and_member() throws Exception {
        assertNotNull( uid1, "uid1 must be set by step 2" );

        deleteGroupIfExists( GROUP_NAME );

        final String createBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"displayName\":\"" + GROUP_NAME + "\","
                + "\"members\":[{\"value\":\"" + uid1 + "\"}]"
                + "}";

        final HttpResponse<String> resp = scimPost( "/scim/v2/Groups", createBody );
        assertEquals( 201, resp.statusCode(),
                "POST /scim/v2/Groups should return 201: " + resp.body() );

        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "id" ), "Response must have 'id': " + resp.body() );
        assertEquals( GROUP_NAME, body.get( "displayName" ).getAsString(),
                "displayName must match: " + resp.body() );

        // members array must contain uid1
        assertTrue( body.has( "members" ), "Response must have 'members': " + resp.body() );
        final JsonArray members = body.getAsJsonArray( "members" );
        assertTrue( containsMember( members, uid1 ),
                "Created group must contain uid1=" + uid1 + ": " + resp.body() );
    }

    // -------------------------------------------------------------------------
    // Step 4: GET by name → 200, member uid1 present
    // -------------------------------------------------------------------------

    @Test
    @Order( 4 )
    void get_group_returns_200_with_member() throws Exception {
        assertNotNull( uid1, "uid1 must be set by step 2" );

        final HttpResponse<String> resp = scimGet( "/scim/v2/Groups/" + GROUP_NAME );
        assertEquals( 200, resp.statusCode(),
                "GET /scim/v2/Groups/" + GROUP_NAME + " should return 200: " + resp.body() );

        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertEquals( GROUP_NAME, body.get( "displayName" ).getAsString(),
                "displayName must match: " + resp.body() );
        assertTrue( body.has( "members" ), "Response must have 'members': " + resp.body() );
        assertTrue( containsMember( body.getAsJsonArray( "members" ), uid1 ),
                "GET must show uid1=" + uid1 + ": " + resp.body() );
    }

    // -------------------------------------------------------------------------
    // Step 5: PATCH add uid2 → 200; GET shows both uid1 + uid2
    // -------------------------------------------------------------------------

    @Test
    @Order( 5 )
    void patch_add_member_shows_both_members() throws Exception {
        assertNotNull( uid1, "uid1 must be set by step 2" );
        assertNotNull( uid2, "uid2 must be set by step 2" );

        final String patchBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{"
                + "\"op\":\"add\","
                + "\"path\":\"members\","
                + "\"value\":[{\"value\":\"" + uid2 + "\"}]"
                + "}]"
                + "}";

        final HttpResponse<String> patchResp = scimPatch( "/scim/v2/Groups/" + GROUP_NAME, patchBody );
        assertEquals( 200, patchResp.statusCode(),
                "PATCH add member should return 200: " + patchResp.body() );

        // Verify via GET
        final HttpResponse<String> getResp = scimGet( "/scim/v2/Groups/" + GROUP_NAME );
        assertEquals( 200, getResp.statusCode(), "GET after PATCH add should return 200: " + getResp.body() );
        final JsonArray members = JsonParser.parseString( getResp.body() )
                .getAsJsonObject().getAsJsonArray( "members" );
        assertTrue( containsMember( members, uid1 ),
                "After PATCH add, uid1 must still be present: " + getResp.body() );
        assertTrue( containsMember( members, uid2 ),
                "After PATCH add, uid2 must be present: " + getResp.body() );
    }

    // -------------------------------------------------------------------------
    // Step 6: PATCH remove uid1 via value-path → 200; GET shows only uid2
    // -------------------------------------------------------------------------

    @Test
    @Order( 6 )
    void patch_remove_member_via_value_path() throws Exception {
        assertNotNull( uid1, "uid1 must be set by step 2" );
        assertNotNull( uid2, "uid2 must be set by step 2" );

        // Value-path form: path = members[value eq "<uid1>"]
        final String valuePath = "members[value eq \\\"" + uid1 + "\\\"]";
        final String patchBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                + "\"Operations\":[{"
                + "\"op\":\"remove\","
                + "\"path\":\"" + valuePath + "\""
                + "}]"
                + "}";

        final HttpResponse<String> patchResp = scimPatch( "/scim/v2/Groups/" + GROUP_NAME, patchBody );
        assertEquals( 200, patchResp.statusCode(),
                "PATCH remove via value-path should return 200: " + patchResp.body() );

        // Verify via GET — only uid2 should remain
        final HttpResponse<String> getResp = scimGet( "/scim/v2/Groups/" + GROUP_NAME );
        assertEquals( 200, getResp.statusCode(),
                "GET after PATCH remove should return 200: " + getResp.body() );
        final JsonObject getBody = JsonParser.parseString( getResp.body() ).getAsJsonObject();
        final JsonArray members = getBody.has( "members" ) && !getBody.get( "members" ).isJsonNull()
                ? getBody.getAsJsonArray( "members" ) : new JsonArray();
        assertTrue( containsMember( members, uid2 ),
                "After PATCH remove, uid2 must remain: " + getResp.body() );
        assertTrue( !containsMember( members, uid1 ),
                "After PATCH remove, uid1 must be gone: " + getResp.body() );
    }

    // -------------------------------------------------------------------------
    // Step 7: PUT replace members with [uid1] → 200; GET shows only uid1
    // -------------------------------------------------------------------------

    @Test
    @Order( 7 )
    void put_replace_members() throws Exception {
        assertNotNull( uid1, "uid1 must be set by step 2" );
        assertNotNull( uid2, "uid2 must be set by step 2" );

        final String putBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"displayName\":\"" + GROUP_NAME + "\","
                + "\"members\":[{\"value\":\"" + uid1 + "\"}]"
                + "}";

        final HttpResponse<String> putResp = scimPut( "/scim/v2/Groups/" + GROUP_NAME, putBody );
        assertEquals( 200, putResp.statusCode(),
                "PUT /scim/v2/Groups/" + GROUP_NAME + " should return 200: " + putResp.body() );

        // Verify via GET — only uid1 should be present
        final HttpResponse<String> getResp = scimGet( "/scim/v2/Groups/" + GROUP_NAME );
        assertEquals( 200, getResp.statusCode(),
                "GET after PUT should return 200: " + getResp.body() );
        final JsonObject getBody = JsonParser.parseString( getResp.body() ).getAsJsonObject();
        final JsonArray members = getBody.has( "members" ) && !getBody.get( "members" ).isJsonNull()
                ? getBody.getAsJsonArray( "members" ) : new JsonArray();
        assertTrue( containsMember( members, uid1 ),
                "After PUT replace, uid1 must be present: " + getResp.body() );
        assertTrue( !containsMember( members, uid2 ),
                "After PUT replace, uid2 must be absent: " + getResp.body() );
    }

    // -------------------------------------------------------------------------
    // Step 8: GET ?filter=displayName eq "<name>" → 1 result
    // -------------------------------------------------------------------------

    @Test
    @Order( 8 )
    void filter_by_display_name_returns_one_result() throws Exception {
        final String filter = "displayName eq \"" + GROUP_NAME + "\"";
        final HttpResponse<String> resp = scimGet(
                "/scim/v2/Groups?filter=" + URLEncoder.encode( filter, StandardCharsets.UTF_8 ) );
        assertEquals( 200, resp.statusCode(),
                "GET /scim/v2/Groups?filter should return 200: " + resp.body() );

        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "totalResults" ),
                "ListResponse must have totalResults: " + resp.body() );
        assertEquals( 1, body.get( "totalResults" ).getAsInt(),
                "Exactly 1 result expected for displayName filter: " + resp.body() );

        final JsonArray resources = body.getAsJsonArray( "Resources" );
        assertNotNull( resources, "Resources array must be present: " + resp.body() );
        assertEquals( 1, resources.size(),
                "Resources must have exactly 1 entry: " + resp.body() );
        assertEquals( GROUP_NAME,
                resources.get( 0 ).getAsJsonObject().get( "displayName" ).getAsString(),
                "Resource displayName must match: " + resp.body() );
    }

    // -------------------------------------------------------------------------
    // Step 9: Admin-role invariant
    //
    // Creates a SCIM group (ScimAdminTest<marker>) with uid3 as member, then
    // asserts uid3 has NO Admin role via a direct JDBC query against the IT
    // PostgreSQL database (same approach as AuditLogIT step 9).
    //
    // The member's login_name is resolved via GET /scim/v2/Users/{uid3} → userName.
    // -------------------------------------------------------------------------

    @Test
    @Order( 9 )
    void scim_group_membership_does_not_grant_admin_role() throws Exception {
        assertNotNull( uid3, "uid3 must be set by step 2" );

        // Create the ScimAdminTest group with uid3 as member
        deleteGroupIfExists( ADMIN_INVARIANT_GROUP );

        final String createBody = "{"
                + "\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"displayName\":\"" + ADMIN_INVARIANT_GROUP + "\","
                + "\"members\":[{\"value\":\"" + uid3 + "\"}]"
                + "}";

        final HttpResponse<String> createResp = scimPost( "/scim/v2/Groups", createBody );
        // Accept 201 (created) or 409 (already exists from a prior run that leaked)
        assertTrue( createResp.statusCode() == 201 || createResp.statusCode() == 409,
                "POST admin-invariant group should return 201 or 409: " + createResp.body() );

        // Step 9a: Group was created as a normal group — GET returns 200
        final HttpResponse<String> getResp = scimGet( "/scim/v2/Groups/" + ADMIN_INVARIANT_GROUP );
        assertEquals( 200, getResp.statusCode(),
                "GET admin-invariant group should return 200: " + getResp.body() );
        final JsonObject groupBody = JsonParser.parseString( getResp.body() ).getAsJsonObject();
        assertEquals( ADMIN_INVARIANT_GROUP, groupBody.get( "displayName" ).getAsString(),
                "Admin-invariant group displayName must match: " + getResp.body() );

        // Step 9b: Resolve uid3's login_name via GET /scim/v2/Users/{uid3}
        final HttpResponse<String> userResp = scimGet( "/scim/v2/Users/" + uid3 );
        assertEquals( 200, userResp.statusCode(),
                "GET /scim/v2/Users/" + uid3 + " should return 200: " + userResp.body() );
        final String loginName = JsonParser.parseString( userResp.body() )
                .getAsJsonObject().get( "userName" ).getAsString();
        assertNotNull( loginName, "userName must be present in user GET response" );

        // Step 9c: Assert via JDBC that login_name has no Admin role row.
        //
        // Uses the same it.db.* system-property pattern as AuditLogIT step 9.
        // The Docker POSTGRES_USER is the app user (jspwiki), which has full access
        // to the roles table — no separate superuser role needed here.
        final String dbUser  = System.getProperty( "it.db.user" );
        final String dbPass  = System.getProperty( "it.db.password" );
        final String dbPort  = System.getProperty( "it.db.port", "55432" );
        final String dbName  = System.getProperty( "it.db.name", "wikantik" );

        if ( dbUser == null || dbUser.isBlank() || dbPass == null || dbPass.isBlank() ) {
            fail( "it.db.user / it.db.password system properties not set — cannot assert "
                    + "admin-role invariant via JDBC (they are set by the IT failsafe run)" );
        }

        final String jdbcUrl = "jdbc:postgresql://localhost:" + dbPort + "/" + dbName;
        try ( Connection conn = DriverManager.getConnection( jdbcUrl, dbUser, dbPass );
              PreparedStatement ps = conn.prepareStatement(
                      "SELECT count(*) FROM roles WHERE login_name = ? AND role = 'Admin'" ) ) {
            ps.setString( 1, loginName );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next(), "Query must return a row" );
                final int adminRoleCount = rs.getInt( 1 );
                assertEquals( 0, adminRoleCount,
                        "SCIM group membership must not grant Admin role to login_name='"
                        + loginName + "': found " + adminRoleCount + " Admin role row(s)" );
            }
        }
        System.out.println( "[ScimGroupsIT] Admin-role invariant PASSED via JDBC: login_name='"
                + loginName + "' has 0 Admin role rows after SCIM group membership." );
    }

    // -------------------------------------------------------------------------
    // Step 10: Hard DELETE → 204; subsequent GET → 404
    // -------------------------------------------------------------------------

    @Test
    @Order( 10 )
    void hard_delete_returns_204_and_subsequent_get_returns_404() throws Exception {
        final HttpResponse<String> delResp = scimDelete( "/scim/v2/Groups/" + GROUP_NAME );
        assertEquals( 204, delResp.statusCode(),
                "DELETE /scim/v2/Groups/" + GROUP_NAME + " should return 204: " + delResp.body() );

        final HttpResponse<String> getResp = scimGet( "/scim/v2/Groups/" + GROUP_NAME );
        assertEquals( 404, getResp.statusCode(),
                "GET after DELETE should return 404: " + getResp.body() );
    }

    // -------------------------------------------------------------------------
    // Step 11: Assert scim.group.create audit row exists
    // -------------------------------------------------------------------------

    @Test
    @Order( 11 )
    void scim_group_create_audit_event_exists() throws Exception {
        try {
            loginAsAdmin();
            // The targetId for group audit events is the group displayName
            final JsonObject auditRow = pollForAuditEvent(
                    "scim.group.create", GROUP_NAME, POLL_TIMEOUT_MS );
            assertNotNull( auditRow, "Expected scim.group.create audit row for " + GROUP_NAME );
            assertEquals( "scim.group.create", auditRow.get( "eventType" ).getAsString(),
                    "Audit eventType must be scim.group.create: " + auditRow );
            System.out.println( "[ScimGroupsIT] Audit row found: " + auditRow );
        } finally {
            logoutAdmin();
        }
    }

    // -------------------------------------------------------------------------
    // Utility: check whether a members JsonArray contains a given uid
    // -------------------------------------------------------------------------

    private static boolean containsMember( final JsonArray members, final String uid ) {
        if ( members == null ) return false;
        for ( final JsonElement el : members ) {
            final JsonObject m = el.getAsJsonObject();
            if ( m.has( "value" ) && uid.equals( m.get( "value" ).getAsString() ) ) {
                return true;
            }
        }
        return false;
    }
}
