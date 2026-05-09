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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the pagination contract on
 * {@code GET /admin/knowledge-graph/proposals} against a Cargo-deployed Tomcat.
 *
 * <p>The unit-test layer ({@code AdminKnowledgeResourceMockTest}) verifies the
 * REST handler's response envelope and limit/offset clamping with a mocked
 * service. This class verifies the live wiring against real PostgreSQL —
 * specifically the parts that are SQL-semantic and thus invisible to mocks:
 *
 * <ul>
 *   <li>{@code total_count} matches the actual filtered row count (not
 *       just whatever count was mocked).</li>
 *   <li>{@code limit} + {@code offset} produce non-overlapping pages whose
 *       union equals the full filtered set.</li>
 *   <li>The {@code machine_status="(null)"} sentinel correctly maps to
 *       {@code AND machine_status IS NULL} in the underlying SQL.</li>
 *   <li>An explicit {@code machine_status='approved'} value excludes
 *       {@code NULL}-valued rows (the regression case for any bug that mixed
 *       up the two patterns).</li>
 * </ul>
 *
 * <p>Tests are scoped to a unique {@code source_page} so they don't depend
 * on or pollute other proposal data in the shared test DB.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class AdminKnowledgePaginationIT {

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;
    /** Unique to this test run so concurrent runs / shared DB state can't collide. */
    private static String sourcePage;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        sourcePage = "PaginationItTest-" + System.currentTimeMillis() + ".md";
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

    private HttpResponse< String > get( final String path )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
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

    private String createProposal( final String name ) throws IOException, InterruptedException {
        final JsonObject body = new JsonObject();
        body.addProperty( "proposal_type", "new-node" );
        body.addProperty( "source_page", sourcePage );
        body.addProperty( "confidence", 0.7 );
        body.addProperty( "reasoning", "IT pagination" );
        final JsonObject proposedData = new JsonObject();
        proposedData.addProperty( "name", name );
        body.add( "proposed_data", proposedData );

        final HttpResponse< String > resp = post(
                "/admin/knowledge-graph/proposals", body.toString() );
        assertEquals( 200, resp.statusCode(),
                "Proposal creation should return 200: " + resp.body() );
        final JsonObject obj = JsonParser.parseString( resp.body() ).getAsJsonObject();
        return obj.get( "id" ).getAsString();
    }

    private String urlEnc( final String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 );
    }

    // ---- tests ----

    /**
     * Seed: create 5 proposals all on the same {@code sourcePage} so subsequent
     * tests can paginate against a known-size set.
     */
    @Test
    @Order( 1 )
    void createProposalsForPagination() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            for ( int i = 0; i < 5; i++ ) {
                assertNotNull( createProposal( "PaginationNode-" + i ),
                        "Each proposal creation must return an id" );
            }
        } finally {
            logoutAdmin();
        }
    }

    /**
     * total_count matches the size of the filtered set when limit covers the
     * entire result. Verifies that countProposalsFiltered's WHERE clause
     * agrees with listProposalsFiltered's, the symmetry the unit tests can't
     * verify since they mock the service.
     */
    @Test
    @Order( 2 )
    void totalCountEqualsFilteredSetSize() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final HttpResponse< String > resp = get(
                    "/admin/knowledge-graph/proposals?source_page=" + urlEnc( sourcePage )
                    + "&limit=100&offset=0" );
            assertEquals( 200, resp.statusCode(), resp.body() );

            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( 5, body.get( "total_count" ).getAsInt(),
                    "total_count must equal the seed size" );
            assertEquals( 5, body.getAsJsonArray( "proposals" ).size(),
                    "proposals[] must contain all 5 when limit covers them" );
            assertEquals( 100, body.get( "limit" ).getAsInt(),
                    "Server should echo the applied limit" );
            assertEquals( 0, body.get( "offset" ).getAsInt(),
                    "Server should echo the applied offset" );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * limit + offset produce non-overlapping pages whose union equals the
     * filtered set. This is the core pagination correctness check.
     */
    @Test
    @Order( 3 )
    void paginatedFetchProducesNonOverlappingUnionOfFilteredSet() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final Set< String > seenIds = new HashSet<>();

            // Page 1 (offset=0, limit=2) — expect 2 ids, total_count=5
            final JsonObject p1 = page( 0, 2 );
            assertEquals( 5, p1.get( "total_count" ).getAsInt() );
            collectIds( p1.getAsJsonArray( "proposals" ), seenIds );
            assertEquals( 2, seenIds.size(), "page 1 should contribute 2 unique ids" );

            // Page 2 (offset=2, limit=2)
            final JsonObject p2 = page( 2, 2 );
            assertEquals( 5, p2.get( "total_count" ).getAsInt() );
            collectIds( p2.getAsJsonArray( "proposals" ), seenIds );
            assertEquals( 4, seenIds.size(),
                    "after page 2 the union must be 4 unique ids — overlap means broken pagination" );

            // Page 3 (offset=4, limit=2) — partial last page
            final JsonObject p3 = page( 4, 2 );
            assertEquals( 5, p3.get( "total_count" ).getAsInt() );
            assertEquals( 1, p3.getAsJsonArray( "proposals" ).size(),
                    "partial last page should have exactly the remaining 1" );
            collectIds( p3.getAsJsonArray( "proposals" ), seenIds );
            assertEquals( 5, seenIds.size(),
                    "the 3 pages combined must equal the full filtered set" );
        } finally {
            logoutAdmin();
        }
    }

    private JsonObject page( final int offset, final int limit ) throws IOException, InterruptedException {
        final HttpResponse< String > resp = get(
                "/admin/knowledge-graph/proposals?source_page=" + urlEnc( sourcePage )
                + "&limit=" + limit + "&offset=" + offset );
        assertEquals( 200, resp.statusCode(), resp.body() );
        return JsonParser.parseString( resp.body() ).getAsJsonObject();
    }

    private void collectIds( final JsonArray proposals, final Set< String > target ) {
        for ( int i = 0; i < proposals.size(); i++ ) {
            target.add( proposals.get( i ).getAsJsonObject().get( "id" ).getAsString() );
        }
    }

    /**
     * The {@code machine_status="(null)"} sentinel must map to
     * {@code AND machine_status IS NULL}. Freshly created proposals are
     * unjudged (machine_status NULL), so all 5 must appear under this filter.
     * This is the regression case for the "Awaiting machine review" UI filter.
     */
    @Test
    @Order( 4 )
    void nullSentinelReturnsUnjudgedRows() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final HttpResponse< String > resp = get(
                    "/admin/knowledge-graph/proposals?source_page=" + urlEnc( sourcePage )
                    + "&machine_status=" + urlEnc( "(null)" )
                    + "&limit=100" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( 5, body.get( "total_count" ).getAsInt(),
                    "all 5 freshly-created (unjudged) proposals must match (null) sentinel" );
            assertEquals( 5, body.getAsJsonArray( "proposals" ).size() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * A literal {@code machine_status} value (e.g. {@code 'approved'}) must
     * NOT include {@code NULL}-valued rows. Regression case for any future
     * bug that conflates the sentinel with a literal value.
     */
    @Test
    @Order( 5 )
    void literalMachineStatusExcludesNullRows() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final HttpResponse< String > resp = get(
                    "/admin/knowledge-graph/proposals?source_page=" + urlEnc( sourcePage )
                    + "&machine_status=approved&limit=100" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( 0, body.get( "total_count" ).getAsInt(),
                    "no proposal in this run has machine_status='approved'" );
            assertEquals( 0, body.getAsJsonArray( "proposals" ).size() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Server caps {@code limit} at {@code MAX_PROPOSAL_PAGE_SIZE} (500) and
     * echoes the applied (capped) value back to the client. Defends against
     * a runaway client request that asks for a million rows.
     */
    @Test
    @Order( 6 )
    void serverCapsLimitAtMaxPageSize() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final HttpResponse< String > resp = get(
                    "/admin/knowledge-graph/proposals?source_page=" + urlEnc( sourcePage )
                    + "&limit=10000&offset=0" );
            assertEquals( 200, resp.statusCode(), resp.body() );
            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertEquals( 500, body.get( "limit" ).getAsInt(),
                    "limit must be capped at MAX_PROPOSAL_PAGE_SIZE (500)" );
        } finally {
            logoutAdmin();
        }
    }
}
