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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collaborative editing ({@code /api/collab/*}) through the deployed wiki: the real filter chain, real Tomcat async for
 * the long-poll, the real page store. Collaboration is enabled for this module's wiki alone (the {@code it.extra.jvmargs} property in
 * the module pom passes {@code -Dwikantik.collab.enabled=true}), with {@code wikantik.collab.poll.timeoutSeconds = 4} so a
 * timed-out poll is quick.
 *
 * <p>The tiny {@link Peer} below speaks the wire contract of
 * {@code wikantik-frontend/src/collab/__fixtures__/collab-protocol.json}: it never applies its own pushes locally, only
 * what a pull returns (a pull returns the caller's own updates too), so what it holds is exactly the server's history.
 * The kill switch cannot be flipped from outside the JVM, so its 503 is pinned by the unit tests, not here.
 */
public class CollabRestIT {

    private static final Gson GSON = new Gson();
    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();
    /** wikantik.collab.poll.timeoutSeconds in the IT properties. */
    private static final long POLL_TIMEOUT_MS = 4_000;

    private static String baseUrl;
    private static HttpClient janne;
    private static HttpClient alice;
    private static HttpClient anonymous;

    private final List< String > pages = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        baseUrl = System.getProperty( "it-wikantik.base.url", "http://localhost:18080/wikantik-it-test-rest" );
        janne = login( "janne", "myP@5sw0rd" );
        alice = login( "Alice", "password" );
        anonymous = client();
    }

    @AfterEach
    void cleanup() {
        for( final String p : pages ) {
            try {
                send( janne, "DELETE", "/api/pages/" + p, null );
            } catch( final Exception e ) {
                System.err.println( "[CollabRestIT] cleanup of " + p + " failed: " + e );
            }
        }
    }

    // ---- the tests -------------------------------------------------------------------------------------------------

    @Test
    void collaborationIsReportedOnAndNeedsAnAuthenticatedCaller() throws Exception {
        final JsonObject caps = JsonParser.parseString( send( anonymous, "GET", "/api/capabilities", null ).body() )
            .getAsJsonObject();
        assertTrue( caps.getAsJsonObject( "collab" ).get( "enabled" ).getAsBoolean(), "capabilities: " + caps );

        final String page = newPage();
        final HttpResponse< String > join = send( anonymous, "POST", "/api/collab/" + page + "/join", "{}" );
        assertEquals( 401, join.statusCode(), join.body() );
        final HttpResponse< String > pull = send( anonymous, "GET",
            "/api/collab/" + page + "/updates?sessionId=x&participantId=y&since=0", null );
        assertEquals( 401, pull.statusCode(), pull.body() );
    }

    @Test
    void anotherUsersParticipantIdIsRefusedAsNotYours() throws Exception {
        final String page = seed( "Mine alone.\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );

        // B uses A's participantId on every kind of call.
        final String spoofBody = GSON.toJson( Map.of( "sessionId", b.sessionId, "participantId", a.participantId,
            "version", b.version, "updates", List.of(), "metaOps", List.of() ) );
        final HttpResponse< String > push = send( alice, "POST", "/api/collab/" + page + "/updates", spoofBody );
        assertEquals( 403, push.statusCode(), push.body() );
        assertEquals( "not-your-participant", reason( push ), push.body() );

        final HttpResponse< String > pull = send( alice, "GET", "/api/collab/" + page + "/updates?sessionId=" + b.sessionId
            + "&participantId=" + a.participantId + "&since=0&metaSince=0&presenceSince=0&eventsSince=0", null );
        assertEquals( 403, pull.statusCode(), pull.body() );
        assertEquals( "not-your-participant", reason( pull ), pull.body() );

        final HttpResponse< String > publish = send( alice, "POST", "/api/collab/" + page + "/publish",
            GSON.toJson( Map.of( "sessionId", b.sessionId, "participantId", a.participantId, "version", 0,
                "changeNote", "" ) ) );
        assertEquals( 403, publish.statusCode(), publish.body() );
        a.leave();
        b.leave();
    }

    /**
     * The page store adds a trailing newline to every save. A body without one must publish, leave the draft equal to
     * the page (the store's newline arrives as a server update), and a second publish must be "unchanged".
     */
    @Test
    void aBodyWithoutATrailingNewlinePublishesAndTheSecondPublishIsUnchanged() throws Exception {
        final String page = newPage();
        final Peer a = new Peer( janne, page ).join( "Alpha line" );
        assertEquals( "Alpha line", a.doc );

        final HttpResponse< String > first = a.publish( 0 );
        assertEquals( 200, first.statusCode(), first.body() );
        final int firstVersion = obj( first ).get( "pageVersion" ).getAsInt();
        assertEquals( "Alpha line\n", lf( pageContent( janne, page ) ), "the stored page" );

        // The store's trailing newline reaches the draft as a server update; the draft then equals the page.
        a.pull( 0 );
        assertEquals( "Alpha line\n", a.doc, "the draft converged on the stored text" );

        final HttpResponse< String > second = a.publish( a.version );
        assertEquals( 200, second.statusCode(), second.body() );
        assertEquals( firstVersion, obj( second ).get( "pageVersion" ).getAsInt(), "an unchanged publish saves no new version" );
        assertEquals( firstVersion, pageVersion( janne, page ), "the page was not saved again" );
        assertEquals( "Alpha line\n", lf( pageContent( janne, page ) ) );
        a.leave();
    }

    @Test
    void twoUsersEditInterleavedConvergeAndPublishBothEdits() throws Exception {
        final String page = seed( "Line one\n\nLine two\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );
        assertEquals( a.doc, b.doc, "both start from the stored page" );
        assertEquals( "Line one\n\nLine two\n", lf( a.doc ) );

        // Each sees the other as a live participant.
        b.pull( 0 );
        assertTrue( b.lastPull.getAsJsonObject( "presence" ).size() >= 1 || b.sawParticipantEvent(),
            "B knows about A: " + b.lastPull );

        // A edits at the start. B, still at the old version, edits the end: its push is a stale base.
        assertEquals( 200, a.insert( 0, "A: " ).statusCode() );
        final HttpResponse< String > stale = b.insert( b.doc.length(), "B: stale\n" );
        assertEquals( 409, stale.statusCode(), stale.body() );
        assertEquals( "stale-base", reason( stale ) );
        assertEquals( 1, obj( stale ).get( "version" ).getAsInt(), "the stale-base answer carries the head" );

        // B pulls, then pushes against the new head.
        b.pull( 0 );
        assertTrue( b.doc.startsWith( "A: Line one" ), b.doc );
        assertEquals( 200, b.insert( b.doc.length(), "B: end\n" ).statusCode() );

        a.pull( 0 );
        b.pull( 0 );
        assertEquals( a.doc, b.doc, "the drafts converged" );
        assertTrue( a.doc.startsWith( "A: Line one" ) && a.doc.endsWith( "B: end\n" ), a.doc );

        final HttpResponse< String > published = a.publish( a.version );
        assertEquals( 200, published.statusCode(), published.body() );
        final String stored = lf( pageContent( janne, page ) );
        assertTrue( stored.contains( "A: Line one" ) && stored.contains( "B: end" ), "the page holds both edits: " + stored );

        // The draft equals the page after the publish, on both sides.
        a.pull( 0 );
        b.pull( 0 );
        assertEquals( stored, lf( a.doc ) );
        assertEquals( stored, lf( b.doc ) );
        a.leave();
        b.leave();
    }

    /** A parked pull, through the real async-supported filter chain, wakes on the other user's push. */
    @Test
    void aParkedPullWakesOnAnotherUsersPush() throws Exception {
        final String page = seed( "Park me\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );
        b.pull( 0 );
        a.pull( 0 );

        final long t0 = System.nanoTime();
        final CompletableFuture< HttpResponse< String > > parked = b.pullAsync();
        Thread.sleep( 700 );
        assertFalse( parked.isDone(), "the poll is parked, not answered, while nothing changed" );
        assertEquals( 200, a.insert( 0, "wake! " ).statusCode() );

        final HttpResponse< String > woke = parked.get( POLL_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS );
        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 );
        assertEquals( 200, woke.statusCode(), woke.body() );
        assertEquals( 1, obj( woke ).getAsJsonArray( "updates" ).size(), woke.body() );
        assertTrue( elapsedMs < POLL_TIMEOUT_MS, "woken by the push (" + elapsedMs + " ms), not by the timeout" );
        a.leave();
        b.leave();
    }

    @Test
    void aParkedPullWithNothingToReportAnswersEmptyAtTheTimeout() throws Exception {
        final String page = seed( "Quiet\n" );
        final Peer a = new Peer( janne, page ).join( null );
        a.pull( 0 );
        final long t0 = System.nanoTime();
        final HttpResponse< String > r = a.pullAsync().get( POLL_TIMEOUT_MS * 4, TimeUnit.MILLISECONDS );
        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject o = obj( r );
        assertEquals( 0, o.getAsJsonArray( "updates" ).size() );
        assertEquals( 0, o.getAsJsonArray( "events" ).size() );
        assertTrue( elapsedMs >= POLL_TIMEOUT_MS - 1_000, "it was parked for about the timeout, not answered at once: " + elapsedMs );
        assertTrue( elapsedMs < POLL_TIMEOUT_MS * 3, "and it did answer: " + elapsedMs );
        a.leave();
    }

    /** A third client's ordinary save (an agent write) during the session reaches the draft; the publish keeps both. */
    @Test
    void anAgentWriteDuringTheSessionIsMergedWithTheDraftAtPublish() throws Exception {
        final String page = seed( "Para one\n\nPara two\n" );
        final Peer a = new Peer( janne, page ).join( null );
        assertEquals( 200, a.insert( 0, "A-edit " ).statusCode() );
        a.pull( 0 );

        final HttpResponse< String > agent = send( alice, "PUT", "/api/pages/" + page, GSON.toJson( Map.of(
            "content", "Para one\n\nPara two edited by agent\n", "metadata", Map.of( "type", "article" ) ) ) );
        assertEquals( 200, agent.statusCode(), agent.body() );

        // The outside change is folded into the draft; wait (bounded) for it to arrive, as the editor does.
        final long deadline = System.currentTimeMillis() + 20_000;
        while( !a.doc.contains( "edited by agent" ) && System.currentTimeMillis() < deadline ) {
            a.pull( 0 );
        }
        assertTrue( a.doc.contains( "edited by agent" ) && a.doc.contains( "A-edit" ), "the draft holds both: " + a.doc );

        HttpResponse< String > published = a.publish( a.version );
        for( int i = 0; i < 3 && published.statusCode() == 409; i++ ) {
            assertEquals( "publish-merged-retry", reason( published ), published.body() );
            a.pull( 0 );
            published = a.publish( a.version );
        }
        assertEquals( 200, published.statusCode(), published.body() );
        final String stored = lf( pageContent( janne, page ) );
        assertTrue( stored.contains( "A-edit" ) && stored.contains( "edited by agent" ), "both writes survive: " + stored );
        a.leave();
    }

    // ---- the peer --------------------------------------------------------------------------------------------------

    /** One participant of a collaboration session, as the browser's transport would be. */
    private static final class Peer {
        final HttpClient client;
        final String page;
        String sessionId;
        String participantId;
        String clientId;
        String doc = "";
        int version;
        long metaSeq;
        long presenceSeq;
        long eventSeq;
        JsonObject lastPull = new JsonObject();

        Peer( final HttpClient client, final String page ) {
            this.client = client;
            this.page = page;
        }

        Peer join( final String initialText ) throws Exception {
            final Map< String, Object > body = new HashMap<>();
            body.put( "priorClientId", null );
            body.put( "initialText", initialText );
            body.put( "initialMeta", null );
            final HttpResponse< String > r = send( client, "POST", "/api/collab/" + page + "/join", GSON_NULLS.toJson( body ) );
            assertEquals( 200, r.statusCode(), "join: " + r.body() );
            final JsonObject o = obj( r );
            sessionId = o.get( "sessionId" ).getAsString();
            participantId = o.get( "participantId" ).getAsString();
            clientId = o.get( "clientID" ).getAsString();
            version = o.get( "version" ).getAsInt();
            doc = o.get( "doc" ).getAsString();
            metaSeq = o.get( "metaSeq" ).getAsLong();
            eventSeq = o.has( "eventSeq" ) ? o.get( "eventSeq" ).getAsLong() : 0;
            return this;
        }

        /** Pushes an insertion at {@code pos} of the document this peer last pulled, at the version it last pulled. */
        HttpResponse< String > insert( final int pos, final String text ) throws Exception {
            final JsonObject update = new JsonObject();
            update.addProperty( "clientID", clientId );
            update.add( "changes", insertion( doc.length(), pos, text ) );
            final JsonArray updates = new JsonArray();
            updates.add( update );
            final JsonObject body = new JsonObject();
            body.addProperty( "sessionId", sessionId );
            body.addProperty( "participantId", participantId );
            body.addProperty( "version", version );
            body.add( "updates", updates );
            body.add( "metaOps", new JsonArray() );
            return send( client, "POST", "/api/collab/" + page + "/updates", body.toString() );
        }

        private String pullPath( final long presenceSince ) {
            return "/api/collab/" + page + "/updates?sessionId=" + enc( sessionId ) + "&participantId=" + enc( participantId )
                + "&since=" + version + "&metaSince=" + metaSeq + "&presenceSince=" + presenceSince
                + "&eventsSince=" + eventSeq;
        }

        CompletableFuture< HttpResponse< String > > pullAsync() {
            return client.sendAsync( request( "GET", pullPath( presenceSeq ), null ), HttpResponse.BodyHandlers.ofString() );
        }

        /** A pull that is answered at once when anything is ahead of the cursors (presenceSince: a full presence map). */
        Peer pull( final long presenceSince ) throws Exception {
            final HttpResponse< String > r = send( client, "GET", pullPath( presenceSince ), null );
            assertEquals( 200, r.statusCode(), "pull: " + r.body() );
            lastPull = obj( r );
            for( final JsonElement u : lastPull.getAsJsonArray( "updates" ) ) {
                doc = applyChanges( doc, u.getAsJsonObject().get( "changes" ).getAsJsonArray() );
                version++;
            }
            metaSeq = lastPull.get( "metaSeq" ).getAsLong();
            presenceSeq = lastPull.get( "presenceSeq" ).getAsLong();
            eventSeq = lastPull.get( "eventSeq" ).getAsLong();
            return this;
        }

        boolean sawParticipantEvent() {
            for( final JsonElement e : lastPull.getAsJsonArray( "events" ) ) {
                if( "participant-joined".equals( e.getAsJsonObject().get( "type" ).getAsString() ) ) {
                    return true;
                }
            }
            return false;
        }

        HttpResponse< String > publish( final int atVersion ) throws Exception {
            return send( client, "POST", "/api/collab/" + page + "/publish", GSON.toJson( Map.of( "sessionId", sessionId,
                "participantId", participantId, "version", atVersion, "changeNote", "collab IT" ) ) );
        }

        void leave() throws Exception {
            send( client, "POST", "/api/collab/" + page + "/leave", GSON.toJson( Map.of( "sessionId", sessionId,
                "participantId", participantId ) ) );
        }
    }

    // ---- ChangeSet JSON --------------------------------------------------------------------------------------------

    /** CodeMirror ChangeSet.toJSON() for one insertion into a document of {@code length} units. */
    static JsonArray insertion( final int length, final int pos, final String text ) {
        final JsonArray sections = new JsonArray();
        if( pos > 0 ) {
            sections.add( pos );
        }
        final JsonArray ins = new JsonArray();
        ins.add( 0 );
        for( final String line : text.split( "\n", -1 ) ) {
            ins.add( line );
        }
        sections.add( ins );
        if( length - pos > 0 ) {
            sections.add( length - pos );
        }
        return sections;
    }

    /** Applies ChangeSet JSON: a number keeps that many units, {@code [deleted, ...insertedLines]} replaces. */
    static String applyChanges( final String doc, final JsonArray changes ) {
        final StringBuilder out = new StringBuilder();
        int pos = 0;
        for( final JsonElement s : changes ) {
            if( s.isJsonPrimitive() ) {
                final int n = s.getAsInt();
                out.append( doc, pos, pos + n );
                pos += n;
            } else {
                final JsonArray a = s.getAsJsonArray();
                pos += a.get( 0 ).getAsInt();
                final List< String > lines = new ArrayList<>();
                for( int i = 1; i < a.size(); i++ ) {
                    lines.add( a.get( i ).getAsString() );
                }
                out.append( String.join( "\n", lines ) );
            }
        }
        assertEquals( doc.length(), pos, "the changes span the whole document" );
        return out.toString();
    }

    // ---- HTTP plumbing ---------------------------------------------------------------------------------------------

    private String newPage() {
        final String name = "CollabRest" + UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 10 );
        pages.add( name );
        return name;
    }

    private String seed( final String text ) throws Exception {
        final String name = newPage();
        final HttpResponse< String > r = send( janne, "PUT", "/api/pages/" + name, GSON.toJson( Map.of( "content", text,
            "metadata", Map.of( "type", "article" ) ) ) );
        assertEquals( 200, r.statusCode(), "seed: " + r.body() );
        return name;
    }

    private static String pageContent( final HttpClient c, final String page ) throws Exception {
        final HttpResponse< String > r = send( c, "GET", "/api/pages/" + page, null );
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonElement content = obj( r ).get( "content" );
        assertNotNull( content );
        return content.getAsString();
    }

    private static int pageVersion( final HttpClient c, final String page ) throws Exception {
        return obj( send( c, "GET", "/api/pages/" + page, null ) ).get( "version" ).getAsInt();
    }

    private static String lf( final String s ) {
        return s.replace( "\r\n", "\n" ).replace( '\r', '\n' );
    }

    private static String enc( final String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 );
    }

    private static JsonObject obj( final HttpResponse< String > r ) {
        return JsonParser.parseString( r.body() ).getAsJsonObject();
    }

    private static String reason( final HttpResponse< String > r ) {
        final JsonElement e = obj( r ).get( "reason" );
        return e == null ? null : e.getAsString();
    }

    private static HttpClient login( final String user, final String password ) throws Exception {
        final HttpClient c = client();
        final HttpResponse< String > r = send( c, "POST", "/api/auth/login",
            GSON.toJson( Map.of( "username", user, "password", password ) ) );
        assertEquals( 200, r.statusCode(), user + " login: " + r.body() );
        return c;
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

    private static HttpRequest request( final String method, final String path, final String json ) {
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri( URI.create( baseUrl + path ) )
            .header( "Accept", "application/json" );
        if( json == null ) {
            b.method( method, HttpRequest.BodyPublishers.noBody() );
        } else {
            b.header( "Content-Type", "application/json" ).method( method, HttpRequest.BodyPublishers.ofString( json ) );
        }
        return b.build();
    }

    private static HttpResponse< String > send( final HttpClient c, final String method, final String path, final String json )
            throws IOException, InterruptedException {
        return c.send( request( method, path, json ), HttpResponse.BodyHandlers.ofString() );
    }
}
