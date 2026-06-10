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
 * Wire-level integration tests for {@code DELETE /api/auth/profile} (self-service
 * account deletion) against a Cargo-deployed Tomcat instance.
 *
 * <p>Each test method is fully self-contained: throwaway users are created and
 * cleaned up independently so the tests can run in any order without shared state.
 * The admin account ({@code janne}) is never deleted.
 *
 * <p>Behaviors covered:
 * <ul>
 *   <li>Happy path — non-admin deletes own account, profile gone, session invalidated.</li>
 *   <li>{@code confirmLoginName} mismatch → 400, account still exists.</li>
 *   <li>Unauthenticated request → 401.</li>
 *   <li>Admin caller → 409, admin account still exists.</li>
 * </ul>
 */
public class SelfDeleteAccountIT {

    private static final Gson GSON = new Gson();

    /** Password used for all throwaway test users at creation time. */
    private static final String TEST_PASSWORD = "ItSelfDel1!";

    /**
     * Replacement password used during the forced-change step.
     * Admin-created users are flagged {@code password_must_change=TRUE}; the
     * {@code MustChangePasswordFilter} blocks gated endpoints (including
     * {@code DELETE /api/auth/profile}) until the password has been changed.
     */
    private static final String CHANGED_PASSWORD = "ItSelfDel2!";

    /** Admin credentials shared across tests (account never deleted). */
    private static final String ADMIN_USER = "janne";
    private static final String ADMIN_PASSWORD = "myP@5sw0rd";

