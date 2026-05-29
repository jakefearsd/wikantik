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
import com.google.gson.reflect.TypeToken;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level integration test for the blog REST surface at {@code /api/blog/*}
 * ({@link com.wikantik.rest.BlogResource}).
 *
 * <p>Makes real HTTP calls against the Cargo-deployed Tomcat instance, mirroring
 * {@link CommentThreadIT}: JDK {@code HttpClient} with a Secure-cookie shim, Gson
 * for assertions, and {@code janne} (Admin group) as the authenticated owner. Blog
 * storage is filesystem-backed ({@code pageDir/blog/<username>/}) and does not use
 * the structural index, so no index-readiness wait is needed.
 *
 * <p>The lifecycle test deletes any blog left by a prior run first (the underlying
 * directory persists across runs in the module's pageDir), then exercises the full
 * happy path: create blog → list → get metadata → create entry → list entries
 * (bare array) → get entry (root title/date + frontmatter-free render) → update
 * entry → update blog home → delete entry → delete blog → 404. A second test
 * asserts anonymous blog creation is rejected with 401.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class BlogIT {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();
    private static final Type LIST_TYPE = new TypeToken< List< Map< String, Object > > >() { }.getType();

    /** janne's login lowercases to this — the blog directory + URL segment. */
    private static final String OWNER = "janne";

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
     * The web.xml sets {@code <secure>true</secure>} on the session cookie; Java's
     * cookie store filters Secure cookies on {@code http} URIs, so rewrite the scheme
     * to https when reading cookies. Copied from {@link CommentThreadIT}.
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
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .GET().build(), HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String jsonBody ) throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > post( final String path, final String jsonBody ) throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .DELETE().build(), HttpResponse.BodyHandlers.ofString() );
    }

    private Map< String, Object > parseObj( final String body ) {
        return GSON.fromJson( body, MAP_TYPE );
    }

    private List< Map< String, Object > > parseList( final String body ) {
        return GSON.fromJson( body, LIST_TYPE );
    }

    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    // ---- full blog lifecycle, ordered so state flows across the steps ----

    @Test
    @Order( 1 )
    void blog_full_lifecycle() throws Exception {
        loginAsAdmin();

        // Clean slate — a prior run may have left blog/janne on disk (createBlog 409s otherwise).
        delete( "/api/blog/" + OWNER );

        // (a) Create the blog → 201, username echoed.
        final HttpResponse< String > createResp = post( "/api/blog", "{}" );
        assertEquals( 201, createResp.statusCode(), "POST create blog should return 201: " + createResp.body() );
        final Map< String, Object > created = parseObj( createResp.body() );
        assertEquals( Boolean.TRUE, created.get( "success" ), "create should report success" );
        assertEquals( OWNER, created.get( "username" ), "create should echo the owner login" );

        // (b) List blogs → janne present with authorFullName.
        final HttpResponse< String > listResp = get( "/api/blog" );
        assertEquals( 200, listResp.statusCode(), "GET blog list should be 200: " + listResp.body() );
        final List< Map< String, Object > > blogs = parseList( listResp.body() );
        assertTrue( blogs.stream().anyMatch( b -> OWNER.equals( b.get( "username" ) ) ),
                "blog list should include the owner: " + listResp.body() );

        // (c) Get blog metadata → carries title, entryCount, authorFullName.
        final HttpResponse< String > metaResp = get( "/api/blog/" + OWNER );
        assertEquals( 200, metaResp.statusCode(), "GET blog metadata should be 200: " + metaResp.body() );
        final Map< String, Object > meta = parseObj( metaResp.body() );
        assertEquals( OWNER, meta.get( "username" ) );
        assertNotNull( meta.get( "title" ), "metadata should include title" );
        assertTrue( meta.containsKey( "authorFullName" ), "metadata should include authorFullName: " + metaResp.body() );

        // (d) Create an entry with body content → 201, slug carries the topic.
        final HttpResponse< String > entryResp = post( "/api/blog/" + OWNER + "/entries",
                GSON.toJson( Map.of( "topic", "MyFirstPost", "content", "Hello body content." ) ) );
        assertEquals( 201, entryResp.statusCode(), "POST create entry should return 201: " + entryResp.body() );
        final Map< String, Object > entry = parseObj( entryResp.body() );
        final String slug = ( String ) entry.get( "name" );
        assertNotNull( slug, "entry create must return a name: " + entryResp.body() );
        assertTrue( slug.contains( "MyFirstPost" ), "entry slug should contain the topic: " + slug );

        // (e) List entries → BARE array (not { entries: [...] }), one entry with name/title/date.
        final HttpResponse< String > entriesResp = get( "/api/blog/" + OWNER + "/entries" );
        assertEquals( 200, entriesResp.statusCode(), "GET entries should be 200: " + entriesResp.body() );
        final List< Map< String, Object > > entries = parseList( entriesResp.body() );
        assertEquals( 1, entries.size(), "should have exactly one entry: " + entriesResp.body() );
        final Map< String, Object > listed = entries.get( 0 );
        assertTrue( ( ( String ) listed.get( "name" ) ).contains( "MyFirstPost" ), "listed name should contain topic" );
        assertEquals( "My First Post", listed.get( "title" ), "listed title should be the spaced form" );
        assertNotNull( listed.get( "date" ), "listed entry should carry a date" );

        // (f) Get the entry rendered → title/date at the ROOT, frontmatter-free HTML.
        final HttpResponse< String > getEntryResp = get( "/api/blog/" + OWNER + "/entries/" + slug + "?render=true" );
        assertEquals( 200, getEntryResp.statusCode(), "GET entry should be 200: " + getEntryResp.body() );
        final Map< String, Object > got = parseObj( getEntryResp.body() );
        assertEquals( "My First Post", got.get( "title" ), "entry response should carry a root-level title" );
        assertNotNull( got.get( "date" ), "entry response should carry a root-level date" );
        final String html = ( String ) got.get( "contentHtml" );
        assertNotNull( html, "rendered entry should include contentHtml" );
        assertFalse( html.contains( "title:" ), "rendered HTML must not leak YAML frontmatter: " + html );
        assertTrue( html.contains( "Hello body content" ), "rendered HTML should include the body: " + html );

        // (g) Update the entry → success.
        final HttpResponse< String > updResp = put( "/api/blog/" + OWNER + "/entries/" + slug,
                GSON.toJson( Map.of( "content", "Updated body content." ) ) );
        assertEquals( 200, updResp.statusCode(), "PUT entry should be 200: " + updResp.body() );
        assertEquals( Boolean.TRUE, parseObj( updResp.body() ).get( "success" ), "entry update should succeed" );

        // (h) Update the blog home → success, content round-trips.
        final HttpResponse< String > homeResp = put( "/api/blog/" + OWNER,
                GSON.toJson( Map.of( "content", "Fresh blog home body." ) ) );
        assertEquals( 200, homeResp.statusCode(), "PUT blog home should be 200: " + homeResp.body() );
        assertEquals( Boolean.TRUE, parseObj( homeResp.body() ).get( "success" ), "blog home update should succeed" );
        final Map< String, Object > homeAfter = parseObj( get( "/api/blog/" + OWNER ).body() );
        assertTrue( ( ( String ) homeAfter.get( "content" ) ).contains( "Fresh blog home body." ),
                "updated blog home content should be returned by GET" );

        // (i) Delete the entry → success, list empty.
        final HttpResponse< String > delEntry = delete( "/api/blog/" + OWNER + "/entries/" + slug );
        assertEquals( 200, delEntry.statusCode(), "DELETE entry should be 200: " + delEntry.body() );
        assertEquals( 0, parseList( get( "/api/blog/" + OWNER + "/entries" ).body() ).size(),
                "entries should be empty after delete" );

        // (j) Delete the blog → success, then metadata GET is 404.
        final HttpResponse< String > delBlog = delete( "/api/blog/" + OWNER );
        assertEquals( 200, delBlog.statusCode(), "DELETE blog should be 200: " + delBlog.body() );
        assertEquals( 404, get( "/api/blog/" + OWNER ).statusCode(),
                "GET blog after delete should be 404" );
    }

    // ---- negative: anonymous create must be 401 ----

    @Test
    @Order( 2 )
    void anonymous_create_is_unauthorized() throws Exception {
        final HttpClient anon = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .build();
        final HttpResponse< String > resp = anon.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/blog" ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( "{}" ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 401, resp.statusCode(),
                "Anonymous POST /api/blog must return 401: " + resp.body() );
    }
}
