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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level IT proving the full self-minted API key lifecycle:
 * <ol>
 *   <li>Login as admin (cookie session).</li>
 *   <li>POST /api/self/apikeys → 201, token starts with {@code wkk_}, scope is {@code all}.</li>
 *   <li>Use the token (Bearer) on a NO-cookie MCP client → MCP initialize + tools/list succeed.</li>
 *   <li>GET /api/self/apikeys → exactly one active key, no sensitive fields.</li>
 *   <li>DELETE /api/self/apikeys/{id} → 200 success.</li>
 *   <li>Re-issue MCP initialize with the revoked bearer → 401 or 403.</li>
 * </ol>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class SelfApiKeyLifecycleIT {

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    /** Cookie-authenticated client — used for /api/self/apikeys calls. */
    private static HttpClient cookieClient;

    /** ID of the minted key, shared across test methods. */
    private static String mintedKeyId;
    /** Plaintext token returned at mint time. */
    private static String mintedToken;

    /** Tracks the MCP client opened in @Order(3) so @AfterEach can close it. */
    private McpSyncClient mcpClient;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        cookieClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
    }

    @AfterEach
    void closeMcpClient() {
        if ( mcpClient != null ) {
            try { mcpClient.close(); } catch ( final Exception ignored ) { /* best-effort */ }
            mcpClient = null;
        }
    }

    // ---- helpers ----

    /**
     * Returns a CookieHandler that accepts cookies unconditionally, mirroring the
     * {@code AdminApiKeysBulkActionIT} harness. The JSESSIONID cookie is issued
     * over HTTP in the IT environment; {@code CookiePolicy.ACCEPT_ALL} is required
     * because Java's default policy rejects cookies without a domain match.
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

    private HttpResponse< String > post( final String path, final String jsonBody )
            throws IOException, InterruptedException {
        return cookieClient.send(
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
        return cookieClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > delete( final String path )
            throws IOException, InterruptedException {
        return cookieClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .DELETE()
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

    /**
     * Builds an MCP client over /knowledge-mcp that sends {@code Authorization: Bearer <token>}
     * on every request. Uses a plain HttpClient with NO cookie jar so that only the bearer
     * token authenticates the call, not any residual session cookie.
     */
    private McpSyncClient buildBearerMcpClient( final String token ) {
        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder( prefix )
                        .endpoint( "knowledge-mcp" )
                        .connectTimeout( Duration.ofSeconds( 15 ) )
                        .customizeRequest( rb -> rb.header( "Authorization", "Bearer " + token ) )
                        .build();
        return McpClient.sync( transport )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-self-apikey", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
    }

    // ---- tests ----

    /**
     * Step 1 + 2: login and mint a self-owned key.
     * Asserts 201, token prefix, scope, and captures id for later steps.
     */
    @Test
    @Order( 1 )
    void mintKey() throws IOException, InterruptedException {
        loginAsAdmin();
        try {
            final String body = GSON.toJson( Map.of( "label", "it-key", "scope", "all" ) );
            final HttpResponse< String > resp = post( "/api/self/apikeys", body );
            assertEquals( 201, resp.statusCode(), "Mint must return 201: " + resp.body() );

            final JsonObject json = JsonParser.parseString( resp.body() ).getAsJsonObject();
            final String token = json.get( "token" ).getAsString();
            assertTrue( token.startsWith( "wkk_" ),
                    "Token must start with wkk_: " + token );
            assertEquals( "all", json.get( "scope" ).getAsString(),
                    "scope must be 'all': " + resp.body() );
            assertNotNull( json.get( "id" ), "Response must include an id: " + resp.body() );

            mintedKeyId = String.valueOf( json.get( "id" ).getAsInt() );
            mintedToken = token;
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 3: bearer-auth calls with the minted token.
     *
     * <p>Two surfaces are exercised:
     * <ol>
     *   <li><b>/knowledge-mcp</b> (MCP Streamable-HTTP): initialize + tools/list.
     *       The IT environment sets {@code mcp.access.allowUnrestricted=true}, so
     *       this asserts the MCP surface is reachable with the bearer header and
     *       returns a valid tool list.</li>
     *   <li><b>/tools/openapi.json</b> (OpenAPI tool server): confirms the same
     *       token is accepted as a real DB-backed bearer credential on an endpoint
     *       whose access filter enforces actual DB-key verification (no unrestricted
     *       bypass in the IT configuration). This is the canonical proof that the
     *       minted key is stored and active in the shared {@link com.wikantik.auth.apikeys.ApiKeyService}.</li>
     * </ol>
     * </p>
     */
    @Test
    @Order( 2 )
    void bearerAuthenticatesMcpSession() throws IOException, InterruptedException {
        assertNotNull( mintedToken, "mintedToken from @Order(1) must not be null" );

        // --- MCP surface ---
        mcpClient = buildBearerMcpClient( mintedToken );
        // initialize() sends the MCP initialize request with the bearer header.
        mcpClient.initialize();

        final McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
        assertNotNull( toolsResult, "tools/list must return a result" );
        assertNotNull( toolsResult.tools(), "tools list must not be null" );
        assertFalse( toolsResult.tools().isEmpty(),
                "At least one knowledge-mcp tool must be listed" );

        // --- Tools server surface (real bearer-auth enforcement, no unrestricted bypass) ---
        final HttpClient bearerClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();
        final int toolsStatus = callToolsWithBearer( bearerClient, mintedToken );
        assertEquals( 200, toolsStatus,
                "Minted token must authenticate /tools/openapi.json (real DB-backed bearer check): "
                + "got HTTP " + toolsStatus );
    }

    /**
     * Step 4: list the caller's own keys — exactly one active key labelled "it-key",
     * and no sensitive fields (keyHash / token / fingerprint / principalLogin) in any row.
     */
    @Test
    @Order( 3 )
    void listKeysShowsExactlyOneWithNoSensitiveFields() throws IOException, InterruptedException {
        assertNotNull( mintedKeyId, "mintedKeyId from @Order(1) must not be null" );
        loginAsAdmin();
        try {
            final HttpResponse< String > resp = get( "/api/self/apikeys" );
            assertEquals( 200, resp.statusCode(), "GET /api/self/apikeys must return 200: " + resp.body() );

            final JsonObject json = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( json.has( "keys" ), "Response must have 'keys': " + resp.body() );
            final JsonArray keys = json.getAsJsonArray( "keys" );

            // Find our key by id
            JsonObject itKey = null;
            for ( final JsonElement el : keys ) {
                final JsonObject row = el.getAsJsonObject();
                if ( mintedKeyId.equals( String.valueOf( row.get( "id" ).getAsInt() ) ) ) {
                    itKey = row;
                    break;
                }
            }
            assertNotNull( itKey, "Key id=" + mintedKeyId + " must appear in the list: " + resp.body() );
            assertEquals( "it-key", itKey.get( "label" ).getAsString(),
                    "Label must be 'it-key': " + itKey );

            // No sensitive field must appear in ANY row
            for ( final JsonElement el : keys ) {
                final JsonObject row = el.getAsJsonObject();
                final String rowStr = row.toString();
                assertFalse( rowStr.contains( "keyHash" ),
                        "keyHash must not appear in self-listing: " + rowStr );
                assertFalse( rowStr.contains( "fingerprint" ),
                        "fingerprint must not appear in self-listing: " + rowStr );
                assertFalse( rowStr.contains( "principalLogin" ),
                        "principalLogin must not appear in self-listing: " + rowStr );
                // 'token' must only appear in the generation response, not in list
                assertFalse( row.has( "token" ),
                        "'token' field must not appear in list row: " + rowStr );
            }
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 5: revoke the minted key.
     * Asserts 200 with {"success":true}.
     */
    @Test
    @Order( 4 )
    void revokeKey() throws IOException, InterruptedException {
        assertNotNull( mintedKeyId, "mintedKeyId from @Order(1) must not be null" );
        loginAsAdmin();
        try {
            final HttpResponse< String > resp = delete( "/api/self/apikeys/" + mintedKeyId );
            assertEquals( 200, resp.statusCode(), "DELETE must return 200: " + resp.body() );

            final JsonObject json = JsonParser.parseString( resp.body() ).getAsJsonObject();
            assertTrue( json.get( "success" ).getAsBoolean(),
                    "Response must carry {success:true}: " + resp.body() );
        } finally {
            logoutAdmin();
        }
    }

    /**
     * Step 6: the revoked token must no longer authenticate downstream calls.
     *
     * <p>The IT environment sets {@code mcp.access.allowUnrestricted=true} for
     * the {@code /knowledge-mcp} endpoint so MCP tests do not need to pre-mint
     * keys. Revocation therefore cannot be proved via MCP in this setup.
     * Instead we verify against {@code /tools/*}, which is gated by the same
     * {@link com.wikantik.auth.apikeys.ApiKeyService#verify} call (no
     * {@code tools.access.allowUnrestricted} is set in the IT environment) and
     * uses the same shared {@code ApiKeyService} singleton. A 403 response from
     * {@code /tools/openapi.json} proves that {@link com.wikantik.auth.apikeys.ApiKeyService#revoke}
     * evicted the verify-cache entry and the DB-level revocation is enforced.</p>
     */
    @Test
    @Order( 5 )
    void revokedTokenFailsClosed() throws IOException, InterruptedException {
        assertNotNull( mintedToken, "mintedToken from @Order(1) must not be null" );

        // A plain HttpClient with no cookie jar — only the bearer token authenticates.
        final HttpClient bearerClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();

        // First attempt — the service evicts immediately on revoke (verifyCache.invalidate)
        // but allow one brief retry in case of any race between the DELETE response
        // and the in-JVM cache invalidation propagating to the tools filter.
        int statusCode = callToolsWithBearer( bearerClient, mintedToken );
        if ( statusCode == 200 ) {
            Thread.sleep( 2_000L );
            statusCode = callToolsWithBearer( bearerClient, mintedToken );
        }
        assertTrue( statusCode == 401 || statusCode == 403,
                "Revoked bearer must be rejected with 401/403 from /tools/openapi.json; got " + statusCode );
    }

    /**
     * GETs {@code /tools/openapi.json} with {@code Authorization: Bearer <token>} and
     * returns the HTTP status code. The tools access filter enforces DB-backed bearer
     * auth without any {@code allowUnrestricted} bypass, so a revoked token must yield
     * 403 and a never-minted token must also yield 403.
     */
    private int callToolsWithBearer( final HttpClient bearerClient, final String token )
            throws IOException, InterruptedException {
        final HttpResponse< String > resp = bearerClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/tools/openapi.json" ) )
                        .header( "Accept", "application/json" )
                        .header( "Authorization", "Bearer " + token )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        return resp.statusCode();
    }
}
