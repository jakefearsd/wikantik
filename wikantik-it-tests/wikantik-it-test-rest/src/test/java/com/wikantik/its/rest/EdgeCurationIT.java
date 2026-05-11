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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke for the admin edge curation endpoints against a
 * Cargo-deployed Tomcat. Mirrors {@link KgStagedValidationIT}'s auth +
 * cookie-handler pattern and uses a closed-vocabulary relationship_type
 * ('related_to') that satisfies V027's CHECK constraint.
 *
 * <p>Tests order matters: seed → create → query (total) → audit → delete-
 * and-reject → bulk-delete 409 drift.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class EdgeCurationIT {

    private static final String IT_SUFFIX = UUID.randomUUID().toString().substring( 0, 8 );
    private static final String IT_SRC = "ITEdgeSrc-" + IT_SUFFIX;
    private static final String IT_TGT = "ITEdgeTgt-" + IT_SUFFIX;
    private static final String IT_REL = "related_to";

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;
    private static String sourceId;
    private static String targetId;
    private static String edgeId;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
            "http://localhost:18080/wikantik-it-test-rest" );
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

    // ---- Tests ----

    @Test
    @Order( 1 )
    void seed_two_nodes_via_admin_post() throws Exception {
        loginAsAdmin();
        for ( final String name : new String[]{ IT_SRC, IT_TGT } ) {
            final HttpResponse< String > resp = post( "/admin/knowledge-graph/nodes",
                GSON.toJson( Map.of( "name", name, "node_type", "concept" ) ) );
            assertEquals( 200, resp.statusCode(), "Seed node " + name + ": " + resp.body() );
            final JsonObject n = JsonParser.parseString( resp.body() ).getAsJsonObject();
            if ( IT_SRC.equals( name ) ) sourceId = n.get( "id" ).getAsString();
            else                          targetId = n.get( "id" ).getAsString();
        }
        assertNotNull( sourceId );
        assertNotNull( targetId );
    }

    @Test
    @Order( 2 )
    void create_edge_stamps_human_curated_and_human_tier() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = post( "/admin/knowledge-graph/edges",
            GSON.toJson( Map.of(
                "source_id", sourceId,
                "target_id", targetId,
                "relationship_type", IT_REL,
                "provenance", "ai-inferred" ) ) ); // request asks for ai-inferred — server must override
        assertEquals( 200, resp.statusCode(), "Create edge: " + resp.body() );
        final JsonObject e = JsonParser.parseString( resp.body() ).getAsJsonObject();
        edgeId = e.get( "id" ).getAsString();
        assertEquals( "human-curated", e.get( "provenance" ).getAsString(),
            "Server must stamp HUMAN_CURATED regardless of body" );
        assertEquals( "human", e.get( "tier" ).getAsString() );
    }

    @Test
    @Order( 3 )
    void query_edges_includes_total_field() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = get(
            "/admin/knowledge-graph/edges?relationship_type=" + IT_REL + "&search=" + IT_SRC );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "total" ), "response must include `total` field: " + resp.body() );
        // At least 1 — there may be other test runs interleaving but our search filter
        // is keyed on a UUID-suffixed source name, so it must be exactly 1.
        assertEquals( 1, body.get( "total" ).getAsInt(), "expected 1 row for our unique source" );
        assertTrue( body.has( "edges" ) );
    }

    @Test
    @Order( 4 )
    void edit_edge_via_re_upsert_writes_update_audit() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = post( "/admin/knowledge-graph/edges",
            GSON.toJson( Map.of(
                "source_id", sourceId,
                "target_id", targetId,
                "relationship_type", IT_REL,
                "properties", Map.of( "note", "edited" ) ) ) );
        assertEquals( 200, resp.statusCode(), resp.body() );
    }

    @Test
    @Order( 5 )
    void audit_endpoint_shows_create_and_update() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = get(
            "/admin/knowledge-graph/edges/" + edgeId + "/audit" );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final JsonArray rows = body.getAsJsonArray( "audit" );
        assertTrue( rows.size() >= 2, "expected CREATE + UPDATE rows: " + resp.body() );
        // Newest first
        assertEquals( "UPDATE", rows.get( 0 ).getAsJsonObject().get( "action" ).getAsString() );
    }

    @Test
    @Order( 6 )
    void delete_and_reject_returns_ok_and_audits_with_reason() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = post(
            "/admin/knowledge-graph/edges/" + edgeId + "/delete-and-reject",
            GSON.toJson( Map.of( "reason", "wrong direction" ) ) );
        assertEquals( 200, resp.statusCode(), "delete-and-reject: " + resp.body() );

        // Audit must show the reason
        final HttpResponse< String > auditR = get(
            "/admin/knowledge-graph/edges/" + edgeId + "/audit" );
        assertEquals( 200, auditR.statusCode() );
        assertTrue( auditR.body().contains( "wrong direction" ),
            "audit must record the rejection reason: " + auditR.body() );
    }

    @Test
    @Order( 7 )
    void bulk_delete_returns_409_on_count_mismatch() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = post(
            "/admin/knowledge-graph/edges/bulk-delete",
            GSON.toJson( Map.of(
                "relationship_type", IT_REL,
                "expected_count", 9999 ) ) );
        assertEquals( 409, resp.statusCode(), "expected 409 for count drift; got: " + resp.body() );
    }
}
