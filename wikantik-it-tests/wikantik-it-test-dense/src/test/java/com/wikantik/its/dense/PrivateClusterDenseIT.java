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
package com.wikantik.its.dense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The private-cluster sweep for the surfaces that need dense retrieval: the bundle, the briefing, the
 * knowledge MCP's retrieval tools and "similar pages". This module's wiki hides {@code it-private}.
 *
 * <p>A private page and a public page that is semantically almost identical to it are seeded. As Admin the
 * private page must be retrieved by meaning (the positive control, so an absence is never an artefact of an
 * un-embedded corpus); as an anonymous caller none of these surfaces may show it. Neither page shares a topic
 * with {@code DenseBundleIT}'s corpus, so that suite's ranking is unaffected.</p>
 */
class PrivateClusterDenseIT {

    private static final String BASE = System.getProperty( "it-wikantik.base.url" );
    private static final String PRIVATE_PAGE = "DenseCyclePrivate";
    private static final String PUBLIC_PAGE = "DenseCyclePublic";
    private static final String MARKER = "zzdenseprivmarker192";
    private static final String QUERY = "how do I stop my bike squealing when I slow down";
    private static final long INDEX_BUDGET_MS = 120_000L;

    private static final String BODY = "# Disc brake pads\n\nReplacing worn brake pads on a bicycle disc brake: remove the wheel, "
            + "push the caliper pistons back, fit the new pads and bed them in with a few hard stops.\n";

    private static HttpClient admin;
    private static HttpClient anonymous;

