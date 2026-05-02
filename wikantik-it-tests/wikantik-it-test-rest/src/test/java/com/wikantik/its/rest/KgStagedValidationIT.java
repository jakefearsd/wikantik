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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke for the KG staged-validation feature against a
 * Cargo-deployed Tomcat. Exercises the full proposal-to-materialisation
 * lifecycle through the human-approval path and verifies the new
 * tier-aware snapshot reads + audit trail.
 *
 * <p>Does NOT exercise the judge LLM — no Ollama is available in CI.
 * The judge's downstream effect (materialisation at tier=machine) is
 * already unit-tested in JudgeRunnerTest; this IT validates the
 * REST/wire surface.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class KgStagedValidationIT {

    /** Source unique enough to avoid collision with seeded data. */
    private static final String IT_SRC = "ITStagedSrc-" + UUID.randomUUID();
    private static final String IT_TGT = "ITStagedTgt-" + UUID.randomUUID();
    private static final String IT_REL = "it_staged_rel";

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;
    private static String createdProposalId;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
            "http://localhost:8080/wikantik-it-test-rest" );
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

    /** Authenticates the shared {@link #client} cookie jar as the admin user. */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    // ---- Tests ----

    @Test
    @Order( 1 )
    void snapshot_default_returns_200() throws Exception {
        final HttpResponse< String > resp = get( "/api/knowledge/graph" );
        assertEquals( 200, resp.statusCode(), "snapshot must be public + 200; got: " + resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertTrue( body.has( "nodes" ), "snapshot must include nodes envelope" );
        assertTrue( body.has( "edges" ), "snapshot must include edges envelope" );
    }

    @Test
    @Order( 2 )
    void snapshot_min_tier_human_returns_200() throws Exception {
        final HttpResponse< String > resp = get( "/api/knowledge/graph?min_tier=human" );
        assertEquals( 200, resp.statusCode(), resp.body() );
    }

    @Test
    @Order( 3 )
    void snapshot_min_tier_machine_returns_200() throws Exception {
        final HttpResponse< String > resp = get( "/api/knowledge/graph?min_tier=machine" );
        assertEquals( 200, resp.statusCode(), resp.body() );
    }

    @Test
    @Order( 4 )
    void snapshot_invalid_min_tier_returns_400() throws Exception {
        final HttpResponse< String > resp = get( "/api/knowledge/graph?min_tier=garbage" );
        assertEquals( 400, resp.statusCode(), "expected 400 for invalid tier; got: " + resp.body() );
    }

    @Test
    @Order( 5 )
    void seed_proposal_via_admin_post() throws Exception {
        loginAsAdmin();
        final String body = GSON.toJson( Map.of(
            "proposal_type", "new-edge",
            "source_page", "ITStagedValidationPage",
            "proposed_data", Map.of(
                "source", IT_SRC,
                "target", IT_TGT,
                "relationship", IT_REL ),
            "confidence", 0.7,
            "reasoning", "IT seed" ) );
        final HttpResponse< String > resp = post( "/admin/knowledge-graph/proposals", body );
        assertEquals( 200, resp.statusCode(), "submit proposal: " + resp.body() );
        final JsonObject created = JsonParser.parseString( resp.body() ).getAsJsonObject();
        createdProposalId = created.get( "id" ).getAsString();
        assertNotNull( createdProposalId );
    }

    @Test
    @Order( 6 )
    void approve_proposal_materialises_at_human_tier() throws Exception {
        loginAsAdmin();
        assertNotNull( createdProposalId, "proposal must have been seeded by step 5" );
        final HttpResponse< String > resp = post(
            "/admin/knowledge-graph/proposals/" + createdProposalId + "/approve", "{}" );
        assertEquals( 200, resp.statusCode(), "approve: " + resp.body() );
        final JsonObject approved = JsonParser.parseString( resp.body() ).getAsJsonObject();
        assertEquals( "approved", approved.get( "status" ).getAsString() );
        // tier may or may not be in the response shape — this is informational, not asserted.
    }

    @Test
    @Order( 7 )
    void approved_node_visible_in_human_tier_snapshot() throws Exception {
        final HttpResponse< String > resp = get( "/api/knowledge/graph?min_tier=human" );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final JsonArray nodes = body.getAsJsonArray( "nodes" );
        assertNotNull( nodes );
        boolean foundSrc = false;
        for ( int i = 0; i < nodes.size(); i++ ) {
            final JsonObject n = nodes.get( i ).getAsJsonObject();
            if ( IT_SRC.equals( n.get( "name" ).getAsString() ) ) { foundSrc = true; break; }
        }
        assertTrue( foundSrc, "human-tier snapshot must include the approved node" );
    }

    @Test
    @Order( 8 )
    void reviews_endpoint_returns_audit_row() throws Exception {
        loginAsAdmin();
        assertNotNull( createdProposalId );
        final HttpResponse< String > resp = get(
            "/admin/knowledge-graph/proposals/" + createdProposalId + "/reviews" );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final JsonArray reviews = body.getAsJsonArray( "reviews" );
        assertNotNull( reviews, "reviews envelope must be present" );
        assertTrue( reviews.size() >= 1, "approve must have appended a human-review row" );
    }

    @Test
    @Order( 9 )
    void judge_run_endpoint_returns_202_or_503() throws Exception {
        loginAsAdmin();
        // Either 202 (judge enabled, runner registered) or 503 (judge disabled in IT config).
        // Both are valid IT outcomes — we just verify the route is wired and authenticated.
        final HttpResponse< String > resp = post( "/admin/knowledge-graph/judge/run", "{}" );
        assertTrue( resp.statusCode() == 202 || resp.statusCode() == 503,
            "expected 202 or 503; got " + resp.statusCode() + ": " + resp.body() );
    }
}
