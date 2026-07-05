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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
 * Wire-level Cargo IT for the session-start context briefing surfaces:
 * {@code GET /api/briefing} (wikantik-rest) and the {@code get_briefing}
 * MCP tool (/knowledge-mcp). Uses only startup fixture pages/clusters
 * ({@code SemanticArticle}, {@code test-cluster}) — freshly-seeded pages
 * lag the structural index by more than the IT profile's polling window.
 */
public class BriefingIT {

    private static final String PIN_PAGE = "SemanticArticle";
    private static final String FIXTURE_CLUSTER = "test-cluster";

    private static String baseUrl;
    private static HttpClient httpClient;
    private static McpSyncClient mcp;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        httpClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();

        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder( prefix )
                        .endpoint( "knowledge-mcp" )
                        .connectTimeout( Duration.ofSeconds( 15 ) )
                        .build();
        mcp = McpClient.sync( transport )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-knowledge-mcp", "1.0.0" ) )
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

    private HttpResponse< String > get( final String path ) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .GET()
                .build();
        return httpClient.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    @Test
    void restBriefingMdWithCluster() throws Exception {
        final HttpResponse< String > resp = get( "/api/briefing?clusters=" + FIXTURE_CLUSTER + "&format=md" );
        assertEquals( 200, resp.statusCode(), "unexpected status: " + resp.body() );
        final String contentType = resp.headers().firstValue( "Content-Type" ).orElse( "" );
        assertTrue( contentType.startsWith( "text/markdown" ),
                "expected text/markdown content-type but was: " + contentType );
        assertTrue( resp.body().startsWith( "# Wiki context briefing" ),
                "markdown briefing must start with the standard heading: " + resp.body() );
    }

    @Test
    void restBriefingJsonWithPin() throws Exception {
        final HttpResponse< String > resp = get( "/api/briefing?pins=" + PIN_PAGE );
        assertEquals( 200, resp.statusCode(), "unexpected status: " + resp.body() );
        final JsonElement parsed = JsonParser.parseString( resp.body() );
        assertTrue( parsed.isJsonObject(), "briefing JSON body must be an object: " + resp.body() );
        final JsonObject body = parsed.getAsJsonObject();
        assertTrue( body.has( "items" ), "briefing JSON must include 'items': " + body );
        final JsonArray items = body.getAsJsonArray( "items" );
        boolean foundPin = false;
        for ( final JsonElement el : items ) {
            final JsonObject item = el.getAsJsonObject();
            if ( item.has( "slug" ) && PIN_PAGE.equals( item.get( "slug" ).getAsString() ) ) {
                foundPin = true;
                break;
            }
        }
        assertTrue( foundPin, "items must contain the pinned page " + PIN_PAGE + ": " + items );
    }

    @Test
    void restBriefingNoParams400() throws Exception {
        final HttpResponse< String > resp = get( "/api/briefing" );
        assertEquals( 400, resp.statusCode(), "expected 400 with no pins/clusters/prompt: " + resp.body() );
    }

    @Test
    void mcpGetBriefingReturnsMarkdown() {
        final McpSchema.CallToolResult result = mcp.callTool(
                new McpSchema.CallToolRequest( "get_briefing", Map.of( "pins", List.of( PIN_PAGE ) ) ) );
        assertFalse( Boolean.TRUE.equals( result.isError() ),
                "get_briefing must not return an error result: " + result );
        assertNotNull( result.content(), "get_briefing must return content" );
        assertFalse( result.content().isEmpty(), "get_briefing content must not be empty" );
        final McpSchema.Content first = result.content().get( 0 );
        assertTrue( first instanceof McpSchema.TextContent, "get_briefing must return TextContent: " + first );
        final String text = ( ( McpSchema.TextContent ) first ).text();
        assertTrue( text.startsWith( "# Wiki context briefing" ),
                "get_briefing markdown must start with the standard heading: " + text );
    }
}
