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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The end-to-end proof of {@code KgChangeEvent}-driven incremental ontology entity sync:
 * a node curated through the admin Knowledge Graph REST surface must become visible to
 * anonymous {@code /sparql} within a bounded window WITHOUT any ontology rebuild being
 * triggered. Startup's {@code rebuildIfEmpty} ran long before this test creates the node,
 * and this test never calls {@code /admin/ontology/rebuild} — so only the
 * {@code KgChangeEvent -> OntologyEntitySync} path can make the node appear.
 *
 * <p>Mirrors {@link EdgeCurationIT}'s auth/cookie scaffolding and node-creation call, and
 * {@link OntologyPublicEndpointsIT}'s anonymous-SPARQL helper + deadline-poll shape.</p>
 */
public class KgIncrementalOntologySyncIT {

    private static final Gson GSON = new Gson();

    private static String baseUrl;
    private static HttpClient client;

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

    /** Anonymous request on a fresh, cookie-less client (mirrors OntologyPublicEndpointsIT#anon). */
    private HttpResponse< String > anon( final String path, final String accept )
            throws IOException, InterruptedException {
        final HttpClient fresh = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        return fresh.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", accept )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    /** Authenticates the shared {@link #client} cookie jar as the admin user. */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    /** Creates a KG node via the same admin surface {@code EdgeCurationIT} uses. */
    private void createNode( final String name, final String nodeType )
            throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/admin/knowledge-graph/nodes",
            GSON.toJson( Map.of( "name", name, "node_type", nodeType ) ) );
        assertEquals( 200, resp.statusCode(), "Create node " + name + ": " + resp.body() );
    }

    /**
     * The end-to-end proof of incremental sync: a freshly curated node becomes visible
     * to anonymous /sparql WITHOUT any ontology rebuild being triggered. Startup's
     * rebuildIfEmpty ran long before this test creates the node, so only the
     * KgChangeEvent -> OntologyEntitySync path can make it appear.
     */
    @Test
    void curatedNodeAppearsInSparqlWithoutRebuild() throws Exception {
        loginAsAdmin();
        final String nodeName = "IncrementalSyncProbe" + System.currentTimeMillis();
        // Create the node via the same admin surface EdgeCurationIT uses (mirror its
        // node-creation call exactly — endpoint, body shape, and status assertion).
        createNode( nodeName, "concept" );

        final String q = java.net.URLEncoder.encode(
            "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#label> \"" + nodeName + "\" }",
            java.nio.charset.StandardCharsets.UTF_8 );
        final long deadline = System.currentTimeMillis() + 30_000;
        int lastStatus = -1;
        String lastBody = "";
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > sel = anon( "/sparql?query=" + q, "application/sparql-results+json" );
            lastStatus = sel.statusCode();
            lastBody = sel.body();
            // The query filters on the label in its WHERE clause and only projects ?s, so
            // the label text itself never appears in the JSON — a non-empty bindings array
            // (rather than a raw substring check) is the correct "found it" signal.
            if ( sel.statusCode() == 200 && hasBindings( sel.body() ) ) {
                return; // incremental sync delivered the entity — pass
            }
            Thread.sleep( 500 );
        }
        fail( "curated node '" + nodeName + "' never appeared in /sparql within 30s "
            + "(incremental entity sync did not fire; check KgChangeEvent wiring). "
            + "Last response: status=" + lastStatus + " body=" + lastBody );
    }

    /** True iff a SPARQL SELECT JSON response's {@code results.bindings} array is non-empty. */
    private static boolean hasBindings( final String sparqlResultsJson ) {
        final JsonObject root = JsonParser.parseString( sparqlResultsJson ).getAsJsonObject();
        return root.getAsJsonObject( "results" ).getAsJsonArray( "bindings" ).size() > 0;
    }
}
