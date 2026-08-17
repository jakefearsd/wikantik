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

import org.junit.jupiter.api.AfterAll;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test proving the new scoped {@code admin:&lt;area&gt;} permission
 * ({@link com.wikantik.auth.permissions.AdminPermission}) actually restricts what it should.
 *
 * <p><strong>What's under test.</strong> {@code /admin/*} used to be all-or-nothing behind
 * {@link com.wikantik.auth.permissions.AllPermission}. {@link com.wikantik.rest.AdminAuthFilter}
 * now derives the area from the first path segment after {@code /admin/} and, before falling back
 * to the audited {@code AllPermission} check, asks the silent {@code isPermitted} twin whether the
 * caller holds a scoped {@code admin:&lt;area&gt;} grant for exactly that area.
 *
 * <p><strong>Why the matrix, not just the happy path.</strong> A single scoped account returning
 * 200 on its own area proves nothing on its own -- it would also pass if the account were a full
 * admin. The 403s and, above all, the cross-checks (a grant for area A must not open area B) are
 * what actually prove the check discriminates by area. See {@code scopedUsersGrant_allowsOwnArea_deniesOthers}
 * below -- it is the single most important assertion in this file.
 *
 * <p><strong>Accounts.</strong> Three throwaway accounts are created via {@code POST /admin/users}
 * as the seeded admin, none of them added to the {@code Admin} group (that would defeat the whole
 * point):
 * <ul>
 *   <li>{@code it-scoped-insights} -- granted {@code admin:insights} / {@code access} only</li>
 *   <li>{@code it-scoped-users} -- granted {@code admin:users} / {@code access} only</li>
 *   <li>{@code it-no-grant} -- authenticated, holds no admin grant at all</li>
 * </ul>
 * plus the existing seeded admin ({@code janne}, same credential used by {@link AuditLogIT}),
 * unchanged, holding full {@code AllPermission}.
 *
 * <p><strong>Password-change gate workaround.</strong> {@code POST /admin/users} sets
 * {@code passwordMustChange=true} on every new profile (see {@link com.wikantik.rest.AdminUserResource}).
 * {@link com.wikantik.rest.MustChangePasswordFilter} gates <em>all</em> of {@code /admin/*} (and
 * every non-auth {@code /api/*} call) behind {@code 403 PASSWORD_CHANGE_REQUIRED} for a flagged
 * session -- exactly the endpoints this test needs to probe. Left alone, every assertion below
 * would 403 for the wrong reason and the matrix would be meaningless. So each new account clears
 * the flag itself, immediately after its first login, via the allowlisted self-service
 * {@code PUT /api/auth/profile} (the same mechanism {@link MustChangePasswordIT} exercises)
 * before this test touches a single {@code /admin/*} endpoint.
 *
 * <p><strong>{@code Accept} header.</strong> Every request in this file sends
 * {@code Accept: application/json}. {@code AdminAuthFilter.isSpaNavigation} lets a browser-style
 * {@code GET} with {@code Accept: text/html} through unauthenticated (so the SPA shell can render
 * the login screen) -- an HTML {@code Accept} header would make every assertion in the matrix pass
 * vacuously (200 for everyone, always), so it must never appear here.
 *
 * <p>Modeled closely on {@link AuditLogIT}: same {@link HttpClient} / secure-cookie-over-http setup,
 * same {@code /api/auth/login} + {@code /api/auth/logout} session handling, same shape of
 * get/post/put/delete helpers, and the same {@code POST /admin/policy} grant-creation +
 * {@code DELETE /admin/policy/{id}} cleanup pattern. The one structural difference: this test
 * juggles four concurrent logged-in sessions (admin + 3 accounts) rather than one, so the HTTP
 * helpers take an explicit {@link HttpClient} parameter instead of reading a single shared field.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class ScopedAdminGrantIT {

    /** Poll ceiling: the async audit writer drains every ~200 ms; 10 s is very generous. */
    private static final long POLL_TIMEOUT_MS = 10_000L;
    private static final long POLL_INTERVAL_MS = 300L;

    private static final Gson GSON = new Gson();

    private static String baseUrl;

    // ---- Accounts ----

    private static final String ADMIN_USER = "janne";
    private static final String ADMIN_PASS = "myP@5sw0rd";

    private static final String SCOPED_INSIGHTS_LOGIN = "it-scoped-insights";
    private static final String SCOPED_INSIGHTS_PASS  = "It-Sc0ped-Insights-83f1!";

    private static final String SCOPED_USERS_LOGIN = "it-scoped-users";
    private static final String SCOPED_USERS_PASS  = "It-Sc0ped-Users-71ab!";

    private static final String NO_GRANT_LOGIN = "it-no-grant";
    private static final String NO_GRANT_PASS  = "It-N0-Grant-Acct-52cd!";

    // One HttpClient == one cookie jar == one session, per account. All four are logged in for
    // the duration of the class so the matrix can freely interleave requests across accounts
    // without repeated login/logout churn.
    private static HttpClient adminClient;
    private static HttpClient scopedInsightsClient;
    private static HttpClient scopedUsersClient;
    private static HttpClient noGrantClient;

    private static int insightsGrantId = -1;
    private static int usersGrantId = -1;

    @BeforeAll
    static void setUp() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );

        adminClient = newClient();
        login( adminClient, ADMIN_USER, ADMIN_PASS );

        createUser( SCOPED_INSIGHTS_LOGIN, SCOPED_INSIGHTS_PASS, "Scoped Insights Test Account" );
        createUser( SCOPED_USERS_LOGIN, SCOPED_USERS_PASS, "Scoped Users Test Account" );
        createUser( NO_GRANT_LOGIN, NO_GRANT_PASS, "No-Grant Test Account" );

        scopedInsightsClient = newClient();
        login( scopedInsightsClient, SCOPED_INSIGHTS_LOGIN, SCOPED_INSIGHTS_PASS );
        clearMustChangePassword( scopedInsightsClient, SCOPED_INSIGHTS_PASS );

        scopedUsersClient = newClient();
        login( scopedUsersClient, SCOPED_USERS_LOGIN, SCOPED_USERS_PASS );
        clearMustChangePassword( scopedUsersClient, SCOPED_USERS_PASS );

        noGrantClient = newClient();
        login( noGrantClient, NO_GRANT_LOGIN, NO_GRANT_PASS );
        clearMustChangePassword( noGrantClient, NO_GRANT_PASS );

        // Grant exactly ONE admin area to each of the first two accounts. it-no-grant gets
        // nothing, and none of the three is ever added to the Admin group -- an AdminPermission
        // grant must be sufficient (and necessary) entirely on its own.
        insightsGrantId = createAdminAreaGrant( SCOPED_INSIGHTS_LOGIN, "insights" );
        usersGrantId    = createAdminAreaGrant( SCOPED_USERS_LOGIN, "users" );
    }

    @AfterAll
    static void tearDown() throws Exception {
        // Best-effort cleanup so re-runs are idempotent. Failures are logged, never swallowed.
        deleteGrantQuietly( insightsGrantId );
        deleteGrantQuietly( usersGrantId );
        deleteUserQuietly( SCOPED_INSIGHTS_LOGIN );
        deleteUserQuietly( SCOPED_USERS_LOGIN );
        deleteUserQuietly( NO_GRANT_LOGIN );
        logout( adminClient );
    }

    // -----------------------------------------------------------------------
    // Cookie-jar helper -- identical to AuditLogIT / MustChangePasswordIT.
    // The web.xml marks the session cookie Secure; Java's InMemoryCookieStore
    // filters Secure cookies on plain http:// requests, so we remap the lookup
    // URI to https:// while still sending the actual request over http.
    // -----------------------------------------------------------------------

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

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    // ---- HTTP helpers (mirror AuditLogIT's get/post/put/delete, parametrized by client
    //      since this test juggles four concurrent sessions instead of one) ----

    private static HttpResponse<String> get( final HttpClient client, final String path )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /**
     * GET with an explicit {@code X-Request-Id} so the resulting audit row (if any) can be
     * correlated back to exactly this request -- same trick as {@link AuditLogIT}'s
     * {@code accessDeniedCarriesTargetAndSourceIp} / {@code enforcedDenialDetailCarriesEnrichedFields}.
     */
    private static HttpResponse<String> getWithMarker( final HttpClient client, final String path,
            final String marker ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .header( "X-Request-Id", marker )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse<String> post( final HttpClient client, final String path, final String jsonBody )
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

    private static HttpResponse<String> put( final HttpClient client, final String path, final String jsonBody )
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

    private static HttpResponse<String> delete( final HttpClient client, final String path )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static void login( final HttpClient client, final String username, final String password )
            throws IOException, InterruptedException {
        final HttpResponse<String> resp = post( client, "/api/auth/login",
                GSON.toJson( Map.of( "username", username, "password", password ) ) );
        assertEquals( 200, resp.statusCode(), "Login as " + username + " should succeed: " + resp.body() );
    }

    private static void logout( final HttpClient client ) throws IOException, InterruptedException {
        post( client, "/api/auth/logout", "{}" );
    }

    // ---- Setup helpers ----

    private static void createUser( final String loginName, final String password, final String fullName )
            throws IOException, InterruptedException {
        final String body = GSON.toJson( Map.of(
                "loginName", loginName,
                "password", password,
                "fullName", fullName ) );
        final HttpResponse<String> resp = post( adminClient, "/admin/users", body );
        assertEquals( 201, resp.statusCode(), "Creating " + loginName + " should return 201: " + resp.body() );
    }

    /**
     * Clears {@code passwordMustChange} for the account owning {@code client}'s session, via the
     * self-service {@code PUT /api/auth/profile} endpoint -- allowlisted by
     * {@code MustChangePasswordFilter} even while the flag is set, since it's the only way an
     * otherwise-gated user can ever satisfy the gate. Reuses the same password as both current and
     * new: {@code PasswordValidator} does not forbid reuse, and it keeps the account's effective
     * credential the one already recorded in the constants above.
     */
    private static void clearMustChangePassword( final HttpClient client, final String password )
            throws IOException, InterruptedException {
        final HttpResponse<String> resp = put( client, "/api/auth/profile",
                GSON.toJson( Map.of( "currentPassword", password, "newPassword", password ) ) );
        assertEquals( 200, resp.statusCode(),
                "Clearing passwordMustChange via PUT /api/auth/profile should return 200: " + resp.body() );
    }

    /** Grants {@code admin:<area>} (action {@code access}) to a single {@code user} principal. Returns the grant id. */
    private static int createAdminAreaGrant( final String loginName, final String area )
            throws IOException, InterruptedException {
        final String body = GSON.toJson( Map.of(
                "principalType",  "user",
                "principalName",  loginName,
                "permissionType", "admin",
                "target",         area,
                "actions",        "access" ) );
        final HttpResponse<String> resp = post( adminClient, "/admin/policy", body );
        assertEquals( 201, resp.statusCode(),
                "Granting admin:" + area + " to " + loginName + " should return 201: " + resp.body() );
        final JsonObject created = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( created.has( "id" ), "Grant response should carry an id: " + resp.body() );
        return created.get( "id" ).getAsInt();
    }

    private static void deleteGrantQuietly( final int grantId ) {
        if ( grantId < 0 ) return;
        try {
            delete( adminClient, "/admin/policy/" + grantId );
        } catch ( final Exception e ) {
            System.err.println( "[ScopedAdminGrantIT] cleanup: failed to delete grant id=" + grantId
                    + ": " + e.getMessage() );
        }
    }

    private static void deleteUserQuietly( final String loginName ) {
        try {
            delete( adminClient, "/admin/users/" + loginName );
        } catch ( final Exception e ) {
            System.err.println( "[ScopedAdminGrantIT] cleanup: failed to delete user=" + loginName
                    + ": " + e.getMessage() );
        }
    }

    // ---- Audit-polling helpers (mirror AuditLogIT.pollForAccessDenied / assertNoEvent) ----

    private static JsonObject pollForAccessDenied( final String correlationId, final long timeoutMs )
            throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse<String> resp = get( adminClient, "/admin/audit?limit=1000" );
            assertEquals( 200, resp.statusCode(), "GET /admin/audit should return 200, got: " + resp.body() );
            final JsonArray rows = JsonParser.parseString( resp.body() ).getAsJsonArray();
            for ( final JsonElement el : rows ) {
                final JsonObject row = el.getAsJsonObject();
                final String type = row.has( "eventType" ) && !row.get( "eventType" ).isJsonNull()
                        ? row.get( "eventType" ).getAsString() : "";
                final String corr = row.has( "correlationId" ) && !row.get( "correlationId" ).isJsonNull()
                        ? row.get( "correlationId" ).getAsString() : "";
                if ( "access.denied".equals( type ) && correlationId.equals( corr ) ) return row;
            }
            Thread.sleep( POLL_INTERVAL_MS );
        }
        fail( "Timed out waiting for access.denied row with correlationId=" + correlationId );
        return null; // unreachable
    }

    /**
     * Asserts NO {@code access.denied} row with the given correlationId appears within the poll
     * window (waited out in full, to give the async audit writer ample time to flush anything it
     * might have recorded).
     */
    private static void assertNoAccessDenied( final String correlationId, final long waitMs )
            throws IOException, InterruptedException {
        Thread.sleep( waitMs );
        final HttpResponse<String> resp = get( adminClient, "/admin/audit?limit=1000" );
        assertEquals( 200, resp.statusCode(), "GET /admin/audit should return 200, got: " + resp.body() );
        final JsonArray rows = JsonParser.parseString( resp.body() ).getAsJsonArray();
        for ( final JsonElement el : rows ) {
            final JsonObject row = el.getAsJsonObject();
            final String type = row.has( "eventType" ) && !row.get( "eventType" ).isJsonNull()
                    ? row.get( "eventType" ).getAsString() : "";
            final String corr = row.has( "correlationId" ) && !row.get( "correlationId" ).isJsonNull()
                    ? row.get( "correlationId" ).getAsString() : "";
            if ( "access.denied".equals( type ) && correlationId.equals( corr ) ) {
                fail( "Unexpected access.denied audit row for correlationId=" + correlationId
                        + " -- the scoped isPermitted() check must be silent on an ALLOWED request. Row: " + row );
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tests: the assertion matrix
    //
    //                            | /admin/insights/acquisition | /admin/users | /admin/policy |
    //   seeded admin (AllPerm)   |             200              |     200      |      200       |
    //   it-scoped-insights       |             200              |     403      |      403       |
    //   it-scoped-users          |             403              |     200      |      403       |
    //   it-no-grant              |             403              |     403      |      403       |
    // -----------------------------------------------------------------------

    @Test
    @Order( 1 )
    void seededAdminReachesEveryArea() throws Exception {
        assertEquals( 200, get( adminClient, "/admin/insights/acquisition" ).statusCode(),
                "Full admin (AllPermission) must reach /admin/insights/acquisition" );
        assertEquals( 200, get( adminClient, "/admin/users" ).statusCode(),
                "Full admin (AllPermission) must reach /admin/users" );
        assertEquals( 200, get( adminClient, "/admin/policy" ).statusCode(),
                "Full admin (AllPermission) must reach /admin/policy" );
    }

    /**
     * Own-area 200 alone would also pass for a full admin -- it proves nothing about scoping.
     * The 403s on the OTHER two areas are the actual proof that the grant is scoped to just
     * {@code insights}.
     */
    @Test
    @Order( 2 )
    void scopedInsightsGrant_allowsOwnArea_deniesOthers() throws Exception {
        assertEquals( 200, get( scopedInsightsClient, "/admin/insights/acquisition" ).statusCode(),
                "it-scoped-insights holds admin:insights -- must reach its own area" );
        assertEquals( 403, get( scopedInsightsClient, "/admin/users" ).statusCode(),
                "it-scoped-insights must NOT reach /admin/users -- it holds no admin:users grant" );
        assertEquals( 403, get( scopedInsightsClient, "/admin/policy" ).statusCode(),
                "it-scoped-insights must NOT reach /admin/policy -- it holds no admin:policy grant" );
    }

    /**
     * THE load-bearing row of the matrix. it-scoped-users holds AN admin grant (just not for
     * insights) -- if AdminAuthFilter's scoped check meant "does this principal hold *any*
     * AdminPermission" rather than "does it hold AdminPermission for THIS area", this call would
     * wrongly return 200. The 403 here is the actual proof that holding an admin grant does not
     * confer any admin area.
     */
    @Test
    @Order( 3 )
    void scopedUsersGrant_allowsOwnArea_deniesOthers() throws Exception {
        assertEquals( 403, get( scopedUsersClient, "/admin/insights/acquisition" ).statusCode(),
                "it-scoped-users must NOT reach /admin/insights -- holding admin:users must not imply admin:insights" );
        assertEquals( 200, get( scopedUsersClient, "/admin/users" ).statusCode(),
                "it-scoped-users holds admin:users -- must reach its own area" );
        assertEquals( 403, get( scopedUsersClient, "/admin/policy" ).statusCode(),
                "it-scoped-users must NOT reach /admin/policy -- it holds no admin:policy grant" );
    }

    @Test
    @Order( 4 )
    void noGrant_deniedEverywhere() throws Exception {
        assertEquals( 403, get( noGrantClient, "/admin/insights/acquisition" ).statusCode(),
                "it-no-grant holds no admin grant at all -- must be denied" );
        assertEquals( 403, get( noGrantClient, "/admin/users" ).statusCode(),
                "it-no-grant holds no admin grant at all -- must be denied" );
        assertEquals( 403, get( noGrantClient, "/admin/policy" ).statusCode(),
                "it-no-grant holds no admin grant at all -- must be denied" );
    }

    /**
     * The scoped check ({@code AdminAuthFilter.hasAreaGrant}) deliberately uses the SILENT
     * {@code isPermitted()} twin, not the audited {@code checkPermission()}, precisely so an
     * ALLOWED request never writes a false {@code access.denied} row. If someone reorders the
     * filter back to trying the audited {@code AllPermission} check first, this test must fail:
     * every scoped-but-allowed request would then log a denial for the AllPermission probe before
     * ever reaching the (now-unreachable) scoped grant.
     */
    @Test
    @Order( 5 )
    void scopedGrantAllowedRequestProducesNoAccessDeniedAuditRow() throws Exception {
        final String marker = "scoped-admin-it-allow-" + System.nanoTime();
        final HttpResponse<String> resp = getWithMarker( scopedInsightsClient, "/admin/insights/acquisition", marker );
        assertEquals( 200, resp.statusCode(), "Scoped request to the account's own area should succeed: " + resp.body() );
        assertNoAccessDenied( marker, POLL_TIMEOUT_MS );
    }

    /** The fix must not have suppressed real denials -- a genuinely unauthorized call must still be audited. */
    @Test
    @Order( 6 )
    void deniedRequestStillProducesAccessDeniedAuditRow() throws Exception {
        final String marker = "scoped-admin-it-deny-" + System.nanoTime();
        final HttpResponse<String> resp = getWithMarker( noGrantClient, "/admin/users", marker );
        assertEquals( 403, resp.statusCode(), "Unauthorized request should be denied: " + resp.body() );
        final JsonObject row = pollForAccessDenied( marker, POLL_TIMEOUT_MS );
        assertNotNull( row, "Expected an access.denied audit row for correlationId=" + marker );
    }
}
