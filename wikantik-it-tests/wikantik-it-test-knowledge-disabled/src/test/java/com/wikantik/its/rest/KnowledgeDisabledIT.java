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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for the {@code wikantik.knowledge.enabled} master flag.
 * This module deploys a Tomcat with the Knowledge Graph subsystem OFF (see
 * {@code src/main/resources/wikantik-custom.properties}).
 *
 * <p>Asserts that the KG surfaces refuse cleanly rather than NPE or 500:</p>
 * <ul>
 *   <li>{@code GET /admin/knowledge-graph/schema} returns {@code 503} whose JSON
 *       body cites {@code wikantik.knowledge.enabled} (the reason).</li>
 *   <li>The {@code /knowledge-mcp} server still starts, but the six KG tools
 *       ({@code discover_schema}, {@code query_nodes}, {@code get_node},
 *       {@code traverse}, {@code search_knowledge}, {@code find_similar}) are
 *       absent while the retrieval / structural-spine tools remain.</li>
 * </ul>
 */
public class KnowledgeDisabledIT {

    private static final Gson GSON = new Gson();

    /** The six KG tools that must NOT be registered when the KG subsystem is off. */
    private static final Set< String > KG_TOOLS = Set.of(
            "discover_schema", "query_nodes", "get_node",
            "traverse", "search_knowledge", "find_similar" );

    private static String baseUrl;
    private static HttpClient client;
    private static McpSyncClient mcp;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-knowledge-disabled" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();

        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder( prefix )
                        .endpoint( "knowledge-mcp" )
                        .connectTimeout( Duration.ofSeconds( 15 ) )
                        .build();
        mcp = McpClient.sync( transport )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-kg-disabled", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
        mcp.initialize();
    }

    @AfterAll
    static void tearDown() {
        if ( mcp != null ) {
            mcp.close();
        }
    }

    @Test
    void adminKnowledgeSchema_returns503CitingFlag() throws Exception {
        loginAsAdmin();
        final HttpResponse< String > resp = get( "/admin/knowledge-graph/schema" );

        assertEquals( 503, resp.statusCode(),
                "KG admin surface must refuse with 503 when the subsystem is off: " + resp.body() );
        assertTrue( resp.body().contains( "wikantik.knowledge.enabled" ),
                "503 payload must cite the flag as the reason: " + resp.body() );
    }

    @Test
    void knowledgeMcp_kgToolsAbsent_retrievalToolsPresent() {
        final Set< String > toolNames = mcp.listTools().tools().stream()
                .map( McpSchema.Tool::name )
                .collect( Collectors.toSet() );

        for ( final String kg : KG_TOOLS ) {
            assertFalse( toolNames.contains( kg ),
                    "KG tool '" + kg + "' must be absent when wikantik.knowledge.enabled=false; tools=" + toolNames );
        }
        // The knowledge-mcp server still serves its non-KG surface.
        assertTrue( toolNames.contains( "list_clusters" ) || toolNames.contains( "retrieve_context" ),
                "retrieval / structural-spine tools must remain registered; tools=" + toolNames );
    }

    // ---- helpers ----

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

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

    /** Accepts the Secure session cookie over plain-HTTP Cargo by rewriting the URI scheme. */
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
}
