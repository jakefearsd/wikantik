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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

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
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Leak probe for the display-name work: no surface a visitor or an ordinary signed-in user can reach
 * may return another user's <em>login name</em>. With Google sign-in a login name is the user's email
 * address, so a leak here is a leak of personal data.
 *
 * <p>The fixture is an account whose login looks like the worst case, {@code leak-probe-<random>@example.test},
 * with a different display name, {@code Leak Probe <random>}. That account edits two pages, starts a
 * comment thread that mentions a second user, is mentioned back by that user, and is named in the access
 * rule of a public page. Then every public read surface is called as an anonymous visitor and as the second
 * signed-in non-admin, and the login must appear in none of the responses. Where a surface shows authorship
 * the display name must be there, so a surface that returns nothing cannot pass vacuously. A final test
 * proves the other direction: administrators, and editors of the page, still see the login.</p>
 *
 * <p>Pages: {@code editedPage} (two versions by the probe, no frontmatter author), {@code aclPage}
 * (version 1 plain, version 2 adds {@code [{ALLOW view All}]} and {@code [{ALLOW edit <login>}]}),
 * {@code trickyPage} (version 2 puts the directive inside an HTML block that opens with {@code <details>}
 * and contains a code fence: CommonMark ends such a block only at a blank line, so the fence is plain text and
 * the ACL manager's scan enforces the directive, which a scanner that treats every fence as code would miss.
 * The renderer does not parse a directive inside an HTML block, so its output must not carry it either) and {@code ontoPage} (frontmatter {@code author:} set to the login, to exercise the ontology's
 * {@code dct:creator} and the display substitution of the SSR data island; it carries an access rule so that
 * readers are not editors, because an editor is handed the frontmatter as written). Free text typed into a page
 * body or into the frontmatter of a page the caller may edit is shown as written (see the design's "Out of
 * scope"), so the sweeps use only the first three pages.</p>
 *
 * <p>The probe's email is its login (the worst case under Google sign-in), so one needle covers both. The
 * anonymous visitor is also checked for the second user's login.</p>
 *
 * <p>The knowledge MCP endpoint runs with {@code mcp.access.allowUnrestricted=true} in this module, so a
 * bearer key may or may not change the identity it runs as. That is fine for a leak probe: it tests what the
 * responses contain, not how the caller was authenticated.</p>
 *
 * <p>Settling: search, feeds, recent articles and the ontology are filled asynchronously. A surface that
 * must be present is polled until its marker appears and then fails if it never does; a best-effort
 * surface is reported as skipped (never as a silent pass) when its marker never appears.</p>
 */
public class DisplayNameLeakProbeIT {

    private static final Gson GSON = new Gson();
    private static final Duration SETTLE = Duration.ofSeconds( 45 );

    private static final String ADMIN_USER = "janne";
    private static final String ADMIN_PASSWORD = "myP@5sw0rd";
    private static final String PASSWORD_INITIAL = "ItLeakProbe1!";
    private static final String PASSWORD_CHANGED = "ItLeakProbe2!";

    private static String baseUrl;

    /** Random per run, so reruns against a long-lived database never collide. */
    private static String rand;
    private static String probeLogin;
    private static String probeDisplay;
    private static String viewerLogin;
    private static String viewerDisplay;
    /** Lower-cased login local part: matches the raw login, its %40-encoded form and its JSON/XML forms. */
    private static String needle;
    private static String marker;
    private static String editedPage;
    private static String aclPage;
    private static String ontoPage;
    private static String trickyPage;
    /** Embeds the tricky page ({@code ![[trickyPage]]}), so its rendered body reaches readers through another page. */
    private static String embedderPage;
    /**
     * Marked verified by the probe through the admin MCP's {@code mark_page_verified}, which records the change note
     * {@code "verified by <login>"} and the frontmatter {@code verified_by: <login>}. It carries an access rule so that
     * readers are not editors (an editor is handed the frontmatter as written).
     */
    private static String verifiedPage;
    private static String verifiedId;
    private static String editedId;
    private static String aclId;
    private static String ontoId;
    private static String trickyId;
    /** Lower-cased local part of the second user's login: only anonymous callers must never see it. */
    private static String viewerNeedle;
    private static String probeUid;
    private static String viewerUid;
    private static String viewerToolsKey;
    private static String viewerMcpKey;

    private static Client admin;
    private static Client probe;
    private static Client viewer;

    // ------------------------------------------------------------------ seeding

    @BeforeAll
    static void seed() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        rand = UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 10 );
        probeLogin = "leak-probe-" + rand + "@example.test";
        probeDisplay = "Leak Probe " + rand;
        viewerLogin = "leak-viewer-" + rand + "@example.test";
        viewerDisplay = "Leak Viewer " + rand;
        needle = ( "leak-probe-" + rand ).toLowerCase( Locale.ROOT );
        viewerNeedle = ( "leak-viewer-" + rand ).toLowerCase( Locale.ROOT );
        marker = "lpword" + rand;
        editedPage = "LeakProbeEdited" + rand;
        aclPage = "LeakProbeAcl" + rand;
        ontoPage = "LeakProbeOnto" + rand;
        trickyPage = "LeakProbeTricky" + rand;
        embedderPage = "LeakProbeEmbedder" + rand;
        verifiedPage = "LeakProbeVerified" + rand;

        admin = new Client().login( ADMIN_USER, ADMIN_PASSWORD );
        createAccount( probeLogin, probeDisplay );
        createAccount( viewerLogin, viewerDisplay );
        probe = signInAndChangePassword( probeLogin );
        viewer = signInAndChangePassword( viewerLogin );

        // editedPage: two versions by the probe. The quick brown fox is the anchor for the comment thread.
        savePage( probe, editedPage, "# Leak probe\n\nThe " + marker + " marker word. The quick brown fox jumps over the lazy dog.\n",
                "first version" );
        savePage( probe, editedPage, "# Leak probe\n\nThe " + marker + " marker word. The quick brown fox jumps over the lazy dog.\n\nSecond revision.\n",
                "second version" );

        // aclPage: version 2 names the probe in an access rule on a page everybody can still read.
        final String aclBody = "The " + marker + " access-rule fixture page.\n";
        savePage( probe, aclPage, aclBody, "first version" );
        savePage( probe, aclPage, "[{ALLOW view All}]\n[{ALLOW edit " + probeLogin + "}]\n\n" + aclBody, "adds an access rule" );

        // trickyPage: the enforced directive sits inside an HTML block whose first line is <details>, so the
        // fence in it is not code. Version 1 is plain, so the probe may save version 2 and then owns the page.
        final String trickyBody = "The " + marker + " tricky access-rule fixture page.\n";
        savePage( probe, trickyPage, trickyBody, "first version" );
        savePage( probe, trickyPage, "[{ALLOW view All}]\n\n<details>\n```\n[{ALLOW edit Admin," + probeLogin
                + "}]\n```\n</details>\n\n" + trickyBody, "adds an access rule inside an HTML block" );

        // embedderPage: a reader sees the tricky page's rendered body through the embed.
        savePage( probe, embedderPage, "Embeds the tricky page:\n\n![[" + trickyPage + "]]\n", "embeds the tricky page" );

        // ontoPage: the editor typed the login as the frontmatter author.
        savePage( probe, ontoPage, "---\ntype: article\nauthor: " + probeLogin + "\n---\n[{ALLOW view All}]\n[{ALLOW edit "
                + probeLogin + "}]\nOntology fixture " + marker + ".\n", "frontmatter author" );

        // verifiedPage: saved by the probe, then stamped verified with the probe as the verifier (the login an SSO
        // administrator's verification would record is an email address, exactly like the probe's).
        savePage( probe, verifiedPage, "[{ALLOW view All}]\n[{ALLOW edit " + probeLogin + "}]\nVerification fixture "
                + "lpverified" + rand + ".\n", "first version" );
        markVerifiedByTheProbe( verifiedPage );

        editedId = awaitCanonicalId( editedPage );
        aclId = awaitCanonicalId( aclPage );
        ontoId = awaitCanonicalId( ontoPage );
        trickyId = awaitCanonicalId( trickyPage );
        verifiedId = awaitCanonicalId( verifiedPage );

        probeUid = mentionableId( viewer, probeDisplay );
        viewerUid = mentionableId( probe, viewerDisplay );

        // The probe starts a thread mentioning the viewer; the viewer replies, mentioning the probe.
        final HttpResponse< String > thread = probe.post( "/api/comment-threads?page=" + editedPage, GSON.toJson( Map.of(
                "exact", "quick brown fox", "prefix", "", "suffix", "",
                "text", "Please review @[" + viewerDisplay + "](user:" + viewerUid + ")" ) ) );
        assertEquals( 200, thread.statusCode(), "probe starts a thread: " + thread.body() );
        final String threadId = JsonParser.parseString( thread.body() ).getAsJsonObject().get( "id" ).getAsString();
        final HttpResponse< String > reply = viewer.post( "/api/comment-threads/" + threadId + "/comments", GSON.toJson( Map.of(
                "text", "Thanks @[" + probeDisplay + "](user:" + probeUid + ")" ) ) );
        assertEquals( 200, reply.statusCode(), "viewer replies: " + reply.body() );

        viewerToolsKey = mintKey( viewer, "tools" );
        viewerMcpKey = mintKey( viewer, "mcp_read" );
    }

    @AfterAll
    static void cleanUp() {
        // Best effort: a failure here must not mask a test failure, but it is never swallowed silently.
        if ( admin == null ) {
            return;
        }
        for ( final String page : new String[] { editedPage, aclPage, trickyPage, embedderPage, ontoPage, verifiedPage } ) {
            quietly( "delete page " + page, () -> admin.delete( "/api/pages/" + page ) );
        }
        for ( final String login : new String[] { probeLogin, viewerLogin } ) {
            quietly( "delete account " + login.substring( 0, login.indexOf( '-', 5 ) ),
                    () -> admin.delete( "/admin/users/" + URLEncoder.encode( login, StandardCharsets.UTF_8 ) ) );
        }
    }

    private interface Call {
        HttpResponse< String > run() throws Exception;
    }

    private static void quietly( final String what, final Call call ) {
        try {
            final HttpResponse< String > resp = call.run();
            if ( resp.statusCode() >= 400 ) {
                System.err.println( "DisplayNameLeakProbeIT cleanup: " + what + " returned " + resp.statusCode() );
            }
        } catch ( final Exception e ) {
            System.err.println( "DisplayNameLeakProbeIT cleanup: " + what + " failed: " + e );
        }
    }

    private static void createAccount( final String login, final String display ) throws Exception {
        final HttpResponse< String > resp = admin.post( "/admin/users", GSON.toJson( Map.of(
                "loginName", login, "fullName", display, "email", login, "password", PASSWORD_INITIAL ) ) );
        assertEquals( 201, resp.statusCode(), "create account (display name '" + display + "'): " + resp.body() );
    }

    /** Admin-created accounts must change their password before any other call; do it as the real flow does. */
    private static Client signInAndChangePassword( final String login ) throws Exception {
        final Client client = new Client().login( login, PASSWORD_INITIAL );
        final HttpResponse< String > changed = client.put( "/api/auth/profile", GSON.toJson( Map.of(
                "currentPassword", PASSWORD_INITIAL, "newPassword", PASSWORD_CHANGED ) ) );
        assertEquals( 200, changed.statusCode(), "forced password change: " + changed.body() );
        return client;
    }

    private static void savePage( final Client as, final String page, final String content, final String note )
            throws Exception {
        final HttpResponse< String > resp = as.put( "/api/pages/" + page,
                GSON.toJson( Map.of( "content", content, "changeNote", note ) ) );
        assertEquals( 200, resp.statusCode(), "save " + page + " (" + note + "): " + resp.body() );
    }

    /** Looks a user up the way the mention picker does, as {@code as}, and returns the opaque id. */
    private static String mentionableId( final Client as, final String displayName ) throws Exception {
        final HttpResponse< String > resp = as.get( "/api/users/mentionable?q=" + enc( displayName ), "application/json" );
        assertEquals( 200, resp.statusCode(), "mentionable lookup of '" + displayName + "': " + resp.body() );
        final JsonArray users = JsonParser.parseString( resp.body() ).getAsJsonObject().getAsJsonArray( "users" );
        for ( final JsonElement e : users ) {
            final JsonObject u = e.getAsJsonObject();
            if ( displayName.equals( u.get( "displayName" ).getAsString() ) ) {
                assertFalse( u.has( "loginName" ) || u.has( "fullName" ),
                        "a mentionable entry carries only {id, displayName}: " + u );
                return u.get( "id" ).getAsString();
            }
        }
        throw new AssertionError( "'" + displayName + "' not offered by the mention picker: " + resp.body() );
    }

    /** The admin MCP's {@code mark_page_verified}, with the probe's login as the verifier. */
    private static void markVerifiedByTheProbe( final String page ) {
        final McpSyncClient adminMcp = mcpClient( "wikantik-admin-mcp", null );
        try {
            final String result = callTool( adminMcp, "mark_page_verified",
                    Map.of( "slugs", List.of( page ), "verifier", probeLogin ) );
            assertTrue( result.contains( "\"ok\":true" ), "mark_page_verified " + page + ": " + result );
        } finally {
            adminMcp.close();
        }
    }

    private static String mintKey( final Client as, final String scope ) throws Exception {
        final HttpResponse< String > resp = as.post( "/api/self/apikeys",
                GSON.toJson( Map.of( "label", "it-display-name-probe", "scope", scope ) ) );
        assertEquals( 201, resp.statusCode(), "mint a " + scope + " key: " + resp.body() );
        return JsonParser.parseString( resp.body() ).getAsJsonObject().get( "token" ).getAsString();
    }

    /** Waits for the structural index to know {@code slug} (it fills asynchronously) and returns its canonical id. */
    private static String awaitCanonicalId( final String slug ) throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > resp = admin.get( "/api/structure/sitemap", "application/json" );
            if ( resp.statusCode() == 200 ) {
                final JsonObject root = JsonParser.parseString( resp.body() ).getAsJsonObject();
                final JsonObject data = root.has( "data" ) ? root.getAsJsonObject( "data" ) : root;
                for ( final JsonElement e : data.getAsJsonArray( "pages" ) ) {
                    final JsonObject page = e.getAsJsonObject();
                    if ( slug.equals( page.get( "slug" ).getAsString() ) ) {
                        return page.get( "id" ).getAsString();
                    }
                }
            }
            Thread.sleep( 500 );
        }
        throw new AssertionError( "page '" + slug + "' never reached the structural index" );
    }

    private static String enc( final String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 );
    }

    // ------------------------------------------------------------------ HTTP plumbing

    /** One cookie jar = one browser session. The web.xml marks JSESSIONID Secure, so cookies are replayed as https. */
    private static final class Client {
        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() )
                .build();

        Client login( final String user, final String password ) throws Exception {
            final HttpResponse< String > resp = post( "/api/auth/login",
                    GSON.toJson( Map.of( "username", user, "password", password ) ) );
            assertEquals( 200, resp.statusCode(), "sign in: " + resp.body() );
            return this;
        }

        HttpResponse< String > get( final String path, final String accept ) throws Exception {
            return send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) ).header( "Accept", accept ).GET() );
        }

        HttpResponse< String > post( final String path, final String json ) throws Exception {
            return send( json( path ).POST( HttpRequest.BodyPublishers.ofString( json ) ) );
        }

        HttpResponse< String > put( final String path, final String json ) throws Exception {
            return send( json( path ).PUT( HttpRequest.BodyPublishers.ofString( json ) ) );
        }

        HttpResponse< String > delete( final String path ) throws Exception {
            return send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Accept", "application/json" ).DELETE() );
        }

        private HttpRequest.Builder json( final String path ) {
            return HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Content-Type", "application/json" ).header( "Accept", "application/json" );
        }

        private HttpResponse< String > send( final HttpRequest.Builder request ) throws Exception {
            return http.send( request.build(), HttpResponse.BodyHandlers.ofString() );
        }
    }

    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > h )
                    throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), h );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > h ) throws IOException {
                cm.put( uri, h );
            }
        };
    }

    // ------------------------------------------------------------------ assertions

    private static void assertNoLogin( final String where, final String body ) {
        assertNoNeedle( needle, where, body );
    }

    /** For callers who are not the second user: neither fixture account's login may appear. */
    private static void assertNoLogins( final String where, final String body ) {
        assertNoNeedle( needle, where, body );
        assertNoNeedle( viewerNeedle, where, body );
    }

    private static void assertNoNeedle( final String lowerCaseNeedle, final String where, final String body ) {
        final String haystack = body.toLowerCase( Locale.ROOT );
        final int at = haystack.indexOf( lowerCaseNeedle );
        if ( at >= 0 ) {
            final int from = Math.max( 0, at - 60 );
            final int to = Math.min( body.length(), at + lowerCaseNeedle.length() + 60 );
            fail( "the login name leaked through " + where + ": ..." + body.substring( from, to ).replace( '\n', ' ' ) + "..." );
        }
    }

    /**
     * Polls {@code path} until it answers 200 with {@code marker} in the body. Returns the last response seen,
     * which the caller inspects: it may be a non-200 or lack the marker when the surface never settled.
     */
    private static HttpResponse< String > awaitMarker( final Client who, final String path, final String accept,
            final String wanted ) throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        HttpResponse< String > last = who.get( path, accept );
        while ( !( last.statusCode() == 200 && last.body().contains( wanted ) ) && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 500 );
            last = who.get( path, accept );
        }
        return last;
    }

    // ------------------------------------------------------------------ the HTTP sweep

    /**
     * @param needsLogin   the surface is for signed-in users; an anonymous caller must get 401
     * @param marker       text that proves the response is the real thing and not an empty list
     * @param showsAuthor  the surface shows authorship, so the display name must be in the body
     * @param mustSettle   fail (rather than skip) when the marker never appears
     */
    private record Surface( String label, String path, String accept, boolean needsLogin, String marker,
                            boolean showsAuthor, boolean mustSettle ) {
    }

    private static List< Surface > httpSurfaces() {
        final String json = "application/json";
        final String html = "text/html";
        return List.of(
            new Surface( "GET /api/pages/{edited}", "/api/pages/" + editedPage, json, false, editedPage, true, true ),
            new Surface( "GET /api/pages/{edited}?version=1", "/api/pages/" + editedPage + "?version=1", json, false, editedPage, true, true ),
            new Surface( "GET /api/pages?names=", "/api/pages?names=" + editedPage + "," + aclPage, json, false, editedPage, true, true ),
            new Surface( "GET /api/history/{edited}", "/api/history/" + editedPage, json, false, probeDisplay, true, true ),
            new Surface( "GET /api/recent-changes", "/api/recent-changes", json, false, editedPage, true, true ),
            new Surface( "GET /api/search", "/api/search?q=" + marker, json, false, editedPage, true, false ),
            new Surface( "GET /api/pages/for-agent/{id}", "/api/pages/for-agent/" + editedId, json, false, editedPage, true, true ),
            new Surface( "GET /api/comment-threads", "/api/comment-threads?page=" + editedPage + "&status=all", json, false, editedPage, true, true ),
            new Surface( "GET /api/users/mentionable", "/api/users/mentionable?q=" + enc( "Leak Probe" ), json, true, probeDisplay, true, true ),
            new Surface( "GET /api/me/mentions", "/api/me/mentions?status=all", json, true, probeDisplay, true, true ),
            new Surface( "GET /wiki/{edited}?format=md", "/wiki/" + editedPage + "?format=md", "text/markdown", false, marker, false, true ),
            new Surface( "GET /wiki/{edited}?format=json", "/wiki/" + editedPage + "?format=json", json, false, marker, false, true ),
            // count=100 (the maximum): with the default 20 the fixture pages can fall off a busy IT wiki's feed.
            new Surface( "GET /feed.xml", "/feed.xml?count=100", "application/atom+xml", false, editedPage, true, false ),
            new Surface( "GET /api/recent-articles", "/api/recent-articles", json, false, editedPage, true, false ),
            // The page that names the probe in its access rule: the rule must be invisible to non-editors.
            new Surface( "GET /api/pages/{acl}", "/api/pages/" + aclPage, json, false, aclPage, true, true ),
            new Surface( "GET /api/pages/{acl}?version=2", "/api/pages/" + aclPage + "?version=2", json, false, aclPage, true, true ),
            new Surface( "GET /wiki/{acl}?format=md", "/wiki/" + aclPage + "?format=md", "text/markdown", false, marker, false, true ),
            new Surface( "GET /wiki/{acl}?format=json", "/wiki/" + aclPage + "?format=json", json, false, marker, false, true ),
            new Surface( "GET /api/history/{acl}", "/api/history/" + aclPage, json, false, probeDisplay, true, true ),
            new Surface( "GET /api/diff/{acl}?from=1&to=2", "/api/diff/" + aclPage + "?from=1&to=2", json, false, aclPage, false, true ),
            new Surface( "GET /api/pages/for-agent/{acl-id}", "/api/pages/for-agent/" + aclId, json, false, aclPage, false, true ),
            new Surface( "GET /api/search (access-rule page)", "/api/search?q=" + marker, json, false, aclPage, false, false ),
            // The page whose enforced directive hides inside an HTML block that a naive scanner reads as code.
            new Surface( "GET /api/pages/{tricky}", "/api/pages/" + trickyPage, json, false, trickyPage, true, true ),
            new Surface( "GET /api/pages/{tricky}?version=2", "/api/pages/" + trickyPage + "?version=2", json, false, trickyPage, true, true ),
            new Surface( "GET /wiki/{tricky}?format=md", "/wiki/" + trickyPage + "?format=md", "text/markdown", false, marker, false, true ),
            new Surface( "GET /wiki/{tricky}?format=json", "/wiki/" + trickyPage + "?format=json", json, false, marker, false, true ),
            new Surface( "GET /api/history/{tricky}", "/api/history/" + trickyPage, json, false, probeDisplay, true, true ),
            new Surface( "GET /api/diff/{tricky}?from=1&to=2", "/api/diff/" + trickyPage + "?from=1&to=2", json, false, trickyPage, false, true ),
            new Surface( "GET /api/pages/for-agent/{tricky-id}", "/api/pages/for-agent/" + trickyId, json, false, trickyPage, false, true ),
            new Surface( "GET /api/search (tricky page)", "/api/search?q=" + marker, json, false, trickyPage, false, false ),
            // Rendered surfaces of the tricky page: the renderer prints a directive inside an HTML block unless display
            // rendering parses the text without the rules the ACL manager enforces.
            new Surface( "GET /api/pages/{tricky}?render=true", "/api/pages/" + trickyPage + "?render=true", json, false, trickyPage, true, true ),
            new Surface( "GET /api/pages/{tricky}/preview", "/api/pages/" + trickyPage + "/preview", json, false, marker, false, true ),
            new Surface( "GET /api/pages/{tricky}/embed", "/api/pages/" + trickyPage + "/embed", json, false, marker, false, true ),
            new Surface( "GET /wiki/{embedder} (SSR HTML, ![[tricky]])", "/wiki/" + embedderPage, html, false, "tricky access-rule", false, true ),
            new Surface( "GET /feed.xml (tricky page)", "/feed.xml?count=100", "application/atom+xml", false, trickyPage, true, false ),
            // The server-rendered HTML a browser or crawler gets for a page: head, no-JS body and the
            // window.__WIKANTIK_PAGE__ data island. The island's metadata carries the frontmatter author.
            new Surface( "GET /wiki/{edited} (SSR HTML)", "/wiki/" + editedPage, html, false, marker, false, true ),
            new Surface( "GET /wiki/{acl} (SSR HTML)", "/wiki/" + aclPage, html, false, marker, false, true ),
            new Surface( "GET /wiki/{tricky} (SSR HTML)", "/wiki/" + trickyPage, html, false, marker, false, true ),
            new Surface( "GET /wiki/{onto} (SSR HTML, frontmatter author)", "/wiki/" + ontoPage, html, false, marker, true, true ) );
    }

    @TestFactory
    Stream< DynamicTest > anonymousVisitorNeverSeesTheLogin() {
        final Client anonymous = new Client();
        return httpSurfaces().stream().map( s -> dynamicTest( "anonymous: " + s.label(), () -> probe( anonymous, false, s ) ) );
    }

    @TestFactory
    Stream< DynamicTest > secondSignedInUserNeverSeesTheLogin() {
        return httpSurfaces().stream().map( s -> dynamicTest( "signed-in non-admin: " + s.label(), () -> probe( viewer, true, s ) ) );
    }

    /** The second user may see their own login (it is theirs); an anonymous visitor may see neither. */
    private static void assertNoForeignLogins( final String where, final String body, final boolean callerIsSecondUser ) {
        if ( callerIsSecondUser ) {
            assertNoLogin( where, body );
        } else {
            assertNoLogins( where, body );
        }
    }

    private void probe( final Client who, final boolean signedIn, final Surface s ) throws Exception {
        if ( s.needsLogin() && !signedIn ) {
            final HttpResponse< String > refused = who.get( s.path(), s.accept() );
            assertEquals( 401, refused.statusCode(), s.label() + " must demand a sign-in: " + refused.body() );
            assertNoLogins( s.label(), refused.body() );
            return;
        }
        final HttpResponse< String > resp = awaitMarker( who, s.path(), s.accept(), s.marker() );
        final boolean settled = resp.statusCode() == 200 && resp.body().contains( s.marker() );
        if ( !settled && !s.mustSettle() ) {
            // Whatever did come back still must not leak; then report the gap as a skip, not a pass.
            assertNoForeignLogins( s.label(), resp.body(), signedIn );
            assumeTrue( false, s.label() + " never showed '" + s.marker() + "' within " + SETTLE.toSeconds()
                    + "s (status " + resp.statusCode() + "); the absence check above was vacuous" );
        }
        assertEquals( 200, resp.statusCode(), s.label() + ": " + resp.body() );
        assertTrue( resp.body().contains( s.marker() ), s.label() + " should contain '" + s.marker() + "': " + resp.body() );
        assertNoForeignLogins( s.label(), resp.body(), signedIn );
        if ( s.showsAuthor() ) {
            assertTrue( resp.body().contains( probeDisplay ),
                    s.label() + " shows authorship, so it must show the display name '" + probeDisplay + "': " + resp.body() );
        }
    }

    // ------------------------------------------------------------------ the verifier of a verified page

    /**
     * {@code mark_page_verified} records the change note {@code "verified by <login>"}: page history, recent articles
     * and the for-agent projection name the verifier by display name, for an anonymous visitor and for the second user.
     */
    @TestFactory
    Stream< DynamicTest > theVerifierOfAVerifiedPageIsNamedByDisplayName() {
        final String note = "verified by " + probeDisplay;
        return Stream.of( new Client(), viewer ).flatMap( who -> {
            final boolean signedIn = who == viewer;
            final String label = signedIn ? "signed-in non-admin: " : "anonymous: ";
            return Stream.of(
                dynamicTest( label + "GET /api/history/{verified}", () -> {
                    final HttpResponse< String > resp = who.get( "/api/history/" + verifiedPage, "application/json" );
                    assertEquals( 200, resp.statusCode(), resp.body() );
                    assertTrue( resp.body().contains( note ), "the history shows '" + note + "': " + resp.body() );
                    assertNoForeignLogins( "GET /api/history/{verified}", resp.body(), signedIn );
                } ),
                dynamicTest( label + "GET /api/pages/for-agent/{verified-id}", () -> {
                    final HttpResponse< String > resp = who.get( "/api/pages/for-agent/" + verifiedId, "application/json" );
                    assertEquals( 200, resp.statusCode(), resp.body() );
                    assertTrue( resp.body().contains( note ), "recent_changes shows '" + note + "': " + resp.body() );
                    assertNoForeignLogins( "GET /api/pages/for-agent/{verified-id}", resp.body(), signedIn );
                } ),
                dynamicTest( label + "GET /api/recent-articles (verified page)", () -> {
                    final HttpResponse< String > resp = awaitMarker( who, "/api/recent-articles?count=100&include=" + verifiedPage,
                            "application/json", verifiedPage );
                    assertNoForeignLogins( "GET /api/recent-articles (verified page)", resp.body(), signedIn );
                    assumeTrue( resp.statusCode() == 200 && resp.body().contains( verifiedPage ), "recent articles never listed "
                            + verifiedPage + " within " + SETTLE.toSeconds() + "s; the absence check above was vacuous" );
                    assertTrue( resp.body().contains( note ), "the change note shows '" + note + "': " + resp.body() );
                } ) );
        } );
    }

    // ------------------------------------------------------------------ comment threads and mentions in detail

    @Test
    void commentThreadsResolveAuthorsAndMentionsToDisplayNames() throws Exception {
        final HttpResponse< String > resp = new Client().get(
                "/api/comment-threads?page=" + editedPage + "&status=all", "application/json" );
        assertEquals( 200, resp.statusCode(), resp.body() );
        final JsonObject root = JsonParser.parseString( resp.body() ).getAsJsonObject();
        final JsonObject thread = root.getAsJsonArray( "threads" ).get( 0 ).getAsJsonObject();

        assertEquals( probeDisplay, thread.get( "createdBy" ).getAsString() );
        assertEquals( probeUid, thread.get( "createdById" ).getAsString() );
        final JsonArray comments = thread.getAsJsonArray( "comments" );
        assertEquals( probeDisplay, comments.get( 0 ).getAsJsonObject().get( "author" ).getAsString() );
        assertEquals( viewerDisplay, comments.get( 1 ).getAsJsonObject().get( "author" ).getAsString() );
        assertEquals( viewerUid, comments.get( 1 ).getAsJsonObject().get( "authorId" ).getAsString() );

        final JsonObject mentions = root.getAsJsonObject( "mentions" );
        assertEquals( probeDisplay, mentions.get( probeUid ).getAsString(), "the mention map resolves the probe" );
        assertEquals( viewerDisplay, mentions.get( viewerUid ).getAsString(), "the mention map resolves the viewer" );
        assertNoLogins( "comment threads", resp.body() );
    }

    @Test
    void theCallersOwnUserIdMatchesTheIdThePickerOffers() throws Exception {
        final HttpResponse< String > me = probe.get( "/api/auth/user", "application/json" );
        assertEquals( 200, me.statusCode(), me.body() );
        assertEquals( probeUid, JsonParser.parseString( me.body() ).getAsJsonObject().get( "userId" ).getAsString(),
                "clients compare comments[].authorId with /api/auth/user userId to find their own comments" );

        final JsonObject anonymous = JsonParser.parseString( new Client().get( "/api/auth/user", "application/json" ).body() )
                .getAsJsonObject();
        assertTrue( !anonymous.has( "userId" ) || anonymous.get( "userId" ).isJsonNull(),
                "an anonymous caller has no userId: " + anonymous );
    }

    // ------------------------------------------------------------------ knowledge MCP and /tools

    private static McpSyncClient mcpClient( final String bearerOrNull ) {
        return mcpClient( "knowledge-mcp", bearerOrNull );
    }

    private static McpSyncClient mcpClient( final String endpoint, final String bearerOrNull ) {
        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final HttpClientStreamableHttpTransport.Builder transport = HttpClientStreamableHttpTransport.builder( prefix )
                .endpoint( endpoint )
                .connectTimeout( Duration.ofSeconds( 15 ) );
        if ( bearerOrNull != null ) {
            transport.httpRequestCustomizer( ( rb, method, uri, body, context ) ->
                    rb.header( "Authorization", "Bearer " + bearerOrNull ) );
        }
        final McpSyncClient client = McpClient.sync( transport.build() )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-display-name-probe", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
        client.initialize();
        return client;
    }

    private static String callTool( final McpSyncClient mcp, final String tool, final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = mcp.callTool( new McpSchema.CallToolRequest( tool, args ) );
        final String text = result.content() == null || result.content().isEmpty()
                ? "" : ( result.content().get( 0 ) instanceof McpSchema.TextContent tc ? tc.text() : "" );
        assertFalse( Boolean.TRUE.equals( result.isError() ), tool + " returned an error: " + text );
        return text;
    }

    /** The IT runs /knowledge-mcp with mcp.access.allowUnrestricted=true; a bearer key adds the viewer's identity where honoured. */
    private void assertMcpHidesTheLogin( final McpSyncClient mcp, final String who, final boolean anonymous ) {
        final java.util.function.BiConsumer< String, String > noLogin = ( where, body ) -> assertNoForeignLogins( where, body, !anonymous );
        final Set< String > tools = mcp.listTools().tools().stream().map( McpSchema.Tool::name ).collect( Collectors.toSet() );
        for ( final String required : List.of( "get_page", "read_pages", "get_page_for_agent", "list_pages" ) ) {
            assertTrue( tools.contains( required ), "knowledge MCP lacks " + required + ": " + tools );
        }

        final String page = callTool( mcp, "get_page", Map.of( "slug", editedPage ) );
        assertTrue( page.contains( editedPage ), who + " get_page: " + page );
        assertTrue( page.contains( probeDisplay ), who + " get_page shows the author's display name: " + page );
        noLogin.accept( who + " get_page", page );

        final String batch = callTool( mcp, "read_pages", Map.of( "slugs", List.of( editedPage, aclPage, trickyPage ) ) );
        assertTrue( batch.contains( aclPage ) && batch.contains( trickyPage ), who + " read_pages: " + batch );
        noLogin.accept( who + " read_pages (access-rule pages included)", batch );

        final String aclOnly = callTool( mcp, "get_page", Map.of( "slug", aclPage ) );
        assertTrue( aclOnly.contains( aclPage ), who + " get_page(acl): " + aclOnly );
        noLogin.accept( who + " get_page of the access-rule page", aclOnly );

        final String trickyOnly = callTool( mcp, "get_page", Map.of( "slug", trickyPage ) );
        assertTrue( trickyOnly.contains( trickyPage ), who + " get_page(tricky): " + trickyOnly );
        noLogin.accept( who + " get_page of the tricky access-rule page", trickyOnly );
        assertFalse( trickyOnly.contains( "[{ALLOW" ), who + " get_page of the tricky page must not carry its access rule: " + trickyOnly );

        // list_pages filters by the display name readers see, and returns it as the author.
        final String listed = callTool( mcp, "list_pages", Map.of( "author", probeDisplay ) );
        assertTrue( listed.contains( editedPage ) && listed.contains( probeDisplay ),
                who + " list_pages(author=display name) finds the probe's pages and shows the display name: " + listed );
        noLogin.accept( who + " list_pages", listed );

        final String projection = callTool( mcp, "get_page_for_agent", Map.of( "canonical_id", editedId ) );
        assertTrue( projection.contains( probeDisplay ), who + " get_page_for_agent shows recent_changes authors by display name: " + projection );
        noLogin.accept( who + " get_page_for_agent", projection );
        noLogin.accept( who + " get_page_for_agent (access-rule page)", callTool( mcp, "get_page_for_agent", Map.of( "canonical_id", aclId ) ) );
        noLogin.accept( who + " get_page_for_agent (tricky page)", callTool( mcp, "get_page_for_agent", Map.of( "canonical_id", trickyId ) ) );

        final String verified = callTool( mcp, "get_page_for_agent", Map.of( "canonical_id", verifiedId ) );
        assertTrue( verified.contains( "verified by " + probeDisplay ),
                who + " get_page_for_agent names the verifier of the verification change note by display name: " + verified );
        noLogin.accept( who + " get_page_for_agent (verified page)", verified );
    }

    @Test
    void knowledgeMcpHidesTheLoginFromAnUnauthenticatedClient() {
        final McpSyncClient mcp = mcpClient( null );
        try {
            assertMcpHidesTheLogin( mcp, "anonymous MCP", true );
        } finally {
            mcp.close();
        }
    }

    @Test
    void knowledgeMcpHidesTheLoginFromTheSecondUsersKey() {
        final McpSyncClient mcp = mcpClient( viewerMcpKey );
        try {
            assertMcpHidesTheLogin( mcp, "viewer MCP", false );
        } finally {
            mcp.close();
        }
    }

    @Test
    void retrieveContextHidesTheLoginWhenRetrievalIsWired() throws Exception {
        final McpSyncClient mcp = mcpClient( null );
        try {
            final Set< String > tools = mcp.listTools().tools().stream().map( McpSchema.Tool::name ).collect( Collectors.toSet() );
            assumeTrue( tools.contains( "retrieve_context" ),
                    "retrieve_context is not registered in this module (hybrid retrieval is not wired here); "
                            + "cover it in wikantik-it-test-dense instead" );
            final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
            String text = callTool( mcp, "retrieve_context", Map.of( "query", marker ) );
            while ( !text.contains( aclPage ) && System.currentTimeMillis() < deadline ) {
                Thread.sleep( 1000 );
                text = callTool( mcp, "retrieve_context", Map.of( "query", marker ) );
            }
            assumeTrue( text.contains( aclPage ),
                    "retrieve_context never returned the fixture pages (chunks not indexed in this module); "
                            + "the absence check would be vacuous" );
            assertNoLogins( "retrieve_context", text );
        } finally {
            mcp.close();
        }
    }

    @Test
    void toolsServerHidesTheLoginAndStaysClosedToAnonymousCallers() throws Exception {
        final HttpClient plain = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
        final HttpResponse< String > anonymous = plain.send( HttpRequest.newBuilder()
                .uri( URI.create( baseUrl + "/tools/page/" + editedPage ) ).GET().build(), HttpResponse.BodyHandlers.ofString() );
        assertEquals( 403, anonymous.statusCode(), "the tools server demands a key in this module: " + anonymous.body() );
        assertNoLogins( "/tools anonymous refusal", anonymous.body() );

        final HttpResponse< String > page = bearer( plain, "GET", "/tools/page/" + editedPage, null );
        assertEquals( 200, page.statusCode(), page.body() );
        assertTrue( page.body().contains( editedPage ) && page.body().contains( probeDisplay ),
                "get_page shows the page and its author's display name: " + page.body() );
        assertNoLogins( "/tools/page/{edited}", page.body() );

        final HttpResponse< String > aclPageBody = bearer( plain, "GET", "/tools/page/" + aclPage, null );
        assertEquals( 200, aclPageBody.statusCode(), aclPageBody.body() );
        assertTrue( aclPageBody.body().contains( aclPage ), aclPageBody.body() );
        assertNoLogins( "/tools/page/{acl}", aclPageBody.body() );

        final HttpResponse< String > trickyBody = bearer( plain, "GET", "/tools/page/" + trickyPage, null );
        assertEquals( 200, trickyBody.statusCode(), trickyBody.body() );
        assertTrue( trickyBody.body().contains( trickyPage ), trickyBody.body() );
        assertNoLogins( "/tools/page/{tricky}", trickyBody.body() );
        assertFalse( trickyBody.body().contains( "[{ALLOW" ), "no access rule text through the tools server: " + trickyBody.body() );

        HttpResponse< String > search = bearer( plain, "POST", "/tools/search_wiki", GSON.toJson( Map.of( "query", marker ) ) );
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        while ( !( search.statusCode() == 200 && search.body().contains( editedPage ) ) && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 500 );
            search = bearer( plain, "POST", "/tools/search_wiki", GSON.toJson( Map.of( "query", marker ) ) );
        }
        assertEquals( 200, search.statusCode(), search.body() );
        assertTrue( search.body().contains( editedPage ), "search_wiki finds the fixture page: " + search.body() );
        assertNoLogins( "/tools/search_wiki", search.body() );
    }

    private static HttpResponse< String > bearer( final HttpClient http, final String method, final String path, final String json )
            throws Exception {
        final HttpRequest.Builder rb = HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" ).header( "Authorization", "Bearer " + viewerToolsKey );
        if ( json == null ) {
            rb.GET();
        } else {
            rb.header( "Content-Type", "application/json" ).method( method, HttpRequest.BodyPublishers.ofString( json ) );
        }
        return http.send( rb.build(), HttpResponse.BodyHandlers.ofString() );
    }

    // ------------------------------------------------------------------ ontology

    @Test
    void ontologyEndpointsExposeTheDisplayNameNotTheLogin() throws Exception {
        rebuildOntologyAndAwaitIdle();
        final Client anonymous = new Client();

        final String query = enc( "SELECT ?s ?c WHERE { ?s <http://purl.org/dc/terms/creator> ?c }" );
        final HttpResponse< String > select = awaitMarker( anonymous, "/sparql?query=" + query, "application/sparql-results+json", probeDisplay );
        assertEquals( 200, select.statusCode(), select.body() );
        assertTrue( select.body().contains( probeDisplay ),
                "dct:creator of the fixture page is the display name (the frontmatter author was the login): " + select.body() );
        assertNoLogins( "/sparql dct:creator", select.body() );

        final HttpResponse< String > dump = awaitMarker( anonymous, "/export/graph.nt", "application/n-triples", probeDisplay );
        assertEquals( 200, dump.statusCode(), "dump status" );
        assertTrue( dump.body().contains( probeDisplay ), "the dump carries the creator's display name" );
        assertNoLogins( "/export/graph.nt", dump.body() );

        final HttpResponse< String > resource = anonymous.get( "/id/page/" + ontoId, "text/turtle" );
        assumeTrue( resource.statusCode() == 200 && resource.body().contains( probeDisplay ),
                "/id/page/{id} did not serve the fixture page (status " + resource.statusCode() + ")" );
        assertNoLogins( "/id/page/{id}", resource.body() );
    }

    private void rebuildOntologyAndAwaitIdle() throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis() + 15_000;
        HttpResponse< String > trigger = null;
        while ( System.currentTimeMillis() < deadline ) {
            awaitOntologyIdle();
            trigger = admin.post( "/admin/ontology/rebuild", "{}" );
            if ( trigger.statusCode() == 202 ) {
                break;
            }
            // 409: another IT's rebuild is still running; wait for it and trigger our own so it sees our pages.
            assertEquals( 409, trigger.statusCode(), "ontology rebuild trigger: " + trigger.body() );
            Thread.sleep( 250 );
        }
        assertTrue( trigger != null && trigger.statusCode() == 202, "no ontology rebuild was accepted in time" );
        awaitOntologyIdle();
    }

    private void awaitOntologyIdle() throws Exception {
        final long deadline = System.currentTimeMillis() + SETTLE.toMillis();
        String state = "(never read)";
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > status = admin.get( "/admin/ontology/status", "application/json" );
            state = JsonParser.parseString( status.body() ).getAsJsonObject().get( "state" ).getAsString();
            if ( "IDLE".equals( state ) ) {
                return;
            }
            Thread.sleep( 250 );
        }
        fail( "ontology rebuild did not reach IDLE; last state=" + state );
    }

    // ------------------------------------------------------------------ the other direction

    @Test
    void theLeakDetectorFiresOnAnAdminResponse() throws Exception {
        final HttpResponse< String > adminView = admin.get( "/admin/users/" + enc( probeLogin ), "application/json" );
        assertEquals( 200, adminView.statusCode(), adminView.body() );
        assertThrows( AssertionError.class, () -> assertNoLogin( "admin control", adminView.body() ),
                "the needle must match a real response that contains the login, or every absence check here is vacuous" );
    }

    @Test
    void administratorsAndEditorsStillSeeTheLogin() throws Exception {
        final HttpResponse< String > one = admin.get( "/admin/users/" + enc( probeLogin ), "application/json" );
        assertEquals( 200, one.statusCode(), one.body() );
        assertTrue( one.body().contains( probeLogin ), "/admin/users/{login} shows the login: " + one.body() );
        assertTrue( one.body().contains( probeDisplay ), "and the display name: " + one.body() );

        final HttpResponse< String > all = admin.get( "/admin/users", "application/json" );
        assertEquals( 200, all.statusCode(), all.body() );
        assertTrue( all.body().contains( probeLogin ), "/admin/users lists the login" );

        // The access rule is hidden from people who cannot edit the page, and plain to those who can.
        final String directive = "[{ALLOW edit " + probeLogin + "}]";
        for ( final Client editor : List.of( admin, probe ) ) {
            final HttpResponse< String > raw = editor.get( "/wiki/" + aclPage + "?format=md", "text/markdown" );
            assertEquals( 200, raw.statusCode(), raw.body() );
            assertTrue( raw.body().contains( directive ), "an editor sees the access rule as written: " + raw.body() );

            final HttpResponse< String > api = editor.get( "/api/pages/" + aclPage, "application/json" );
            assertEquals( 200, api.statusCode(), api.body() );
            assertTrue( api.body().contains( "ALLOW edit " + probeLogin ),
                    "GET /api/pages hands an editor the raw text, access rule included: " + api.body() );
        }
        final HttpResponse< String > apiNonEditor = viewer.get( "/api/pages/" + aclPage, "application/json" );
        assertEquals( 200, apiNonEditor.statusCode(), apiNonEditor.body() );
        assertFalse( apiNonEditor.body().contains( "ALLOW" ), "GET /api/pages hands a non-editor no access rule: " + apiNonEditor.body() );
        for ( final String page : List.of( aclPage, trickyPage ) ) {
            final HttpResponse< String > nonEditor = viewer.get( "/wiki/" + page + "?format=md", "text/markdown" );
            assertEquals( 200, nonEditor.statusCode(), nonEditor.body() );
            assertTrue( nonEditor.body().contains( marker ), "the page itself is still readable: " + nonEditor.body() );
            assertFalse( nonEditor.body().contains( "[{ALLOW" ), "no access rule text for a non-editor: " + nonEditor.body() );
        }
    }

    /**
     * Neither a signed-in non-editor nor an anonymous visitor may be handed access-rule text by the page API, for the page
     * whose directive sits in an HTML block.
     */
    @Test
    void thePageApiHandsNonEditorsNoAccessRuleTextForTheTrickyPage() throws Exception {
        for ( final Client reader : List.of( viewer, new Client() ) ) {
            for ( final String path : List.of( "/api/pages/" + trickyPage, "/api/pages/" + trickyPage + "?version=2" ) ) {
                final HttpResponse< String > resp = reader.get( path, "application/json" );
                assertEquals( 200, resp.statusCode(), resp.body() );
                assertFalse( resp.body().contains( "ALLOW" ), path + " must carry no access rule text: " + resp.body() );
            }
        }
    }

    /**
     * Rendering the tricky page first (server-side HTML, the rendered page API, a page that embeds it) never makes the
     * second user an editor of it, nor hands them its rule. (Who else may edit it is deliberately not asserted:
     * enforcement is unchanged from 2.4.57, where the ACL manager's text scan grants the probe edit but the render path,
     * which cannot see a rule inside an HTML block, sets an ACL without it on the instance it renders. That disagreement is
     * pre-existing and has its own issue; either reading leaves the second user without edit.)
     */
    @Test
    void renderingTheTrickyPageFirstNeverMakesTheSecondUserAnEditor() throws Exception {
        final Client anonymous = new Client();
        assertEquals( 200, anonymous.get( "/wiki/" + trickyPage, "text/html" ).statusCode() );
        assertEquals( 200, anonymous.get( "/api/pages/" + trickyPage + "?render=true", "application/json" ).statusCode() );
        assertEquals( 200, anonymous.get( "/wiki/" + embedderPage, "text/html" ).statusCode() );

        final HttpResponse< String > asViewer = viewer.get( "/api/pages/" + trickyPage, "application/json" );
        assertEquals( 200, asViewer.statusCode(), asViewer.body() );
        assertFalse( JsonParser.parseString( asViewer.body() ).getAsJsonObject().getAsJsonObject( "permissions" )
                .get( "edit" ).getAsBoolean(), "the second user is in no edit rule: " + asViewer.body() );
        assertFalse( asViewer.body().contains( "ALLOW" ), asViewer.body() );
        assertNoLogin( "GET /api/pages/{tricky} after renders", asViewer.body() );
    }

    /**
     * A frontmatter {@code author:} that is exactly an account's login is shown to readers as that account's display
     * name, in the page JSON and in the server-rendered data island. (Editors of the page get the text as written, which
     * is why the page carries an access rule: without one this wiki lets anonymous visitors edit.)
     */
    @Test
    void readersSeeTheDisplayNameForAFrontmatterAuthorThatIsALogin() throws Exception {
        final Client anonymous = new Client();
        final HttpResponse< String > page = anonymous.get( "/api/pages/" + ontoPage, "application/json" );
        assertEquals( 200, page.statusCode(), page.body() );
        final JsonObject metadata = JsonParser.parseString( page.body() ).getAsJsonObject().getAsJsonObject( "metadata" );
        assertEquals( probeDisplay, metadata.get( "author" ).getAsString(), "frontmatter author, as readers get it: " + page.body() );
        assertNoLogins( "GET /api/pages/{onto}", page.body() );

        final HttpResponse< String > html = anonymous.get( "/wiki/" + ontoPage, "text/html" );
        assertEquals( 200, html.statusCode(), html.body() );
        final int island = html.body().indexOf( "window.__WIKANTIK_PAGE__" );
        assertTrue( island >= 0, "the server-rendered page carries the data island" );
        assertTrue( html.body().substring( island ).contains( probeDisplay ), "the island names the author by display name" );
    }
}
