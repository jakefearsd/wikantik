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
 * Integration tests for {@code POST /admin/knowledge-graph/proposals/bulk-action}
 * against a Cargo-deployed Tomcat instance.
 *
 * <p>Creates throwaway KG proposals via the submit endpoint, then exercises
 * the bulk approve and reject actions, verifying the standard response envelope
 * shape and partial-failure semantics.
 *
 * <p>Note: the judge action is not exercised in IT because it requires a live
 * Ollama instance and is covered by unit tests in
 * {@code AdminKnowledgeResourceBulkTest}.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class AdminKnowledgeBulkActionIT {

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

    /** ID of proposal created in @Order(1), consumed in approve test. */
    private static String proposalIdA;
    /** ID of a second proposal, consumed in reject test. */
    private static String proposalIdB;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    // ---- helpers ----

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

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    /** Creates a throwaway KG proposal and returns its UUID string. */
    private String createProposal( final String name ) throws IOException, InterruptedException {
        final JsonObject body = new JsonObject();
        body.addProperty( "proposal_type", "new-node" );
        body.addProperty( "source_page", "BulkItTest.md" );
        body.addProperty( "confidence", 0.8 );
        body.addProperty( "reasoning", "IT bulk test" );
        final JsonObject proposedData = new JsonObject();
        proposedData.addProperty( "name", name );
        body.add( "proposed_data", proposedData );

        final HttpResponse< String > resp = post(
                "/admin/knowledge-graph/proposals", body.toString() );
        assertEquals( 200, resp.statusCode(),
                "Proposal creation should return 200: " + resp.body() );
        final JsonObject obj = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final String id = obj.get( "id" ).getAsString();
        assertNotNull( id, "Proposal id must not be null" );
        return id;
    }

    // ---- tests ----

    /**
     * Creates two proposals that will be consumed by subsequent bulk tests.
     */
    @Test
    @Order( 1 )
    void createProposalsForBulkTests() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            proposalIdA = createProposal( "BulkItNodeA-" + System.currentTimeMillis() );
            proposalIdB = createProposal( "BulkItNodeB-" + System.currentTimeMillis() );
            assertNotNull( proposalIdA, "proposalIdA must be set" );
            assertNotNull( proposalIdB, "proposalIdB must be set" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Happy path: bulk-approve a single valid proposal.
     * Expects 200 with succeeded=[id] and failed=[].
     */
    @Test
    @Order( 2 )
    void bulkApproveHappyPath() throws IOException, InterruptedException {
        assertNotNull( proposalIdA, "proposalIdA from @Order(1) must not be null" );
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "approve" );
            final JsonArray ids = new JsonArray();
            ids.add( proposalIdA );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post(
                    "/admin/knowledge-graph/proposals/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action approve should return 200: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            assertEquals( 1, succeeded.size(), "One proposal should have succeeded" );
            assertEquals( proposalIdA, succeeded.get( 0 ).getAsString() );

            final JsonArray failed = result.getAsJsonArray( "failed" );
            assertEquals( 0, failed.size(), "No failures expected" );

            assertTrue( result.get( "message" ).getAsString().contains( "1 of 1" ),
                    "Message should reflect 1 of 1" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Happy path: bulk-reject a single valid proposal with a reason.
     * Expects 200 with succeeded=[id] and failed=[].
     */
    @Test
    @Order( 3 )
    void bulkRejectHappyPath() throws IOException, InterruptedException {
        assertNotNull( proposalIdB, "proposalIdB from @Order(1) must not be null" );
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "reject" );
            body.addProperty( "reason", "IT test: low confidence" );
            final JsonArray ids = new JsonArray();
            ids.add( proposalIdB );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post(
                    "/admin/knowledge-graph/proposals/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action reject should return 200: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            assertEquals( 1, succeeded.size(), "One proposal should have succeeded" );
            assertEquals( proposalIdB, succeeded.get( 0 ).getAsString() );

            assertEquals( 0, result.getAsJsonArray( "failed" ).size() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Partial failure: bulk-approve a non-existent UUID alongside a newly created proposal.
     * The non-existent UUID ends up in failed[], the real proposal in succeeded[].
     */
    @Test
    @Order( 4 )
    void bulkApprovePartialFailure() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final String freshProposalId = createProposal( "BulkItPartial-" + System.currentTimeMillis() );

            final JsonObject body = new JsonObject();
            body.addProperty( "action", "approve" );
            final JsonArray ids = new JsonArray();
            ids.add( "00000000-0000-0000-0000-000000000000" );  // non-existent
            ids.add( freshProposalId );
            body.add( "ids", ids );

            final HttpResponse< String > resp = post(
                    "/admin/knowledge-graph/proposals/bulk-action", body.toString() );
            assertEquals( 200, resp.statusCode(),
                    "bulk-action approve partial should return 200: " + resp.body() );

            final JsonObject result = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( "completed", result.get( "status" ).getAsString() );

            final JsonArray succeeded = result.getAsJsonArray( "succeeded" );
            assertEquals( 1, succeeded.size(), "One proposal should succeed" );
            assertEquals( freshProposalId, succeeded.get( 0 ).getAsString() );

            final JsonArray failed = result.getAsJsonArray( "failed" );
            assertEquals( 1, failed.size(), "One proposal should fail" );
            assertEquals( "00000000-0000-0000-0000-000000000000",
                    failed.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Request-level validation: reject without reason → 400.
     */
    @Test
    @Order( 5 )
    void rejectWithoutReasonReturns400() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final JsonObject body = new JsonObject();
            body.addProperty( "action", "reject" );
            final JsonArray ids = new JsonArray();
            ids.add( "00000000-0000-0000-0000-000000000099" );
            body.add( "ids", ids );
            // no reason field

            final HttpResponse< String > resp = post(
                    "/admin/knowledge-graph/proposals/bulk-action", body.toString() );
            assertEquals( 400, resp.statusCode(),
                    "reject without reason should return 400: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }
}
