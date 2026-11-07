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
    /** How long a test lets a long-poll reach the server and park, per attempt; each well below the poll timeout. */
    private static final long[] PARK_DWELLS_MS = { 700, 1_500, 2_500 };

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
        assertTrue( b.lastPull.getAsJsonObject( "presence" ).has( a.participantId ),
            "B's presence map names A (" + a.participantId + "): " + b.lastPull );

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
        // Whether the poll reached the server before the push does not change this test's answer (a late poll catches up
        // with the same update), so one dwell suffices: only "unanswered while nothing changed" needs it, bounded.
        final CompletableFuture< HttpResponse< String > > parked = parkedPull( b, PARK_DWELLS_MS[ 0 ] );
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
            a.pull( a.presenceSeq ); // parks as a real long-poll until something is ahead of the cursors
        }
        assertTrue( a.doc.contains( "edited by agent" ) && a.doc.contains( "A-edit" ), "the draft holds both: " + a.doc );
        final JsonObject change = a.event( "external-change" );
        assertNotNull( change, "the agent write reached A as an external-change event: " + a.events );
        assertTrue( change.has( "source" ) && change.has( "pageVersion" ), change.toString() );
        final String beforePublish = lf( pageContent( janne, page ) );
        assertTrue( beforePublish.contains( "edited by agent" ) && !beforePublish.contains( "A-edit" ),
            "A's own edit is still only in the draft, nothing was published: " + beforePublish );

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

    /** A and B add different tags at the same time (presence-only pushes carrying meta ops); the publish has both. */
    @Test
    void twoUsersAddDifferentTagsConcurrentlyAndBothArePublished() throws Exception {
        final String page = seed( "Tag me\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );

        final CompletableFuture< HttpResponse< String > > fa = CompletableFuture.supplyAsync( () -> a.pushMeta( tagAdd( "tag-from-a" ), null ) );
        final CompletableFuture< HttpResponse< String > > fb = CompletableFuture.supplyAsync( () -> b.pushMeta( tagAdd( "tag-from-b" ), null ) );
        assertEquals( 200, fa.get( 20, TimeUnit.SECONDS ).statusCode() );
        assertEquals( 200, fb.get( 20, TimeUnit.SECONDS ).statusCode() );

        a.pull( 0 );
        b.pull( 0 );
        for( final Peer p : List.of( a, b ) ) {
            final String ops = p.lastPull.getAsJsonArray( "metaOps" ).toString();
            assertTrue( ops.contains( "tag-from-a" ) && ops.contains( "tag-from-b" ), "both tags reached " + p.participantId + ": " + ops );
        }
        final HttpResponse< String > published = a.publish( a.version );
        assertEquals( 200, published.statusCode(), published.body() );
        final String tags = obj( send( janne, "GET", "/api/pages/" + page, null ) ).getAsJsonObject( "metadata" )
            .get( "tags" ).toString();
        assertTrue( tags.contains( "tag-from-a" ) && tags.contains( "tag-from-b" ), "both tags are in the published frontmatter: " + tags );
        a.leave();
        b.leave();
    }

    /** B's selection (a presence-only push) appears in A's pull, under B's participant id, with its coordinates. */
    @Test
    void aSelectionPushedByOneUserAppearsInTheOthersPull() throws Exception {
        final String page = seed( "Select some of this\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );
        final JsonObject selection = new JsonObject();
        selection.addProperty( "anchor", 2 );
        selection.addProperty( "head", 6 );
        selection.addProperty( "version", b.version );
        selection.addProperty( "unconfirmed", 0 );
        assertEquals( 200, b.pushMeta( new JsonArray(), selection ).statusCode() );

        a.pull( 0 );
        final JsonObject p = a.lastPull.getAsJsonObject( "presence" ).getAsJsonObject( b.participantId );
        assertNotNull( p, "B is in A's presence map: " + a.lastPull );
        assertEquals( 2, p.get( "anchor" ).getAsInt() );
        assertEquals( 6, p.get( "head" ).getAsInt() );
        assertFalse( p.get( "displayName" ).getAsString().isBlank(), p.toString() );
        a.leave();
        b.leave();
    }

    /** A rename through the REST endpoint: a page-renamed event, and every later call under the new name works. */
    @Test
    void aRenameGivesAPageRenamedEventAndTheSessionContinuesUnderTheNewName() throws Exception {
        final String page = seed( "Rename me\n" );
        final String renamed = newPage();
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );

        final HttpResponse< String > r = send( janne, "POST", "/api/pages/" + page + "/rename",
            GSON.toJson( Map.of( "newName", renamed, "changeReferrers", false ) ) );
        assertEquals( 200, r.statusCode(), r.body() );

        final JsonObject ev = a.awaitEvent( "page-renamed" );
        assertEquals( renamed, ev.get( "to" ).getAsString(), ev.toString() );
        a.page = renamed;
        b.awaitEvent( "page-renamed" );
        b.page = renamed;

        assertEquals( 200, a.insert( 0, "after rename " ).statusCode(), "a push under the new name" );
        b.pull( 0 );
        assertTrue( b.doc.startsWith( "after rename " ), b.doc );
        final HttpResponse< String > published = a.publish( a.version );
        assertEquals( 200, published.statusCode(), published.body() );
        assertTrue( lf( pageContent( janne, renamed ) ).startsWith( "after rename " ) );
        a.leave();
        b.leave();
    }

    /** A delete freezes the draft (page-deleted, then 409 frozen on a push); a recreate publish by an admin succeeds. */
    @Test
    void aDeleteFreezesTheDraftAndAnAdminCanRecreateIt() throws Exception {
        final String page = seed( "Delete me\n" );
        final Peer a = new Peer( janne, page ).join( null );
        assertEquals( 200, a.insert( 0, "keep this " ).statusCode() );
        a.pull( 0 );

        assertEquals( 200, send( janne, "DELETE", "/api/pages/" + page, null ).statusCode() );
        assertNotNull( a.awaitEvent( "page-deleted" ), "page-deleted reached A" );

        final HttpResponse< String > push = a.insert( 0, "late " );
        assertEquals( 409, push.statusCode(), push.body() );
        assertEquals( "frozen", reason( push ), push.body() );

        final HttpResponse< String > recreate = a.publish( a.version );
        assertEquals( 200, recreate.statusCode(), recreate.body() );
        assertTrue( lf( pageContent( janne, page ) ).startsWith( "keep this " ), "the draft's text is the recreated page" );
        a.leave();
    }

    // ---- Phase 3: draft summary, attribution, draft_active, audit, metrics, admin purge -----------------------------

    private static final String JANNE_NAME = "Janne Jalkanen";
    private static final String ALICE_NAME = "Alice";

    /** Both users edit, A publishes: the history lists both at the new version by display name; a later edit is the next version's. */
    @Test
    void bothContributorsAreCreditedInTheHistoryAndALaterEditBelongsToTheNextVersion() throws Exception {
        final String page = seed( "Credit line\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );

        assertEquals( 200, a.insert( 0, "A-EDIT " ).statusCode() );
        b.pull( 0 );
        assertEquals( 200, b.insert( b.doc.length(), "B-EDIT\n" ).statusCode() );
        a.pull( 0 );
        final HttpResponse< String > published = a.publish( a.version );
        assertEquals( 200, published.statusCode(), published.body() );
        final int first = obj( published ).get( "pageVersion" ).getAsInt();

        final List< String > firstContributors = contributorsAt( page, first );
        assertTrue( firstContributors.contains( JANNE_NAME ) && firstContributors.contains( ALICE_NAME ),
            "v" + first + " credits both by display name: " + firstContributors );
        assertEquals( JANNE_NAME, firstContributors.get( 0 ), "the publisher (the version's author) is listed first" );
        for( final String c : firstContributors ) {
            assertFalse( c.equals( "janne" ) || c.contains( "@" ), "never a login or an email: " + firstContributors );
        }

        // An edit after the publish point is credited to the next version, not to the one just published.
        b.pull( 0 );
        assertEquals( 200, b.insert( b.doc.length(), "B-LATER\n" ).statusCode() );
        final HttpResponse< String > second = b.publish( b.version );
        assertEquals( 200, second.statusCode(), second.body() );
        final int next = obj( second ).get( "pageVersion" ).getAsInt();
        assertTrue( next > first, "a new version: " + next + " > " + first );
        assertEquals( List.of( ALICE_NAME ), contributorsAt( page, next ), "only Alice contributed to v" + next );
        assertTrue( contributorsAt( page, first ).contains( JANNE_NAME ), "v" + first + " keeps its credits" );
        a.leave();
        b.leave();
    }

    /** {@code GET /draft} answers the HTML diff and the metadata changes, and the contributors by display name. */
    @Test
    void theDraftSummaryHoldsTheServerDiffAndTheMetadataChanges() throws Exception {
        final String page = seed( "Summary base\n" );
        final Peer a = new Peer( janne, page ).join( null );
        assertEquals( 200, a.insert( 0, "DIFF-MARKER " ).statusCode() );
        final String tag = "drafttag" + UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 6 );
        assertEquals( 200, a.pushMeta( tagAdd( tag ), null ).statusCode() );

        final HttpResponse< String > r = a.draft();
        assertEquals( 200, r.statusCode(), r.body() );
        final JsonObject o = obj( r );
        assertTrue( o.get( "unpublished" ).getAsBoolean(), r.body() );
        assertTrue( o.get( "diff" ).getAsString().contains( "DIFF-MARKER" ), "the diff names the new text: " + r.body() );
        assertTrue( o.get( "diff" ).getAsString().contains( "<" ), "the diff is the server's HTML" );
        assertTrue( o.getAsJsonArray( "contributors" ).toString().contains( JANNE_NAME ), r.body() );
        boolean tagsChanged = false;
        for( final JsonElement c : o.getAsJsonArray( "metaChanges" ) ) {
            tagsChanged |= "tags".equals( c.getAsJsonObject().get( "field" ).getAsString() );
        }
        assertTrue( tagsChanged, "metaChanges lists the tags field: " + r.body() );
        a.leave();
    }

    /** {@code draft_active} is true on both page reads while a draft has unpublished changes, and false after the publish. */
    @Test
    void draftActiveIsTrueWhileTheDraftIsUnpublishedAndFalseAfterThePublish() throws Exception {
        final String page = seed( "Active base\n" );
        final String canonicalId = obj( send( janne, "GET", "/api/pages/" + page, null ) ).getAsJsonObject( "metadata" )
            .get( "canonical_id" ).getAsString();
        assertFalse( draftActive( page, canonicalId ).get( 0 ), "no draft yet: page read" );

        final Peer a = new Peer( janne, page ).join( null );
        assertEquals( 200, a.insert( 0, "ACTIVE " ).statusCode() );
        awaitDraftActive( page, canonicalId, true );

        final HttpResponse< String > published = a.publish( a.version );
        assertEquals( 200, published.statusCode(), published.body() );
        // draft_active also holds while anyone is editing (design: live-session-first), so it clears once A has left
        a.leave();
        awaitDraftActive( page, canonicalId, false );
    }

    /** The collaboration audit entries: join, publish (with both contributor keys), discard; and the metrics scrape. */
    @Test
    void theAuditLogAndTheMetricsScrapeSeeTheSession() throws Exception {
        final String page = seed( "Audit base\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );
        assertEquals( 200, a.insert( 0, "A " ).statusCode() );
        b.pull( 0 );
        assertEquals( 200, b.insert( b.doc.length(), "B\n" ).statusCode() );
        a.pull( 0 );
        assertEquals( 200, a.publish( a.version ).statusCode() );

        a.pull( 0 );
        assertEquals( 200, a.insert( 0, "DISCARD-ME " ).statusCode() );
        final HttpResponse< String > discarded = a.discard( a.version );
        assertEquals( 204, discarded.statusCode(), discarded.body() );

        assertNotNull( awaitAudit( "collab.join", page ), "collab.join" );
        final JsonObject publish = awaitAudit( "collab.publish", page );
        final String detail = publish.get( "detail" ).getAsString();
        assertTrue( detail.contains( "janne" ) && detail.contains( "Alice" ), "both contributor keys: " + detail );
        assertNotNull( awaitAudit( "collab.discard", page ), "collab.discard" );

        final HttpResponse< String > scrape = send( janne, "GET", "/metrics", null );
        assertEquals( 200, scrape.statusCode(), "metrics scrape: " + scrape.body() );
        assertTrue( scrape.body().contains( "wikantik_collab_push_total" ), "push counter scraped" );
        assertTrue( scrape.body().contains( "wikantik_collab_sessions_active" ), "sessions gauge scraped" );
        a.leave();
        b.leave();
    }

    /** An administrator lists the drafts and purges one; the participant's next call is refused as evicted (purged). */
    @Test
    void anAdminListsDraftsAndPurgesOneAndTheParticipantIsEvicted() throws Exception {
        // B's long-poll must be parked when the purge lands; no server signal says it has arrived, so a poll that
        // arrived after the purge (answered 403/409 instead) retries the scenario on a fresh page with a longer dwell
        for( int attempt = 0; attempt < PARK_DWELLS_MS.length; attempt++ ) {
            if( purgeWhileParked( PARK_DWELLS_MS[ attempt ], attempt == PARK_DWELLS_MS.length - 1 ) ) {
                return;
            }
        }
    }

    /** One attempt; false (retry) when B's poll arrived after the purge and {@code last} is not set. */
    private boolean purgeWhileParked( final long dwellMs, final boolean last ) throws Exception {
        final String page = seed( "Purge base\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );
        assertEquals( 200, a.insert( 0, "PURGE-ME " ).statusCode() );
        b.pull( 0 );

        assertEquals( 403, send( alice, "GET", "/admin/collab/drafts", null ).statusCode(), "a non-admin is refused" );
        final JsonArray drafts = obj( send( janne, "GET", "/admin/collab/drafts", null ) ).getAsJsonArray( "drafts" );
        String id = null;
        for( final JsonElement d : drafts ) {
            if( page.equals( d.getAsJsonObject().get( "pageName" ).getAsString() ) ) {
                id = d.getAsJsonObject().get( "sessionId" ).getAsString();
            }
        }
        assertNotNull( id, "the draft is listed: " + drafts );

        // B is parked in a long-poll when the purge lands, as an open editor is: that pull is answered with the event.
        final CompletableFuture< HttpResponse< String > > parked = parkedPull( b, dwellMs );
        final HttpResponse< String > purge = send( janne, "DELETE", "/admin/collab/drafts/" + id, null );
        assertEquals( 204, purge.statusCode(), purge.body() );

        final HttpResponse< String > told = parked.get( 20, TimeUnit.SECONDS );
        if( !last && ( told.statusCode() == 403 || told.statusCode() == 409 ) ) {
            return false;   // the poll reached the server only after the purge: not parked yet
        }
        assertEquals( 200, told.statusCode(), told.body() );
        boolean evictedEvent = false;
        for( final JsonElement e : obj( told ).getAsJsonArray( "events" ) ) {
            final JsonObject ev = e.getAsJsonObject();
            evictedEvent |= "evicted".equals( ev.get( "type" ).getAsString() ) && "purged".equals( ev.get( "reason" ).getAsString() );
        }
        assertTrue( evictedEvent, "the parked pull carries the evicted (purged) event: " + told.body() );
        // A call with no parked pull to carry the tombstone finds no session: 409 rejoin (a known, parked wart: the
        // client then rejoins a fresh draft). Either answer refuses the push; neither lets it land.
        final HttpResponse< String > push = b.insert( 0, "late " );
        assertTrue( push.statusCode() == 403 || push.statusCode() == 409, "the push is refused: " + push.statusCode() + " " + push.body() );
        assertNotNull( awaitAudit( "collab.purge", page ), "collab.purge audited" );
        final JsonArray after = obj( send( janne, "GET", "/admin/collab/drafts", null ) ).getAsJsonArray( "drafts" );
        for( final JsonElement d : after ) {
            assertFalse( id.equals( d.getAsJsonObject().get( "sessionId" ).getAsString() ), "the purged draft is gone" );
        }
        return true;
    }

    /** {@code GET /draft} is rate limited per participant: a burst is answered 429 with a retry hint. */
    @Test
    void theDraftSummaryIsRateLimitedPerParticipant() throws Exception {
        final String page = seed( "Rate base\n" );
        final Peer a = new Peer( janne, page ).join( null );
        final Peer b = new Peer( alice, page ).join( null );
        HttpResponse< String > limited = null;
        for( int i = 0; i < 12 && limited == null; i++ ) {
            final HttpResponse< String > r = a.draft();
            if( r.statusCode() == 429 ) {
                limited = r;
            } else {
                assertEquals( 200, r.statusCode(), r.body() );
            }
        }
        assertNotNull( limited, "a burst of draft reads is limited" );
        assertEquals( "rate-limited", reason( limited ), limited.body() );
        assertTrue( obj( limited ).get( "retryAfterMs" ).getAsLong() > 0, limited.body() );
        // another participant's budget is its own
        assertEquals( 200, b.draft().statusCode() );
        a.leave();
        b.leave();
    }

    private List< String > contributorsAt( final String page, final int version ) throws Exception {
        final JsonArray versions = obj( send( janne, "GET", "/api/history/" + page, null ) ).getAsJsonArray( "versions" );
        for( final JsonElement v : versions ) {
            if( v.getAsJsonObject().get( "version" ).getAsInt() == version ) {
                final List< String > out = new ArrayList<>();
                for( final JsonElement c : v.getAsJsonObject().getAsJsonArray( "contributors" ) ) {
                    out.add( c.getAsString() );
                }
                return out;
            }
        }
        throw new AssertionError( "no version " + version + " in the history: " + versions );
    }

    /** {@code [page read, for-agent read]} draft_active. */
    private List< Boolean > draftActive( final String page, final String canonicalId ) throws Exception {
        final HttpResponse< String > p = send( janne, "GET", "/api/pages/" + page, null );
        assertEquals( 200, p.statusCode(), p.body() );
        final HttpResponse< String > f = send( janne, "GET", "/api/pages/for-agent/" + canonicalId, null );
        assertEquals( 200, f.statusCode(), f.body() );
        return List.of( obj( p ).get( "draft_active" ).getAsBoolean(),
            obj( f ).getAsJsonObject( "data" ).get( "draft_active" ).getAsBoolean() );
    }

    /**
     * Starts {@code peer}'s long-poll and checks it is still unanswered {@code dwellMs} later, as a parked poll is (a
     * poll answered meanwhile fails the test with its body). Every dwell is well below the poll timeout.
     */
    private static CompletableFuture< HttpResponse< String > > parkedPull( final Peer peer, final long dwellMs )
            throws Exception {
        final CompletableFuture< HttpResponse< String > > poll = peer.pullAsync();
        try {
            final HttpResponse< String > early = poll.get( dwellMs, TimeUnit.MILLISECONDS );
            throw new AssertionError( "the poll was answered while nothing changed: " + early.statusCode() + " "
                + early.body() );
        } catch( final java.util.concurrent.TimeoutException parked ) {
            return poll;   // still open: parked (or still on its way, which the callers tolerate)
        }
    }

    private void awaitDraftActive( final String page, final String canonicalId, final boolean expected ) throws Exception {
        final long deadline = System.currentTimeMillis() + 10_000;
        List< Boolean > last = List.of();
        while( System.currentTimeMillis() < deadline ) {
            last = draftActive( page, canonicalId );
            if( last.get( 0 ) == expected && last.get( 1 ) == expected ) {
                return;
            }
            Thread.sleep( 200 );
        }
        throw new AssertionError( "draft_active [page, for-agent] never became " + expected + "; last " + last );
    }

    /** Polls {@code /admin/audit} for a row of {@code type} about {@code target}. */
    private JsonObject awaitAudit( final String type, final String target ) throws Exception {
        final long deadline = System.currentTimeMillis() + 20_000;
        while( System.currentTimeMillis() < deadline ) {
            final HttpResponse< String > r = send( janne, "GET", "/admin/audit?limit=1000", null );
            assertEquals( 200, r.statusCode(), r.body() );
            for( final JsonElement el : JsonParser.parseString( r.body() ).getAsJsonArray() ) {
                final JsonObject row = el.getAsJsonObject();
                if( type.equals( str( row, "eventType" ) ) && target.equals( str( row, "targetId" ) ) ) {
                    return row;
                }
            }
            Thread.sleep( 300 );
        }
        throw new AssertionError( "no " + type + " audit row for " + target );
    }

    private static String str( final JsonObject o, final String key ) {
        return o.has( key ) && !o.get( key ).isJsonNull() ? o.get( key ).getAsString() : "";
    }

    private static JsonArray tagAdd( final String tag ) {
        final JsonObject op = new JsonObject();
        op.addProperty( "field", "tags" );
        op.addProperty( "add", tag );
        final JsonArray ops = new JsonArray();
        ops.add( op );
        return ops;
    }

    // ---- the peer --------------------------------------------------------------------------------------------------

    /** One participant of a collaboration session, as the browser's transport would be. */
    private static final class Peer {
        final HttpClient client;
        String page;
        final List< JsonObject > events = new ArrayList<>();
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
            for( final JsonElement e : lastPull.getAsJsonArray( "events" ) ) {
                events.add( e.getAsJsonObject() );
            }
            return this;
        }

        /** The first event of {@code type} pulled so far, or null. */
        JsonObject event( final String type ) {
            return events.stream().filter( e -> type.equals( e.get( "type" ).getAsString() ) ).findFirst().orElse( null );
        }

        /** Pulls (parking as a real long-poll) until an event of {@code type} has arrived; bounded. */
        JsonObject awaitEvent( final String type ) throws Exception {
            final long deadline = System.currentTimeMillis() + 20_000;
            while( event( type ) == null && System.currentTimeMillis() < deadline ) {
                pull( presenceSeq );
            }
            final JsonObject e = event( type );
            assertNotNull( e, "no " + type + " event within 20 s; saw " + events );
            return e;
        }

        /** A push with no text updates: meta ops and/or a selection ({@code null} for none). Skips the version check. */
        HttpResponse< String > pushMeta( final JsonArray metaOps, final JsonObject selection ) {
            try {
                final JsonObject body = new JsonObject();
                body.addProperty( "sessionId", sessionId );
                body.addProperty( "participantId", participantId );
                body.addProperty( "version", version );
                body.add( "updates", new JsonArray() );
                body.add( "metaOps", metaOps );
                if( selection != null ) {
                    body.add( "selection", selection );
                }
                return send( client, "POST", "/api/collab/" + page + "/updates", body.toString() );
            } catch( final IOException | InterruptedException e ) {
                throw new IllegalStateException( e );
            }
        }

        HttpResponse< String > publish( final int atVersion ) throws Exception {
            return send( client, "POST", "/api/collab/" + page + "/publish", GSON.toJson( Map.of( "sessionId", sessionId,
                "participantId", participantId, "version", atVersion, "changeNote", "collab IT" ) ) );
        }

        HttpResponse< String > draft() throws Exception {
            return send( client, "GET", "/api/collab/" + page + "/draft?sessionId=" + enc( sessionId )
                + "&participantId=" + enc( participantId ), null );
        }

        HttpResponse< String > discard( final int atVersion ) throws Exception {
            return send( client, "POST", "/api/collab/" + page + "/discard", GSON.toJson( Map.of( "sessionId", sessionId,
                "participantId", participantId, "version", atVersion, "changeNote", "" ) ) );
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
