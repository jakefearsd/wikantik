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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cross-surface leak and bake-in check for live values ({@code {{wiki:name}}} tokens). A fixture page carries
 * tokens in its summary and body (one in a paragraph, one in a fence). Every reader surface must show the expanded
 * value and no stray token; the write/edit surfaces (admin MCP {@code read_page}, {@code GET /api/pages}
 * {@code content} and {@code metadata}) must keep the raw token, and no write path may bake a value into storage.
 *
 * <p>Not asserted: the {@code wiki://pages/...} admin MCP resources and the export zip (a compressed body would
 * prove nothing to a text search); both read raw text by ruling and are unit-tested.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class LiveValuesIT {

    private static final Gson GSON = new Gson();
    private static final String PAGE = "LiveValuesItFixture";
    private static final String TOKEN = "{{wiki:articles}}";
    private static final String SUMMARY_RAW = "Live values fixture page that states " + TOKEN + " articles across the wiki corpus";
    private static final String FENCE_TOKEN = "{{wiki:pages}}";
    private static final String BODY_RAW = "# Live values fixture\n\n"
            + "The wiki holds " + TOKEN + " articles today.\n\n"
            + "Knowledge tools: {{wiki:tools.knowledge}}. Admin tools: {{wiki:tools.admin}}.\n\n"
            + "```text\nliterally " + FENCE_TOKEN + " in a fence\n```\n";
    private static final String CONTENT = "---\ntype: article\nsummary: '" + SUMMARY_RAW + "'\n---\n" + BODY_RAW;

    private static final Pattern SUMMARY_VALUE = Pattern.compile( "states (\\d[\\d,]*) articles across" );
    private static final Pattern BODY_VALUE = Pattern.compile( "holds (\\d[\\d,]*) articles today" );
    private static final Pattern TOOLS_VALUE = Pattern.compile( "Knowledge tools: (\\d+)\\. Admin tools: (\\d+)\\." );
    private static final Pattern CANONICAL_ID = Pattern.compile( "canonical_id\\\\?\"?[:=]\\s*\\\\?\"?([0-9A-Za-z_-]{8,})" );
    private static final long BUDGET_MS = 90_000L;

    private static String baseUrl;
    private static String canonicalId;
    private static String toolsKey;
    private static int knowledgeTools;
    private static int adminTools;
    private static HttpClient admin;
    private static HttpClient anon;
    private static McpSyncClient knowledge;
    private static McpSyncClient adminMcp;

    @BeforeAll
    static void setUp() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        admin = newClient();
        anon = newClient();
        final HttpResponse< String > login = send( admin, "POST", "/api/auth/login",
                GSON.toJson( Map.of( "username", "janne", "password", "myP@5sw0rd" ) ), null );
        assertEquals( 200, login.statusCode(), "admin login: " + login.body() );
        final HttpResponse< String > key = send( admin, "POST", "/api/self/apikeys",
                GSON.toJson( Map.of( "label", "it-live-values", "scope", "tools" ) ), null );
        assertEquals( 201, key.statusCode(), "mint a tools key: " + key.body() );
        toolsKey = JsonParser.parseString( key.body() ).getAsJsonObject().get( "token" ).getAsString();
        knowledge = mcpClient( "knowledge-mcp" );
        adminMcp = mcpClient( "wikantik-admin-mcp" );
        knowledgeTools = knowledge.listTools().tools().size();
        adminTools = adminMcp.listTools().tools().size();
        assertTrue( knowledgeTools > 0 && adminTools > 0, "tool counts: " + knowledgeTools + "/" + adminTools );
    }

    @AfterAll
    static void tearDown() {
        for ( final McpSyncClient c : new McpSyncClient[] { knowledge, adminMcp } ) {
            if ( c != null ) {
                c.close();
            }
        }
    }

    // ------------------------------------------------------------------ the tests

    @Test
    @Order( 1 )
    void fixtureIsWrittenThroughTheAdminMcp() {
        final String result = tool( adminMcp, "write_pages", Map.of( "pages", List.of(
                Map.of( "slug", PAGE, "content", CONTENT ) ) ) );
        assertTrue( result.contains( "\"createdCount\":1" ) || result.contains( "\"updatedCount\":1" )
                || result.contains( "\"createdCount\": 1" ), "write_pages: " + result );
    }

    @Test
    @Order( 2 )
    void ssrShowsValuesInHeadBodyAndIsland() throws Exception {
        final String html = await( "SSR /wiki/" + PAGE, () -> get( anon, "/wiki/" + PAGE, "text/html" ).body(),
                b -> SUMMARY_VALUE.matcher( b ).find() && BODY_VALUE.matcher( b ).find() );
        assertExpandedTools( html );
        assertTrue( count( html, "{{wiki:" ) <= 1, "SSR may keep only the fenced token: " + html );
        assertTrue( html.contains( FENCE_TOKEN ), "the fenced token stays literal in the SSR body" );
        // The head carries the summary (meta description), the island carries it again: both expanded.
        final Matcher m = SUMMARY_VALUE.matcher( html );
        int summaries = 0;
        while ( m.find() ) {
            summaries++;
        }
        assertTrue( summaries >= 2, "summary expanded in head and island, found " + summaries + " in " + html );
        assertFalse( html.contains( "articles across" + "{{" ), "sanity" );
        canonicalId = canonicalIdOf();
    }

    @Test
    @Order( 3 )
    void apiPagesShowsExpandedRenderButRawContentAndMetadata() throws Exception {
        final String body = get( admin, "/api/pages/" + PAGE + "?render=true", "application/json" ).body();
        final JsonObject o = JsonParser.parseString( body ).getAsJsonObject();
        final String html = o.get( "contentHtml" ).getAsString();
        assertTrue( BODY_VALUE.matcher( html ).find(), "contentHtml expanded: " + html );
        assertExpandedTools( html );
        assertTrue( count( html, "{{wiki:" ) <= 1, "contentHtml keeps only the fenced token: " + html );
        final String resolved = o.getAsJsonObject( "resolved" ).get( "summary" ).getAsString();
        assertTrue( SUMMARY_VALUE.matcher( resolved ).find(), "resolved.summary expanded: " + resolved );
        // Write/edit surfaces stay raw.
        assertTrue( o.get( "content" ).getAsString().contains( TOKEN + " articles today" ), "content raw: " + body );
        assertEquals( SUMMARY_RAW, o.getAsJsonObject( "metadata" ).get( "summary" ).getAsString(), "metadata raw: " + body );
    }

    @Test
    @Order( 4 )
    void formatMdAndJsonShowValues() throws Exception {
        final String md = get( anon, "/wiki/" + PAGE + "?format=md", "text/markdown" ).body();
        assertTrue( BODY_VALUE.matcher( md ).find(), "format=md body expanded: " + md );
        assertExpandedTools( md );
        assertTrue( count( md, "{{wiki:" ) <= 1, "format=md keeps only the fenced token: " + md );
        final String json = get( anon, "/wiki/" + PAGE + "?format=json", "application/json" ).body();
        assertTrue( BODY_VALUE.matcher( json ).find() || SUMMARY_VALUE.matcher( json ).find(),
                "format=json expanded: " + json );
        assertTrue( count( json, "{{wiki:" ) <= 1, "format=json keeps only the fenced token: " + json );
        assertFalse( json.contains( TOKEN ), "format=json: no live token outside the fence: " + json );
    }

    @Test
    @Order( 5 )
    void feedStructureByIdAndForAgentShowValues() throws Exception {
        final String feed = await( "/feed.xml", () -> get( anon, "/feed.xml", "application/atom+xml" ).body(),
                b -> b.contains( PAGE ) );
        assertTrue( count( feed, "{{wiki:" ) == 0, "Atom feed carries no token: " + feed );
        assertTrue( SUMMARY_VALUE.matcher( feed ).find(), "Atom summary expanded: " + feed );

        final String structure = await( "/api/structure/pages", () -> get( anon, "/api/structure/pages?limit=1000",
                "application/json" ).body(), b -> b.contains( PAGE ) );
        assertTrue( count( structure, "{{wiki:" ) == 0, "structure carries no token: " + structure );
        assertTrue( SUMMARY_VALUE.matcher( structure ).find(), "structure summary expanded: " + structure );

        final String byId = get( anon, "/api/pages/by-id/" + canonicalId, "application/json" ).body();
        assertNoLeak( "by-id", byId, 1 );
        assertTrue( BODY_VALUE.matcher( byId ).find() || SUMMARY_VALUE.matcher( byId ).find(), "by-id expanded: " + byId );

        final String forAgent = get( anon, "/api/pages/for-agent/" + canonicalId, "application/json" ).body();
        assertNoLeak( "for-agent", forAgent, 1 );
        assertTrue( BODY_VALUE.matcher( forAgent ).find() || SUMMARY_VALUE.matcher( forAgent ).find(),
                "for-agent expanded: " + forAgent );
    }

    @Test
    @Order( 6 )
    void knowledgeMcpToolsShowValues() {
        final String page = tool( knowledge, "get_page", Map.of( "slug", PAGE ) );
        assertNoLeak( "get_page", page, 1 );
        assertTrue( BODY_VALUE.matcher( page ).find() || SUMMARY_VALUE.matcher( page ).find(), "get_page: " + page );
        final String read = tool( knowledge, "read_pages", Map.of( "slugs", List.of( PAGE ) ) );
        assertNoLeak( "read_pages", read, 1 );
        assertTrue( BODY_VALUE.matcher( read ).find() || SUMMARY_VALUE.matcher( read ).find(), "read_pages: " + read );
        final String byId = tool( knowledge, "get_page_by_id", Map.of( "canonical_id", canonicalId ) );
        assertNoLeak( "get_page_by_id", byId, 1 );
        assertTrue( BODY_VALUE.matcher( byId ).find() || SUMMARY_VALUE.matcher( byId ).find(), "get_page_by_id: " + byId );
    }

    @Test
    @Order( 7 )
    void retrievalAndToolsServerShowValues() throws Exception {
        final String retrieved = await( "retrieve_context", () -> tool( knowledge, "retrieve_context",
                Map.of( "query", "live values fixture articles wiki corpus" ) ), b -> b.contains( PAGE ) );
        assertNoLeak( "retrieve_context", retrieved, 1 );

        final String page = bearer( "GET", "/tools/page/" + PAGE, null ).body();
        assertNoLeak( "/tools/page", page, 1 );
        assertTrue( BODY_VALUE.matcher( page ).find(), "/tools/page body expanded: " + page );
        final String search = await( "/tools/search_wiki", () -> bearer( "POST", "/tools/search_wiki",
                GSON.toJson( Map.of( "query", "live values fixture" ) ) ).body(), b -> b.contains( PAGE ) );
        assertNoLeak( "/tools/search_wiki", search, 1 );
    }

    @Test
    @Order( 8 )
    void toolCountsAreTheEndpointsOwn() throws Exception {
        final String html = get( anon, "/wiki/" + PAGE, "text/html" ).body();
        final Matcher m = TOOLS_VALUE.matcher( html );
        assertTrue( m.find(), "tool counts rendered: " + html );
        assertEquals( knowledgeTools, Integer.parseInt( m.group( 1 ) ), "{{wiki:tools.knowledge}} == tools/list size" );
        assertEquals( adminTools, Integer.parseInt( m.group( 2 ) ), "{{wiki:tools.admin}} == tools/list size" );
    }

    @Test
    @Order( 9 )
    void adminMcpReadPageKeepsTheRawTokens() {
        assertRaw( rawContentViaMcp(), "admin MCP read_page" );
    }

    @Test
    @Order( 10 )
    void noWritePathBakesAValueIn() throws Exception {
        // 1. The SPA editor round trip: GET /api/pages content, PUT it back unchanged.
        final JsonObject got = JsonParser.parseString( get( admin, "/api/pages/" + PAGE, "application/json" ).body() ).getAsJsonObject();
        final String content = got.get( "content" ).getAsString();
        final HttpResponse< String > put = send( admin, "PUT", "/api/pages/" + PAGE,
                GSON.toJson( Map.of( "content", content, "metadata", got.get( "metadata" ), "changeNote", "LiveValuesIT" ) ), null );
        assertTrue( put.statusCode() == 200 || put.statusCode() == 201, "SPA-style PUT: " + put.statusCode() + " " + put.body() );
        assertRaw( rawContentViaMcp(), "after the SPA-style PUT" );

        // 2. update_page with an unrelated body edit.
        final JsonObject read = JsonParser.parseString( tool( adminMcp, "read_page", Map.of( "slug", PAGE ) ) ).getAsJsonObject();
        final JsonObject upd = JsonParser.parseString( tool( adminMcp, "update_page", Map.of( "slug", PAGE,
                "content", read.get( "content" ).getAsString() + "\nAn unrelated closing line.\n",
                "expectedContentHash", read.get( "contentHash" ).getAsString() ) ) ).getAsJsonObject();
        assertTrue( upd.has( "updated" ) && upd.get( "updated" ).getAsBoolean(), "update_page: " + upd );
        final String afterUpdate = rawContentViaMcp();
        assertRaw( afterUpdate, "after update_page" );
        assertTrue( afterUpdate.contains( "An unrelated closing line." ), "the edit was stored: " + afterUpdate );

        // 3. mark_page_verified rewrites the frontmatter.
        final String verified = tool( adminMcp, "mark_page_verified", Map.of( "slugs", List.of( PAGE ), "verifier", "janne" ) );
        assertTrue( verified.contains( "\"ok\":true" ), "mark_page_verified: " + verified );
        final String afterVerify = rawContentViaMcp();
        assertRaw( afterVerify, "after mark_page_verified" );
        assertTrue( afterVerify.contains( "verified_at" ), "verification stored: " + afterVerify );

        // The reader still sees values after all of it.
        final String html = get( anon, "/wiki/" + PAGE, "text/html" ).body();
        assertTrue( SUMMARY_VALUE.matcher( html ).find() && BODY_VALUE.matcher( html ).find(), "still expanded: " + html );
    }

    // ------------------------------------------------------------------ assertions

    private static void assertRaw( final String content, final String when ) {
        // YAML may fold the long summary across lines, so check the head of it
        assertTrue( content.contains( "states " + TOKEN + " articles across" ), when + ": summary token intact: " + content );
        assertTrue( content.contains( "holds " + TOKEN + " articles today" ), when + ": body token intact: " + content );
        assertTrue( content.contains( "{{wiki:tools.knowledge}}" ) && content.contains( "{{wiki:tools.admin}}" ),
                when + ": tool tokens intact: " + content );
        assertTrue( content.contains( FENCE_TOKEN ), when + ": fence intact: " + content );
    }

    private static void assertExpandedTools( final String text ) {
        final Matcher m = TOOLS_VALUE.matcher( text );
        assertTrue( m.find(), "tool counts expanded in: " + text );
    }

    /** No live token other than the (at most {@code allowedFenced}) literal one in the fence. */
    private static void assertNoLeak( final String surface, final String text, final int allowedFenced ) {
        assertTrue( count( text, "{{wiki:" ) <= allowedFenced, surface + " leaks a token: " + text );
        assertFalse( text.contains( TOKEN ), surface + " leaks " + TOKEN + ": " + text );
        assertFalse( text.contains( "{{wiki:tools." ), surface + " leaks a tools token: " + text );
        assertFalse( text.contains( "\\u007b\\u007bwiki:" ), surface + " leaks an escaped token: " + text );
    }

    private static int count( final String text, final String needle ) {
        int n = 0;
        for ( int i = text.indexOf( needle ); i >= 0; i = text.indexOf( needle, i + 1 ) ) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ helpers

    private static String rawContentViaMcp() {
        final JsonObject read = JsonParser.parseString( tool( adminMcp, "read_page", Map.of( "slug", PAGE ) ) ).getAsJsonObject();
        final JsonElement c = read.get( "content" );
        assertNotNull( c, "read_page content: " + read );
        return c.getAsString();
    }

    private static String canonicalIdOf() throws Exception {
        final String body = get( admin, "/api/pages/" + PAGE, "application/json" ).body();
        final Matcher m = CANONICAL_ID.matcher( body );
        assertTrue( m.find(), "no canonical_id in " + body );
        return m.group( 1 );
    }

    private static String await( final String what, final ThrowingSupplier< String > fetch, final Predicate< String > ready )
            throws Exception {
        final long deadline = System.currentTimeMillis() + BUDGET_MS;
        String last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = fetch.get();
            if ( ready.test( last ) ) {
                return last;
            }
            Thread.sleep( 500 );
        }
        fail( what + " never reached the expected state within " + ( BUDGET_MS / 1000 ) + "s; last: " + last );
        return last;
    }

    @FunctionalInterface
    private interface ThrowingSupplier< T > {
        T get() throws Exception;
    }

    private static HttpResponse< String > bearer( final String method, final String path, final String json ) throws Exception {
        final HttpRequest.Builder rb = HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Accept", "application/json" ).header( "Authorization", "Bearer " + toolsKey );
        if ( json == null ) {
            rb.GET();
        } else {
            rb.header( "Content-Type", "application/json" ).method( method, HttpRequest.BodyPublishers.ofString( json ) );
        }
        return HttpClient.newHttpClient().send( rb.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< String > get( final HttpClient c, final String path, final String accept ) throws Exception {
        return c.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) ).header( "Accept", accept ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpResponse< String > send( final HttpClient c, final String method, final String path, final String json,
                                                final String unused ) throws Exception {
        return c.send( HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .method( method, HttpRequest.BodyPublishers.ofString( json ) ).build(), HttpResponse.BodyHandlers.ofString() );
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).cookieHandler( secureCookieOverHttp() ).build();
    }

    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri, final Map< String, List< String > > h ) throws IOException {
                return cm.get( URI.create( uri.toString().replaceFirst( "^http:", "https:" ) ), h );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > h ) throws IOException {
                cm.put( uri, h );
            }
        };
    }

    private static McpSyncClient mcpClient( final String endpoint ) {
        final String prefix = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";
        final McpSyncClient client = McpClient.sync( HttpClientStreamableHttpTransport.builder( prefix )
                        .endpoint( endpoint ).connectTimeout( Duration.ofSeconds( 15 ) ).build() )
                .clientInfo( new McpSchema.Implementation( "wikantik-it-live-values", "1.0.0" ) )
                .requestTimeout( Duration.ofSeconds( 30 ) )
                .initializationTimeout( Duration.ofSeconds( 30 ) )
                .build();
        client.initialize();
        return client;
    }

    private static String tool( final McpSyncClient mcp, final String name, final Map< String, Object > args ) {
        final McpSchema.CallToolResult result = mcp.callTool( new McpSchema.CallToolRequest( name, args ) );
        if ( result == null || result.content() == null || result.content().isEmpty() ) {
            return "";
        }
        return result.content().get( 0 ) instanceof McpSchema.TextContent tc ? tc.text() : "";
    }
}
