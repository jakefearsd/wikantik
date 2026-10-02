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
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level IT for native {@code [[ ]]} / {@code ![[ ]]} wikilinks: reference graph, rendering,
 * {@code ?format=md}, the embed endpoint's ACL gate, cache isolation between viewers of a
 * restricted embed, and rename rewriting.
 *
 * <p>Tests are ordered: {@link #seedPages()} creates the fixtures the rest read. Page names carry
 * a random suffix so reruns never collide; cleanup is best-effort.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class WikiLinksIT {

    private static final Gson GSON = new Gson();
    private static final String S = UUID.randomUUID().toString().substring( 0, 6 );
    private static final String TARGET = "WlTarget" + S;
    private static final String SECRET = "WlSecret" + S;
    private static final String LINKER = "WlLinker" + S;
    private static final String EMBEDS_SECRET = "WlEmbedsSecret" + S;
    private static final String RENAMED = "WlRenamed" + S;
    private static final String ALIAS = "wlit alias " + S;

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
                for ( final String p : List.of( TARGET, RENAMED, SECRET, LINKER, EMBEDS_SECRET ) ) {
                    delete( "/api/pages/" + p );
                }
            } finally {
                logoutAdmin();
            }
        } catch ( final Exception e ) {
            System.err.println( "WikiLinksIT.tearDown: best-effort cleanup failed - " + e );
        }
    }

    // -------------------------------------------------------------------------
    // The web.xml sets <secure>true</secure> on the session cookie.
    // Java's InMemoryCookieStore filters Secure cookies on plain http:// requests.
    // This wrapper fools the store into treating every URI as HTTPS so the
    // JSESSIONID cookie is always sent.
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private static HttpResponse< String > getString( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< byte[] > getBytes( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray() );
    }

    private static HttpResponse< String > put( final String path, final String jsonBody )
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< String > post( final String path, final String jsonBody )
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

    private static HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + path ) )
                        .header( "Accept", "application/json" )
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    // -------------------------------------------------------------------------
    // Auth helpers
    // -------------------------------------------------------------------------

    private static void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    /**
     * Seeded non-admin fixture user ({@code it-test-seed.sql}: role {@code Authenticated} only,
     * no Admin group membership; password is the literal {@code "password"}).
     */
    private static void loginAsNonAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "Alice", "password", "password" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Non-admin login should succeed: " + resp.body() );
    }

    private static void logoutAdmin() throws IOException, InterruptedException {
        final HttpResponse< String > resp = post( "/api/auth/logout", "{}" );
        assertEquals( 200, resp.statusCode(), "Logout should succeed: " + resp.body() );
    }

    private static HttpResponse< String > getHtmlOrJson( final String path ) throws IOException, InterruptedException {
        return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< String > pollUntil( final String what, final int seconds,
            final Supplier< HttpResponse< String > > call, final Predicate< HttpResponse< String > > ok )
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + seconds * 1000L;
        HttpResponse< String > last = null;
        while ( System.currentTimeMillis() < deadline ) {
            try {
                last = call.get();
                if ( ok.test( last ) ) {
                    return last;
                }
            } catch ( final RuntimeException e ) {
                System.err.println( "WikiLinksIT.pollUntil(" + what + "): " + e );
            }
            Thread.sleep( 500L );
        }
        throw new AssertionError( "Timed out after " + seconds + "s waiting for " + what + "; last="
                + ( last == null ? "none" : last.statusCode() + " " + last.body() ) );
    }

    private static JsonObject json( final HttpResponse< String > r ) {
        return JsonParser.parseString( r.body() ).getAsJsonObject();
    }

    private static HttpResponse< String > uncheckedGet( final String path ) {
        try {
            return getString( path );
        } catch ( final IOException e ) {
            throw new IllegalStateException( e );
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException( e );
        }
    }

    private static void save( final String name, final String content, final Map< String, Object > meta )
            throws Exception {
        final Map< String, Object > body = meta == null
                ? Map.of( "content", content ) : Map.of( "content", content, "metadata", meta );
        final HttpResponse< String > r = put( "/api/pages/" + name, GSON.toJson( body ) );
        assertEquals( 200, r.statusCode(), "Saving " + name + ": " + r.body() );
    }

    private static String renderedHtml( final String page ) {
        final HttpResponse< String > r = uncheckedGet( "/api/pages/" + page + "?render=true" );
        assertEquals( 200, r.statusCode(), "render of " + page + ": " + r.body() );
        return json( r ).get( "contentHtml" ).getAsString();
    }

    /** Logs in as the unprivileged viewer when anonymous cannot view ordinary pages. */
    private static boolean anonymousCanView() {
        return uncheckedGet( "/api/pages/" + LINKER ).statusCode() == 200;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    void seedPages() throws Exception {
        loginAsAdmin();
        try {
            save( TARGET, "## Section A\n\nalpha text\n", Map.of( "aliases", List.of( ALIAS ) ) );
            save( SECRET, "[{ALLOW view Admin}]\n\nclassified text\n", null );
            final String lower = TARGET.toLowerCase();
            pollUntil( "structural index to resolve " + lower, 60,
                    () -> uncheckedGet( "/api/pages?names=" + lower + "&resolve=true" ),
                    r -> r.statusCode() == 200 && json( r ).has( "resolved" )
                            && json( r ).getAsJsonObject( "resolved" ).has( lower )
                            && TARGET.equals( json( r ).getAsJsonObject( "resolved" ).get( lower ).getAsString() ) );
            save( LINKER, "See [[" + lower + "]] and [[" + ALIAS + "]].\n\n![[" + TARGET + "#Section A]]\n", null );
            save( EMBEDS_SECRET, "![[" + SECRET + "]]\n", null );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    @Order( 2 )
    void backlinksIncludeNativeLinks() throws Exception {
        pollUntil( "backlinks of " + TARGET + " to include " + LINKER, 60,
                () -> uncheckedGet( "/api/backlinks/" + TARGET ),
                r -> r.statusCode() == 200 && r.body().contains( "\"" + LINKER + "\"" ) );
    }

    @Test
    @Order( 3 )
    void renderedPageHasLinksAndEmbed() throws Exception {
        loginAsAdmin();
        try {
            final String html = renderedHtml( LINKER );
            assertTrue( html.contains( "href=\"" ) && html.contains( "/wiki/" + TARGET ), html );
            assertTrue( html.contains( "class=\"wiki-embed\"" ), html );
            assertTrue( html.contains( "alpha text" ), html );
        } finally {
            logoutAdmin();
        }
    }

    @Test
    @Order( 4 )
    void formatMdEmitsStandardLinks() throws Exception {
        final HttpResponse< String > r = getHtmlOrJson( "/wiki/" + LINKER + "?format=md" );
        if ( r.statusCode() != 200 ) {
            loginAsAdmin();
            try {
                assertMd( getHtmlOrJson( "/wiki/" + LINKER + "?format=md" ) );
            } finally {
                logoutAdmin();
            }
        } else {
            assertMd( r );
        }
    }

    private static void assertMd( final HttpResponse< String > r ) {
        assertEquals( 200, r.statusCode(), r.body() );
        final String b = r.body();
        assertTrue( b.contains( "/wiki/" + TARGET ), b );
        assertTrue( b.contains( "[Embedded: " + TARGET + " > Section A](" ), b );
        assertFalse( b.contains( "[[" ), b );
    }

    @Test
    @Order( 5 )
    void embedEndpointHonoursAcl() throws Exception {
        loginAsAdmin();
        try {
            final HttpResponse< String > r = getString( "/api/pages/" + SECRET + "/embed" );
            assertEquals( 200, r.statusCode(), r.body() );
            assertTrue( json( r ).get( "html" ).getAsString().contains( "classified text" ), r.body() );
        } finally {
            logoutAdmin();
        }
        assertEquals( 403, getString( "/api/pages/" + SECRET + "/embed" ).statusCode() );
    }

    @Test
    @Order( 6 )
    void restrictedEmbedIsNotSharedBetweenViewers() throws Exception {
        final boolean anon = anonymousCanView();
        if ( !anon ) {
            loginAsNonAdmin();
        }
        final String first = renderedHtml( EMBEDS_SECRET );
        assertTrue( first.contains( "wiki-embed-restricted" ), first );
        assertFalse( first.contains( "classified" ), first );
        if ( !anon ) {
            logoutAdmin();
        }

        loginAsAdmin();
        try {
            assertTrue( renderedHtml( EMBEDS_SECRET ).contains( "classified text" ) );
        } finally {
            logoutAdmin();
        }

        if ( !anon ) {
            loginAsNonAdmin();
        }
        try {
            final String again = renderedHtml( EMBEDS_SECRET );
            assertFalse( again.contains( "classified" ), again );
            assertTrue( again.contains( "wiki-embed-restricted" ), again );
        } finally {
            if ( !anon ) {
                logoutAdmin();
            }
        }
    }

    @Test
    @Order( 7 )
    void renameRewritesNativeLinks() throws Exception {
        loginAsAdmin();
        try {
            final HttpResponse< String > r = post( "/api/pages/" + TARGET + "/rename",
                    GSON.toJson( Map.of( "newName", RENAMED ) ) );
            assertEquals( 200, r.statusCode(), r.body() );
            final HttpResponse< String > page = getString( "/api/pages/" + LINKER );
            assertEquals( 200, page.statusCode(), page.body() );
            final String content = json( page ).get( "content" ).getAsString();
            assertTrue( content.contains( "[[" + RENAMED + "]]" ), content );
            assertTrue( content.contains( "![[" + RENAMED + "#Section A]]" ), content );
            assertTrue( content.contains( "[[" + ALIAS + "]]" ), content );
        } finally {
            logoutAdmin();
        }
    }
}
