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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for the KG inclusion/exclusion policy admin REST endpoints.
 *
 * <p>Exercises the policy plumbing end-to-end against a Cargo-deployed Tomcat:
 * list clusters, set/get/update policy rows, inspect the audit trail,
 * explain a system page, check reconciliation status, delete a policy row,
 * and mark a cluster as reviewed.</p>
 *
 * <p>Uses the same cookie-based admin session pattern as {@link RestApiIT}.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class KgPolicyAdminIT {

    /** Cluster name unique enough to avoid collisions with any real clusters. */
    private static final String SMOKE_CLUSTER = "it-smoke-cluster";

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

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
     * {@code http://} requests. This wrapper fools the store into treating every
     * URI as HTTPS so the JSESSIONID is always forwarded.
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

    // ---- HTTP helpers ----

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String jsonBody )
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

    private HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
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
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    /** Clears the admin session so subsequent tests start anonymous. */
    private void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    // ---- Tests ----

    /**
     * GET /admin/kg-policy/clusters — verifies the resource is mounted,
     * KgInclusionPolicy is wired, and the response has a {@code clusters} envelope key.
     */
    @Test
    @Order( 1 )
    void list_clusters_returns_json_envelope() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/kg-policy/clusters" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "clusters" ), "missing 'clusters' key: " + resp.body() );
            assertTrue( body.get( "clusters" ).isJsonArray(), "'clusters' should be an array" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * PUT /admin/kg-policy/clusters/it-smoke-cluster — write path works,
     * returns the action echoed in the response.
     */
    @Test
    @Order( 2 )
    void put_cluster_creates_policy_row() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = put(
                    "/admin/kg-policy/clusters/" + SMOKE_CLUSTER,
                    "{\"action\":\"include\",\"reason\":\"smoke\"}" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "include", body.get( "action" ).getAsString(),
                    "action in response should be 'include'" );
            assertEquals( SMOKE_CLUSTER, body.get( "cluster" ).getAsString() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * GET /admin/kg-policy/clusters/it-smoke-cluster — confirms the policy was
     * persisted and at least one audit row is present.
     */
    @Test
    @Order( 3 )
    void get_cluster_returns_persisted_policy_with_audit() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/kg-policy/clusters/" + SMOKE_CLUSTER );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "include", body.get( "action" ).getAsString() );
            assertTrue( body.has( "audit" ), "missing 'audit' key" );
            final JsonArray audit = body.getAsJsonArray( "audit" );
            assertTrue( audit.size() >= 1,
                    "expected at least 1 audit entry, got " + audit.size() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * PUT again with action=exclude, then GET /admin/kg-policy/audit?cluster=...
     * — confirms the audit log shows at least 2 rows.
     */
    @Test
    @Order( 4 )
    void put_cluster_again_appends_audit() throws Exception {
        try {
            loginAsAdmin();

            // Flip to exclude
            final HttpResponse< String > flip = put(
                    "/admin/kg-policy/clusters/" + SMOKE_CLUSTER,
                    "{\"action\":\"exclude\",\"reason\":\"smoke flip\"}" );
            assertEquals( 200, flip.statusCode(), flip.body() );

            // Confirm via standalone audit endpoint
            final HttpResponse< String > auditResp = get(
                    "/admin/kg-policy/audit?cluster=" + SMOKE_CLUSTER + "&limit=10" );
            assertEquals( 200, auditResp.statusCode(), auditResp.body() );
            final JsonObject auditBody = JsonParser.parseString( auditResp.body() ).getAsJsonObject();
            assertTrue( auditBody.has( "audit" ), "missing 'audit' key" );
            final JsonArray rows = auditBody.getAsJsonArray( "audit" );
            assertTrue( rows.size() >= 2,
                    "expected at least 2 audit rows after two PUTs, got " + rows.size() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * GET /admin/kg-policy/explain/Main — confirms the explain endpoint returns 200
     * with the expected fields when the Main page is present.  Main is always a
     * system page, so if the response is 200 it must carry {@code system_page:true}
     * or {@code effective_action:"exclude"}.  A 404 is also acceptable when the IT
     * harness hasn't populated the structural index for Main.
     */
    @Test
    @Order( 5 )
    void explain_system_page_returns_expected_shape() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/kg-policy/explain/Main" );
            if ( resp.statusCode() == 200 ) {
                final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
                assertTrue( body.has( "effective_action" ),
                        "missing 'effective_action': " + resp.body() );
                assertTrue( body.has( "system_page" ),
                        "missing 'system_page': " + resp.body() );
                // Main is a system page; either flag is set or action is exclude.
                assertTrue(
                        body.get( "system_page" ).getAsBoolean()
                        || "exclude".equals( body.get( "effective_action" ).getAsString() ),
                        "expected system_page=true OR effective_action=exclude: " + resp.body() );
            } else {
                assertEquals( 404, resp.statusCode(),
                        "explain/Main: unexpected status " + resp.statusCode() + " — " + resp.body() );
            }
        } finally {
            logoutAdmin();
        }
    }

    /**
     * GET /admin/kg-policy/reconciliation — confirms the endpoint returns 200
     * with a {@code reconciliation} envelope key (array, may be empty).
     */
    @Test
    @Order( 6 )
    void reconciliation_endpoint_returns_array() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = get( "/admin/kg-policy/reconciliation" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "reconciliation" ), "missing 'reconciliation' key" );
            assertTrue( body.get( "reconciliation" ).isJsonArray(),
                    "'reconciliation' should be an array" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * POST /admin/kg-policy/clusters/it-smoke-cluster/review — confirms the
     * mark-reviewed path returns 200.  The cluster exists (set in Order 4) so
     * this exercises the normal write path.
     */
    @Test
    @Order( 7 )
    void mark_reviewed_returns_ok() throws Exception {
        try {
            loginAsAdmin();
            final HttpResponse< String > resp = post(
                    "/admin/kg-policy/clusters/" + SMOKE_CLUSTER + "/review", "{}" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.get( "reviewed" ).getAsBoolean(),
                    "expected reviewed=true: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * DELETE /admin/kg-policy/clusters/it-smoke-cluster — confirms the clear path
     * works and the subsequent GET shows no action row.
     */
    @Test
    @Order( 8 )
    void delete_cluster_removes_policy() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > delResp = delete( "/admin/kg-policy/clusters/" + SMOKE_CLUSTER );
            assertEquals( 200, delResp.statusCode(), delResp.body() );
            final JsonObject delBody = JsonParser.parseString( delResp.body() ).getAsJsonObject();
            assertTrue( delBody.get( "cleared" ).getAsBoolean(),
                    "expected cleared=true: " + delResp.body() );

            // After deletion the GET should still return 200 but with a null action.
            final HttpResponse< String > getResp = get( "/admin/kg-policy/clusters/" + SMOKE_CLUSTER );
            assertEquals( 200, getResp.statusCode(), getResp.body() );
            final JsonObject getBody = JsonParser.parseString( getResp.body() ).getAsJsonObject();
            assertNotNull( getBody, "GET after delete should parse as JSON" );
            // action should be absent or null — either is acceptable
            final boolean actionIsNull = !getBody.has( "action" )
                    || getBody.get( "action" ).isJsonNull()
                    || getBody.get( "action" ).getAsString().isEmpty();
            assertTrue( actionIsNull,
                    "action should be null/absent after deletion: " + getResp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Unauthorized access to admin endpoints must return 403.
     * Verifies the {@code AdminAuthFilter} rejects unauthenticated callers.
     */
    @Test
    @Order( 9 )
    void unauthenticated_request_is_rejected() throws Exception {
        // Use a fresh client with its own isolated cookie jar (no admin session).
        final HttpClient anonClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        final HttpRequest req = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/admin/kg-policy/clusters" ) )
                .header( "Accept", "application/json" )
                .GET()
                .build();
        final HttpResponse< String > resp = anonClient.send( req, HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, resp.statusCode(),
                "Unauthenticated access to /admin/* should be 403, got: " + resp.body() );
    }
}