    private static String baseUrl;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
    }

    // -----------------------------------------------------------------------
    // Helper: create a fresh HttpClient with its own isolated cookie jar.
    // Each test gets its own client so sessions never bleed between tests.
    // -----------------------------------------------------------------------

    /**
     * The web.xml sets {@code <secure>true</secure>} on the session cookie.
     * Java's {@link java.net.InMemoryCookieStore} silently drops Secure cookies
     * on plain {@code http://} requests — the same workaround used by every other
     * REST IT in this module.
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

    /** Creates a new HttpClient with its own isolated cookie jar. */
    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    // -----------------------------------------------------------------------
    // Low-level HTTP helpers
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

    private HttpResponse< String > delete( final HttpClient client, final String path,
            final String jsonBody ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .method( "DELETE", HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    // -----------------------------------------------------------------------
    // Domain helpers
    // -----------------------------------------------------------------------

    /**
     * Logs in with the given credentials on the provided client and asserts
     * 200 is returned. The client retains the JSESSIONID cookie for subsequent calls.
     */
    private void login( final HttpClient client, final String username,
            final String password ) throws IOException, InterruptedException {
        final String body = GSON.toJson( Map.of( "username", username, "password", password ) );
        final HttpResponse< String > resp = post( client, "/api/auth/login", body );
        assertEquals( 200, resp.statusCode(),
                "Login as '" + username + "' should succeed: " + resp.body() );
    }

    /**
     * Creates a throwaway non-admin user via the admin endpoint.
     * Caller must already be logged in as admin on {@code adminClient}.
     * Returns the login name on success.
     */
    private String createUser( final HttpClient adminClient,
            final String loginName ) throws IOException, InterruptedException {
        final String body = GSON.toJson( Map.of(
                "loginName", loginName,
                "fullName", "IT SelfDelete " + loginName,
                "email", loginName + "@self-del-it.example.com",
                "password", TEST_PASSWORD ) );
        final HttpResponse< String > resp = post( adminClient, "/admin/users", body );
        assertEquals( 201, resp.statusCode(),
                "User creation should return 201: " + resp.body() );
        return loginName;
    }

    /**
     * Best-effort cleanup: deletes the user via the admin endpoint.
     * Logs in as admin on a fresh client to avoid state interference.
     * Silently ignores failures so it does not mask test failures.
     */
    private void deleteUserBestEffort( final String loginName ) {
        try {
            final HttpClient cleanup = newClient();
            login( cleanup, ADMIN_USER, ADMIN_PASSWORD );
            cleanup.send(
                    HttpRequest.newBuilder()
                            .uri( URI.create( baseUrl + "/admin/users/" + loginName ) )
                            .header( "Accept", "application/json" )
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString() );
        } catch ( final Exception ignored ) {
            // best-effort; don't mask the real test failure
        }
    }

    /**
     * Asserts that a user exists in the admin user list (200 from
     * {@code GET /admin/users/<loginName>}).
     */
    private void assertUserExists( final HttpClient adminClient,
            final String loginName ) throws IOException, InterruptedException {
        final HttpResponse< String > resp = get( adminClient, "/admin/users/" + loginName );
        assertEquals( 200, resp.statusCode(),
                "User '" + loginName + "' should still exist (got " + resp.statusCode()
                + "): " + resp.body() );
    }

    /**
     * Asserts that a user is absent in the admin system (404 from
     * {@code GET /admin/users/<loginName>}).
     */
    private void assertUserGone( final HttpClient adminClient,
            final String loginName ) throws IOException, InterruptedException {
        final HttpResponse< String > resp = get( adminClient, "/admin/users/" + loginName );
        assertEquals( 404, resp.statusCode(),
                "User '" + loginName + "' should be gone after deletion (got "
                + resp.statusCode() + "): " + resp.body() );
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Happy path: a non-admin user deletes their own account.
     *
     * <p>Verifies:
     * <ol>
     *   <li>200 response with {@code deleted:true} and {@code loginName} in the body.</li>
     *   <li>The profile no longer exists — {@code GET /admin/users/<login>} returns 404.</li>
     *   <li>The session is invalidated — reusing the same cookie for
     *       {@code GET /api/auth/profile} returns 401.</li>
     * </ol>
     */
    @Test
    void happyPath_nonAdminDeletesOwnAccount() throws IOException, InterruptedException {
        final String loginName = "self-del-happy-" + System.nanoTime();

        // Create the user (admin session).
        final HttpClient adminClient = newClient();
        login( adminClient, ADMIN_USER, ADMIN_PASSWORD );
        createUser( adminClient, loginName );

        // Log in AS the new user (separate client with its own cookie jar).
        final HttpClient userClient = newClient();
        login( userClient, loginName, TEST_PASSWORD );

        // Admin-created users are flagged password_must_change; MustChangePasswordFilter
        // blocks DELETE /api/auth/profile until the password has been changed.
        final String changeBody = GSON.toJson( Map.of(
                "currentPassword", TEST_PASSWORD,
                "newPassword", CHANGED_PASSWORD ) );
        final HttpResponse< String > changeResp =
                put( userClient, "/api/auth/profile", changeBody );
        assertEquals( 200, changeResp.statusCode(),
                "Forced password change should return 200: " + changeResp.body() );

        // Self-delete (using the new password — the session cookie is still valid).
        final String deleteBody = GSON.toJson( Map.of( "confirmLoginName", loginName ) );
        final HttpResponse< String > deleteResp =
                delete( userClient, "/api/auth/profile", deleteBody );
        assertEquals( 200, deleteResp.statusCode(),
                "Self-delete should return 200: " + deleteResp.body() );

        final JsonObject json = JsonParser.parseString( deleteResp.body() ).getAsJsonObject();
        assertTrue( json.get( "deleted" ).getAsBoolean(),
                "Response body must contain deleted:true" );
        assertEquals( loginName, json.get( "loginName" ).getAsString(),
                "Response body must echo back the loginName" );

        // Verify the account is gone (admin check).
        assertUserGone( adminClient, loginName );

        // Verify the session is invalidated — reusing userClient's cookie should now get 401.
        final HttpResponse< String > profileResp = get( userClient, "/api/auth/profile" );
        assertEquals( 401, profileResp.statusCode(),
                "Reusing the old session cookie after self-deletion should return 401; got "
                + profileResp.statusCode() + ": " + profileResp.body() );
    }

    /**
     * Mismatch: the {@code confirmLoginName} field does not match the session login.
     *
     * <p>Verifies:
     * <ol>
     *   <li>400 response — request rejected.</li>
     *   <li>The account is still present — no deletion happened.</li>
     * </ol>
     */
    @Test
    void mismatch_confirmLoginName_returns400_accountUntouched()
            throws IOException, InterruptedException {
        final String loginName = "self-del-mismatch-" + System.nanoTime();

        final HttpClient adminClient = newClient();
        login( adminClient, ADMIN_USER, ADMIN_PASSWORD );
        createUser( adminClient, loginName );

        final HttpClient userClient = newClient();
        login( userClient, loginName, TEST_PASSWORD );

        // Admin-created users are flagged password_must_change; complete the forced
        // change so the session is no longer blocked before testing the 400 path.
        final String changeBody = GSON.toJson( Map.of(
                "currentPassword", TEST_PASSWORD,
                "newPassword", CHANGED_PASSWORD ) );
        final HttpResponse< String > changeResp =
                put( userClient, "/api/auth/profile", changeBody );
        assertEquals( 200, changeResp.statusCode(),
                "Forced password change should return 200: " + changeResp.body() );

        try {
            // Send a wrong confirmLoginName.
            final String deleteBody = GSON.toJson( Map.of( "confirmLoginName", "definitely-not-me" ) );
            final HttpResponse< String > resp =
                    delete( userClient, "/api/auth/profile", deleteBody );
            assertEquals( 400, resp.statusCode(),
                    "Mismatched confirmLoginName should return 400: " + resp.body() );

            // Account must still exist.
            assertUserExists( adminClient, loginName );
        } finally {
            deleteUserBestEffort( loginName );
        }
    }

    /**
     * Unauthenticated: no session cookie — should return 401 immediately.
     * The body's {@code confirmLoginName} is irrelevant.
     */
    @Test
    void unauthenticated_returns401() throws IOException, InterruptedException {
        // Fresh client with empty cookie jar — no login performed.
        final HttpClient anonClient = newClient();
        final String deleteBody = GSON.toJson( Map.of( "confirmLoginName", "whoever" ) );
        final HttpResponse< String > resp =
                delete( anonClient, "/api/auth/profile", deleteBody );
        assertEquals( 401, resp.statusCode(),
                "Unauthenticated DELETE /api/auth/profile should return 401: " + resp.body() );
    }

    /**
     * Admin caller blocked: the {@code janne} account holds the Admin role.
     * Self-deletion should be rejected with 409 Conflict.
     *
     * <p>Verifies:
     * <ol>
     *   <li>409 response — request rejected.</li>
     *   <li>The admin account is still present.</li>
     * </ol>
     */
    @Test
    void adminCaller_returns409_accountUntouched() throws IOException, InterruptedException {
        // Log in as the IT admin user who holds the Admin role.
        final HttpClient adminClient = newClient();
        login( adminClient, ADMIN_USER, ADMIN_PASSWORD );

        final String deleteBody = GSON.toJson( Map.of( "confirmLoginName", ADMIN_USER ) );
        final HttpResponse< String > resp =
                delete( adminClient, "/api/auth/profile", deleteBody );
        assertEquals( 409, resp.statusCode(),
                "Admin self-delete should return 409 Conflict: " + resp.body() );

        // Verify the admin account is still present via a fresh admin session.
        final HttpClient verifyClient = newClient();
        login( verifyClient, ADMIN_USER, ADMIN_PASSWORD );
        assertUserExists( verifyClient, ADMIN_USER );
    }
}
