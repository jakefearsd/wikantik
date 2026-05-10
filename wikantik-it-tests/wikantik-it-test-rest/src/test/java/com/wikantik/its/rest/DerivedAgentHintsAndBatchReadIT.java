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
import com.google.gson.reflect.TypeToken;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the derived agent-hints stack: {@code /api/pages/for-agent},
 * {@code /knowledge-mcp} {@code read_pages} tool, and {@code /admin/agent-grade-audit}.
 *
 * <p>Three scenarios tested here; cluster-centrality ordering and hub-overlay
 * activation are unit-tested in Tasks 4-6 and skipped here to avoid brittle
 * page-seeding in the IT environment.</p>
 *
 * <h2>Scenario 1 — {@code /api/pages/for-agent}</h2>
 * <p>Fetches any seed page via {@code /api/structure/sitemap} to obtain a live
 * canonical_id, then asserts the {@code for-agent} projection JSON contains both
 * {@code agent_hints} and {@code summary_synthesized} keys (wire-shape proof).</p>
 *
 * <h2>Scenario 2 — {@code /knowledge-mcp} {@code read_pages} tool</h2>
 * <ul>
 *   <li><b>Happy path</b>: {@code read_pages(slugs=["Main"])} → 200 with per-page
 *       entry containing {@code content}, no {@code error}.</li>
 *   <li><b>Cap exceeded</b>: 21 slugs → MCP error result.</li>
 *   <li><b>Partial failure</b>: {@code ["Main","DoesNotExist123"]} → Main has
 *       {@code content}, DoesNotExist123 has {@code error: "not_found"}.</li>
 * </ul>
 *
 * <h2>Scenario 3 — {@code /admin/agent-grade-audit}</h2>
 * <p>Calls as admin, asserts 200 and JSON shape with {@code total}, {@code limit},
 * {@code offset}, {@code pages} fields.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class DerivedAgentHintsAndBatchReadIT {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();

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

    /** Clears the admin session so subsequent tests start anonymous. */
    private void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    /**
     * Waits for the structural index to reach UP status (needed before we can
     * resolve canonical_ids from the sitemap).
     */
    private void waitForStructuralIndexUp() throws Exception {
        for ( int attempt = 0; attempt < 30; attempt++ ) {
            final HttpResponse< String > resp = get( "/api/health/structural-index" );
            if ( resp.statusCode() == 200 && resp.body().contains( "\"UP\"" ) ) {
                return;
            }
            Thread.sleep( 1000 );
        }
        throw new AssertionError( "Structural index never reached UP within 30s" );
    }

    // ---- Scenario 1: /api/pages/for-agent returns agent_hints + summary_synthesized ----

    /**
     * Fetches the sitemap to obtain a canonical_id for a live page, then calls the
     * {@code for-agent} endpoint and asserts the wire shape includes the two new
     * fields introduced by the derived-agent-hints stack.
     */
    @Test
    @Order( 1 )
    @SuppressWarnings( "unchecked" )
    void forAgent_projection_contains_agentHints_and_summarySynthesized() throws Exception {
        waitForStructuralIndexUp();

        // Fetch sitemap to obtain a real canonical_id
        final HttpResponse< String > sitemapResp = get( "/api/structure/sitemap" );
        assertEquals( 200, sitemapResp.statusCode(),
                "GET /api/structure/sitemap should return 200: " + sitemapResp.body() );

        final JsonObject sitemapEnv = JsonParser.parseString( sitemapResp.body() ).getAsJsonObject();
        // Sitemap is wrapped in a "data" envelope: { "data": { "pages": [...], "count": N } }
        final JsonObject sitemapData = sitemapEnv.has( "data" )
                ? sitemapEnv.getAsJsonObject( "data" )
                : sitemapEnv;
        assertTrue( sitemapData.has( "pages" ),
                "sitemap response should contain 'pages': " + sitemapResp.body() );

        final JsonArray pages = sitemapData.getAsJsonArray( "pages" );
        assertFalse( pages.isEmpty(), "sitemap should have at least one page" );

        // Pick the first available canonical_id
        final JsonObject firstPage = pages.get( 0 ).getAsJsonObject();
        final String canonicalId = firstPage.get( "id" ).getAsString();
        assertFalse( canonicalId.isBlank(), "first sitemap entry must have a non-blank id" );

        // Call /api/pages/for-agent/{canonical_id}
        final HttpResponse< String > resp = get( "/api/pages/for-agent/" + canonicalId );
        assertEquals( 200, resp.statusCode(),
                "GET /api/pages/for-agent/" + canonicalId + " should return 200: " + resp.body() );

        final JsonObject envelope = JsonParser.parseString( resp.body() ).getAsJsonObject();
        // PageForAgentResource wraps its payload in a "data" envelope.
        final JsonObject projection = envelope.has( "data" )
                ? envelope.getAsJsonObject( "data" )
                : envelope;

        // The two new fields must be present (wire-shape proof).
        // agent_hints may be null (no mcp_tool_hints in seed pages) but the key must exist.
        assertTrue( projection.has( "agent_hints" ),
                "projection must contain 'agent_hints' key: " + resp.body() );
        assertTrue( projection.has( "summary_synthesized" ),
                "projection must contain 'summary_synthesized' key: " + resp.body() );
    }

    // ---- Scenario 2: /knowledge-mcp read_pages tool ----

    /**
     * Creates a short-lived {@link McpSyncClient} connected to the
     * {@code /knowledge-mcp} endpoint. The IT configuration enables
     * {@code mcp.access.allowUnrestricted=true} so no auth header is needed.
     */
    private McpSyncClient buildKnowledgeMcpClient() {
        final String knowledgeMcpUrl = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder( knowledgeMcpUrl )
                        .endpoint( "knowledge-mcp" )
                        .connectTimeout( Duration.ofSeconds( 15 ) )
                        .build();

        final McpSyncClient syncClient = McpClient.sync( transport )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-derived-agent-hints", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
        syncClient.initialize();
        return syncClient;
    }

    /**
     * Extracts the JSON text from the first {@link McpSchema.TextContent} in a
     * {@link McpSchema.CallToolResult}, then parses it into a {@link JsonObject}.
     */
    private JsonObject extractResultJson( final McpSchema.CallToolResult result ) {
        final List< McpSchema.Content > contents = result.content();
        assertNotNull( contents, "CallToolResult.content() must not be null" );
        assertFalse( contents.isEmpty(), "CallToolResult.content() must not be empty" );
        final McpSchema.Content first = contents.get( 0 );
        assertTrue( first instanceof McpSchema.TextContent,
                "first content element must be TextContent" );
        final String text = ( ( McpSchema.TextContent ) first ).text();
        return JsonParser.parseString( text ).getAsJsonObject();
    }

    /**
     * Happy path: {@code read_pages(["Main"])} returns a per-page entry with
     * {@code content} present and no {@code error} field.
     */
    @Test
    @Order( 2 )
    @SuppressWarnings( "unchecked" )
    void readPages_happyPath_mainPage_hasContent() {
        try ( final McpSyncClient mcp = buildKnowledgeMcpClient() ) {
            final McpSchema.CallToolResult result = mcp.callTool(
                    new McpSchema.CallToolRequest( "read_pages",
                            Map.of( "slugs", List.of( "Main" ) ) ) );

            assertNotNull( result, "CallToolResult must not be null" );
            // read_pages never sets isError=true for per-page failures — only for input validation.
            final Boolean isError = result.isError();
            assertFalse( Boolean.TRUE.equals( isError ),
                    "read_pages([Main]) should not return an error result: "
                            + ( result.content() != null && !result.content().isEmpty()
                                    ? ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text()
                                    : "<empty>" ) );

            final JsonObject body = extractResultJson( result );
            assertTrue( body.has( "pages" ), "result must have 'pages' key: " + body );

            final JsonArray pagesArr = body.getAsJsonArray( "pages" );
            assertEquals( 1, pagesArr.size(), "expected exactly one page entry" );

            final JsonObject pageEntry = pagesArr.get( 0 ).getAsJsonObject();
            assertEquals( "Main", pageEntry.get( "slug" ).getAsString() );
            assertTrue( pageEntry.has( "content" ),
                    "page entry for Main must have 'content': " + pageEntry );
            // content may be null (Main is a system page — may be excluded from KG)
            // but the key must be present; no error field
            final JsonElement errorEl = pageEntry.get( "error" );
            assertTrue( errorEl == null || errorEl.isJsonNull(),
                    "page entry for Main must not have an error: " + pageEntry );
        }
    }

    /**
     * Cap exceeded: 21 slugs → MCP error result.
     */
    @Test
    @Order( 3 )
    void readPages_capExceeded_21Slugs_returnsError() {
        try ( final McpSyncClient mcp = buildKnowledgeMcpClient() ) {
            final List< String > slugs = new ArrayList<>();
            for ( int i = 1; i <= 21; i++ ) {
                slugs.add( "Page" + i );
            }

            final McpSchema.CallToolResult result = mcp.callTool(
                    new McpSchema.CallToolRequest( "read_pages",
                            Map.of( "slugs", slugs ) ) );

            assertNotNull( result, "CallToolResult must not be null" );
            assertTrue( Boolean.TRUE.equals( result.isError() ),
                    "read_pages(21 slugs) should return isError=true (cap exceeded)" );
        }
    }

    /**
     * Partial failure: {@code read_pages(["Main","DoesNotExist123"])} →
     * Main has content (no error), DoesNotExist123 has {@code error: "not_found"}.
     */
    @Test
    @Order( 4 )
    @SuppressWarnings( "unchecked" )
    void readPages_partialFailure_knownAndUnknownSlug() {
        try ( final McpSyncClient mcp = buildKnowledgeMcpClient() ) {
            final McpSchema.CallToolResult result = mcp.callTool(
                    new McpSchema.CallToolRequest( "read_pages",
                            Map.of( "slugs", List.of( "Main", "DoesNotExist123" ) ) ) );

            assertNotNull( result );
            // Per-page failures do NOT make the tool result an error.
            assertFalse( Boolean.TRUE.equals( result.isError() ),
                    "partial failure should not set isError=true at the result level" );

            final JsonObject body = extractResultJson( result );
            assertTrue( body.has( "pages" ), "result must have 'pages': " + body );

            final JsonArray pagesArr = body.getAsJsonArray( "pages" );
            assertEquals( 2, pagesArr.size(), "expected exactly two page entries" );

            JsonObject mainEntry = null;
            JsonObject missingEntry = null;
            for ( final JsonElement el : pagesArr ) {
                final JsonObject entry = el.getAsJsonObject();
                final String slug = entry.get( "slug" ).getAsString();
                if ( "Main".equals( slug ) ) {
                    mainEntry = entry;
                } else if ( "DoesNotExist123".equals( slug ) ) {
                    missingEntry = entry;
                }
            }

            assertNotNull( mainEntry, "result must contain an entry for 'Main'" );
            final JsonElement mainError = mainEntry.get( "error" );
            assertTrue( mainError == null || mainError.isJsonNull(),
                    "Main entry must not have error: " + mainEntry );

            assertNotNull( missingEntry, "result must contain an entry for 'DoesNotExist123'" );
            assertTrue( missingEntry.has( "error" ),
                    "DoesNotExist123 entry must have 'error' key: " + missingEntry );
            assertEquals( "not_found", missingEntry.get( "error" ).getAsString(),
                    "DoesNotExist123 error must be 'not_found': " + missingEntry );
        }
    }

    // ---- Scenario 3: /admin/agent-grade-audit ----

    /**
     * Calls {@code GET /admin/agent-grade-audit} as admin and asserts the response
     * has the expected envelope shape ({@code total}, {@code limit}, {@code offset},
     * {@code pages}). Does not assert specific content — the seed may have any number
     * of weak pages.
     */
    @Test
    @Order( 5 )
    void agentGradeAudit_returnsEnvelopeShape() throws Exception {
        try {
            loginAsAdmin();

            final HttpResponse< String > resp = get( "/admin/agent-grade-audit" );
            assertEquals( 200, resp.statusCode(),
                    "GET /admin/agent-grade-audit should return 200: " + resp.body() );

            final JsonObject body = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( body.has( "total" ),
                    "response must contain 'total': " + resp.body() );
            assertTrue( body.has( "limit" ),
                    "response must contain 'limit': " + resp.body() );
            assertTrue( body.has( "offset" ),
                    "response must contain 'offset': " + resp.body() );
            assertTrue( body.has( "pages" ),
                    "response must contain 'pages': " + resp.body() );
            assertTrue( body.get( "pages" ).isJsonArray(),
                    "'pages' must be a JSON array: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Verifies that unauthenticated access to the audit endpoint returns 403.
     */
    @Test
    @Order( 6 )
    void agentGradeAudit_unauthenticated_returns403() throws Exception {
        // Use a fresh client with its own isolated cookie jar (no admin session).
        final HttpClient anonClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        final HttpRequest req = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/admin/agent-grade-audit" ) )
                .header( "Accept", "application/json" )
                .GET()
                .build();
        final HttpResponse< String > resp = anonClient.send( req, HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, resp.statusCode(),
                "Unauthenticated access to /admin/agent-grade-audit should be 403: " + resp.body() );
    }
}