    @BeforeAll
    static void seed() throws Exception {
        admin = client();
        anonymous = client();
        final HttpResponse< String > login = send( admin, HttpRequest.newBuilder( URI.create( BASE + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( "{\"username\":\"janne\",\"password\":\"myP@5sw0rd\"}" ) ) );
        assertEquals( 200, login.statusCode(), "admin login: " + login.body() );
        put( PRIVATE_PAGE, "---\ncluster: it-private\nsummary: private bicycle brake notes\n---\n" + BODY
                + "\nThe private reference code is " + MARKER + ".\n" );
        put( PUBLIC_PAGE, "---\nsummary: bicycle brake notes\n---\n" + BODY + "\nSee also [" + PRIVATE_PAGE + "](" + PRIVATE_PAGE + ").\n" );
    }

    @Test
    void denseSurfacesNeverShowAPrivatePageToAnonymous() throws Exception {
        final String q = URLEncoder.encode( QUERY, StandardCharsets.UTF_8 );

        // Positive controls, as Admin: the private page is retrieved by meaning, and is the public page's neighbour.
        await( "the private page never appeared in an admin bundle", () -> get( admin, "/api/bundle?q=" + q ).body(),
                body -> body.contains( PRIVATE_PAGE ) && body.contains( PUBLIC_PAGE ) );
        await( "the private page never became the public page's similar page for an admin",
                () -> get( admin, "/api/pages/" + PUBLIC_PAGE + "/similar" ).body(), body -> body.contains( PRIVATE_PAGE ) );

        // Positive controls on the surfaces judged below: the public twin is found by the same queries.
        await( "dense /api/search never found the public twin", () -> get( anonymous, "/api/search?q=" + q ).body(),
                body -> body.contains( PUBLIC_PAGE ) );

        // As anonymous, immediately after, and then as a plain logged-in user.
        final String anonBundle = get( anonymous, "/api/bundle?q=" + q ).body();
        assertTrue( anonBundle.contains( PUBLIC_PAGE ), "positive control: the public twin is in the anonymous bundle: " + anonBundle );
        assertSurfacesHide( anonymous, "anonymous", q );
        final HttpClient plain = plainUser();
        assertTrue( get( plain, "/api/bundle?q=" + q ).body().contains( PUBLIC_PAGE ),
                "positive control: the public twin is in the plain user's bundle" );
        assertSurfacesHide( plain, "plain user", q );

        // The knowledge MCP's retrieval tools.
        final McpSession mcp = new McpSession( "knowledge-mcp" );
        final String retrieved = mcp.call( "retrieve_context", Map.of( "query", QUERY ) );
        assertTrue( retrieved.contains( PUBLIC_PAGE ), "positive control: the public twin is retrieved over MCP: " + retrieved );
        assertHidden( "mcp retrieve_context", retrieved );
        assertHidden( "mcp retrieve_context (marker)", mcp.call( "retrieve_context", Map.of( "query", MARKER ) ) );
        final String assembled = mcp.call( "assemble_bundle", Map.of( "query", QUERY ) );
        assertTrue( assembled.contains( PUBLIC_PAGE ), "positive control: the public twin is in the MCP bundle: " + assembled );
        assertHidden( "mcp assemble_bundle", assembled );
        assertHidden( "mcp get_briefing", mcp.call( "get_briefing", Map.of( "clusters", List.of( "it-private" ),
                "pins", List.of( PRIVATE_PAGE ), "prompt", QUERY ) ) );
        assertHidden( "mcp get_page", mcp.call( "get_page", Map.of( "slug", PRIVATE_PAGE ) ) );
    }

    // ------------------------------------------------------------------ assertions

    private static void assertSurfacesHide( final HttpClient who, final String label, final String q ) throws Exception {
        assertHidden( label + " /api/bundle", get( who, "/api/bundle?q=" + q ).body() );
        assertHidden( label + " /api/bundle?mode=dense", get( who, "/api/bundle?mode=dense&q=" + q ).body() );
        assertHidden( label + " /api/bundle?mode=lexical", get( who, "/api/bundle?mode=lexical&q=" + MARKER ).body() );
        assertHidden( label + " /api/search", get( who, "/api/search?q=" + q ).body() );
        assertHidden( label + " /api/pages/{public}/similar", get( who, "/api/pages/" + PUBLIC_PAGE + "/similar" ).body() );
        assertHidden( label + " /api/pages/{private}/similar", get( who, "/api/pages/" + PRIVATE_PAGE + "/similar" ).body() );
        assertHidden( label + " /api/briefing", get( who, "/api/briefing?clusters=it-private&pins=" + PRIVATE_PAGE
                + "&prompt=" + q + "&format=json" ).body() );
        // debug=rankings is administrators only: a caller who is not one is refused, and sees no ranking at all.
        final HttpResponse< String > rankings = get( who, "/api/bundle?debug=rankings&q=" + MARKER );
        assertEquals( 403, rankings.statusCode(), label + " debug=rankings must be refused: " + rankings.body() );
    }

    /** A plain account (no groups), created through the admin API, signed in with its password already changed. */
    private static HttpClient plainUser() throws Exception {
        final String login = "densesweepuser";
        final String initial = "ItDenseSweep1!";
        final HttpResponse< String > created = send( admin, HttpRequest.newBuilder( URI.create( BASE + "/admin/users" ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( "{\"loginName\":\"" + login + "\",\"fullName\":\"Dense Sweep User\","
                        + "\"email\":\"" + login + "@example.com\",\"password\":\"" + initial + "\"}" ) ) );
        assertEquals( 201, created.statusCode(), "create the plain user: " + created.body() );
        final HttpClient client = client();
        final HttpResponse< String > in = send( client, HttpRequest.newBuilder( URI.create( BASE + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString( "{\"username\":\"" + login + "\",\"password\":\"" + initial + "\"}" ) ) );
        assertEquals( 200, in.statusCode(), "plain user login: " + in.body() );
        final HttpResponse< String > changed = send( client, HttpRequest.newBuilder( URI.create( BASE + "/api/auth/profile" ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( "{\"currentPassword\":\"" + initial + "\",\"newPassword\":\"ItDenseSweep2!\"}" ) ) );
        assertEquals( 200, changed.statusCode(), "forced password change: " + changed.body() );
        return client;
    }

    /**
     * Neither the marker nor the private page's name may appear, ignoring the caller's own echoed query, the public
     * twin's link to the private page, and a refusal that repeats the slug (exactly what a missing page says).
     */
    private static void assertHidden( final String surface, final String raw ) {
        String body = raw.replace( "[" + PRIVATE_PAGE + "](" + PRIVATE_PAGE + ")", "" )
                .replace( "unknown pin: " + PRIVATE_PAGE, "" )
                .replace( "\"exists\":false,\"pageName\":\"" + PRIVATE_PAGE + "\"", "" );
        try {
            final JsonElement parsed = JsonParser.parseString( body );
            if ( parsed.isJsonObject() ) {
                final JsonObject o = parsed.getAsJsonObject();
                o.remove( "query" );
                o.remove( "prompt" );
                body = o.toString();
            }
        } catch ( final RuntimeException e ) {
            // not JSON (markdown): judged as the text it is, with the echoes above already removed
            body = body.strip();
        }
        assertFalse( body.contains( MARKER ), surface + " leaked the private marker: " + raw );
        assertFalse( body.contains( PRIVATE_PAGE ), surface + " names the private page: " + raw );
    }

    // ------------------------------------------------------------------ http

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler( secureCookieOverHttp() ).build();
    }

    private static HttpResponse< String > get( final HttpClient c, final String path ) throws Exception {
        return send( c, HttpRequest.newBuilder( URI.create( BASE + path ) ).header( "Accept", "application/json" ).GET() );
    }

    private static HttpResponse< String > send( final HttpClient c, final HttpRequest.Builder b ) throws IOException, InterruptedException {
        return c.send( b.build(), HttpResponse.BodyHandlers.ofString() );
    }

    private static void put( final String name, final String markdown ) throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "content", markdown );
        final HttpResponse< String > r = send( admin, HttpRequest.newBuilder( URI.create( BASE + "/api/pages/" + name ) )
                .header( "Content-Type", "application/json" ).header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString( body.toString() ) ) );
        assertEquals( 200, r.statusCode(), "seeding " + name + ": " + r.body() );
    }

