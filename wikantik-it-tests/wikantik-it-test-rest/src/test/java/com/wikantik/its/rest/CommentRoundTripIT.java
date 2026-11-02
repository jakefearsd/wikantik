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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Obsidian {@code %% comments %%} are stripped from every reader surface for callers without edit; an editor is sent the
 * raw text and saves it back. This pins, against a deployed wiki, that the editor's round trip (GET, then PUT of what
 * was sent) silently drops neither the comment, the frontmatter nor the access rule, and that an anonymous reader is
 * shown none of the comment text on either reader surface.
 */
public class CommentRoundTripIT {

    private static final Gson GSON = new Gson();
    private static final String PAGE = "CommentRoundTrip" + UUID.randomUUID().toString().substring( 0, 8 );
    private static final String COMMENT = "%% secret note %%";
    private static final String CONTENT = "[{ALLOW view All}]\n[{ALLOW edit Admin}]\n\n"
            + "Intro with ==hl== text and a " + COMMENT + " in it.\n\nSecond paragraph.\n";

    private static String baseUrl;
    private static HttpClient editor;
    private static HttpClient anonymous;

    @BeforeAll
    static void setUp() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        editor = client();
        final HttpResponse< String > login = send( editor, "POST", "/api/auth/login", "application/json",
                GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ) );
        assertEquals( 200, login.statusCode(), "janne login: " + login.body() );
        anonymous = client();
    }

    private static HttpClient client() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        // The session cookie is Secure; the store would not send it over plain http without this.
        final CookieHandler secureOverHttp = new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > headers ) throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), headers );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > headers ) throws IOException {
                cm.put( uri, headers );
            }
        };
        return HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).cookieHandler( secureOverHttp ).build();
    }

    private static HttpResponse< String > send( final HttpClient c, final String method, final String path, final String accept,
                                                final String json ) throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) ).header( "Accept", accept );
        if ( json == null ) {
            b.GET();
        } else {
            b.header( "Content-Type", "application/json" ).method( method, HttpRequest.BodyPublishers.ofString( json ) );
        }
        return c.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private static JsonObject page( final HttpClient c ) throws Exception {
        final HttpResponse< String > resp = send( c, "GET", "/api/pages/" + PAGE, "application/json", null );
        assertEquals( 200, resp.statusCode(), resp.body() );
        return GSON.fromJson( resp.body(), JsonObject.class );
    }

    @Test
    void anEditorsRoundTripKeepsTheCommentAndAnonymousReadersNeverSeeIt() throws Exception {
        final HttpResponse< String > created = send( editor, "PUT", "/api/pages/" + PAGE, "application/json",
                GSON.toJson( Map.of( "content", CONTENT, "metadata", Map.of( "title", "Comment Round Trip", "type", "article" ) ) ) );
        assertEquals( 200, created.statusCode(), created.body() );

        // The editor is sent the raw text, comment included, and saves exactly what it was sent.
        final JsonObject sent = page( editor );
        assertTrue( sent.get( "content" ).getAsString().contains( COMMENT ), "the editor is sent the comment: " + sent );
        final JsonObject back = new JsonObject();
        back.addProperty( "content", sent.get( "content" ).getAsString() );
        back.add( "metadata", sent.getAsJsonObject( "metadata" ) );
        final HttpResponse< String > saved = send( editor, "PUT", "/api/pages/" + PAGE, "application/json", back.toString() );
        assertEquals( 200, saved.statusCode(), saved.body() );

        // The stored text, read back raw as the editor: nothing was lost.
        final JsonObject after = page( editor );
        final String body = after.get( "content" ).getAsString();
        assertTrue( body.contains( COMMENT ), "the comment survived the round trip: " + body );
        assertTrue( body.contains( "==hl==" ), "the highlight survived: " + body );
        assertTrue( body.contains( "[{ALLOW edit Admin}]" ), "the rule survived: " + body );
        assertEquals( "Comment Round Trip", after.getAsJsonObject( "metadata" ).get( "title" ).getAsString(),
                "the frontmatter survived" );
        // ?format=md is derived reader text for every caller (block markers and comments removed, like the
        // edge-cached SSR page); an editor's source of truth is /api/pages, asserted above.
        final HttpResponse< String > editorMd = send( editor, "GET", "/wiki/" + PAGE + "?format=md", "text/markdown", null );
        assertEquals( 200, editorMd.statusCode(), editorMd.body() );
        assertTrue( editorMd.body().contains( "Second paragraph." ), "control: the editor gets the page" );
        assertFalse( editorMd.body().contains( "secret note" ), "?format=md is reader text, even for an editor: " + editorMd.body() );

        // An anonymous reader is shown the page, without the comment text, on both reader surfaces.
        final HttpResponse< String > anonApi = send( anonymous, "GET", "/api/pages/" + PAGE, "application/json", null );
        assertEquals( 200, anonApi.statusCode(), anonApi.body() );
        assertTrue( anonApi.body().contains( "Second paragraph." ), "control: the reader gets the page" );
        assertFalse( anonApi.body().contains( "secret note" ), "no comment text via /api/pages: " + anonApi.body() );
        final HttpResponse< String > anonMd = send( anonymous, "GET", "/wiki/" + PAGE + "?format=md", "text/markdown", null );
        assertEquals( 200, anonMd.statusCode(), anonMd.body() );
        assertTrue( anonMd.body().contains( "Second paragraph." ), "control: the reader gets the page" );
        assertFalse( anonMd.body().contains( "secret note" ), "no comment text via ?format=md: " + anonMd.body() );
    }
}
