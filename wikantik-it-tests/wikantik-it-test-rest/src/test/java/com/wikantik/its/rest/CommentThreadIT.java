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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level integration test for the anchored comment-thread REST surface at
 * {@code /api/comment-threads/*} ({@link com.wikantik.rest.CommentThreadResource}).
 * <p>
 * Makes real HTTP calls against the Cargo-deployed Tomcat instance, mirroring
 * {@code RestApiIT}: {@code java.net.http.HttpClient} (JDK 21) with a Secure-cookie
 * shim, Gson into {@code Map<String,Object>} for assertions, and {@code janne}
 * (Admin group, holds the {@code comment} permission) as the authenticated user.
 * <p>
 * Anchored comments are keyed by {@code canonical_id}, which the resource resolves
 * server-side from the page slug via the structural index. So the test first seeds
 * a page (PUT) and waits for the structural index to report UP, guaranteeing the
 * slug resolves before exercising the thread lifecycle.
 * <p>
 * Sequence: create thread → list open → reply → list (2 comments) → resolve →
 * list resolved/open → reopen → list open. Plus the negative case: GET with no
 * {@code page} param must be HTTP 400.
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class CommentThreadIT {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();

    /** A page seeded by this test; lives in the structural index so its slug resolves. */
    private static final String PAGE_SLUG = "CommentThreadITPage";
    private static final String ANCHOR_EXACT = "the quick brown fox";

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
     * The web.xml sets {@code <secure>true</secure>} on the session cookie. Java's
     * cookie store filters Secure cookies on {@code http} URIs, so this shim rewrites
     * the scheme to https when reading cookies, ensuring JSESSIONID is always sent.
     * Copied from {@code RestApiIT} to keep the two suites self-contained.
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
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .GET()
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > put( final String path, final String jsonBody ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > post( final String path, final String jsonBody ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( jsonBody ) )
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private HttpResponse< String > delete( final String path ) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" )
                .DELETE()
                .build();
        return client.send( request, HttpResponse.BodyHandlers.ofString() );
    }

    private Map< String, Object > parseJson( final String body ) {
        return GSON.fromJson( body, MAP_TYPE );
    }

    /**
     * Authenticates the shared {@link #client} cookie jar as {@code janne}
     * (member of the Admin group in the IT XML group DB; the Admin policy grant
     * confers the {@code comment} permission). Subsequent requests carry the
     * session cookie.
     */
    private void loginAsAdmin() throws IOException, InterruptedException {
        final String loginBody = GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) );
        final HttpResponse< String > resp = post( "/api/auth/login", loginBody );
        assertEquals( 200, resp.statusCode(), "Admin login should succeed: " + resp.body() );
    }

    /**
     * The structural-spine service rebuilds asynchronously at startup; a freshly
     * PUT page only becomes slug-resolvable once the index has picked it up. Poll
     * the health endpoint until UP (30s budget — far longer than the test corpus
     * needs). Mirrors {@code RestApiIT.waitForStructuralIndexUp}.
     */
    private void waitForStructuralIndexUp() throws Exception {
        for ( int attempt = 0; attempt < 30; attempt++ ) {
            final HttpResponse< String > resp = get( "/api/health/structural-index" );
            if ( resp.statusCode() == 200 && resp.body().matches( "(?s).*\"status\"\\s*:\\s*\"UP\".*" ) ) {
                return;
            }
            Thread.sleep( 1000 );
        }
        throw new AssertionError( "Structural index never reached UP within 30s" );
    }

    @SuppressWarnings( "unchecked" )
    private List< Map< String, Object > > threadsFor( final String status ) throws Exception {
        final HttpResponse< String > resp = get(
                "/api/comment-threads?page=" + PAGE_SLUG + "&status=" + status );
        assertEquals( 200, resp.statusCode(), "GET threads (" + status + ") should be 200: " + resp.body() );
        final Map< String, Object > json = parseJson( resp.body() );
        assertEquals( PAGE_SLUG, json.get( "page" ), "page echo should match slug" );
        final Object threads = json.get( "threads" );
        assertNotNull( threads, "response must carry a threads array: " + resp.body() );
        return ( List< Map< String, Object > > ) threads;
    }

    // ---- full lifecycle, ordered so state flows across the steps ----

    @Test
    @Order( 1 )
    @SuppressWarnings( "unchecked" )
    void thread_create_reply_resolve_reopen_lifecycle() throws Exception {
        loginAsAdmin();

        // Seed a page so the structural index can resolve its canonical_id from the slug.
        final HttpResponse< String > putResp = put( "/api/pages/" + PAGE_SLUG,
                GSON.toJson( Map.of(
                        "content", "Body containing " + ANCHOR_EXACT + " for anchoring a comment.",
                        "changeNote", "seed for CommentThreadIT" ) ) );
        assertEquals( 200, putResp.statusCode(), "Seeding the page should return 200: " + putResp.body() );
        waitForStructuralIndexUp();

        // (a) Create a thread.
        final HttpResponse< String > createResp = post( "/api/comment-threads?page=" + PAGE_SLUG,
                GSON.toJson( Map.of(
                        "exact", ANCHOR_EXACT,
                        "prefix", "",
                        "suffix", "",
                        "text", "first comment" ) ) );
        assertEquals( 200, createResp.statusCode(),
                "POST create thread should return 200: " + createResp.body() );
        final Map< String, Object > created = parseJson( createResp.body() );
        final String threadId = ( String ) created.get( "id" );
        assertNotNull( threadId, "created thread must carry an id: " + createResp.body() );
        assertEquals( "open", created.get( "status" ), "new thread status must be open" );
        final Map< String, Object > anchor = ( Map< String, Object > ) created.get( "anchor" );
        assertNotNull( anchor, "created thread must carry an anchor" );
        assertEquals( ANCHOR_EXACT, anchor.get( "exact" ), "anchor.exact must round-trip" );

        // (b) List open → exactly 1 thread, anchor matches.
        List< Map< String, Object > > open = threadsFor( "open" );
        assertEquals( 1, open.size(), "expected exactly 1 open thread; got " + open );
        assertEquals( ANCHOR_EXACT,
                ( ( Map< String, Object > ) open.get( 0 ).get( "anchor" ) ).get( "exact" ),
                "listed thread anchor.exact must match" );

        // (c) Reply, then verify the thread now has 2 comments with the reply last.
        final HttpResponse< String > replyResp = post(
                "/api/comment-threads/" + threadId + "/comments",
                GSON.toJson( Map.of( "text", "a reply" ) ) );
        assertEquals( 200, replyResp.statusCode(), "POST reply should return 200: " + replyResp.body() );

        open = threadsFor( "open" );
        assertEquals( 1, open.size(), "still exactly 1 open thread after reply; got " + open );
        final List< Map< String, Object > > comments =
                ( List< Map< String, Object > > ) open.get( 0 ).get( "comments" );
        assertNotNull( comments, "thread must carry comments" );
        assertEquals( 2, comments.size(), "thread must have 2 comments after reply; got " + comments );
        assertEquals( "a reply", comments.get( 1 ).get( "body" ),
                "second comment body must be the reply" );

        // (d) Resolve → resolved list has 1, open list has 0.
        final HttpResponse< String > resolveResp = post(
                "/api/comment-threads/" + threadId + "/resolve", "{}" );
        assertEquals( 200, resolveResp.statusCode(),
                "POST resolve should return 200: " + resolveResp.body() );
        assertEquals( 1, threadsFor( "resolved" ).size(), "resolved list should have 1 thread" );
        assertEquals( 0, threadsFor( "open" ).size(), "open list should be empty after resolve" );

        // (e) Reopen → open list has 1 again.
        final HttpResponse< String > reopenResp = post(
                "/api/comment-threads/" + threadId + "/reopen", "{}" );
        assertEquals( 200, reopenResp.statusCode(),
                "POST reopen should return 200: " + reopenResp.body() );
        assertEquals( 1, threadsFor( "open" ).size(), "open list should have 1 thread after reopen" );

        // (f) Moderator deletes the thread → 200 {deleted:true}, list empty across all filters.
        final HttpResponse< String > deleteResp = delete( "/api/comment-threads/" + threadId );
        assertEquals( 200, deleteResp.statusCode(),
                "DELETE thread should return 200 for an admin caller: " + deleteResp.body() );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > delJson = parseJson( deleteResp.body() );
        assertEquals( Boolean.TRUE, delJson.get( "deleted" ),
                "DELETE response should include deleted:true: " + deleteResp.body() );
        assertEquals( 0, threadsFor( "all" ).size(), "no threads should remain after delete" );
    }

    // ---- negative: GET without ?page must be a 400 ----

    @Test
    @Order( 2 )
    void list_without_page_param_is_bad_request() throws Exception {
        final HttpResponse< String > resp = get( "/api/comment-threads" );
        assertEquals( 400, resp.statusCode(),
                "GET /api/comment-threads with no page param must return 400: " + resp.body() );
    }

    // ---- @-mention end-to-end: janne mentions a seeded user; that user sees the row ----

    /**
     * Wire-level cross-user smoke for the {@code @<login>} pipeline.
     *
     * <p>Uses the pre-seeded {@code Alice} from the IT XML user database
     * ({@code wikantik-selenide-tests/src/test/resources/userdatabase.xml}).
     * Alice's {@code loginName}, {@code wikiName}, and {@code fullName} are all
     * the literal string {@code "Alice"}, which matters: the {@link MentionService}
     * keys rows by login name, while {@link com.wikantik.api.core.Session#getUserPrincipal()}
     * returns the full-name principal (preferred) — for Alice those are identical,
     * so the round-trip works. Creating a fresh user via {@code POST /admin/users}
     * is not a viable substitute here: the admin endpoint synthesises {@code fullName}
     * from the supplied display name (e.g. {@code "IT Target …"}), so the read-side
     * {@code currentUser()} would query against the full name and miss the mention
     * row written for the login.
     *
     * <ol>
     *   <li>As {@code janne}, seed a dedicated page and wait for the structural
     *       index so the slug resolves server-side to a {@code canonical_id}.</li>
     *   <li>POST a thread on that page with body {@code @Alice please review} —
     *       janne is the author, so the {@link MentionService} writes a single
     *       row keyed to {@code Alice} (authors are filtered out of their own
     *       direct-mention writes).</li>
     *   <li>Log into a fresh {@link HttpClient} (isolated cookie jar) as Alice
     *       so {@code /api/me/mentions} sees her session, not janne's.</li>
     *   <li>GET {@code /api/me/mentions?status=unread} — assert 200, a
     *       {@code mentions} array with at least one entry, and the comment
     *       snippet round-tripped in the body.</li>
     *   <li>GET {@code /api/me/mentions/unread-count} — assert 200 and a positive
     *       {@code count} via a permissive regex (the lower bound is 1; allow any
     *       positive integer in case multiple test runs pile up unread rows for
     *       Alice and no cleanup has run).</li>
     * </ol>
     */
    @Test
    @Order( 3 )
    @SuppressWarnings( "unchecked" )
    void mention_in_comment_appears_in_my_mentions_feed() throws Exception {
        loginAsAdmin();

        // (1) Seed a dedicated page so slug → canonical_id resolves at thread-create time.
        final String mentionPage = "MentionITPage";
        final String anchor = "the quick brown fox";
        final HttpResponse< String > putResp = put( "/api/pages/" + mentionPage,
                GSON.toJson( Map.of(
                        "content", "Body containing " + anchor + " for anchoring a mention.",
                        "changeNote", "seed for mention_in_comment_appears_in_my_mentions_feed" ) ) );
        assertEquals( 200, putResp.statusCode(),
                "Seeding the page should return 200: " + putResp.body() );
        waitForStructuralIndexUp();

        // (2) janne creates a thread mentioning Alice. janne is the author and
        // filtered out of mentions; Alice is the sole row.
        final String targetLogin = "Alice";
        final String commentText = "@" + targetLogin + " please review";
        final HttpResponse< String > createResp = post(
                "/api/comment-threads?page=" + mentionPage,
                GSON.toJson( Map.of(
                        "exact", anchor,
                        "prefix", "",
                        "suffix", "",
                        "text", commentText ) ) );
        assertEquals( 200, createResp.statusCode(),
                "POST create thread should return 200: " + createResp.body() );

        // (3) Switch to Alice on a fresh client (independent cookie jar). The
        // password is the literal string "password" — see test-users.properties
        // in wikantik-main/src/test/resources for the convention shared with the
        // seeded SSHA hashes.
        final HttpClient targetClient = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();
        final String targetLoginBody = GSON.toJson( Map.of(
                "username", targetLogin, "password", "password" ) );
        final HttpResponse< String > targetLoginResp = targetClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/auth/login" ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( targetLoginBody ) )
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, targetLoginResp.statusCode(),
                "Login as '" + targetLogin + "' should succeed: " + targetLoginResp.body() );

        // (4) GET /api/me/mentions?status=unread on Alice's session.
        final HttpResponse< String > feed = targetClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/me/mentions?status=unread" ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, feed.statusCode(),
                "GET /api/me/mentions should return 200: " + feed.body() );
        // Snippet round-trip: the comment text lands in the JSON response body.
        assertTrue( feed.body().contains( commentText ),
                "feed should contain the comment snippet: " + feed.body() );
        // Shape: mentions array is present and non-empty.
        final Map< String, Object > feedJson = parseJson( feed.body() );
        final Object mentions = feedJson.get( "mentions" );
        assertNotNull( mentions, "response must carry a mentions array: " + feed.body() );
        final List< Map< String, Object > > mentionsList = ( List< Map< String, Object > > ) mentions;
        assertTrue( mentionsList.size() >= 1,
                "mentions list should have at least one entry: " + feed.body() );

        // (5) GET /api/me/mentions/unread-count — a positive integer.
        final HttpResponse< String > count = targetClient.send(
                HttpRequest.newBuilder()
                        .uri( URI.create( baseUrl + "/api/me/mentions/unread-count" ) )
                        .header( "Accept", "application/json" )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, count.statusCode(),
                "GET /api/me/mentions/unread-count should return 200: " + count.body() );
        assertTrue( count.body().matches( "(?s).*\"count\"\\s*:\\s*[1-9][0-9]*.*" ),
                "unread-count should be a positive integer: " + count.body() );
    }
}
