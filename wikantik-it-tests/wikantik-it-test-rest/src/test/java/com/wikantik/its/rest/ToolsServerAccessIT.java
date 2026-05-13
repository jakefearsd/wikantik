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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level Cargo IT for the {@code /tools/*} OpenAPI tool-server access filter.
 *
 * <p>The IT runtime ships with a DB-backed {@code ApiKeyService} wired up but
 * no minted keys, no legacy {@code tools.access.keys}, no CIDR allowlist, and
 * no {@code tools.access.allowUnrestricted=true} flag. Per the filter logic
 * in {@link com.wikantik.tools.ToolsAccessFilter#ToolsAccessFilter}:
 * {@code failClosed = !hasLegacyAuth && !hasDbKeys && !allowUnrestricted}.
 * Because {@code hasDbKeys=true}, the filter does NOT fail closed — it
 * enters normal mode and rejects every unauthenticated request with HTTP
 * 403 {@code {"error":"Access denied"}}.</p>
 *
 * <p>That distinction matters: a regression that flips the gating to
 * "open" would surface as 200; a regression that breaks the DB-keys wiring
 * would flip to 503. Both are detectable by these tests since we pin the
 * exact 403 envelope.</p>
 *
 * <p>Previously the only coverage for the tools server was unit tests inside
 * the {@code wikantik-tools} module — there was no IT to confirm the servlet
 * was actually mounted, mapped, and gated by the access filter in a real
 * Tomcat container.</p>
 */
public class ToolsServerAccessIT {

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();
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

    /**
     * Anonymous {@code GET /tools/openapi.json} must be rejected with 403
     * and a discriminating JSON body. A regression that flipped the filter
     * to "open" would surface as 200; a regression that broke DB-key
     * wiring would flip to 503. Either drift fails this assertion.
     */
    @Test
    void openapiSpec_anonymousIs403WithJsonBody() throws Exception {
        final HttpResponse< String > resp = get( "/tools/openapi.json" );
        assertEquals( 403, resp.statusCode(),
                "Tools server must reject anonymous requests: got " + resp.statusCode() + " " + resp.body() );
        assertTrue( resp.body().contains( "Access denied" ),
                "403 body must say 'Access denied': " + resp.body() );
        // Content-Type must be JSON so callers can parse the envelope.
        final String contentType = resp.headers().firstValue( "content-type" ).orElse( "" );
        assertTrue( contentType.contains( "application/json" ),
                "403 body must be JSON: content-type=" + contentType );
    }

    /**
     * {@code GET /tools/page/Main} (a real tool endpoint) must also be
     * gated — verifying the filter is applied to the full {@code /tools/*}
     * pattern, not just the openapi spec path.
     */
    @Test
    void toolEndpoint_anonymousIs403() throws Exception {
        final HttpResponse< String > resp = get( "/tools/page/Main" );
        assertEquals( 403, resp.statusCode(),
                "Tool endpoints must reject anonymous requests: got " + resp.statusCode() + " " + resp.body() );
    }

    /**
     * {@code POST /tools/search_wiki} (write-side tool) must also be gated.
     * Confirms the filter intercepts POSTs too, not just GETs.
     */
    @Test
    void searchWikiTool_postAnonymousIs403() throws Exception {
        final HttpResponse< String > resp = post( "/tools/search_wiki",
                "{\"query\":\"hello\"}" );
        assertEquals( 403, resp.statusCode(),
                "POST /tools/search_wiki must be 403 without auth: got " + resp.statusCode() + " " + resp.body() );
    }

    /**
     * A bogus {@code Authorization: Bearer ...} header must not bypass
     * the gating — the filter must verify the key against the DB / legacy
     * key list, and reject when neither path matches.
     */
    @Test
    void bogusBearerTokenIsRejected() throws Exception {
        final HttpResponse< String > resp = client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/tools/openapi.json" ) )
                        .header( "Accept", "application/json" )
                        .header( "Authorization", "Bearer not-a-real-key" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, resp.statusCode(),
                "Bogus bearer must be rejected: got " + resp.statusCode() + " " + resp.body() );
        assertTrue( resp.body().contains( "Access denied" ),
                "Bogus-bearer 403 body must say 'Access denied': " + resp.body() );
    }
}
