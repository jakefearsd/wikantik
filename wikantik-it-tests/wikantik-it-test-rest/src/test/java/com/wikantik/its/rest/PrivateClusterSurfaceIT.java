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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The private-cluster surface sweep. This module's wiki runs with
 * {@code wikantik.access.privateClusters=it-private} and {@code wikantik.access.privateClusters.viewers=ItPrivateViewers}
 * (see the module pom), so a page in the {@code it-private} cluster must be invisible to an anonymous caller and to
 * a plain logged-in user through <em>every</em> read surface, including the ones that answer from an index or a
 * render cache, must stay visible to Admin, and must be readable by a member of the viewers group.
 *
 * <p>Every hidden-from-caller check runs <em>immediately</em> after the admin save returns: no surface may leak
 * during the window before an index catches up. Then each index-backed or cached surface is waited on until the
 * PUBLIC control page shows on that same surface, and the same absence checks run again, so an absence is never an
 * artefact of an index that had not caught up.</p>
 *
 * <h2>Surfaces this class does not assert, and why</h2>
 * <ul>
 *   <li>{@code /api/ingest}, {@code /api/convert}, {@code /api/fences/*}: they take caller-supplied bytes or text and
 *       render or convert it; none reads a stored page, so there is nothing of a private page to return.</li>
 *   <li>{@code /api/export} (the zip download): it uses the same {@code ExportService} selection and permission
 *       filter as {@code /api/export/preview}, which is asserted for the plain user. A zip body is compressed
 *       binary, so a name search over it would prove nothing.</li>
 *   <li>{@code /api/collab/*} beyond {@code join}: every call after {@code join} needs a session that {@code join}
 *       issued, and {@code join} is refused for a page the caller cannot view (asserted for anonymous and the plain
 *       user, with a control that the plain user can join the public page; this module has collab enabled).</li>
 *   <li>{@code list_stale_citations} (knowledge MCP): no citation is seeded here, so any assertion would be vacuous.
 *       Its per-row view filter is unit-tested in {@code ListStaleCitationsToolTest}.</li>
 *   <li>{@code /api/me/*} other than {@code /api/me/pages}: {@code mentions} and the rest list the caller's own
 *       notifications and are not keyed by page visibility. {@code /api/me/pages} is asserted.</li>
 *   <li>{@code /api/self/apikeys}, {@code /api/auth/*}, {@code /api/users/mentionable}, {@code /api/page-templates},
 *       {@code /api/frontmatter-schema}, {@code /api/frontmatter/validate}, {@code /api/capabilities}: no page
 *       content, names or counts of pages.</li>
 *   <li>{@code /api/import/obsidian/*}, {@code /api/health*}: write-side or operational. {@code /api/health/structural-index}
 *       reports whole-corpus totals (pages, clusters, tags), which are deliberately not per-viewer.</li>
 *   <li>{@code /admin/*}: {@code AdminAuthFilter} requires {@code AllPermission}; ITs of those servlets live elsewhere.</li>
 *   <li>{@code /api/bundle?debug=rankings}: administrators only (unit-tested in {@code BundleResourceTest}); the dense
 *       module's {@code DenseBundleIT} calls it as admin.</li>
 *   <li>{@code /api/bundle} and {@code /api/briefing} with embeddings: this module has none, so a bundle here would
 *       be empty and prove nothing. They are asserted with a live embedder in {@code PrivateClusterDenseIT}.</li>
 *   <li>Page history and diff of a page moved <em>out</em> of a private cluster: accepted, they follow the latest
 *       version's cluster, as the latest version's ACL governs every version today.</li>
 *   <li>The admin MCP ({@code /wikantik-admin-mcp}) reads and writes any page by design; its write path is asserted
 *       to keep working.</li>
 * </ul>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class PrivateClusterSurfaceIT {

    private static final Gson GSON = new Gson();
    private static final String HUB = "ItPrivateHub";
    private static final String RUNBOOK = "ItPrivateRunbook";
    private static final String PUBLIC = "ItPublicPage";
    private static final String CONTROL_HUB = "ItControlHub";
    private static final String TRANSCLUDER = "ItPublicTransclude";
    /** A PUBLIC hub, and its public member, of a cluster the private runbook also joins (third entry of its list). */
    private static final String SHARED_HUB = "ItSharedHub";
    private static final String SHARED_SIBLING = "ItSharedSibling";
    private static final String SHARED_CLUSTER = "it-shared";
    /** A public page full of page-listing and page-reading plugins. */
    private static final String PLUGINS = "ItPublicPlugins";
    /** What a markdown {@code IfPlugin} (it cannot carry a body there) renders when its condition holds. */
    private static final String IF_HELD = "Plugin insertion failed";
    private static final String PRIVATE_MARKER = "zzprivmarker192";
    private static final String PUBLIC_MARKER = "zzpubmarker192";
    /** Only the private hub's body carries this, so a hub quoted anywhere is recognised even without its name. */
    private static final String HUB_MARKER = "zzhubmarker192";
    /** A tag only the two private pages carry (and so used on two pages: the export picker would list it unfiltered). */
    private static final String PRIVATE_TAG = "itprivtag";
    private static final String ATTACHMENT = "secret.txt";
    private static final String CLUSTER = "it-private";
    private static final String CONTROL_CLUSTER = "it-control";
    private static final String VIEWERS_GROUP = "ItPrivateViewers";
    private static final String PLAIN_LOGIN = "itplainuser";
    private static final String VIEWER_LOGIN = "itvieweruser";
    private static final String INITIAL_PASSWORD = "ItPrivateSweep1!";
    private static final String CHANGED_PASSWORD = "ItPrivateSweep2!";
    private static final long CONTROL_BUDGET_MS = 90_000L;
    /** What a logged-in caller who cannot view the page gets from a collab join (forbidden: "No view permission on <page>"). */
    private static final int PLAIN_COLLAB_REFUSAL = 403;

    /** The public page's own link to the runbook: a surface quoting the public page's text legitimately carries it. */
    private static final String PUBLIC_PAGE_LINK = "[" + RUNBOOK + "](" + RUNBOOK + ")";
    /** A briefing repeats a pin it cannot resolve, word for word as it does for a page that does not exist. */
    private static final String UNKNOWN_PIN_ECHO = "unknown pin: " + RUNBOOK;

    private static String baseUrl;
    private static String runbookCanonicalId;
    private static String sharedHubCanonicalId;
    private static String sharedSiblingCanonicalId;
    private static Http plainUser;
    private static Http viewerUser;

    /** One cookie-isolated HTTP client. */
    private static final class Http {
        private final HttpClient client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL )
                .cookieHandler( secureCookieOverHttp() ).build();

        HttpResponse< String > get( final String path, final String accept ) throws Exception {
            return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Accept", accept ).GET().build(), HttpResponse.BodyHandlers.ofString() );
        }

        HttpResponse< String > send( final String method, final String path, final String json ) throws Exception {
            return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                    .method( method, HttpRequest.BodyPublishers.ofString( json ) ).build(),
                    HttpResponse.BodyHandlers.ofString() );
        }

        HttpResponse< String > upload( final String path, final String fileName, final String content )
                throws Exception {
            final String boundary = "----itboundary" + System.nanoTime();
            final String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + fileName + "\"\r\nContent-Type: text/plain\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
            return client.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                    .header( "Content-Type", "multipart/form-data; boundary=" + boundary )
                    .header( "Accept", "application/json" )
                    .POST( HttpRequest.BodyPublishers.ofString( body ) ).build(),
                    HttpResponse.BodyHandlers.ofString() );
        }

        Http login( final String user, final String password ) throws Exception {
            final HttpResponse< String > resp = send( "POST", "/api/auth/login",
                    GSON.toJson( Map.of( "username", user, "password", password ) ) );
            assertEquals( 200, resp.statusCode(), "login as " + user + ": " + resp.body() );
            return this;
        }

        Http loginAsAdmin() throws Exception {
            return login( "janne", "myP@5sw0rd" );
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

    private static String enc( final String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 );
    }

    @BeforeAll
    static void setUp() {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
    }

    // ------------------------------------------------------------------ fixtures

    private static void put( final Http admin, final String page, final String content ) throws Exception {
        final HttpResponse< String > r = admin.send( "PUT", "/api/pages/" + page,
                GSON.toJson( Map.of( "content", content, "changeNote", "PrivateClusterSurfaceIT" ) ) );
        assertTrue( r.statusCode() == 200 || r.statusCode() == 201, "save " + page + ": " + r.statusCode() + " " + r.body() );
    }

    /** Seeds the private hub and runbook, the public control pages, the attachment and the transcluding page. */
    private static void seed( final Http admin ) throws Exception {
        put( admin, HUB, "---\ntype: hub\ncluster: " + CLUSTER + "\ntags: [" + PRIVATE_TAG + "]\nsummary: private hub\n"
                + "related: [" + RUNBOOK + "]\n---\n"
                + "# Private hub\n\nHub " + HUB_MARKER + ". Members: [" + RUNBOOK + "](" + RUNBOOK + ")\n" );
        // The title is the slug, so a surface that quotes the runbook's title is caught by the name check.
        put( admin, RUNBOOK, "---\ntitle: " + RUNBOOK + "\ncluster: [it-public, " + CLUSTER + ", " + SHARED_CLUSTER + "]\ntags: ["
                + PRIVATE_TAG + "]\nsummary: private runbook\n---\n"
                + "# Private runbook\n\nThe secret " + PRIVATE_MARKER + " lives here. Back to [" + PUBLIC + "](" + PUBLIC + ").\n" );
        put( admin, CONTROL_HUB, "---\ntype: hub\ncluster: " + CONTROL_CLUSTER + "\ntags: [itsweep]\nsummary: public control hub\n"
                + "related: [" + PUBLIC + "]\n---\n"
                + "# Control hub\n\nMembers: [" + PUBLIC + "](" + PUBLIC + ")\n" );
        put( admin, PUBLIC, "---\ncluster: " + CONTROL_CLUSTER + "\ntags: [itsweep]\nsummary: public sweep page\n---\n"
                + "# Public page\n\nPublic " + PUBLIC_MARKER + " text. See [" + RUNBOOK + "](" + RUNBOOK + ").\n" );
        put( admin, TRANSCLUDER, "# Transcluder\n\nEmbed: ![[" + RUNBOOK + "]]\n\n[{InsertPage page='" + RUNBOOK + "'}]\n" );
        // A generic hub summary, so the for-agent projection synthesizes one from the hub's preferred pages' titles.
        put( admin, SHARED_HUB, "---\ntype: hub\ncluster: " + SHARED_CLUSTER + "\ntags: [itsweep]\n"
                + "summary: Index of pages on shared sweep things.\n---\n# Shared hub\n\nThe shared hub.\n" );
        put( admin, SHARED_SIBLING, "---\ntitle: " + SHARED_SIBLING + "\ncluster: " + SHARED_CLUSTER + "\ntags: [itsweep]\n"
                + "summary: public shared sibling\n---\n# Shared sibling\n\nShared sibling text.\n" );
        // Each listing or page-reading plugin, with its public control beside it. (A markdown IfPlugin cannot hold a
        // body, so what it shows when its condition holds is IF_HELD.)
        put( admin, PLUGINS, "# Public plugins\n\nIndex: [{IndexPlugin include='It.*'}]\n\n"
                + "Referring: [{ReferringPagesPlugin page='" + PUBLIC + "'}]\n\n"
                + "Unused: [{UnusedPagesPlugin include='It*'}]\n\n"
                + "Probe: [{IfPlugin page='" + RUNBOOK + "' contains='zzpriv'}]\n\n"
                + "Control probe: [{IfPlugin page='" + PUBLIC + "' contains='zzpub'}]\n\n"
                + "Hub set: [{HubSetPlugin hub='" + HUB + "'}]\n\n"
                + "Control hub set: [{HubSetPlugin hub='" + CONTROL_HUB + "'}]\n" );
        final HttpResponse< String > up = admin.upload( "/api/attachments/" + RUNBOOK, ATTACHMENT,
                "attachment " + PRIVATE_MARKER );
        assertTrue( up.statusCode() / 100 == 2, "attachment upload: " + up.statusCode() + " " + up.body() );
    }

    /** A plain account and a viewers-group member, as the admin API creates them; both change their password at once. */
    private static void createCallers( final Http admin ) throws Exception {
        plainUser = account( admin, PLAIN_LOGIN, "It Plain Sweep User" );
        viewerUser = account( admin, VIEWER_LOGIN, "It Viewer Sweep User" );
        final HttpResponse< String > group = admin.send( "PUT", "/admin/groups/" + VIEWERS_GROUP,
                GSON.toJson( Map.of( "members", List.of( VIEWER_LOGIN ) ) ) );
        assertTrue( group.statusCode() / 100 == 2, "create the viewers group: " + group.statusCode() + " " + group.body() );
    }

    private static Http account( final Http admin, final String login, final String display ) throws Exception {
        final HttpResponse< String > created = admin.send( "POST", "/admin/users", GSON.toJson( Map.of(
                "loginName", login, "fullName", display, "email", login + "@example.com", "password", INITIAL_PASSWORD ) ) );
        assertEquals( 201, created.statusCode(), "create " + login + ": " + created.body() );
        final Http caller = new Http().login( login, INITIAL_PASSWORD );
        final HttpResponse< String > changed = caller.send( "PUT", "/api/auth/profile", GSON.toJson( Map.of(
                "currentPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD ) ) );
        assertEquals( 200, changed.statusCode(), "forced password change for " + login + ": " + changed.body() );
        return caller;
    }

    private static final Pattern CANONICAL_ID = Pattern.compile( "canonical_id\\\\?\"?[:=]\\s*\\\\?\"?([0-9A-Za-z_-]{8,})" );

    private static String canonicalIdOf( final Http admin, final String page ) throws Exception {
        final HttpResponse< String > r = admin.get( "/api/pages/" + page, "application/json" );
        assertEquals( 200, r.statusCode(), r.body() );
        final Matcher m = CANONICAL_ID.matcher( r.body() );
        assertTrue( m.find(), "no canonical_id in " + page + ": " + r.body() );
        return m.group( 1 );
    }

    // ------------------------------------------------------------------ assertions

    /**
     * The only text of a response that is allowed to name a private page although it is not the page's: the caller's
     * own request ({@code query}/{@code prompt}), the PUBLIC pages' own bodies where they link to or embed the
     * runbook, a briefing's {@code unknown pin: X}, and a refusal that repeats the exact slug or id it was asked for.
     * Exactly these forms are removed; nothing else is exempt.
     */
    private static String withoutEchoes( final String body ) {
        String stripped = body.replace( PUBLIC_PAGE_LINK, "" ).replace( UNKNOWN_PIN_ECHO, "" )
                .replace( "![[" + RUNBOOK + "]]", "" )
                // the PUBLIC pages' own bodies as plain text (recent-articles excerpts, search snippets)
                .replace( "See " + RUNBOOK + ".", "" ).replace( "Embed: " + RUNBOOK, "" )
                .replaceAll( "InsertPage page(?:=|\\\\u003d)(?:'|\\\\u0027)" + RUNBOOK + "(?:'|\\\\u0027)", "" )
                // the PLUGINS page's own source: the plugin calls that name the private pages they probe
                .replaceAll( "IfPlugin page(?:=|\\\\u003d)(?:'|\\\\u0027)" + RUNBOOK + "(?:'|\\\\u0027)", "" )
                .replaceAll( "HubSetPlugin hub(?:=|\\\\u003d)(?:'|\\\\u0027)" + HUB + "(?:'|\\\\u0027)", "" )
                // HubSet's refusal repeats the hub it was asked for, word for word as for a hub that does not exist
                .replaceAll( "Hub (?:'|\\\\u0027|&#39;|\\\\u0026#39;|&apos;)" + HUB
                        + "(?:'|\\\\u0027|&#39;|\\\\u0026#39;|&apos;) does not exist", "" );
        if ( runbookCanonicalId != null ) {
            // a refusal that repeats the id it was asked for: the request, not the page
            stripped = stripped.replace( "no page for canonical_id " + runbookCanonicalId, "" )
                    .replace( "/id/page/" + runbookCanonicalId, "" );
        }
        for ( final String slug : List.of( RUNBOOK, HUB ) ) {
            // refusals that repeat the page name the caller supplied
            stripped = stripped.replace( "no page named " + slug, "" ).replace( "No view permission on " + slug, "" );
            final String q = Pattern.quote( slug );
            // {"exists":false,"pageName":"<slug>"} and {"slug":"<slug>","error":"not_found"}: the slug that was asked for
            stripped = stripped
                    .replaceAll( "\\{[^{}]*\"exists\"\\s*:\\s*false[^{}]*\"pageName\"\\s*:\\s*\"" + q + "\"[^{}]*\\}", "" )
                    .replaceAll( "\\{[^{}]*\"pageName\"\\s*:\\s*\"" + q + "\"[^{}]*\"exists\"\\s*:\\s*false[^{}]*\\}", "" )
                    .replaceAll( "\\{\\s*\"slug\"\\s*:\\s*\"" + q + "\"\\s*,\\s*\"error\"\\s*:\\s*\"not_found\"\\s*\\}", "" );
        }
        try {
            final JsonElement parsed = JsonParser.parseString( stripped );
            if ( parsed.isJsonObject() ) {
                final JsonObject o = parsed.getAsJsonObject();
                o.remove( "query" );
                o.remove( "prompt" );
                return o.toString();
            }
        } catch ( final RuntimeException e ) {
            // not JSON (markdown, XML, HTML): judged as the text it is
            return stripped;
        }
        return stripped;
    }

    private static void assertNoPrivateText( final String surface, final HttpResponse< String > r ) {
        assertFalse( withoutEchoes( r.body() ).contains( PRIVATE_MARKER ), surface + " leaked the private marker: " + r.body() );
    }

    private static void assertNoPrivateTextOrName( final String surface, final HttpResponse< String > r ) {
        assertNoPrivateTextOrName( surface, r.body() );
    }

    private static void assertNoPrivateTextOrName( final String surface, final String raw ) {
        final String body = withoutEchoes( raw );
        for ( final String forbidden : List.of( PRIVATE_MARKER, HUB_MARKER ) ) {
            assertFalse( body.contains( forbidden ), surface + " leaked private text '" + forbidden + "': " + raw );
        }
        // Names are matched on token boundaries over the WHOLE body, so "ItPrivateRunbook.md", an attachment path, a
        // url fragment or free text all count; the runbook's canonical id is as good as its name.
        for ( final String name : List.of( RUNBOOK, HUB ) ) {
            assertFalse( Pattern.compile( "(?<![A-Za-z0-9])" + Pattern.quote( name ) + "(?![A-Za-z0-9])" ).matcher( body ).find(),
                    surface + " names the private page '" + name + "': " + raw );
        }
        assertFalse( runbookCanonicalId != null && body.contains( runbookCanonicalId ),
                surface + " carries the private runbook's canonical id: " + raw );
    }

    private static void assertDenied( final String surface, final HttpResponse< String > r ) {
        assertTrue( r.statusCode() == 404 || r.statusCode() == 403 || r.statusCode() == 401,
                surface + " must be denied, got " + r.statusCode() + ": " + r.body() );
        assertNoPrivateText( surface, r );
    }

    private interface Probe {
        HttpResponse< String > call() throws Exception;
    }

    /** Polls a surface until its (successful) answer satisfies {@code ready}: the positive control. */
    private static String awaitControl( final String surface, final Probe probe, final Predicate< String > ready )
            throws Exception {
        final long deadline = System.currentTimeMillis() + CONTROL_BUDGET_MS;
        HttpResponse< String > last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = probe.call();
            if ( last.statusCode() == 200 && ready.test( last.body() ) ) {
                return last.body();
            }
            Thread.sleep( 500 );
        }
        fail( "positive control: " + surface + " never showed the public control page within "
                + ( CONTROL_BUDGET_MS / 1000 ) + "s; last: " + ( last == null ? "(none)" : last.statusCode() + " " + last.body() ) );
        return null;
    }

    private static Probe getOf( final Http who, final String path, final String accept ) {
        return () -> who.get( path, accept );
    }

    // ------------------------------------------------------------------ the HTTP sweep

    /**
     * Everything a caller who may not see {@code it-private} must not get through any HTTP read surface. Run for an
     * anonymous caller (twice: at once, and once the indexes have caught up) and for a plain logged-in user.
     */
    private static void assertHttpSurfacesHide( final Http who, final String label, final int collabRefusal,
                                                final boolean collabControl ) throws Exception {
        // --- rendered pages and the raw-content filter
        assertDenied( label + " /wiki page", who.get( "/wiki/" + RUNBOOK, "text/html" ) );
        assertEquals( 404, who.get( "/wiki/" + RUNBOOK, "text/html" ).statusCode(), label );
        assertEquals( 404, who.get( "/wiki/" + RUNBOOK + "?format=md", "text/markdown" ).statusCode(), label );
        assertEquals( 404, who.get( "/wiki/" + RUNBOOK + "?format=json", "application/json" ).statusCode(), label );
        assertDenied( label + " /wiki hub", who.get( "/wiki/" + HUB, "text/html" ) );
        assertDenied( label + " /wiki hub ?format=md", who.get( "/wiki/" + HUB + "?format=md", "text/markdown" ) );

        // --- /api/pages/{name} and its sub-resources
        assertDenied( label + " /api/pages/{name}", who.get( "/api/pages/" + RUNBOOK, "application/json" ) );
        assertDenied( label + " ?render=true", who.get( "/api/pages/" + RUNBOOK + "?render=true", "application/json" ) );
        assertDenied( label + " ?version=1", who.get( "/api/pages/" + RUNBOOK + "?version=1", "application/json" ) );
        for ( final String sub : List.of( "preview", "embed", "similar", "blocks" ) ) {
            assertDenied( label + " /api/pages/{name}/" + sub, who.get( "/api/pages/" + RUNBOOK + "/" + sub, "application/json" ) );
        }
        assertDenied( label + " for-agent", who.get( "/api/pages/for-agent/" + runbookCanonicalId, "application/json" ) );
        assertDenied( label + " by-id", who.get( "/api/pages/by-id/" + runbookCanonicalId, "application/json" ) );
        assertNoPrivateTextOrName( label + " similar pages of the public page",
                who.get( "/api/pages/" + PUBLIC + "/similar", "application/json" ) );

        // --- page lists and search
        assertNoPrivateTextOrName( label + " /api/pages?names=",
                who.get( "/api/pages?names=" + RUNBOOK + "," + HUB + "," + PUBLIC, "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/pages?q=", who.get( "/api/pages?q=ItPrivate&limit=50", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/pages?cluster=", who.get( "/api/pages?cluster=" + CLUSTER, "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/pages (all)", who.get( "/api/pages?limit=1000", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/search", who.get( "/api/search?q=" + PRIVATE_MARKER, "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/search by name", who.get( "/api/search?q=" + RUNBOOK, "application/json" ) );

        // --- feeds, sitemap, change feeds
        assertNoPrivateTextOrName( label + " /sitemap.xml", who.get( "/sitemap.xml", "application/xml" ) );
        assertNoPrivateTextOrName( label + " /feed.xml", who.get( "/feed.xml", "application/atom+xml" ) );
        assertNoPrivateTextOrName( label + " /api/recent-articles", who.get( "/api/recent-articles", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/recent-changes", who.get( "/api/recent-changes", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/changes", who.get( "/api/changes?since=2000-01-01T00:00:00Z", "application/json" ) );

        // --- links, structure, graph
        assertDenied( label + " backlinks of the private page", who.get( "/api/backlinks/" + RUNBOOK, "application/json" ) );
        assertNoPrivateTextOrName( label + " backlinks of the public page (the runbook links to it)",
                who.get( "/api/backlinks/" + PUBLIC, "application/json" ) );
        assertDenied( label + " outbound links of the private page", who.get( "/api/outbound-links/" + RUNBOOK, "application/json" ) );
        assertNoPrivateTextOrName( label + " outbound links of the public page (it links to the runbook)",
                who.get( "/api/outbound-links/" + PUBLIC, "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/structure/sitemap", who.get( "/api/structure/sitemap", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/structure/pages?cluster=",
                who.get( "/api/structure/pages?cluster=" + CLUSTER, "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/structure/pages", who.get( "/api/structure/pages?limit=1000", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/structure/tags", who.get( "/api/structure/tags", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/page-graph/snapshot", who.get( "/api/page-graph/snapshot", "application/json" ) );
        final HttpResponse< String > clusters = who.get( "/api/structure/clusters", "application/json" );
        assertNoPrivateTextOrName( label + " /api/structure/clusters", clusters );
        for ( final String hidden : List.of( CLUSTER, "it-public" ) ) {
            assertFalse( clusters.body().contains( "\"" + hidden + "\"" ),
                    label + " a cluster with no viewable member is listed, with its size and age: " + clusters.body() );
            assertEquals( 404, who.get( "/api/structure/clusters/" + hidden, "application/json" ).statusCode(),
                    label + " cluster detail of '" + hidden + "'" );
        }

        // --- history, diff, comments, attachments
        assertDenied( label + " /api/history", who.get( "/api/history/" + RUNBOOK, "application/json" ) );
        assertDenied( label + " /api/diff", who.get( "/api/diff/" + RUNBOOK + "?from=1&to=1", "application/json" ) );
        assertDenied( label + " /api/comment-threads", who.get( "/api/comment-threads?page=" + RUNBOOK, "application/json" ) );
        assertDenied( label + " /api/attachments list", who.get( "/api/attachments/" + RUNBOOK, "application/json" ) );
        assertDenied( label + " /api/attachments file", who.get( "/api/attachments/" + RUNBOOK + "/" + ATTACHMENT, "text/plain" ) );
        assertDenied( label + " /attach file", who.get( "/attach/" + RUNBOOK + "/" + ATTACHMENT, "text/plain" ) );

        // --- pages that embed the private page render without it
        assertNoPrivateText( label + " transcluding page (SSR)", who.get( "/wiki/" + TRANSCLUDER, "text/html" ) );
        assertNoPrivateText( label + " transcluding page (API render)",
                who.get( "/api/pages/" + TRANSCLUDER + "?render=true", "application/json" ) );

        // --- agent surfaces over REST (no embedder here: the dense module asserts them against vectors)
        assertNoPrivateTextOrName( label + " /api/bundle", who.get( "/api/bundle?q=" + PRIVATE_MARKER, "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/briefing", who.get( "/api/briefing?clusters=" + CLUSTER + "&pins=" + RUNBOOK
                + "&prompt=" + PRIVATE_MARKER + "&format=json", "application/json" ) );
        assertNoPrivateTextOrName( label + " /api/briefing md",
                who.get( "/api/briefing?clusters=" + CLUSTER + "&pins=" + RUNBOOK + "&format=md", "text/markdown" ) );

        // --- the tools server needs an API key: exactly 403 for anyone without one
        final HttpResponse< String > tools = who.get( "/tools/page/" + RUNBOOK, "application/json" );
        assertEquals( 403, tools.statusCode(), label + " /tools must refuse a caller with no API key: " + tools.body() );
        assertNoPrivateText( label + " /tools", tools );

        // --- collaborative editing (this module runs with wikantik.collab.enabled=true): joining a page the caller
        // cannot view is refused with exactly the expected status. A logged-in caller must be able to join the PUBLIC
        // page, so the refusal comes from the gate and not from collab being unavailable to that caller.
        if ( collabControl ) {
            final HttpResponse< String > control = who.send( "POST", "/api/collab/" + PUBLIC + "/join", "{}" );
            assertEquals( 200, control.statusCode(), label + " control: the caller can join the public page: " + control.body() );
        }
        final HttpResponse< String > join = who.send( "POST", "/api/collab/" + RUNBOOK + "/join", "{}" );
        assertEquals( collabRefusal, join.statusCode(), label + " collab join of a hidden page: " + join.body() );
        assertNoPrivateText( label + " collab join", join );
    }

    /** The rendered HTML of a page as {@code GET /api/pages/{name}?render=true} returns it (not the raw source). */
    private static String renderedHtml( final Http who, final String page ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/pages/" + page + "?render=true", "application/json" );
        assertEquals( 200, r.statusCode(), page + " render: " + r.body() );
        final JsonElement html = JsonParser.parseString( r.body() ).getAsJsonObject().get( "contentHtml" );
        assertTrue( html != null && !html.isJsonNull(), page + " render carries contentHtml: " + r.body() );
        return html.getAsString();
    }

    /** How a hub's JSON-LD {@code hasPart} names a member. */
    private static String hasPartName( final String slug ) {
        return "\"@type\":\"Article\",\"name\":\"" + slug + "\"";
    }

    private static int occurrences( final String haystack, final String needle ) {
        int n = 0;
        for ( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + needle.length() ) ) {
            n++;
        }
        return n;
    }

    private static int memberCountOf( final String pageJson ) {
        return JsonParser.parseString( pageJson ).getAsJsonObject().getAsJsonObject( "cluster_status" )
                .get( "member_count" ).getAsInt();
    }

    private static int articleCountOf( final String clustersJson, final String cluster ) {
        for ( final JsonElement c : JsonParser.parseString( clustersJson ).getAsJsonObject().getAsJsonObject( "data" )
                .getAsJsonArray( "clusters" ) ) {
            if ( cluster.equals( c.getAsJsonObject().get( "name" ).getAsString() ) ) {
                return c.getAsJsonObject().get( "article_count" ).getAsInt();
            }
        }
        fail( "cluster '" + cluster + "' is not listed: " + clustersJson );
        return -1;
    }

    /**
     * Pages that are public but whose content is drawn from OTHER pages: a public hub whose cluster the private runbook
     * also joins (its JSON-LD hasPart, its member count, the for-agent projections of it and of its public member) and a
     * page of listing and page-reading plugins. Each absence is judged next to its positive control, the public page on
     * the same surface. Run once the structural index holds the shared cluster.
     */
    private static void assertSharedClusterAndPluginsHide( final Http who, final String label, final boolean signedIn )
            throws Exception {
        // --- the public hub of a cluster the private runbook also joins
        final HttpResponse< String > hubHtml = who.get( "/wiki/" + SHARED_HUB, "text/html" );
        assertEquals( 200, hubHtml.statusCode(), label + " SSR of the shared hub" );
        assertTrue( hubHtml.body().contains( "\"hasPart\"" ) && hubHtml.body().contains( hasPartName( SHARED_SIBLING ) ),
                label + " control: the hub's hasPart names its public member: " + hubHtml.body() );
        assertNoPrivateTextOrName( label + " SSR of the shared hub (head and body)", hubHtml );
        if ( signedIn ) {
            assertEquals( "private, no-store", hubHtml.headers().firstValue( "Cache-Control" ).orElse( "" ),
                    label + " a signed-in caller's copy of a per-viewer page is never stored" );
        } else {
            assertTrue( hubHtml.headers().firstValue( "ETag" ).orElse( "" ).endsWith( "-a\"" ),
                    label + " the anonymous copy keeps a validator of its own: " + hubHtml.headers().map() );
        }
        final HttpResponse< String > hubApi = who.get( "/api/pages/" + SHARED_HUB, "application/json" );
        assertEquals( 200, hubApi.statusCode(), hubApi.body() );
        assertNoPrivateTextOrName( label + " /api/pages of the shared hub", hubApi );
        // The cluster's members are the hub itself, its public member and (hidden here) the private runbook.
        assertEquals( 2, memberCountOf( hubApi.body() ), label + " the hub counts only the members the caller may view: "
                + hubApi.body() );
        assertEquals( 2, articleCountOf( who.get( "/api/structure/clusters", "application/json" ).body(), SHARED_CLUSTER ),
                label + " the shared cluster is listed, counting only the members the caller may view" );

        // --- for-agent projections of the public hub and its public member (prefer_pages, synthesized hub summary)
        final HttpResponse< String > siblingAgent = who.get( "/api/pages/for-agent/" + sharedSiblingCanonicalId, "application/json" );
        assertEquals( 200, siblingAgent.statusCode(), siblingAgent.body() );
        assertTrue( siblingAgent.body().contains( sharedHubCanonicalId ),
                label + " control: the member's projection prefers its public hub: " + siblingAgent.body() );
        assertNoPrivateTextOrName( label + " for-agent of the shared member", siblingAgent );
        final HttpResponse< String > hubAgent = who.get( "/api/pages/for-agent/" + sharedHubCanonicalId, "application/json" );
        assertEquals( 200, hubAgent.statusCode(), hubAgent.body() );
        assertTrue( hubAgent.body().contains( sharedSiblingCanonicalId ) && hubAgent.body().contains( "Highest-signal pages: " + SHARED_SIBLING ),
                label + " control: the hub's projection prefers, and its summary quotes, the public member: " + hubAgent.body() );
        assertNoPrivateTextOrName( label + " for-agent of the shared hub", hubAgent );

        // --- the page of listing and page-reading plugins, server-rendered and through the API
        final HttpResponse< String > pluginsHtml = who.get( "/wiki/" + PLUGINS, "text/html" );
        assertEquals( 200, pluginsHtml.statusCode(), label + " SSR of the plugins page" );
        assertNoPrivateTextOrName( label + " SSR of the plugins page", pluginsHtml );
        final String rendered = renderedHtml( who, PLUGINS );
        assertNoPrivateTextOrName( label + " render of the plugins page", rendered );
        assertTrue( rendered.contains( "/wiki/" + PUBLIC ) && rendered.contains( "/wiki/" + CONTROL_HUB )
                        && rendered.contains( "/wiki/" + SHARED_SIBLING ),
                label + " control: the listings name the public pages: " + rendered );
        assertEquals( 1, occurrences( rendered, IF_HELD ),
                label + " only the control IfPlugin holds; the probe of the private page must read as false: " + rendered );
        if ( signedIn ) {
            assertEquals( "private, no-store", pluginsHtml.headers().firstValue( "Cache-Control" ).orElse( "" ),
                    label + " the plugins page is per viewer" );
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order( 1 )
    void anonymousReachesNoPrivatePageThroughAnyHttpSurfaceAtOnce() throws Exception {
        final Http admin = new Http().loginAsAdmin();
        seed( admin );
        createCallers( admin );
        runbookCanonicalId = canonicalIdOf( admin, RUNBOOK );
        sharedHubCanonicalId = canonicalIdOf( admin, SHARED_HUB );
        sharedSiblingCanonicalId = canonicalIdOf( admin, SHARED_SIBLING );

        // Anonymous, immediately after the saves.
        final Http anon = new Http();
        assertHttpSurfacesHide( anon, "[anonymous, at once]", 401, false );

        // The admin sees it all.
        final HttpResponse< String > adminMd = admin.get( "/wiki/" + RUNBOOK + "?format=md", "text/markdown" );
        assertEquals( 200, adminMd.statusCode(), adminMd.body() );
        assertTrue( adminMd.body().contains( PRIVATE_MARKER ), "admin must read the private page" );
        final HttpResponse< String > adminHtml = admin.get( "/wiki/" + RUNBOOK, "text/html" );
        assertEquals( 200, adminHtml.statusCode(), "admin SSR page" );
        assertTrue( adminHtml.body().contains( PRIVATE_MARKER ), "admin SSR page carries the marker" );
        final HttpResponse< String > adminTransclude = admin.get( "/api/pages/" + TRANSCLUDER + "?render=true", "application/json" );
        assertTrue( adminTransclude.body().contains( PRIVATE_MARKER ),
                "an admin's render of the transcluding page does include the private text (this is what must not be cached)" );

        // The render caches did not keep the admin's view.
        assertEquals( 404, anon.get( "/wiki/" + RUNBOOK, "text/html" ).statusCode(), "render cache leaked an admin SSR page" );
        assertDenied( "render cache (api)", anon.get( "/api/pages/" + RUNBOOK + "?render=true", "application/json" ) );
        assertNoPrivateText( "render cache (transcluding page)", anon.get( "/wiki/" + TRANSCLUDER, "text/html" ) );
        assertNoPrivateText( "render cache (transcluding page, api)",
                anon.get( "/api/pages/" + TRANSCLUDER + "?render=true", "application/json" ) );
    }

    @Test
    @Order( 2 )
    void onceTheIndexesHaveCaughtUpAnonymousStillReachesNothing() throws Exception {
        final Http admin = new Http().loginAsAdmin();
        awaitIndexed( admin, RUNBOOK );
        awaitIndexed( admin, PUBLIC );
        final Http anon = new Http();

        // Positive controls: the PUBLIC page is visible on each index-backed or cached surface before absence is judged.
        awaitControl( "/api/search", getOf( anon, "/api/search?q=" + PUBLIC_MARKER, "application/json" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/sitemap.xml", getOf( anon, "/sitemap.xml", "application/xml" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/feed.xml", getOf( anon, "/feed.xml", "application/atom+xml" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/api/recent-articles", getOf( anon, "/api/recent-articles", "application/json" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/api/recent-changes", getOf( anon, "/api/recent-changes", "application/json" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/api/changes", getOf( anon, "/api/changes?since=2000-01-01T00:00:00Z", "application/json" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/api/structure/pages", getOf( anon, "/api/structure/pages?limit=1000", "application/json" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/api/structure/sitemap", getOf( anon, "/api/structure/sitemap", "application/json" ), b -> b.contains( PUBLIC ) );
        awaitControl( "/api/structure/clusters", getOf( anon, "/api/structure/clusters", "application/json" ),
                b -> b.contains( "\"" + CONTROL_CLUSTER + "\"" ) );
        awaitControl( "/api/structure/tags", getOf( anon, "/api/structure/tags", "application/json" ), b -> b.contains( "itsweep" ) );
        awaitControl( "/api/backlinks of the public page", getOf( anon, "/api/backlinks/" + PUBLIC, "application/json" ),
                b -> b.contains( CONTROL_HUB ) );
        // The snapshot is cached for 60 s and may predate the seed: wait until it holds the public page.
        awaitControl( "/api/page-graph/snapshot", getOf( anon, "/api/page-graph/snapshot", "application/json" ),
                b -> b.contains( "\"" + PUBLIC + "\"" ) );

        assertHttpSurfacesHide( anon, "[anonymous, indexed]", 401, false );

        awaitIndexed( admin, SHARED_HUB );
        awaitIndexed( admin, SHARED_SIBLING );
        awaitControl( "hasPart of the shared hub", getOf( anon, "/wiki/" + SHARED_HUB, "text/html" ),
                b -> b.contains( hasPartName( SHARED_SIBLING ) ) );
        assertSharedClusterAndPluginsHide( anon, "[anonymous, indexed]", false );
    }

    @Test
    @Order( 3 )
    void anonymousReachesNoPrivatePageThroughTheKnowledgeMcp() throws Exception {
        final McpSyncClient mcp = mcpClient( "knowledge-mcp" );
        try {
            // Controls on the same surfaces: the PUBLIC page and its cluster are retrievable and listed.
            awaitMcp( "list_pages", mcp, "list_pages", Map.of(), b -> b.contains( PUBLIC ) );
            awaitMcp( "list_clusters", mcp, "list_clusters", Map.of(), b -> b.contains( CONTROL_CLUSTER ) );
            awaitMcp( "list_metadata_values(cluster)", mcp, "list_metadata_values", Map.of( "field", "cluster" ),
                    b -> b.contains( CONTROL_CLUSTER ) );
            awaitMcp( "list_tags", mcp, "list_tags", Map.of(), b -> b.contains( "itsweep" ) );
            awaitMcp( "list_pages_by_filter", mcp, "list_pages_by_filter", Map.of( "cluster", CONTROL_CLUSTER ), b -> b.contains( PUBLIC ) );
            awaitMcp( "retrieve_context", mcp, "retrieve_context", Map.of( "query", PUBLIC_MARKER ), b -> b.contains( PUBLIC ) );
            awaitMcp( "get_briefing", mcp, "get_briefing", Map.of( "clusters", List.of( CONTROL_CLUSTER ), "prompt", PUBLIC_MARKER ),
                    b -> b.contains( PUBLIC ) );
            awaitMcp( "get_page_neighbors", mcp, "get_page_neighbors", Map.of( "slug", PUBLIC ), b -> b.contains( CONTROL_HUB ) );

            final List< String > bodies = new ArrayList<>();
            bodies.add( tool( mcp, "get_page", Map.of( "slug", RUNBOOK ) ) );
            bodies.add( tool( mcp, "get_page", Map.of( "slug", HUB ) ) );
            bodies.add( tool( mcp, "read_pages", Map.of( "slugs", List.of( RUNBOOK, HUB ) ) ) );
            bodies.add( tool( mcp, "get_page_for_agent", Map.of( "canonical_id", runbookCanonicalId ) ) );
            // The public hub and member of a cluster the runbook also joins: their projections hold no trace of it.
            final String siblingProjection = tool( mcp, "get_page_for_agent", Map.of( "canonical_id", sharedSiblingCanonicalId ) );
            assertTrue( siblingProjection.contains( sharedHubCanonicalId ),
                    "control: the member's projection prefers its public hub: " + siblingProjection );
            final String hubProjection = tool( mcp, "get_page_for_agent", Map.of( "canonical_id", sharedHubCanonicalId ) );
            assertTrue( hubProjection.contains( sharedSiblingCanonicalId ),
                    "control: the hub's projection prefers its public member: " + hubProjection );
            bodies.add( siblingProjection );
            bodies.add( hubProjection );
            bodies.add( tool( mcp, "get_page_by_id", Map.of( "canonical_id", runbookCanonicalId ) ) );
            bodies.add( tool( mcp, "retrieve_context", Map.of( "query", PRIVATE_MARKER ) ) );
            bodies.add( tool( mcp, "assemble_bundle", Map.of( "query", PRIVATE_MARKER ) ) );
            bodies.add( tool( mcp, "list_pages_by_filter", Map.of( "cluster", CLUSTER ) ) );
            bodies.add( tool( mcp, "list_pages", Map.of() ) );
            bodies.add( tool( mcp, "list_clusters", Map.of() ) );
            bodies.add( tool( mcp, "list_tags", Map.of() ) );
            bodies.add( tool( mcp, "list_metadata_values", Map.of( "field", "cluster" ) ) );
            bodies.add( tool( mcp, "get_page_neighbors", Map.of( "slug", RUNBOOK ) ) );
            bodies.add( tool( mcp, "get_page_neighbors", Map.of( "slug", PUBLIC ) ) );
            bodies.add( tool( mcp, "sparql_query", Map.of( "query",
                    "SELECT ?s WHERE { ?s ?p ?o . FILTER(CONTAINS(STR(?s), \"" + RUNBOOK + "\")) }" ) ) );
            for ( final String raw : bodies ) {
                assertNoPrivateTextOrName( "knowledge MCP", raw );
            }
            // Cluster and tag enumerations: the private cluster and tag are not offered (the public ones are the controls above).
            final String clusterList = tool( mcp, "list_clusters", Map.of() );
            final String clusterValues = tool( mcp, "list_metadata_values", Map.of( "field", "cluster" ) );
            final String tagList = tool( mcp, "list_tags", Map.of() );
            for ( final String hidden : List.of( CLUSTER, "it-public" ) ) {
                assertFalse( clusterList.contains( "\"" + hidden + "\"" ), "list_clusters shows '" + hidden + "': " + clusterList );
                assertFalse( clusterValues.contains( hidden ), "list_metadata_values(cluster) shows '" + hidden + "': " + clusterValues );
            }
            assertFalse( tagList.contains( PRIVATE_TAG ), "list_tags shows the private tag: " + tagList );
            // The briefing quotes public pages in full and the public page links to the runbook in its own body, so
            // the runbook's name alone proves nothing there: look for it as a briefing entry or for its text.
            final String briefing = tool( mcp, "get_briefing", Map.of( "clusters", List.of( CLUSTER ),
                    "pins", List.of( RUNBOOK ), "prompt", PRIVATE_MARKER ) );
            assertFalse( withoutEchoes( briefing ).contains( PRIVATE_MARKER ), "briefing leaked the marker: " + briefing );
            assertFalse( briefing.contains( HUB ), "briefing names the private hub: " + briefing );
            assertFalse( briefing.contains( "(`" + RUNBOOK + "`)" ) || briefing.contains( "`" + RUNBOOK + "`)" ),
                    "briefing lists the private page: " + briefing );
        } finally {
            mcp.close();
        }
    }

    @Test
    @Order( 4 )
    void anonymousReachesNoPrivatePageThroughTheOntologyEndpoints() throws Exception {
        final Http admin = new Http().loginAsAdmin();
        awaitIndexed( admin, RUNBOOK );
        awaitIndexed( admin, PUBLIC );
        rebuildOntology( admin );

        final Http anon = new Http();
        final String q = enc( "SELECT ?u WHERE { ?s <https://schema.org/url> ?u }" );
        final HttpResponse< String > sel = anon.get( "/sparql?query=" + q, "application/sparql-results+json" );
        assertEquals( 200, sel.statusCode(), sel.body() );
        assertTrue( sel.body().contains( PUBLIC ), "positive control: the public page is in the ontology" );
        assertNoPrivateTextOrName( "/sparql", sel );
        final HttpResponse< String > dump = anon.get( "/export/graph.nt", "application/n-triples" );
        assertEquals( 200, dump.statusCode() );
        assertTrue( dump.body().contains( PUBLIC ), "positive control: the public page is in the dump" );
        assertNoPrivateTextOrName( "/export/graph.nt", dump );
        // The Turtle export is the T-Box (the vocabulary), not the page data: it must answer, and name no page.
        final HttpResponse< String > ttl = anon.get( "/export/ontology.ttl", "text/turtle" );
        assertEquals( 200, ttl.statusCode(), "the turtle export must answer before its body is judged: " + ttl.body() );
        assertNoPrivateTextOrName( "/export/ontology.ttl", ttl );
        final HttpResponse< String > id = anon.get( "/id/page/" + runbookCanonicalId, "text/turtle" );
        assertEquals( 404, id.statusCode(), "/id of a private page: " + id.body() );
        assertNoPrivateTextOrName( "/id", id );

        // The knowledge MCP's SPARQL tool reads the same dataset.
        final McpSyncClient mcp = mcpClient( "knowledge-mcp" );
        try {
            final String body = tool( mcp, "sparql_query", Map.of( "query", "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5000" ) );
            assertTrue( body.contains( PUBLIC ), "positive control: the public page is in the MCP's dataset: " + body );
            assertNoPrivateTextOrName( "knowledge MCP sparql_query", body );
        } finally {
            mcp.close();
        }
    }

    @Test
    @Order( 5 )
    void aPlainLoggedInUserReachesNoPrivatePage() throws Exception {
        assertHttpSurfacesHide( plainUser, "[plain user]", PLAIN_COLLAB_REFUSAL, true );
        assertSharedClusterAndPluginsHide( plainUser, "[plain user]", true );

        // Surfaces that need a login.
        assertNoPrivateTextOrName( "/api/me/pages", plainUser.get( "/api/me/pages", "application/json" ) );
        final HttpResponse< String > mentions = plainUser.send( "POST", "/api/mentions/scan", GSON.toJson( Map.of(
                "text", "Notes on ItPrivateRunbook, the Private runbook, and the Private hub.", "page", "" ) ) );
        assertNoPrivateTextOrName( "/api/mentions/scan", mentions );
        for ( final String path : List.of( "/api/export/options", "/api/export/preview?cluster=" + CLUSTER,
                "/api/export/preview?cluster=it-public&hops=2", "/api/export/preview?tag=" + PRIVATE_TAG + "&hops=2",
                "/api/export/preview?tag=itsweep&hops=2" ) ) {
            // export is granted to every logged-in user (V060), so anything but 200 would make this check vacuous
            final HttpResponse< String > export = plainUser.get( path, "application/json" );
            assertEquals( 200, export.statusCode(), path + ": " + export.body() );
            assertNoPrivateTextOrName( path, export );
        }
        // The pickers: no cluster or tag that only hidden pages carry, and the public ones as the control.
        final String options = plainUser.get( "/api/export/options", "application/json" ).body();
        assertTrue( options.contains( "\"" + CONTROL_CLUSTER + "\"" ) && options.contains( "\"itsweep\"" ),
                "control: the export pickers offer the public cluster and tag: " + options );
        for ( final String hidden : List.of( CLUSTER, "it-public", PRIVATE_TAG ) ) {
            assertFalse( options.contains( "\"" + hidden + "\"" ), "the export pickers offer '" + hidden + "': " + options );
        }
        // The same MCP, as the plain user's identity, is the guest's: see anonymousReachesNoPrivatePageThroughTheKnowledgeMcp.
    }

    @Test
    @Order( 6 )
    void aMemberOfTheViewersGroupCanReadAndFindThePrivatePage() throws Exception {
        final HttpResponse< String > md = viewerUser.get( "/wiki/" + RUNBOOK + "?format=md", "text/markdown" );
        assertEquals( 200, md.statusCode(), md.body() );
        assertTrue( md.body().contains( PRIVATE_MARKER ), "a viewer reads the private page raw" );
        final HttpResponse< String > html = viewerUser.get( "/wiki/" + RUNBOOK, "text/html" );
        assertEquals( 200, html.statusCode() );
        assertTrue( html.body().contains( PRIVATE_MARKER ), "a viewer reads the private page rendered" );
        final HttpResponse< String > api = viewerUser.get( "/api/pages/" + RUNBOOK, "application/json" );
        assertEquals( 200, api.statusCode(), api.body() );
        assertTrue( api.body().contains( PRIVATE_MARKER ) );
        assertEquals( 200, viewerUser.get( "/wiki/" + HUB + "?format=md", "text/markdown" ).statusCode() );
        awaitControl( "/api/search for a viewer", getOf( viewerUser, "/api/search?q=" + PRIVATE_MARKER, "application/json" ),
                b -> b.contains( RUNBOOK ) );
        awaitControl( "/api/structure/clusters for a viewer", getOf( viewerUser, "/api/structure/clusters", "application/json" ),
                b -> b.contains( "\"" + CLUSTER + "\"" ) );
        final String viewerOptions = viewerUser.get( "/api/export/options", "application/json" ).body();
        assertTrue( viewerOptions.contains( "\"" + CLUSTER + "\"" ) && viewerOptions.contains( "\"" + PRIVATE_TAG + "\"" ),
                "a viewer's export pickers do offer the private cluster and tag: " + viewerOptions );
        // The surfaces drawn from other pages do name the private page for a viewer: the absences above were filters.
        final HttpResponse< String > hubHtml = viewerUser.get( "/wiki/" + SHARED_HUB, "text/html" );
        assertTrue( hubHtml.body().contains( hasPartName( RUNBOOK ) ), "a viewer's hasPart names the private member" );
        assertEquals( 3, memberCountOf( viewerUser.get( "/api/pages/" + SHARED_HUB, "application/json" ).body() ),
                "a viewer's member count includes the private member" );
        assertEquals( 3, articleCountOf( viewerUser.get( "/api/structure/clusters", "application/json" ).body(), SHARED_CLUSTER ) );
        final String hubAgent = viewerUser.get( "/api/pages/for-agent/" + sharedHubCanonicalId, "application/json" ).body();
        assertTrue( hubAgent.contains( runbookCanonicalId ) && hubAgent.contains( RUNBOOK ),
                "a viewer's projection of the shared hub prefers, and quotes, the private member: " + hubAgent );
        final String viewerPlugins = renderedHtml( viewerUser, PLUGINS );
        assertTrue( viewerPlugins.contains( "/wiki/" + RUNBOOK ) && viewerPlugins.contains( "/wiki/" + HUB ),
                "a viewer's listings name the private pages: " + viewerPlugins );
        assertEquals( 2, occurrences( viewerPlugins, IF_HELD ), "a viewer's probe of the private page holds: " + viewerPlugins );
        // ...and the viewer's renders were not kept for anyone else.
        final Http anonAfter = new Http();
        assertNoPrivateTextOrName( "plugins page render after a viewer's", renderedHtml( anonAfter, PLUGINS ) );
        assertNoPrivateTextOrName( "plugins page SSR after a viewer's", anonAfter.get( "/wiki/" + PLUGINS, "text/html" ) );
        assertNoPrivateTextOrName( "shared hub SSR after a viewer's", anonAfter.get( "/wiki/" + SHARED_HUB, "text/html" ) );

        // A plain user still sees none of it (the viewers grant is the group's, not the wiki's).
        assertEquals( 404, plainUser.get( "/wiki/" + RUNBOOK + "?format=md", "text/markdown" ).statusCode() );
    }

    @Test
    @Order( 7 )
    void theAdminMcpStillWritesThePrivatePage() throws Exception {
        // jakemon publishes runbooks through this path; the private cluster must not break it.
        final McpSyncClient mcp = mcpClient( "wikantik-admin-mcp" );
        try {
            final JsonObject read = JsonParser.parseString( tool( mcp, "read_page", Map.of( "slug", RUNBOOK ) ) ).getAsJsonObject();
            assertTrue( read.get( "exists" ).getAsBoolean(), "admin MCP reads the private page: " + read );
            final String hash = read.get( "contentHash" ).getAsString();
            final String content = read.get( "content" ).getAsString() + "\nUpdated by the admin MCP.\n";

            final JsonObject upd = JsonParser.parseString( tool( mcp, "update_page",
                    Map.of( "slug", RUNBOOK, "content", content, "expectedContentHash", hash ) ) ).getAsJsonObject();
            assertTrue( upd.has( "updated" ), "update_page must answer with an 'updated' field (a refusal has none): " + upd );
            assertTrue( upd.get( "updated" ).getAsBoolean(), "admin MCP update_page on a private page must succeed: " + upd );

            final JsonObject back = JsonParser.parseString( tool( mcp, "read_page", Map.of( "slug", RUNBOOK ) ) ).getAsJsonObject();
            assertTrue( back.get( "content" ).getAsString().contains( "Updated by the admin MCP." ),
                    "the update must be stored: " + back );
        } finally {
            mcp.close();
        }
        // The update did not open the page: still hidden from anonymous.
        assertEquals( 404, new Http().get( "/wiki/" + RUNBOOK, "text/html" ).statusCode() );
    }

    @Test
    @Order( 8 )
    void theContentDatesStatusEndpointAnswersForAdmin() throws Exception {
        final Http admin = new Http().loginAsAdmin();
        final HttpResponse< String > r = admin.get( "/admin/seo/content-dates/status", "application/json" );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject o = JsonParser.parseString( r.body() ).getAsJsonObject();
        assertTrue( o.has( "state" ), "status carries a state: " + r.body() );
    }

    @Test
    @Order( 9 )
    void theHomePageStillServesAnonymouslyWithPrivateClustersOn() throws Exception {
        final HttpResponse< String > r = new Http().get( "/", "text/html" );
        assertEquals( 200, r.statusCode(), "GET / must still serve the home page: " + r.body() );
        assertTrue( r.body().contains( "- Wikantik</title>" ), "the home page document: " + r.body() );
    }

    // ------------------------------------------------------------------ helpers

    private static McpSyncClient mcpClient( final String endpoint ) {
        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final McpSyncClient client = McpClient.sync( HttpClientStreamableHttpTransport.builder( prefix )
                        .endpoint( endpoint ).connectTimeout( Duration.ofSeconds( 15 ) ).build() )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-private-cluster", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
        client.initialize();
        return client;
    }

    /** Calls a tool and returns its text whether it succeeded or refused (a refusal is a valid answer here). */
    private static String tool( final McpSyncClient mcp, final String name, final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = mcp.callTool( new McpSchema.CallToolRequest( name, args ) );
        if ( result == null || result.content() == null || result.content().isEmpty() ) {
            return "";
        }
        return result.content().get( 0 ) instanceof McpSchema.TextContent tc ? tc.text() : "";
    }

    private static void awaitMcp( final String surface, final McpSyncClient mcp, final String tool,
                                  final Map< String, Object > args, final Predicate< String > ready ) throws Exception {
        final long deadline = System.currentTimeMillis() + CONTROL_BUDGET_MS;
        String last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = tool( mcp, tool, args );
            if ( ready.test( last ) ) {
                return;
            }
            Thread.sleep( 500 );
        }
        fail( "positive control: knowledge MCP " + surface + " never showed the public control page within "
                + ( CONTROL_BUDGET_MS / 1000 ) + "s; last: " + last );
    }

    private static void awaitIndexed( final Http admin, final String slug ) throws Exception {
        final long deadline = System.currentTimeMillis() + 60_000;
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > resp = admin.get( "/api/structure/sitemap", "application/json" );
            if ( resp.statusCode() == 200 && resp.body().contains( "\"" + slug + "\"" ) ) {
                return;
            }
            Thread.sleep( 250 );
        }
        fail( "page '" + slug + "' never reached the structural index within 60s" );
    }

    private static void rebuildOntology( final Http admin ) throws Exception {
        final long deadline = System.currentTimeMillis() + 90_000;
        while ( System.currentTimeMillis() < deadline ) {
            awaitIdle( admin );
            final HttpResponse< String > trig = admin.send( "POST", "/admin/ontology/rebuild", "{}" );
            if ( trig.statusCode() == 202 ) {
                awaitIdle( admin );
                return;
            }
            assertEquals( 409, trig.statusCode(), "rebuild trigger: " + trig.body() );
            Thread.sleep( 250 );
        }
        fail( "no ontology rebuild was accepted within 90s" );
    }

    private static void awaitIdle( final Http admin ) throws Exception {
        final long deadline = System.currentTimeMillis() + 60_000;
        String state = "(never read)";
        while ( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > st = admin.get( "/admin/ontology/status", "application/json" );
            state = JsonParser.parseString( st.body() ).getAsJsonObject().get( "state" ).getAsString();
            if ( "IDLE".equals( state ) ) {
                return;
            }
            Thread.sleep( 250 );
        }
        fail( "ontology rebuild did not reach IDLE within 60s; last state=" + state );
    }
}