    private interface Probe { String body() throws Exception; }

    private static void await( final String failure, final Probe probe, final java.util.function.Predicate< String > ready )
            throws Exception {
        final long deadline = System.currentTimeMillis() + INDEX_BUDGET_MS;
        String last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = probe.body();
            if ( ready.test( last ) ) {
                return;
            }
            Thread.sleep( 2_000L );
        }
        fail( failure + " within " + ( INDEX_BUDGET_MS / 1000 ) + "s. Last response: " + last );
    }

    /** A minimal streamable-HTTP MCP session: initialize, notifications/initialized, then tools/call. */
    private static final class McpSession {
        private final String endpoint;
        private final String sessionId;
        private int nextId = 1;

        McpSession( final String endpoint ) throws Exception {
            this.endpoint = endpoint;
            final HttpResponse< String > init = post( null, rpc( "initialize", Map.of(
                    "protocolVersion", "2025-03-26", "capabilities", Map.of(),
                    "clientInfo", Map.of( "name", "wikantik-it-private-cluster-dense", "version", "1.0.0" ) ) ) );
            assertEquals( 200, init.statusCode(), "mcp initialize: " + init.body() );
            sessionId = init.headers().firstValue( "Mcp-Session-Id" ).orElse( null );
            post( sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}" );
        }

        String call( final String tool, final Map< String, Object > args ) throws Exception {
            final HttpResponse< String > r = post( sessionId, rpc( "tools/call", Map.of( "name", tool, "arguments", args ) ) );
            for ( final String line : r.body().split( "\n" ) ) {
                if ( line.startsWith( "data:" ) ) {
                    final JsonObject result = JsonParser.parseString( line.substring( 5 ).trim() ).getAsJsonObject();
                    final JsonArray content = result.has( "result" ) ? result.getAsJsonObject( "result" ).getAsJsonArray( "content" ) : null;
                    return content == null || content.isEmpty() ? result.toString()
                            : content.get( 0 ).getAsJsonObject().get( "text" ).getAsString();
                }
            }
            return r.body();
        }

        private String rpc( final String method, final Map< String, Object > params ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "jsonrpc", "2.0" );
            o.addProperty( "id", nextId++ );
            o.addProperty( "method", method );
            o.add( "params", new com.google.gson.Gson().toJsonTree( params ) );
            return o.toString();
        }

        private HttpResponse< String > post( final String session, final String json ) throws Exception {
            final HttpRequest.Builder b = HttpRequest.newBuilder( URI.create( BASE + "/" + endpoint ) )
                    .header( "Content-Type", "application/json" )
                    .header( "Accept", "application/json, text/event-stream" )
                    .POST( HttpRequest.BodyPublishers.ofString( json ) );
            if ( session != null ) {
                b.header( "Mcp-Session-Id", session );
            }
            return send( anonymous, b );
        }
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
}
