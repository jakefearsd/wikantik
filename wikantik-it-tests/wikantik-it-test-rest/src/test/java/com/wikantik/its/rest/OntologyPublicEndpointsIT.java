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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Phase 3 security centerpiece: the public RDF endpoints (/sparql, /export, /id)
 * must serve ACL-PUBLIC resources and must NEVER leak ACL-restricted ones. Seeds a
 * public page and a restricted page ([{ALLOW view Admin}]), rebuilds the ontology, then
 * asserts anonymously that the public page appears and the restricted page does not.
 */
public class OntologyPublicEndpointsIT {

    private static final Gson GSON = new Gson();
    private static final String PUBLIC_PAGE = "OntologyPubEndpointPg";
    private static final String SECRET_PAGE = "OntologySecretEndpointPg";

    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url",
                "http://localhost:18080/wikantik-it-test-rest" );
        client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() ).build();
    }

    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri,
                    final Map< String, List< String > > h ) throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), h );
            }
            @Override
            public void put( final URI uri, final Map< String, List< String > > h ) throws IOException {
                cm.put( uri, h );
            }
        };
    }

    private HttpResponse< String > send( final HttpClient c, final HttpRequest r )
            throws IOException, InterruptedException {
        return c.send( r, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return send( client, HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" ).GET().build() );
    }

    private HttpResponse< String > post( final String path, final String body )
            throws IOException, InterruptedException {
        return send( client, HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( body ) ).build() );
    }

    private HttpResponse< String > put( final String path, final String body )
            throws IOException, InterruptedException {
        return send( client, HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( body ) ).build() );
    }

    private HttpResponse< String > anon( final String path, final String accept )
            throws IOException, InterruptedException {
        final HttpClient fresh = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() ).build();
        return send( fresh, HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", accept ).GET().build() );
    }

    private HttpResponse< String > anonPostForm( final String path, final String form )
            throws IOException, InterruptedException {
        final HttpClient fresh = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() ).build();
        return send( fresh, HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/x-www-form-urlencoded" )
                .POST( HttpRequest.BodyPublishers.ofString( form ) ).build() );
    }

    private void loginAsAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/login",
                GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ) );
        assertEquals( 200, resp.statusCode(), "admin login: " + resp.body() );
    }

    private void logoutAdmin() throws IOException, InterruptedException {
        post( "/api/auth/logout", "{}" );
    }

    /**
     * Rebuilds the ontology so it provably contains the pages this test just saved.
     *
     * <p>Two orderings have to be forced, and getting either wrong makes the assertions
     * below test a stale dataset rather than a leak:</p>
     * <ol>
     *   <li>A full rebuild projects exactly the rows in {@code page_canonical_ids}, and
     *       that row is written asynchronously after the save returns. So we wait for
     *       both pages to surface in the structural index before triggering.</li>
     *   <li>Another IT class ({@code AdminOntologyRebuildIT}) fires a rebuild and
     *       deliberately does not wait for it. If that one is still RUNNING, our trigger
     *       is refused with 409 — and simply polling for IDLE would then observe
     *       <em>its</em> rebuild finishing, a snapshot taken before our pages existed.
     *       So we drain first and insist on a trigger that is actually accepted.</li>
     * </ol>
     */
    private void rebuildAndAwaitIdle() throws IOException, InterruptedException {
        awaitIndexed( PUBLIC_PAGE );
        awaitIndexed( SECRET_PAGE );

        final long deadline = System.currentTimeMillis() + 60_000;
        HttpResponse< String > trig = null;
        while ( System.currentTimeMillis() < deadline ) {
            awaitIdle();
            trig = post( "/admin/ontology/rebuild", "{}" );
            if ( trig.statusCode() == 202 ) {
                break;
            }
            assertEquals( 409, trig.statusCode(),
                    "rebuild trigger: " + trig.statusCode() + " " + trig.body() );
            Thread.sleep( 250 );
        }
        assertTrue( trig != null && trig.statusCode() == 202,
                "no rebuild was accepted within 60s; last trigger: "
                        + ( trig == null ? "(none)" : trig.statusCode() + " " + trig.body() ) );
        awaitIdle();
    }

    /** Blocks until the ontology rebuild coordinator reports IDLE. */
    private void awaitIdle() throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + 60_000;
        String state = "(never read)";
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > st = get( "/admin/ontology/status" );
            final JsonObject body = JsonParser.parseString( st.body() ).getAsJsonObject();
            state = body.get( "state" ).getAsString();
            if ( "IDLE".equals( state ) ) {
                return;
            }
            Thread.sleep( 250 );
        }
        fail( "ontology rebuild did not reach IDLE within 60s; last state=" + state );
    }

    /**
     * Blocks until {@code slug} has a canonical-id row, which is what a full rebuild
     * enumerates. The structural index is seeded asynchronously, so a page saved a
     * moment ago is not necessarily projectable yet.
     */
    private void awaitIndexed( final String slug ) throws IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + 60_000;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > resp = get( "/api/structure/sitemap" );
            if ( resp.statusCode() == 200 && resp.body().contains( "\"" + slug + "\"" ) ) {
                return;
            }
            Thread.sleep( 250 );
        }
        fail( "page '" + slug + "' never reached the structural index within 60s; "
                + "a full ontology rebuild would not project it" );
    }

    @Test
    void publicEndpointsServePublicResourcesAndHideRestricted() throws Exception {
        loginAsAdmin();
        try {
            put( "/api/pages/" + PUBLIC_PAGE, GSON.toJson( Map.of(
                    "content", "Public ontology endpoint body.", "changeNote", "OntologyPublicEndpointsIT" ) ) );
            put( "/api/pages/" + SECRET_PAGE, GSON.toJson( Map.of(
                    "content", "[{ALLOW view Admin}]\n\nSecret ontology endpoint body.",
                    "changeNote", "OntologyPublicEndpointsIT" ) ) );
            rebuildAndAwaitIdle();
        } finally {
            logoutAdmin();
        }

        // --- /export/graph.nt : public present, restricted absent (the core leak check) ---
        final HttpResponse< String > dump = anon( "/export/graph.nt", "application/n-triples" );
        assertEquals( 200, dump.statusCode(), "anonymous dump must be public: " + dump.statusCode() );
        assertEquals( "*", dump.headers().firstValue( "Access-Control-Allow-Origin" ).orElse( "" ),
                "public dump must send permissive CORS" );
        assertTrue( dump.body().contains( PUBLIC_PAGE ),
                "public page must appear in the dump" );
        assertTrue( !dump.body().contains( SECRET_PAGE ),
                "RESTRICTED page must NOT appear in the public dump (leak!): " + SECRET_PAGE );

        // --- /sparql GET (anonymous): public page url present, secret absent ---
        final String q = URLEncoder.encode(
                "SELECT ?u WHERE { ?s <https://schema.org/url> ?u }", StandardCharsets.UTF_8 );
        final HttpResponse< String > sel = anon( "/sparql?query=" + q, "application/sparql-results+json" );
        assertEquals( 200, sel.statusCode(), "anonymous SPARQL SELECT: " + sel.statusCode() + " " + sel.body() );
        assertTrue( sel.body().contains( PUBLIC_PAGE ), "public page url must be in SPARQL results" );
        assertTrue( !sel.body().contains( SECRET_PAGE ),
                "RESTRICTED page url must NOT be in SPARQL results (leak!)" );

        // --- POST /sparql (form) works without a CSRF token (public read-only) ---
        final HttpResponse< String > post = anonPostForm( "/sparql", "query=" + q );
        assertEquals( 200, post.statusCode(), "POST /sparql must succeed (CSRF-exempt): " + post.statusCode() );

        // --- /id/* is wired and returns 404 for an unknown resource (not 500) ---
        final HttpResponse< String > id404 = anon( "/id/page/NoSuchCanonicalId00000000", "text/turtle" );
        assertEquals( 404, id404.statusCode(), "/id of unknown resource should 404: " + id404.statusCode() );
    }
}
