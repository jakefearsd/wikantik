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

import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level IT for Obsidian block references: {@code ^id} anchors in rendered HTML, {@code [[Page#^id]]} links,
 * {@code GET /embed?section=^id}, {@code GET/POST /api/pages/{name}/blocks} (assign, version bump, stale 409), and
 * {@code ?format=md} keeping the {@code #^id} fragment while stripping the marker.
 *
 * <p>Tests are ordered: {@link #seedPages()} creates the fixtures the rest read. Page names carry a random suffix.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class BlockRefsIT {

    private static final Gson GSON = new Gson();
    private static final String S = UUID.randomUUID().toString().substring( 0, 6 );
    private static final String SELF = "BrSelf" + S;
    private static final String OTHER = "BrOther" + S;

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

    @AfterAll
    static void tearDown() {
        try {
            loginAsAdmin();
            try {
                delete( "/api/pages/" + SELF );
                delete( "/api/pages/" + OTHER );
            } finally {
                post( "/api/auth/logout", "{}" );
            }
        } catch ( final Exception e ) {
            System.err.println( "BlockRefsIT.tearDown: best-effort cleanup failed - " + e );
        }
    }

    // The web.xml sets <secure>true</secure> on the session cookie; present every URI as https to the cookie store.
    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri,
                    final Map< String, List< String > > requestHeaders ) throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), requestHeaders );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > responseHeaders ) throws IOException {
                cm.put( uri, responseHeaders );
            }
        };
    }

    private static HttpResponse< String > send( final String method, final String path, final String body )
            throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" );
        if ( body != null ) {
            b.header( "Content-Type", "application/json" );
        }
        b.method( method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString( body ) );
        return client.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< String > get( final String path ) throws IOException, InterruptedException {
        return send( "GET", path, null );
    }

    private static HttpResponse< String > put( final String path, final String body )
            throws IOException, InterruptedException {
        return send( "PUT", path, body );
    }

    private static HttpResponse< String > post( final String path, final String body )
            throws IOException, InterruptedException {
        return send( "POST", path, body );
    }

    private static HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
        return send( "DELETE", path, null );
    }

    private static void loginAsAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/login",
                GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ) );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    private static JsonObject json( final HttpResponse< String > r ) {
        return JsonParser.parseString( r.body() ).getAsJsonObject();
    }

    private static void save( final String name, final String content ) throws Exception {
        final HttpResponse< String > r = put( "/api/pages/" + name, GSON.toJson( Map.of( "content", content ) ) );
        assertEquals( 200, r.statusCode(), "Saving " + name + ": " + r.body() );
    }

    private static JsonObject blocks( final String page ) throws Exception {
        final HttpResponse< String > r = get( "/api/pages/" + page + "/blocks?limit=100" );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r );
    }

    private static JsonObject blockContaining( final JsonObject listing, final String text ) {
        for ( final var e : listing.getAsJsonArray( "blocks" ) ) {
            if ( e.getAsJsonObject().get( "text" ).getAsString().contains( text ) ) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError( "no block containing '" + text + "' in " + listing );
    }

    private static String assignBody( final int version, final int index ) {
        return GSON.toJson( Map.of( "version", version, "index", index ) );
    }

    @Test
    @Order( 1 )
    void seedPages() throws Exception {
        loginAsAdmin();
        save( SELF, "Para one ^abc\n\nUnmarked paragraph\n\nSee [[" + SELF + "#^abc]]\n" );
        save( OTHER, "Intro paragraph\n\nTail paragraph\n" );
    }

    @Test
    @Order( 2 )
    void renderedHtmlHasAnchorAndBlockLink() throws Exception {
        final HttpResponse< String > r = get( "/api/pages/" + SELF + "?render=true" );
        assertEquals( 200, r.statusCode(), r.body() );
        final String html = json( r ).get( "contentHtml" ).getAsString();
        assertTrue( html.contains( "id=\"^abc\"" ), html );
        assertTrue( Pattern.compile( "href=\"[^\"]*#\\^abc\"" ).matcher( html ).find(), html );
        assertFalse( html.contains( "Para one ^abc" ), "the marker text must not be rendered: " + html );
    }

    @Test
    @Order( 3 )
    void embedEndpointRendersJustTheBlock() throws Exception {
        final HttpResponse< String > r = get( "/api/pages/" + SELF + "/embed?section=%5Eabc" );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject o = json( r );
        assertFalse( o.get( "missing" ).getAsBoolean(), r.body() );
        final String html = o.get( "html" ).getAsString();
        assertTrue( html.contains( "Para one" ), html );
        assertFalse( html.contains( "Unmarked paragraph" ), "only the addressed block is embedded: " + html );

        final HttpResponse< String > none = get( "/api/pages/" + SELF + "/embed?section=%5Enope" );
        assertEquals( 200, none.statusCode(), none.body() );
        assertTrue( json( none ).get( "missing" ).getAsBoolean(), none.body() );
    }

    @Test
    @Order( 4 )
    void blocksListHasTheDocumentedShape() throws Exception {
        final JsonObject listing = blocks( SELF );
        assertTrue( listing.get( "version" ).getAsInt() >= 1, listing.toString() );
        assertTrue( listing.get( "canAssign" ).getAsBoolean(), listing.toString() );
        final JsonArray arr = listing.getAsJsonArray( "blocks" );
        assertTrue( arr.size() >= 3, listing.toString() );
        final JsonObject first = blockContaining( listing, "Para one" );
        assertEquals( "abc", first.get( "id" ).getAsString().replace( "^", "" ), first.toString() );
        assertEquals( "paragraph", first.get( "kind" ).getAsString(), first.toString() );
        final JsonObject unmarked = blockContaining( listing, "Unmarked paragraph" );
        assertTrue( unmarked.get( "id" ).isJsonNull(), unmarked.toString() );
        assertTrue( unmarked.has( "index" ), unmarked.toString() );
    }

    @Test
    @Order( 5 )
    void postAssignsAnIdAndBumpsTheVersionThenRefusesStale() throws Exception {
        final JsonObject listing = blocks( OTHER );
        final int v0 = listing.get( "version" ).getAsInt();
        final int index = blockContaining( listing, "Tail paragraph" ).get( "index" ).getAsInt();

        final HttpResponse< String > ok = post( "/api/pages/" + OTHER + "/blocks", assignBody( v0, index ) );
        assertEquals( 200, ok.statusCode(), ok.body() );
        final String id = json( ok ).get( "id" ).getAsString();
        assertTrue( id.matches( "[a-z0-9]{6}" ), "assigned id is 6 chars [a-z0-9]: " + id );
        assertTrue( json( ok ).get( "version" ).getAsInt() > v0, ok.body() );

        final JsonObject after = blocks( OTHER );
        assertEquals( id, blockContaining( after, "Tail paragraph" ).get( "id" ).getAsString(), after.toString() );
        final HttpResponse< String > page = get( "/api/pages/" + OTHER );
        assertTrue( page.body().contains( "Tail paragraph ^" + id ), page.body() );

        // The same POST again, still carrying the version it read before the first assign.
        final HttpResponse< String > stale = post( "/api/pages/" + OTHER + "/blocks", assignBody( v0, index ) );
        assertEquals( 409, stale.statusCode(), stale.body() );
        assertEquals( "stale", json( stale ).get( "reason" ).getAsString(), stale.body() );
    }

    @Test
    @Order( 6 )
    void formatMdKeepsTheBlockFragmentAndStripsTheMarker() throws Exception {
        final HttpResponse< String > r = get( "/wiki/" + SELF + "?format=md" );
        assertEquals( 200, r.statusCode(), r.body() );
        final String b = r.body();
        assertTrue( b.contains( "#^abc" ), "the block fragment survives: " + b );
        assertTrue( b.contains( "Para one" ), b );
        final Matcher marker = Pattern.compile( "Para one\\s+\\^abc" ).matcher( b );
        assertFalse( marker.find(), "the inline marker is stripped: " + b );
    }

    private static String draftBody( final String text, final Integer offset ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "text", text );
        if ( offset != null ) {
            o.addProperty( "offset", offset );
        }
        return o.toString();
    }

    @Test
    @Order( 7 )
    void draftListsTheBlocksOfUnsavedTextAsTheServerReadsThem() throws Exception {
        // the reader reads the indented ^b as code, so ^b belongs to "Other" — the editor's own parse once said otherwise
        final String text = "> Para\n>\n> [{ALLOW view Admin}]    ^b\n\nOther ^b\n";
        final HttpResponse< String > r = post( "/api/pages/" + SELF + "/blocks/draft", draftBody( text, null ) );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject other = blockContaining( json( r ), "Other" );
        assertEquals( "b", other.get( "id" ).getAsString(), r.body() );
        assertEquals( text.indexOf( "Other" ), other.get( "from" ).getAsInt(), r.body() );
        final JsonObject para = blockContaining( json( r ), "Para" );
        assertTrue( para.get( "id" ).isJsonNull(), r.body() );
    }

    @Test
    @Order( 8 )
    void draftAtAnOffsetAnswersTheEditThatGivesTheBlockAnIdAndSavesNothing() throws Exception {
        final int version = blocks( SELF ).get( "version" ).getAsInt();
        final String text = "First draft para.\n\nSecond draft para.\n";
        final HttpResponse< String > r = post( "/api/pages/" + SELF + "/blocks/draft",
                draftBody( text, text.indexOf( "Second" ) + 2 ) );
        assertEquals( 200, r.statusCode(), r.body() );
        final String id = json( r ).get( "id" ).getAsString();
        final JsonObject change = json( r ).getAsJsonObject( "change" );
        assertEquals( text.indexOf( "Second draft para." ) + "Second draft para.".length(), change.get( "from" ).getAsInt(), r.body() );
        assertEquals( " ^" + id, change.get( "insert" ).getAsString(), r.body() );
        assertEquals( version, blocks( SELF ).get( "version" ).getAsInt(), "a draft saves nothing" );
        final HttpResponse< String > none = post( "/api/pages/" + SELF + "/blocks/draft", draftBody( "# Heading\n", 2 ) );
        assertEquals( 409, none.statusCode(), none.body() );
        assertEquals( "no-block", json( none ).get( "reason" ).getAsString() );
    }
}
