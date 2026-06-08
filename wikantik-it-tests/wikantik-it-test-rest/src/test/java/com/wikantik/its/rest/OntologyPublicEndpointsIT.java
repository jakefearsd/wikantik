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

    private void rebuildAndAwaitIdle() throws IOException, InterruptedException {
        final HttpResponse< String > trig = post( "/admin/ontology/rebuild", "{}" );
        assertTrue( trig.statusCode() == 202 || trig.statusCode() == 409,
                "rebuild trigger: " + trig.statusCode() + " " + trig.body() );
        final long deadline = System.currentTimeMillis() + 30_000;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > st = get( "/admin/ontology/status" );
            final JsonObject body = JsonParser.parseString( st.body() ).getAsJsonObject();
            if ( "IDLE".equals( body.get( "state" ).getAsString() ) ) {
                return;
            }
            Thread.sleep( 500 );
        }
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
