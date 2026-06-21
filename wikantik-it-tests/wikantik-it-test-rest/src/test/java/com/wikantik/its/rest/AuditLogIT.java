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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test for the tamper-evident audit log.
 *
 * <p>Verifies that the audit log captures the expected events — login,
 * page-delete, policy-grant change, and opted-in page-read — while NOT
 * capturing page reads for ordinary pages.  Also verifies the hash-chain
 * integrity check endpoint and (best-effort) the DB-level UPDATE revocation
 * against the app role.
 *
 * <p>Uses the same harness pattern as {@link KgPolicyAdminIT}: the
 * {@code it-wikantik.base.url} system property, a shared {@link HttpClient}
 * with the http→https cookie wrapper, and GSON for JSON parsing.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class AuditLogIT {

    /** Poll ceiling: the async writer drains every ~200 ms; 10 s is very generous. */
    private static final long POLL_TIMEOUT_MS = 10_000L;
    private static final long POLL_INTERVAL_MS = 300L;

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

    // Page names unique enough to avoid collisions with other IT runs.
    private static final String DELETE_PAGE  = "AuditLogITDeletePage";
    private static final String AUDITED_PAGE = "AuditLogITAuditedPage";
    private static final String PLAIN_PAGE   = "AuditLogITPlainPage";
    private static final String RENAME_PAGE  = "AuditLogITRenamePage";
    private static final String RENAME_PAGE2 = "AuditLogITRenamedPage";

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    /**
     * The web.xml sets {@code <secure>true</secure>} on the session cookie.
     * Java's {@link java.net.InMemoryCookieStore} filters Secure cookies on plain
     * {@code http://} requests.  This wrapper fools the store into treating every
     * URI as HTTPS so the JSESSIONID is always forwarded — identical to the
     * pattern in {@link KgPolicyAdminIT}.
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

    // ---- HTTP helpers (mirrors KgPolicyAdminIT) ----

    private HttpResponse<String> get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse<String> put( final String path, final String jsonBody )
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

    private HttpResponse<String> post( final String path, final String jsonBody )
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

    private HttpResponse<String> delete( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /** Authenticates the shared {@link #client} cookie jar as the admin user. */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse<String> resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    /** Clears the admin session so subsequent tests start anonymous. */
    private void logoutAdmin() throws IOException, InterruptedException {
        post( "/api/auth/logout", "{}" );
    }

    // ---- Audit polling helper ----

    /**
     * Polls {@code GET /admin/audit} until a row whose {@code eventType} equals
     * {@code expectedType} and whose {@code targetId} equals {@code expectedTarget}
     * (if non-null) appears, or the timeout elapses.
     *
     * @return the matching {@link JsonObject}, never null
     * @throws AssertionError if no matching row appears within {@link #POLL_TIMEOUT_MS}
     */
    private JsonObject pollForEvent( final String expectedType,
                                     final String expectedTarget,
                                     final long timeoutMs )
            throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse<String> resp = get( "/admin/audit?limit=1000" );
            assertEquals( 200, resp.statusCode(),
                    "GET /admin/audit should return 200, got: " + resp.body() );
            final JsonArray rows = JsonParser.parseString( resp.body() ).getAsJsonArray();
            for ( final JsonElement el : rows ) {
                final JsonObject row = el.getAsJsonObject();
                final String rowType = row.has( "eventType" ) && !row.get( "eventType" ).isJsonNull()
                        ? row.get( "eventType" ).getAsString() : "";
                if ( !expectedType.equals( rowType ) ) continue;
                if ( expectedTarget != null ) {
                    final String rowTarget = row.has( "targetId" ) && !row.get( "targetId" ).isJsonNull()
                            ? row.get( "targetId" ).getAsString() : "";
                    if ( !expectedTarget.equals( rowTarget ) ) continue;
                }
                return row;
            }
            Thread.sleep( POLL_INTERVAL_MS );
        }
        fail( "Timed out waiting for audit event eventType=" + expectedType
                + ( expectedTarget != null ? " targetId=" + expectedTarget : "" ) );
        return null; // unreachable
    }

    /** Polls {@code GET /admin/audit} for an access.denied row with the given correlationId. */
    private JsonObject pollForAccessDenied( final String correlationId, final long timeoutMs )
            throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse<String> resp = get( "/admin/audit?limit=1000" );
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
     * Verifies that NO row with the given {@code eventType} and {@code targetId}
     * exists in the audit log after waiting the full poll window (to give the
     * async writer ample time to flush anything it might record).
     */
    private void assertNoEvent( final String expectedType,
                                final String expectedTarget,
                                final long waitMs )
            throws IOException, InterruptedException {
        Thread.sleep( waitMs );
        final HttpResponse<String> resp = get( "/admin/audit?limit=1000" );
        assertEquals( 200, resp.statusCode(),
                "GET /admin/audit should return 200, got: " + resp.body() );
        final JsonArray rows = JsonParser.parseString( resp.body() ).getAsJsonArray();
        for ( final JsonElement el : rows ) {
            final JsonObject row = el.getAsJsonObject();
            final String rowType = row.has( "eventType" ) && !row.get( "eventType" ).isJsonNull()
                    ? row.get( "eventType" ).getAsString() : "";
            if ( !expectedType.equals( rowType ) ) continue;
            final String rowTarget = row.has( "targetId" ) && !row.get( "targetId" ).isJsonNull()
                    ? row.get( "targetId" ).getAsString() : "";
            if ( expectedTarget.equals( rowTarget ) ) {
                fail( "Unexpected audit event found: eventType=" + expectedType
                        + " targetId=" + expectedTarget + " row=" + row );
            }
        }
    }

    // ---- Tests ----

    /**
     * Step 1: Login itself generates a {@code login.ok} audit event.
     */
    @Test
    @Order( 1 )
    void login_produces_login_ok_event() throws Exception {
        try {
            loginAsAdmin();
            // The login event actor is "janne"; target may vary — just poll by type.
            final JsonObject row = pollForEvent( "login.ok", null, POLL_TIMEOUT_MS );
            assertNotNull( row, "Expected a login.ok audit row" );
            assertEquals( "login.ok", row.get( "eventType" ).getAsString() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 2: Create then delete a page; assert a {@code page.delete} event is recorded.
     */
    @Test
    @Order( 2 )
    void page_delete_produces_audit_event() throws Exception {
        try {
            loginAsAdmin();

            // Create the page first.
            final String createBody = GSON.toJson(
                    Map.of( "content", "Temporary page for audit delete test.",
                            "changeNote", "created by AuditLogIT" ) );
            final HttpResponse<String> putResp = put( "/api/pages/" + DELETE_PAGE, createBody );
            assertTrue( putResp.statusCode() == 200 || putResp.statusCode() == 201,
                    "Page create should succeed: " + putResp.body() );

            // Delete it.
            final HttpResponse<String> delResp = delete( "/api/pages/" + DELETE_PAGE );
            assertEquals( 200, delResp.statusCode(), "Page delete should succeed: " + delResp.body() );

            // Poll for the page.delete event targeting DELETE_PAGE.
            final JsonObject row = pollForEvent( "page.delete", DELETE_PAGE, POLL_TIMEOUT_MS );
            assertNotNull( row );
            assertEquals( "page.delete", row.get( "eventType" ).getAsString() );
            assertEquals( DELETE_PAGE, row.get( "targetId" ).getAsString() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 3: POST to {@code /admin/policy} to create a policy grant;
     * {@link com.wikantik.rest.AdminPolicyResource} explicitly records a
     * {@code policy.grant.update} event to the main audit log.
     * Clean up by deleting the grant afterwards.
     */
    @Test
    @Order( 3 )
    void policy_grant_update_produces_audit_event() throws Exception {
        try {
            loginAsAdmin();

            // Create a throw-away role grant. The "role" principal type and "wiki"
            // permission type (with "createPages" action) are always valid values.
            final String grantBody = GSON.toJson( Map.of(
                    "principalType",  "role",
                    "principalName",  "AuditLogITSmokeRole",
                    "permissionType", "wiki",
                    "target",         "*",
                    "actions",        "createPages" ) );
            final HttpResponse<String> resp = post( "/admin/policy", grantBody );
            // AdminPolicyResource returns 201 Created on success.
            assertEquals( 201, resp.statusCode(),
                    "POST /admin/policy should return 201: " + resp.body() );
            final JsonObject created = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( created.has( "id" ), "Response should contain grant id: " + resp.body() );
            final int grantId = created.get( "id" ).getAsInt();

            // Clean up: delete the grant to avoid polluting other ITs.
            delete( "/admin/policy/" + grantId );

            // Poll for policy.grant.update in the main audit log.
            final long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
            JsonObject found = null;
            outer:
            while ( System.currentTimeMillis() < deadline ) {
                final HttpResponse<String> auditResp = get( "/admin/audit?limit=1000" );
                assertEquals( 200, auditResp.statusCode() );
                final JsonArray rows = JsonParser.parseString( auditResp.body() ).getAsJsonArray();
                for ( final JsonElement el : rows ) {
                    final JsonObject row = el.getAsJsonObject();
                    final String et = row.has( "eventType" ) && !row.get( "eventType" ).isJsonNull()
                            ? row.get( "eventType" ).getAsString() : "";
                    if ( et.startsWith( "policy.grant." ) ) {
                        found = row;
                        break outer;
                    }
                }
                Thread.sleep( POLL_INTERVAL_MS );
            }
            assertNotNull( found,
                    "Expected a policy.grant.* audit event but none appeared within "
                            + POLL_TIMEOUT_MS + " ms" );
            assertTrue( found.get( "eventType" ).getAsString().startsWith( "policy.grant." ),
                    "eventType should start with 'policy.grant.': " + found );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 4: Create a page with {@code audit_reads: true} in its frontmatter,
     * read it via the REST API, then assert a {@code page.read} event is recorded.
     */
    @Test
    @Order( 4 )
    void opted_in_page_read_produces_audit_event() throws Exception {
        try {
            loginAsAdmin();

            // Frontmatter opts the page into read auditing.
            final String body = "---\naudit_reads: true\n---\nContent for the audited page.";
            final String createBody = GSON.toJson(
                    Map.of( "content", body, "changeNote", "created by AuditLogIT" ) );
            final HttpResponse<String> putResp = put( "/api/pages/" + AUDITED_PAGE, createBody );
            assertTrue( putResp.statusCode() == 200 || putResp.statusCode() == 201,
                    "Audited page create should succeed: " + putResp.body() );

            // Read the page — this should trigger the page.read audit event.
            final HttpResponse<String> getResp = get( "/api/pages/" + AUDITED_PAGE );
            assertEquals( 200, getResp.statusCode(),
                    "GET audited page should return 200: " + getResp.body() );

            // Poll for the page.read event targeting AUDITED_PAGE.
            final JsonObject row = pollForEvent( "page.read", AUDITED_PAGE, POLL_TIMEOUT_MS );
            assertNotNull( row );
            assertEquals( "page.read", row.get( "eventType" ).getAsString() );
            assertEquals( AUDITED_PAGE, row.get( "targetId" ).getAsString() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 5: Create an ordinary page (no audit_reads frontmatter), read it,
     * and assert NO {@code page.read} event is recorded for it.
     */
    @Test
    @Order( 5 )
    void ordinary_page_read_does_not_produce_audit_event() throws Exception {
        try {
            loginAsAdmin();

            final String createBody = GSON.toJson(
                    Map.of( "content", "Ordinary page — no audit_reads frontmatter.",
                            "changeNote", "created by AuditLogIT" ) );
            final HttpResponse<String> putResp = put( "/api/pages/" + PLAIN_PAGE, createBody );
            assertTrue( putResp.statusCode() == 200 || putResp.statusCode() == 201,
                    "Plain page create should succeed: " + putResp.body() );

            // Read the page.
            final HttpResponse<String> getResp = get( "/api/pages/" + PLAIN_PAGE );
            assertEquals( 200, getResp.statusCode(),
                    "GET plain page should return 200: " + getResp.body() );

            // Wait the full poll window, then assert no page.read row appeared for this page.
            assertNoEvent( "page.read", PLAIN_PAGE, POLL_TIMEOUT_MS );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 6: Create a page and rename it via the REST API.  A rename emits a
     * {@code page.rename} audit event whose {@code detail} field carries a JSON
     * string ({@code {"from":...,"to":...}}).  This step exists specifically to put
     * a detail-bearing row into the chain BEFORE the hash-chain integrity check
     * (Step 8), so that {@code audit_hash_chain_is_intact} proves the JSONB→TEXT
     * fix (V037) works: prior to the fix PostgreSQL would reformat the stored JSON
     * relative to the hashed value, breaking chain verification for any detail row.
     */
    @Test
    @Order( 6 )
    void page_rename_produces_detail_bearing_audit_event() throws Exception {
        try {
            loginAsAdmin();

            // Create the page to rename.
            final String createBody = GSON.toJson(
                    Map.of( "content", "Page created for rename audit test.",
                            "changeNote", "created by AuditLogIT rename step" ) );
            final HttpResponse<String> putResp = put( "/api/pages/" + RENAME_PAGE, createBody );
            assertTrue( putResp.statusCode() == 200 || putResp.statusCode() == 201,
                    "Rename-source page create should succeed: " + putResp.body() );

            // Rename it via POST /api/pages/{name}/rename — fires a page.rename
            // event with detail={"from":...,"to":...}.
            final String renameBody = GSON.toJson(
                    Map.of( "newName", RENAME_PAGE2, "changeReferrers", false ) );
            final HttpResponse<String> renameResp = post(
                    "/api/pages/" + RENAME_PAGE + "/rename", renameBody );
            assertEquals( 200, renameResp.statusCode(),
                    "Page rename should succeed: " + renameResp.body() );

            // Poll for the page.rename event — the targetId is set to the NEW name.
            // Confirms the detail-bearing event landed in the chain.
            final JsonObject row = pollForEvent( "page.rename", RENAME_PAGE2, POLL_TIMEOUT_MS );
            assertNotNull( row );
            assertEquals( "page.rename", row.get( "eventType" ).getAsString() );
            // detail must be present and non-null (it carries {"from":...,"to":...}).
            assertTrue( row.has( "detail" ) && !row.get( "detail" ).isJsonNull(),
                    "page.rename event must carry a non-null detail field: " + row );

            // Clean up the renamed page so it does not interfere with other tests.
            delete( "/api/pages/" + RENAME_PAGE2 );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 7: Consolidated assertion — poll the audit log and verify all expected
     * event types (from the earlier steps) are present, and that no page.read
     * event exists for the ordinary page.
     */
    @Test
    @Order( 7 )
    void audit_log_contains_all_expected_events() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse<String> resp = get( "/admin/audit?limit=1000" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonArray rows = JsonParser.parseString( resp.body() ).getAsJsonArray();
            assertTrue( rows.size() > 0, "Audit log should be non-empty" );

            boolean hasLoginOk       = false;
            boolean hasPageDelete    = false;
            boolean hasPolicyGrant   = false;
            boolean hasAuditedRead   = false;
            boolean foundPlainRead   = false;

            for ( final JsonElement el : rows ) {
                final JsonObject row = el.getAsJsonObject();
                final String et = row.has( "eventType" ) && !row.get( "eventType" ).isJsonNull()
                        ? row.get( "eventType" ).getAsString() : "";
                final String tid = row.has( "targetId" ) && !row.get( "targetId" ).isJsonNull()
                        ? row.get( "targetId" ).getAsString() : "";

                if ( "login.ok".equals( et ) )                         hasLoginOk     = true;
                if ( "page.delete".equals( et ) && DELETE_PAGE.equals( tid ) )  hasPageDelete  = true;
                if ( et.startsWith( "policy.grant." ) )                hasPolicyGrant = true;
                if ( "page.read".equals( et ) && AUDITED_PAGE.equals( tid ) )   hasAuditedRead = true;
                if ( "page.read".equals( et ) && PLAIN_PAGE.equals( tid ) )     foundPlainRead = true;
            }

            assertTrue( hasLoginOk,    "Expected login.ok event in audit log" );
            assertTrue( hasPageDelete,  "Expected page.delete event for " + DELETE_PAGE );
            assertTrue( hasPolicyGrant, "Expected policy.grant.* event in audit log" );
            assertTrue( hasAuditedRead, "Expected page.read event for opted-in page " + AUDITED_PAGE );
            assertFalse( foundPlainRead, "Must NOT have page.read event for plain page " + PLAIN_PAGE );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 8: {@code GET /admin/audit/verify} must return {@code {"ok":true}}.
     * At this point the chain contains at least one detail-bearing row (the
     * page.rename event from Step 6), so this assertion proves the JSONB→TEXT fix
     * keeps detail rows hashing correctly end-to-end.
     */
    @Test
    @Order( 8 )
    void audit_hash_chain_is_intact() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse<String> resp = get( "/admin/audit/verify" );
            assertEquals( 200, resp.statusCode(),
                    "GET /admin/audit/verify should return 200: " + resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "ok" ), "Response must have 'ok' field: " + resp.body() );
            assertTrue( body.get( "ok" ).getAsBoolean(),
                    "Hash chain verification must pass: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 9: Prove the V036 locked grant. The IT PostgreSQL superuser
     * (Docker {@code POSTGRES_USER=jspwiki}) bypasses privilege checks, so we
     * cannot demonstrate {@code REVOKE} against it directly. Instead we use the
     * superuser connection to create a dedicated {@code NOSUPERUSER} role with the
     * SAME grants V036 applies to the app role, then connect AS that role and
     * assert that {@code SELECT} works but {@code UPDATE}/{@code DELETE} on
     * {@code audit_log} are denied — exactly as production (non-superuser app
     * role) enforces it.
     */
    @Test
    @Order( 9 )
    void non_superuser_role_cannot_mutate_audit_log() throws Exception {
        final String suUser   = System.getProperty( "it.db.user" );
        final String suPass   = System.getProperty( "it.db.password" );
        final String port     = System.getProperty( "it.db.port", "55432" );
        final String dbName   = System.getProperty( "it.db.name", "wikantik" );

        if ( suUser == null || suUser.isBlank() || suPass == null || suPass.isBlank() ) {
            fail( "it.db.user / it.db.password system properties not set — cannot run "
                    + "the audit_log immutability proof (they are set by the IT failsafe run)" );
        }

        final String jdbcUrl = "jdbc:postgresql://localhost:" + port + "/" + dbName;
        final String roRole = "audit_ro";
        final String roPass = "audit_ro";

        // 1. Setup as superuser: create the NOSUPERUSER role + V036 grants (idempotent).
        try ( Connection su = DriverManager.getConnection( jdbcUrl, suUser, suPass );
              Statement st = su.createStatement() ) {
            st.execute( "DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + roRole + "') THEN "
                    + "CREATE ROLE " + roRole + " LOGIN PASSWORD '" + roPass + "' NOSUPERUSER; "
                    + "END IF; END $$;" );
            st.execute( "GRANT CONNECT ON DATABASE \"" + dbName + "\" TO " + roRole );
            st.execute( "GRANT USAGE ON SCHEMA public TO " + roRole );
            // Exactly the V036 grant statements:
            st.execute( "GRANT  SELECT, INSERT ON audit_log TO " + roRole );
            st.execute( "REVOKE UPDATE, DELETE ON audit_log FROM " + roRole );
        }

        // 2. Assert as the NOSUPERUSER role.
        try ( Connection ro = DriverManager.getConnection( jdbcUrl, roRole, roPass ) ) {
            // SELECT works (proves the role can read — denial below is write-specific).
            try ( Statement s = ro.createStatement();
                  ResultSet rs = s.executeQuery( "SELECT count(*) FROM audit_log" ) ) {
                assertTrue( rs.next(), "SELECT count(*) should return a row" );
            }
            // UPDATE denied.
            final SQLException up = assertThrows( SQLException.class, () -> {
                try ( Statement s = ro.createStatement() ) {
                    s.execute( "UPDATE audit_log SET event_type='x' WHERE seq < 0" );
                }
            }, "UPDATE on audit_log must be denied for the NOSUPERUSER role" );
            assertPermissionDenied( up );
            // DELETE denied.
            final SQLException del = assertThrows( SQLException.class, () -> {
                try ( Statement s = ro.createStatement() ) {
                    s.execute( "DELETE FROM audit_log WHERE seq < 0" );
                }
            }, "DELETE on audit_log must be denied for the NOSUPERUSER role" );
            assertPermissionDenied( del );
            System.out.println( "[AuditLogIT] DB-immutability check PASSED: SELECT allowed, "
                    + "UPDATE + DELETE denied for NOSUPERUSER role '" + roRole + "'" );
        } finally {
            // 3. Best-effort teardown so a persistent DB stays clean across re-runs.
            try ( Connection su = DriverManager.getConnection( jdbcUrl, suUser, suPass );
                  Statement st = su.createStatement() ) {
                st.execute( "DROP OWNED BY " + roRole );
                st.execute( "DROP ROLE IF EXISTS " + roRole );
            } catch ( final SQLException e ) {
                System.out.println( "[AuditLogIT] teardown of role '" + roRole
                        + "' failed (non-fatal): " + e.getMessage() );
            }
        }
    }

    @Test
    @Order( 10 )
    void accessDeniedCarriesTargetAndSourceIp() throws IOException, InterruptedException {
        logoutAdmin(); // ensure the next request is anonymous

        final String marker = "audit-it-deny-" + System.nanoTime();
        final HttpResponse<String> denied = client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/admin/users" ) )
                        .header( "Accept", "application/json" )
                        .header( "X-Request-Id", marker )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, denied.statusCode(),
                "anonymous /admin/users should be forbidden: " + denied.body() );

        loginAsAdmin();
        final JsonObject row = pollForAccessDenied( marker, POLL_TIMEOUT_MS );

        assertEquals( "all", row.get( "targetType" ).getAsString(),
                "AllPermission denial should map targetType=all" );
        final String sourceIp = row.has( "sourceIp" ) && !row.get( "sourceIp" ).isJsonNull()
                ? row.get( "sourceIp" ).getAsString() : null;
        assertNotNull( sourceIp, "access.denied should carry sourceIp" );
        assertFalse( sourceIp.isBlank(), "sourceIp should be non-blank" );
    }

    /** Asserts a SQLException is a PostgreSQL insufficient-privilege error. */
    private static void assertPermissionDenied( final SQLException e ) {
        final String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        // SQLState 42501 = insufficient_privilege.
        assertTrue( msg.contains( "permission denied" ) || msg.contains( "42501" )
                        || "42501".equals( e.getSQLState() ),
                "Expected an insufficient-privilege SQL error, got: " + e.getMessage()
                        + " (SQLState=" + e.getSQLState() + ")" );
    }
}
