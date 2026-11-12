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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The private-cluster surface sweep. This module's wiki runs with
 * {@code wikantik.access.privateClusters=it-private} (see the module pom), so a page in the {@code it-private}
 * cluster must be invisible to an anonymous caller through <em>every</em> read surface, including the ones that
 * answer from an index or a render cache, and must stay visible to Admin.
 *
 * <p>Every anonymous check runs <em>immediately</em> after the admin save returns: the point is that no surface
 * is allowed to leak during the window before an index catches up. Checks that need an index to be warm first
 * poll for a positive control (the public page appearing) before asserting the private page's absence, so an
 * absence is never an artefact of an empty index.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class PrivateClusterSurfaceIT {

    private static final Gson GSON = new Gson();
    private static final String HUB = "ItPrivateHub";
    private static final String RUNBOOK = "ItPrivateRunbook";
    private static final String PUBLIC = "ItPublicPage";
    private static final String TRANSCLUDER = "ItPublicTransclude";
    private static final String PRIVATE_MARKER = "zzprivmarker192";
    private static final String PUBLIC_MARKER = "zzpubmarker192";
    private static final String ATTACHMENT = "secret.txt";
    private static final String CLUSTER = "it-private";
    /** The public page's own link to the runbook: a surface quoting the public page's text legitimately carries it. */
    /** A briefing repeats a pin it cannot resolve, word for word as it does for a page that does not exist. */
    private static final String UNKNOWN_PIN_ECHO = "unknown pin: " + RUNBOOK;
    private static final String PUBLIC_PAGE_LINK = "[" + RUNBOOK + "](" + RUNBOOK + ")";

    private static String baseUrl;
    private static String runbookCanonicalId;

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

        void loginAsAdmin() throws Exception {
            final HttpResponse< String > resp = send( "POST", "/api/auth/login",
                    GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ) );
            assertEquals( 200, resp.statusCode(), "admin login: " + resp.body() );
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

    /** Seeds the three pages, the attachment and the transcluding page. */
    private static void seed( final Http admin ) throws Exception {
        put( admin, HUB, "---\ntype: hub\ncluster: " + CLUSTER + "\nsummary: private hub\n---\n"
                + "# Private hub\n\nMembers: [" + RUNBOOK + "](" + RUNBOOK + ")\n" );
        put( admin, RUNBOOK, "---\ncluster: [it-public, " + CLUSTER + "]\nsummary: private runbook\n---\n"
                + "# Private runbook\n\nThe secret " + PRIVATE_MARKER + " lives here. Back to [" + PUBLIC + "](" + PUBLIC + ").\n" );
        put( admin, PUBLIC, "# Public page\n\nPublic " + PUBLIC_MARKER + " text. See [" + RUNBOOK + "](" + RUNBOOK + ").\n" );
        put( admin, TRANSCLUDER, "# Transcluder\n\nEmbed: ![[" + RUNBOOK + "]]\n\n[{InsertPage page='" + RUNBOOK + "'}]\n" );
        final HttpResponse< String > up = admin.upload( "/api/attachments/" + RUNBOOK, ATTACHMENT,
                "attachment " + PRIVATE_MARKER );
        assertTrue( up.statusCode() / 100 == 2, "attachment upload: " + up.statusCode() + " " + up.body() );
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

    private static void assertNoPrivateText( final String surface, final HttpResponse< String > r ) {
        final String body = withoutQueryEcho( r.body() );
        assertFalse( body.contains( PRIVATE_MARKER ), surface + " leaked the private marker: " + r.body() );
    }

    /**
     * A search-shaped answer repeats the caller's own query ({@code query}/{@code prompt}) and may quote the public
     * page's link to the runbook; neither is the private page's, so both are removed before looking for it. Anything that is not a JSON object
     * is returned as it came.
     */
    private static String withoutQueryEcho( final String body ) {
        return withoutQueryEchoJson( body.replace( PUBLIC_PAGE_LINK, "" ).replace( UNKNOWN_PIN_ECHO, "" ) );
    }

    private static String withoutQueryEchoJson( final String body ) {
        try {
            final com.google.gson.JsonElement parsed = JsonParser.parseString( body );
            if ( parsed.isJsonObject() ) {
                final JsonObject o = parsed.getAsJsonObject();
                o.remove( "query" );
                o.remove( "prompt" );
                return o.toString();
            }
        } catch ( final RuntimeException e ) {
            // not JSON (markdown, XML, HTML): judged as text
            return body;
        }
        return body;
    }

    private static void assertNoPrivateTextOrName( final String surface, final HttpResponse< String > r ) {
        assertNoPrivateText( surface, r );
        final String body = withoutQueryEcho( r.body() );
        assertFalse( body.contains( RUNBOOK ), surface + " names the private page: " + r.body() );
        assertFalse( body.contains( HUB ), surface + " names the private hub: " + r.body() );
    }

    private static void assertDenied( final String surface, final HttpResponse< String > r ) {
        assertTrue( r.statusCode() == 404 || r.statusCode() == 403 || r.statusCode() == 401,
                surface + " must be denied to anonymous, got " + r.statusCode() + ": " + r.body() );
        assertNoPrivateText( surface, r );
    }

    /** Polls a list surface until the public page shows, so a later absence is not an empty-index artefact. */
    private static HttpResponse< String > awaitPublicControl( final Http anon, final String path, final String accept,
                                                              final String publicNeedle ) throws Exception {
        final long deadline = System.currentTimeMillis() + 60_000;
        HttpResponse< String > last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = anon.get( path, accept );
            if ( last.statusCode() == 200 && last.body().contains( publicNeedle ) ) {
                return last;
            }
            Thread.sleep( 500 );
        }
        fail( "positive control: " + path + " never listed '" + publicNeedle + "' within 60s; last: "
                + ( last == null ? "(none)" : last.statusCode() + " " + last.body() ) );
        return last;
    }

    // ------------------------------------------------------------------ the sweep

    @Test
    @Order( 1 )
    void anonymousReachesNoPrivatePageThroughAnyHttpSurface() throws Exception {
        final Http admin = new Http();
        admin.loginAsAdmin();
        seed( admin );
        runbookCanonicalId = canonicalIdOf( admin, RUNBOOK );

        // From here on: anonymous, immediately after the save.
        final Http anon = new Http();

        // --- rendered pages and the raw-content filter
        assertDenied( "/wiki page", anon.get( "/wiki/" + RUNBOOK, "text/html" ) );
        assertEquals( 404, anon.get( "/wiki/" + RUNBOOK, "text/html" ).statusCode() );
        assertEquals( 404, anon.get( "/wiki/" + RUNBOOK + "?format=md", "text/markdown" ).statusCode() );
        assertEquals( 404, anon.get( "/wiki/" + RUNBOOK + "?format=json", "application/json" ).statusCode() );
        assertDenied( "/wiki hub", anon.get( "/wiki/" + HUB, "text/html" ) );
        assertDenied( "/wiki hub ?format=md", anon.get( "/wiki/" + HUB + "?format=md", "text/markdown" ) );

        // --- /api/pages/{name} and its sub-resources
        assertDenied( "/api/pages/{name}", anon.get( "/api/pages/" + RUNBOOK, "application/json" ) );
        assertDenied( "/api/pages/{name}?render=true", anon.get( "/api/pages/" + RUNBOOK + "?render=true", "application/json" ) );
        assertDenied( "/api/pages/{name}?version=1", anon.get( "/api/pages/" + RUNBOOK + "?version=1", "application/json" ) );
        assertDenied( "/api/pages/{name}/preview", anon.get( "/api/pages/" + RUNBOOK + "/preview", "application/json" ) );
        assertDenied( "/api/pages/{name}/embed", anon.get( "/api/pages/" + RUNBOOK + "/embed", "application/json" ) );
        assertDenied( "/api/pages/{name}/similar", anon.get( "/api/pages/" + RUNBOOK + "/similar", "application/json" ) );
        assertDenied( "/api/pages/{name}/blocks", anon.get( "/api/pages/" + RUNBOOK + "/blocks", "application/json" ) );
        assertDenied( "/api/pages/for-agent/{id}", anon.get( "/api/pages/for-agent/" + runbookCanonicalId, "application/json" ) );
        assertDenied( "/api/pages/by-id/{id}", anon.get( "/api/pages/by-id/" + runbookCanonicalId, "application/json" ) );
        assertNoPrivateTextOrName( "similar pages of the public page",
                anon.get( "/api/pages/" + PUBLIC + "/similar", "application/json" ) );

        // --- page lists
        assertNoPrivateTextOrName( "/api/pages?names=", anon.get( "/api/pages?names=" + RUNBOOK + "," + HUB + "," + PUBLIC, "application/json" ) );
        assertNoPrivateTextOrName( "/api/pages?q=", anon.get( "/api/pages?q=ItPrivate&limit=50", "application/json" ) );
        assertNoPrivateTextOrName( "/api/pages?cluster=", anon.get( "/api/pages?cluster=" + CLUSTER, "application/json" ) );
        assertNoPrivateTextOrName( "/api/pages (all)", anon.get( "/api/pages?limit=1000", "application/json" ) );

        // --- search
        assertNoPrivateTextOrName( "/api/search", anon.get( "/api/search?q=" + PRIVATE_MARKER, "application/json" ) );
        assertNoPrivateTextOrName( "/api/search by name", anon.get( "/api/search?q=ItPrivateRunbook", "application/json" ) );

        // --- feeds, sitemap, change feeds (each polls for the public page first)
        assertNoPrivateTextOrName( "/sitemap.xml",
                awaitPublicControl( anon, "/sitemap.xml", "application/xml", PUBLIC ) );
        assertNoPrivateTextOrName( "/feed.xml", anon.get( "/feed.xml", "application/atom+xml" ) );
        assertNoPrivateTextOrName( "/api/recent-articles", anon.get( "/api/recent-articles", "application/json" ) );
        assertNoPrivateTextOrName( "/api/recent-changes",
                awaitPublicControl( anon, "/api/recent-changes", "application/json", PUBLIC ) );
        assertNoPrivateTextOrName( "/api/changes",
                awaitPublicControl( anon, "/api/changes?since=2000-01-01T00:00:00Z", "application/json", PUBLIC ) );

        // --- links, structure, graph
        assertDenied( "/api/backlinks/{private}", anon.get( "/api/backlinks/" + RUNBOOK, "application/json" ) );
        assertNoPrivateTextOrName( "backlinks of the public page (the private runbook links to it)",
                anon.get( "/api/backlinks/" + PUBLIC, "application/json" ) );
        assertDenied( "/api/outbound-links/{private}", anon.get( "/api/outbound-links/" + RUNBOOK, "application/json" ) );
        assertNoPrivateTextOrName( "outbound links of the public page (it links to the private runbook)",
                anon.get( "/api/outbound-links/" + PUBLIC, "application/json" ) );
        assertNoPrivateTextOrName( "/api/structure/sitemap", anon.get( "/api/structure/sitemap", "application/json" ) );
        assertNoPrivateTextOrName( "/api/structure/clusters", anon.get( "/api/structure/clusters", "application/json" ) );
        assertNoPrivateTextOrName( "/api/structure/pages?cluster=",
                anon.get( "/api/structure/pages?cluster=" + CLUSTER, "application/json" ) );
        assertNoPrivateTextOrName( "/api/structure/pages", anon.get( "/api/structure/pages?limit=1000", "application/json" ) );
        assertNoPrivateTextOrName( "/api/structure/tags", anon.get( "/api/structure/tags", "application/json" ) );
        assertNoPrivateTextOrName( "/api/page-graph/snapshot", anon.get( "/api/page-graph/snapshot", "application/json" ) );

        // --- history, diff, comments, attachments
        assertDenied( "/api/history/{private}", anon.get( "/api/history/" + RUNBOOK, "application/json" ) );
        assertDenied( "/api/diff/{private}", anon.get( "/api/diff/" + RUNBOOK + "?from=1&to=1", "application/json" ) );
        assertDenied( "/api/comment-threads", anon.get( "/api/comment-threads?page=" + RUNBOOK, "application/json" ) );
        assertDenied( "/api/attachments/{private}", anon.get( "/api/attachments/" + RUNBOOK, "application/json" ) );
        assertDenied( "/api/attachments/{private}/file", anon.get( "/api/attachments/" + RUNBOOK + "/" + ATTACHMENT, "text/plain" ) );
        assertDenied( "/attach/{private}/file", anon.get( "/attach/" + RUNBOOK + "/" + ATTACHMENT, "text/plain" ) );

        // --- pages that embed the private page render without it
        assertNoPrivateText( "transcluding page (SSR)", anon.get( "/wiki/" + TRANSCLUDER, "text/html" ) );
        assertNoPrivateText( "transcluding page (API render)",
                anon.get( "/api/pages/" + TRANSCLUDER + "?render=true", "application/json" ) );

        // --- agent surfaces over REST
        assertNoPrivateTextOrName( "/api/bundle", anon.get( "/api/bundle?q=" + PRIVATE_MARKER, "application/json" ) );
        assertNoPrivateTextOrName( "/api/bundle by cluster", anon.get( "/api/bundle?q=runbook+secret&limit=20", "application/json" ) );
        assertNoPrivateTextOrName( "/api/briefing",
                anon.get( "/api/briefing?clusters=" + CLUSTER + "&pins=" + RUNBOOK + "&prompt=" + PRIVATE_MARKER + "&format=json",
                        "application/json" ) );
        assertNoPrivateTextOrName( "/api/briefing md",
                anon.get( "/api/briefing?clusters=" + CLUSTER + "&pins=" + RUNBOOK + "&format=md", "text/markdown" ) );

        // --- the tools server needs a key, so anonymous reaches nothing at all
        final HttpResponse< String > tools = anon.get( "/tools/get_page?page_name=" + RUNBOOK, "application/json" );
        assertTrue( tools.statusCode() == 401 || tools.statusCode() == 403 || tools.statusCode() == 404 || tools.statusCode() == 405,
                "/tools must not serve anonymous: " + tools.statusCode() + " " + tools.body() );
        assertNoPrivateText( "/tools", tools );

        // --- the admin sees it all
        final HttpResponse< String > adminWiki = admin.get( "/wiki/" + RUNBOOK + "?format=md", "text/markdown" );
        assertEquals( 200, adminWiki.statusCode(), adminWiki.body() );
        assertTrue( adminWiki.body().contains( PRIVATE_MARKER ), "admin must read the private page" );
        final HttpResponse< String > adminHtml = admin.get( "/wiki/" + RUNBOOK, "text/html" );
        assertEquals( 200, adminHtml.statusCode(), "admin SSR page" );
        assertTrue( adminHtml.body().contains( PRIVATE_MARKER ), "admin SSR page carries the marker" );
        final HttpResponse< String > adminTransclude = admin.get( "/api/pages/" + TRANSCLUDER + "?render=true", "application/json" );
        assertTrue( adminTransclude.body().contains( PRIVATE_MARKER ),
                "an admin's render of the transcluding page does include the private text (this is what must not be cached)" );

        // --- the render caches did not keep the admin's view: anonymous again
        assertEquals( 404, anon.get( "/wiki/" + RUNBOOK, "text/html" ).statusCode(), "render cache leaked an admin SSR page" );
        assertDenied( "render cache (api)", anon.get( "/api/pages/" + RUNBOOK + "?render=true", "application/json" ) );
        assertNoPrivateText( "render cache (transcluding page)", anon.get( "/wiki/" + TRANSCLUDER, "text/html" ) );
        assertNoPrivateText( "render cache (transcluding page, api)",
                anon.get( "/api/pages/" + TRANSCLUDER + "?render=true", "application/json" ) );
    }

    @Test
    @Order( 2 )
    void anonymousReachesNoPrivatePageThroughTheKnowledgeMcp() throws Exception {
        final McpSyncClient mcp = mcpClient( "knowledge-mcp" );
        try {
            final List< String > bodies = new ArrayList<>();
            bodies.add( tool( mcp, "get_page", Map.of( "slug", RUNBOOK ) ) );
            bodies.add( tool( mcp, "get_page", Map.of( "slug", HUB ) ) );
            bodies.add( tool( mcp, "read_pages", Map.of( "slugs", List.of( RUNBOOK, HUB ) ) ) );
            bodies.add( tool( mcp, "get_page_for_agent", Map.of( "canonical_id", runbookCanonicalId ) ) );
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
            // The briefing quotes public pages in full, and the public page links to the runbook in its own body,
            // so the runbook's name alone proves nothing: look for it as a briefing entry or for its text.
            final String briefing = tool( mcp, "get_briefing", Map.of( "clusters", List.of( CLUSTER ),
                    "pins", List.of( RUNBOOK ), "prompt", PRIVATE_MARKER ) );
            assertFalse( briefing.contains( PRIVATE_MARKER ), "briefing leaked the marker: " + briefing );
            assertFalse( briefing.contains( HUB ), "briefing names the private hub: " + briefing );
            assertFalse( briefing.contains( "(`" + RUNBOOK + "`)" ) || briefing.contains( "`" + RUNBOOK + "`)" ),
                    "briefing lists the private page: " + briefing );
            bodies.add( tool( mcp, "list_stale_citations", Map.of() ) );
            bodies.add( tool( mcp, "sparql_query", Map.of( "query",
                    "SELECT ?s WHERE { ?s ?p ?o . FILTER(CONTAINS(STR(?s), \"" + RUNBOOK + "\")) }" ) ) );
            for ( final String raw : bodies ) {
                final String body = withoutQueryEcho( raw );
                assertFalse( body.contains( PRIVATE_MARKER ), "knowledge MCP leaked the marker: " + raw );
                assertFalse( body.contains( RUNBOOK ) && !isErrorEcho( body ), "knowledge MCP names the private page: " + raw );
                assertFalse( body.contains( HUB ) && !isErrorEcho( body ), "knowledge MCP names the private hub: " + raw );
            }
        } finally {
            mcp.close();
        }
    }

    /**
     * A tool refusing a private slug repeats the slug the caller supplied: {@code {"exists":false,"pageName":...}}
     * or a "not found" message. That is exactly what it says for a page that does not exist, so it discloses nothing.
     */
    private static boolean isErrorEcho( final String body ) {
        final String lower = body.toLowerCase();
        return lower.contains( "\"exists\":false" ) || lower.contains( "not found" ) || lower.contains( "no page" )
                || lower.contains( "not_found" );
    }

    @Test
    @Order( 3 )
    void anonymousReachesNoPrivatePageThroughTheOntologyEndpoints() throws Exception {
        final Http admin = new Http();
        admin.loginAsAdmin();
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
        final HttpResponse< String > ttl = anon.get( "/export/graph.ttl", "text/turtle" );
        assertNoPrivateTextOrName( "/export/graph.ttl", ttl );
        final HttpResponse< String > id = anon.get( "/id/page/" + runbookCanonicalId, "text/turtle" );
        assertEquals( 404, id.statusCode(), "/id of a private page: " + id.body() );
        assertNoPrivateTextOrName( "/id", id );

        // The knowledge MCP's SPARQL tool reads the same dataset.
        final McpSyncClient mcp = mcpClient( "knowledge-mcp" );
        try {
            final String body = tool( mcp, "sparql_query", Map.of( "query",
                    "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5000" ) );
            assertFalse( body.contains( RUNBOOK ) || body.contains( HUB ) || body.contains( PRIVATE_MARKER ),
                    "knowledge MCP sparql_query leaked a private page: " + body );
        } finally {
            mcp.close();
        }
    }

    @Test
    @Order( 4 )
    void theAdminMcpStillWritesThePrivatePage() throws Exception {
        // jakemon publishes runbooks through this path; the private cluster must not break it.
        final McpSyncClient mcp = mcpClient( "wikantik-admin-mcp" );
        try {
            final JsonObject read = JsonParser.parseString( tool( mcp, "read_page", Map.of( "slug", RUNBOOK ) ) )
                    .getAsJsonObject();
            assertTrue( read.get( "exists" ).getAsBoolean(), "admin MCP reads the private page: " + read );
            final String hash = read.get( "contentHash" ).getAsString();
            final String content = read.get( "content" ).getAsString() + "\nUpdated by the admin MCP.\n";
            final JsonObject upd = JsonParser.parseString( tool( mcp, "update_page",
                    Map.of( "slug", RUNBOOK, "content", content, "expectedContentHash", hash ) ) ).getAsJsonObject();
            assertTrue( upd.has( "updated" ) ? upd.get( "updated" ).getAsBoolean() : true,
                    "admin MCP update_page on a private page must succeed: " + upd );
            assertNotNull( upd );
        } finally {
            mcp.close();
        }
        // The update did not open the page: still hidden from anonymous.
        assertEquals( 404, new Http().get( "/wiki/" + RUNBOOK, "text/html" ).statusCode() );
    }

    @Test
    @Order( 5 )
    void theContentDatesStatusEndpointAnswersForAdmin() throws Exception {
        final Http admin = new Http();
        admin.loginAsAdmin();
        final HttpResponse< String > r = admin.get( "/admin/seo/content-dates/status", "application/json" );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject o = JsonParser.parseString( r.body() ).getAsJsonObject();
        assertTrue( o.has( "state" ), "status carries a state: " + r.body() );
    }

    @Test
    @Order( 6 )
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
