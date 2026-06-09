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
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.lang.reflect.Type;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke for {@code GET /api/page-knowledge/{name}} and the page-scoped
 * entity/edge write endpoints against a Cargo-deployed Tomcat.
 *
 * <p>Tests (ordered to chain state through the session):</p>
 * <ol>
 *   <li>GET the slice for {@code Main} as admin — asserts the camelCase contract
 *       ({@code entities} + {@code edges} arrays present), not extraction output.</li>
 *   <li>POST two entities ({@code technology} + {@code concept}) via
 *       {@code .../entities}; assert {@code id} + {@code node} block returned.</li>
 *   <li>Re-GET the slice and verify the created entity's {@code nodeType} key is
 *       camelCase — this is the regression that catches a request-key contract drift
 *       between the MCP path (camelCase) and this REST path.</li>
 *   <li>POST a conformant {@code implements} edge (technology → concept) → 200.</li>
 *   <li>POST a non-conformant {@code implements} edge (concept → concept) → 422
 *       with {@code error: "kg_edge_refused"} and a {@code violations} array citing
 *       the SHACL constraint.</li>
 * </ol>
 *
 * <p>Does NOT assert on freshly-extracted KG mentions (extraction lags >20s in the
 * custom-jdbc profile). The curate-then-verify round-trip tests the write gate
 * end-to-end without depending on the background extraction pipeline.</p>
 *
 * <p>Mirrors {@link EdgeCurationIT}'s cookie-based admin session pattern.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class PageKnowledgeIT {

    /**
     * Use {@code Main} as the host page for the curated entities. Main always
     * exists in the IT wiki corpus and has public view + admin edit permissions.
     * We do NOT assert on its pre-existing KG content (extraction lag), only on
     * the entities we inject in this test run.
     */
    private static final String TEST_PAGE = "Main";

    /** Unique suffix per test run so parallel runs don't collide in the KG tables. */
    private static final String IT_SUFFIX = UUID.randomUUID().toString().substring( 0, 8 );

    private static final String TECH_NAME = "ItTechX-" + IT_SUFFIX;
    private static final String CONCEPT_NAME = "ItConceptY-" + IT_SUFFIX;

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    private static String baseUrl;
    private static HttpClient client;

    /** Captured in test 2, used in tests 3–5. */
    private static String techNodeId;
    private static String conceptNodeId;

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

    private Map< String, Object > parseJson( final String body ) {
        return GSON.fromJson( body, MAP_TYPE );
    }

    /** Authenticates the shared {@link #client} cookie jar as the admin user. */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    // ---- Tests ----

    /**
     * GET the KG slice as admin. Asserts the camelCase JSON contract:
     * {@code entities} and {@code edges} arrays must be present. Does NOT
     * assert on their contents (extraction is async and may not have run yet).
     */
    @Test
    @Order( 1 )
    @SuppressWarnings( "unchecked" )
    void getSliceAsAdminReturnsEntitiesAndEdgesArrays() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = get( "/api/page-knowledge/" + TEST_PAGE );
        assertEquals( 200, resp.statusCode(),
                "GET /api/page-knowledge/Main must return 200: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        // Contract: camelCase keys for both arrays (not snake_case)
        assertTrue( json.containsKey( "entities" ),
                "Response must contain 'entities' key (camelCase): " + resp.body() );
        assertTrue( json.containsKey( "edges" ),
                "Response must contain 'edges' key (camelCase): " + resp.body() );

        // Both must be lists (may be empty — extraction lag is expected)
        assertNotNull( json.get( "entities" ) );
        assertNotNull( json.get( "edges" ) );
        assertTrue( json.get( "entities" ) instanceof List,
                "'entities' must be an array: " + json.get( "entities" ) );
        assertTrue( json.get( "edges" ) instanceof List,
                "'edges' must be an array: " + json.get( "edges" ) );
    }

    /**
     * POST two entities via the page-scoped entity endpoint.
     * Asserts the camelCase request key {@code nodeType} is accepted
     * (not snake_case {@code node_type} used by the admin KG endpoint).
     * Captures {@code nodeId}s for subsequent edge tests.
     */
    @Test
    @Order( 2 )
    @SuppressWarnings( "unchecked" )
    void postEntitiesWithCamelCaseNodeTypeReturnsNodeId() throws Exception {
        loginAsAdmin();

        // POST technology node
        final String techBody = GSON.toJson( Map.of( "name", TECH_NAME, "nodeType", "technology" ) );
        final HttpResponse< String > techResp = post(
                "/api/page-knowledge/" + TEST_PAGE + "/entities", techBody );
        assertEquals( 200, techResp.statusCode(),
                "POST technology entity must return 200: " + techResp.body() );
        final Map< String, Object > techJson = parseJson( techResp.body() );
        assertTrue( Boolean.TRUE.equals( techJson.get( "ok" ) ),
                "technology entity response must carry ok=true: " + techResp.body() );
        assertNotNull( techJson.get( "id" ),
                "technology entity response must carry 'id': " + techResp.body() );
        techNodeId = String.valueOf( techJson.get( "id" ) );

        // The response also contains a 'node' block — verify nodeType camelCase is in it
        final Map< String, Object > techNode = ( Map< String, Object > ) techJson.get( "node" );
        assertNotNull( techNode, "POST entity response must carry a 'node' block: " + techResp.body() );
        assertEquals( "technology", techNode.get( "nodeType" ),
                "node.nodeType must be 'technology' (camelCase key): " + techNode );

        // POST concept node
        final String conceptBody = GSON.toJson( Map.of( "name", CONCEPT_NAME, "nodeType", "concept" ) );
        final HttpResponse< String > conceptResp = post(
                "/api/page-knowledge/" + TEST_PAGE + "/entities", conceptBody );
        assertEquals( 200, conceptResp.statusCode(),
                "POST concept entity must return 200: " + conceptResp.body() );
        final Map< String, Object > conceptJson = parseJson( conceptResp.body() );
        assertTrue( Boolean.TRUE.equals( conceptJson.get( "ok" ) ),
                "concept entity response must carry ok=true: " + conceptResp.body() );
        assertNotNull( conceptJson.get( "id" ),
                "concept entity response must carry 'id': " + conceptResp.body() );
        conceptNodeId = String.valueOf( conceptJson.get( "id" ) );

        final Map< String, Object > conceptNode = ( Map< String, Object > ) conceptJson.get( "node" );
        assertNotNull( conceptNode, "POST entity response must carry a 'node' block: " + conceptResp.body() );
        assertEquals( "concept", conceptNode.get( "nodeType" ),
                "node.nodeType must be 'concept' (camelCase key): " + conceptNode );
    }

    /**
     * Re-GET the slice after creating the two entities. Finds one of the newly created
     * entities in the returned list and asserts its {@code nodeType} key is camelCase.
     * This catches a server-side regression where the write path accepts camelCase but
     * the read path serialises snake_case.
     */
    @Test
    @Order( 3 )
    @SuppressWarnings( "unchecked" )
    void getSliceAfterUpsertReturnsCreatedEntityWithCamelCaseNodeType() throws Exception {
        loginAsAdmin();
        // techNodeId is captured by test 2; if this test runs without test 2, skip gracefully.
        if ( techNodeId == null ) {
            return; // test 2 must have run first
        }
        final HttpResponse< String > resp = get( "/api/page-knowledge/" + TEST_PAGE );
        assertEquals( 200, resp.statusCode(), resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        final List< Map< String, Object > > entities =
                ( List< Map< String, Object > > ) json.get( "entities" );
        assertNotNull( entities );

        // Find the entity we just created
        final Map< String, Object > found = entities.stream()
                .filter( e -> techNodeId.equals( e.get( "id" ) ) )
                .findFirst()
                .orElse( null );
        assertNotNull( found,
                "Freshly created entity id=" + techNodeId
                + " must appear in the GET slice; found ids: "
                + entities.stream().map( e -> e.get( "id" ) ).toList() );

        // The camelCase contract: the key must be "nodeType", not "node_type"
        assertTrue( found.containsKey( "nodeType" ),
                "Entity in GET slice must use camelCase key 'nodeType': " + found );
        assertEquals( "technology", found.get( "nodeType" ),
                "nodeType must be 'technology': " + found );
    }

    /**
     * POST a conformant {@code implements} edge: technology → concept.
     * The SHACL gate requires source=technology, target=concept — this must succeed.
     */
    @Test
    @Order( 4 )
    void postConformantImplementsEdgeReturns200() throws Exception {
        loginAsAdmin();
        if ( techNodeId == null || conceptNodeId == null ) {
            return; // depends on test 2
        }
        // camelCase keys: sourceId, targetId, relationshipType
        final String edgeBody = GSON.toJson( Map.of(
                "sourceId", techNodeId,
                "targetId", conceptNodeId,
                "relationshipType", "implements" ) );
        final HttpResponse< String > resp = post(
                "/api/page-knowledge/" + TEST_PAGE + "/edges", edgeBody );
        assertEquals( 200, resp.statusCode(),
                "Conformant implements edge (technology→concept) must return 200: " + resp.body() );
        final Map< String, Object > json = parseJson( resp.body() );
        assertTrue( Boolean.TRUE.equals( json.get( "ok" ) ),
                "Response must carry ok=true: " + resp.body() );
        assertNotNull( json.get( "id" ),
                "Response must carry edge 'id': " + resp.body() );
    }

    /**
     * POST a non-conformant {@code implements} edge: concept → concept.
     * The SHACL gate requires source=technology — this must be rejected with 422
     * carrying {@code error: "kg_edge_refused"} and a {@code violations} array.
     * This is the end-to-end proof that the SHACL gate is wired through the
     * page-scoped write endpoint (not only the admin endpoint).
     */
    @Test
    @Order( 5 )
    @SuppressWarnings( "unchecked" )
    void postNonConformantImplementsEdgeReturns422WithViolations() throws Exception {
        loginAsAdmin();
        if ( conceptNodeId == null ) {
            return; // depends on test 2
        }
        // concept → concept violates wk:ImplementsShape (source must be a wk:Technology)
        final String edgeBody = GSON.toJson( Map.of(
                "sourceId", conceptNodeId,
                "targetId", conceptNodeId,
                "relationshipType", "implements" ) );
        final HttpResponse< String > resp = post(
                "/api/page-knowledge/" + TEST_PAGE + "/edges", edgeBody );
        assertEquals( 422, resp.statusCode(),
                "Non-conformant implements edge (concept→concept) must return 422: " + resp.body() );

        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( "kg_edge_refused", json.get( "error" ),
                "422 body must carry error='kg_edge_refused': " + resp.body() );

        final List< Map< String, Object > > violations =
                ( List< Map< String, Object > > ) json.get( "violations" );
        assertNotNull( violations,
                "422 body must contain a 'violations' array: " + resp.body() );
        assertFalse( violations.isEmpty(),
                "violations must not be empty: " + resp.body() );

        // At least one violation must cite the shape/Technology/implements constraint
        final String violationsStr = violations.toString().toLowerCase();
        assertTrue( violationsStr.contains( "technology" )
                        || violationsStr.contains( "shape" )
                        || violationsStr.contains( "implement" ),
                "violation message must cite the shape/Technology constraint: " + violations );
    }

}
