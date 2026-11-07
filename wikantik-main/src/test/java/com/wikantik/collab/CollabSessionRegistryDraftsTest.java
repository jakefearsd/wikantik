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
package com.wikantik.collab;

import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.model.DraftListing;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PurgeOutcome;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 3 Task 4: the admin drafts list, the purge, draft retention, the draft-active index and the participant count
 * (section 7.7, section 11) over the in-memory doubles.
 */
class CollabSessionRegistryDraftsTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    /** Records completions; a second completion fails the test. */
    private static final class Waiter implements PullWaiter {
        final List< PullResponse > got = new ArrayList<>();

        @Override
        public void complete( final PullResponse r ) {
            if( !got.isEmpty() ) {
                fail( "waiter completed twice: " + got + " then " + r );
            }
            got.add( r );
        }
    }

    private InMemoryCollabStore mem;
    /** While set, the registry's store throws on {@code sessions()} and {@code sessionForPage}. */
    private final AtomicBoolean storeDown = new AtomicBoolean();
    private CollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private CollabSessionRegistry registry;

    private static CollabConfig config( final int retentionDays ) {
        // a long presence timeout: these tests move the clock by days with participants still present
        return new CollabConfig( true, 20, 65536, 25, 3600, 30, 100, 500, 10, retentionDays, 1000 );
    }

    private CollabSessionRegistry registry( final CollabConfig c ) {
        final CollabPermissions everyone = new CollabPermissions() {
            @Override
            public boolean canEdit( final Object participantSession, final String pageName ) {
                return true;
            }

            @Override
            public boolean canCreate( final Object participantSession, final String pageName ) {
                return true;
            }
        };
        return new CollabSessionRegistry( store, writer, everyone, key -> null, clock, c, 1 << 20 );
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock( T0 );
        mem = new InMemoryCollabStore( clock );
        store = ( CollabStore ) Proxy.newProxyInstance( CollabStore.class.getClassLoader(),
            new Class< ? >[]{ CollabStore.class }, ( proxy, method, args ) -> {
                final String name = method.getName();
                calls.computeIfAbsent( name, k -> new java.util.concurrent.atomic.AtomicInteger() ).incrementAndGet();
                if( storeDown.get() && DOWN.contains( name ) ) {
                    throw new IllegalStateException( "test: the store is down (" + name + ")" );
                }
                try {
                    return method.invoke( mem, args );
                } catch( final InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        registry = registry( config( 0 ) );
    }

    /** The registry's store calls so far, by method name. */
    private final Map< String, java.util.concurrent.atomic.AtomicInteger > calls =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** The reads that throw while {@link #storeDown} is set. */
    private static final Set< String > DOWN = Set.of( "sessions", "sessionForPage", "draftEntries", "unpublishedByPage" );

    private int calls( final String method ) {
        final java.util.concurrent.atomic.AtomicInteger n = calls.get( method );
        return n == null ? 0 : n.get();
    }

    private Joined join( final String page, final String author ) {
        return assertInstanceOf( Joined.class, registry.join( page,
            new JoinRequest( author, null, author, author.toUpperCase(), null, null ) ) );
    }

    private Joined join( final String author ) {
        return join( PAGE, author );
    }

    private static PushResponse type( final Joined j, final String author, final String text ) {
        final CollabSession.View v = j.session().view();
        return j.session().push( j.response().participantId(), author, v.head(),
            List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) );
    }

    private static void leave( final Joined j, final String author ) {
        assertEquals( 200, j.session().leave( j.response().participantId(), author ).status() );
    }

    /** One tick to notice the session is empty, then the idle time, then the evicting tick. */
    private void idleOut() {
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
    }

    /** A stored-only draft with changes on {@code page}: joined, typed, left and idled out of memory. */
    private UUID storedDraft( final String page ) {
        final Joined j = join( page, "carol" );
        assertEquals( 200, type( j, "carol", "c" ).status() );
        leave( j, "carol" );
        idleOut();
        assertTrue( registry.liveForPage( page ).isEmpty() );
        return j.response().sessionId();
    }

    /** A record written straight into the store (no snapshot: never joined, only listed, purged or expired). */
    private SessionRecord rawDraft( final String page, final boolean pendingPublish ) {
        return rawDraft( page, pendingPublish, false );
    }

    private SessionRecord rawDraft( final String page, final boolean pendingPublish, final boolean unpublished ) {
        final SessionRecord rec = CollabSession.newRecord( UUID.randomUUID(), page, Optional.empty(), "dave", clock );
        final SessionRecord pending = pendingPublish ? rec.withPending( 1, 0, "dave" ) : rec;
        final SessionRecord out = unpublished ? SessionLoader.draftMarkers( pending, true, null ) : pending;
        mem.createSession( out );
        return out;
    }

    private static DraftListing listingOf( final List< DraftListing > all, final UUID id ) {
        return all.stream().filter( d -> d.sessionId().equals( id ) ).findFirst()
            .orElseThrow( () -> new AssertionError( "no listing for " + id + " in " + all ) );
    }

    // ---- the admin drafts list ---------------------------------------------------------------------------------

    @Test
    void draftsListsStoredAndLiveSessionsWithLiveState() {
        final UUID stored = storedDraft( "Stored" );
        final SessionRecord frozen = rawDraft( "Gone", false );
        mem.updateSession( frozen.withStatus( SessionRecord.FROZEN ) );
        clock.advance( Duration.ofMinutes( 5 ) );
        final Joined a = join( "alice" );
        join( "bob" );
        clock.advance( Duration.ofSeconds( 7 ) );
        assertEquals( 200, type( a, "alice", "x" ).status() );

        final List< DraftListing > all = registry.drafts();
        assertEquals( 3, all.size() );

        final DraftListing live = listingOf( all, a.response().sessionId() );
        assertEquals( PAGE, live.pageName() );
        assertEquals( SessionRecord.ACTIVE, live.status() );
        assertEquals( "alice", live.createdBy(), "the author key: this is an admin surface" );
        assertTrue( live.live() );
        assertEquals( 2, live.participants() );
        assertEquals( clock.instant(), live.lastEditAt(), "the live session's own clock, not the stored marker" );
        assertFalse( live.publishPending() );

        final DraftListing idle = listingOf( all, stored );
        assertEquals( "Stored", idle.pageName() );
        assertFalse( idle.live() );
        assertEquals( 0, idle.participants() );
        assertEquals( "carol", idle.createdBy() );
        assertEquals( T0, idle.createdAt() );

        final DraftListing gone = listingOf( all, frozen.id() );
        assertEquals( SessionRecord.FROZEN, gone.status() );
        assertFalse( gone.live() );
    }

    // ---- purge -------------------------------------------------------------------------------------------------

    @Test
    void purgeEvictsEveryParticipantWithPurgedAndDeletesTheDraft() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final Waiter parked = new Waiter();
        assertEquals( null, b.session().pullOrPark( b.response().participantId(), "bob", b.session().view().head(),
            null, parked ) );
        final UUID id = a.response().sessionId();

        final PurgeOutcome out = registry.purge( id );

        assertEquals( new PurgeOutcome( 204, null, PAGE, true ), out );
        assertEquals( 1, parked.got.size(), "the parked pull is woken" );
        assertEquals( 403, parked.got.get( 0 ).status() );
        assertEquals( CollabSession.EVICTED, parked.got.get( 0 ).reason() );
        assertEquals( CollabSessionRegistry.PURGED, parked.got.get( 0 ).detail() );
        final PushResponse late = type( a, "alice", "y" );
        assertEquals( 403, late.status() );
        assertEquals( CollabSessionRegistry.PURGED, late.detail() );
        assertTrue( mem.session( id ).isEmpty(), "the draft is deleted" );
        assertTrue( registry.live( id ).isEmpty() );
        assertTrue( registry.liveForPage( PAGE ).isEmpty() );
        assertEquals( 0, registry.liveCount() );
        assertEquals( 1, writer.history( PAGE ).size(), "a purge never publishes" );

        final Joined fresh = join( "carol" );
        assertFalse( id.equals( fresh.response().sessionId() ), "the next join starts a new draft" );
        assertEquals( BODY, fresh.response().doc() );
    }

    @Test
    void purgeOfAStoredDraftDeletesIt() {
        final UUID id = storedDraft( "Stored" );
        assertEquals( new PurgeOutcome( 204, null, "Stored", false ), registry.purge( id ) );
        assertTrue( mem.session( id ).isEmpty() );
    }

    @Test
    void purgeDuringAPublishIs423() throws Exception {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final CompletableFuture< PublishResponse > publish = CompletableFuture.supplyAsync(
            () -> a.session().publish( a.response().participantId(), "alice", a.session().view().head(), "" ) );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );

        assertEquals( new PurgeOutcome( 423, "publish-in-progress", PAGE, true ),
            registry.purge( a.response().sessionId() ) );
        assertTrue( a.session().hasParticipant( a.response().participantId() ), "nobody is evicted" );
        pause.release();
        assertEquals( 200, publish.get( 10, TimeUnit.SECONDS ).status() );
        assertTrue( mem.session( a.response().sessionId() ).isPresent() );

        final SessionRecord marked = rawDraft( "Marked", true );
        assertEquals( new PurgeOutcome( 423, "publish-in-progress", "Marked", false ), registry.purge( marked.id() ),
            "a stored pending-publish marker is a publish in progress too" );
        assertTrue( mem.session( marked.id() ).isPresent() );
    }

    @Test
    void purgeOfAnUnknownSessionIs404() {
        assertEquals( new PurgeOutcome( 404, "unknown", null, false ), registry.purge( UUID.randomUUID() ) );
    }

    @Test
    void aPurgeStoreFailureKeepsTheDraft() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final UUID id = a.response().sessionId();
        mem.failOn( InMemoryCollabStore.Op.DELETE, 1 );

        assertEquals( new PurgeOutcome( 503, "store", PAGE, true ), registry.purge( id ) );

        assertTrue( mem.session( id ).isPresent(), "the draft is kept" );
        assertTrue( registry.draftActive( PAGE ) );
        final Joined again = join( "bob" );
        assertEquals( id, again.response().sessionId(), "the next join reloads the kept draft" );
        assertEquals( "x" + BODY, again.response().doc() );
    }

    // ---- retention ---------------------------------------------------------------------------------------------

    @Test
    void retentionExpiresOldIdleDraftsAndNeverPublishes() {
        registry = registry( config( 30 ) );
        final UUID stored = storedDraft( "Stored" );
        final SessionRecord raw = rawDraft( "Raw", false );
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        leave( a, "alice" );   // live in memory, nobody in it
        final UUID live = a.response().sessionId();
        final int versions = writer.history( PAGE ).size();

        clock.advance( Duration.ofDays( 29 ) );
        registry.tick();
        assertTrue( mem.session( stored ).isPresent(), "29 days old: kept" );

        clock.advance( Duration.ofDays( 2 ) );
        registry.tick();
        assertTrue( mem.session( stored ).isEmpty(), "31 days idle: expired" );
        assertTrue( mem.session( raw.id() ).isEmpty(), "never edited: aged by its creation" );
        assertTrue( mem.session( live ).isEmpty(), "a live draft with nobody in it expires too" );
        assertTrue( registry.live( live ).isEmpty(), "and leaves memory first" );
        assertEquals( versions, writer.history( PAGE ).size(), "retention never publishes" );
        assertFalse( registry.draftActive( PAGE ) );
    }

    @Test
    void retentionRunsAtMostHourly() {
        registry = registry( config( 30 ) );
        final SessionRecord raw = rawDraft( "Raw", false );
        registry.tick();
        clock.advance( Duration.ofDays( 31 ) );
        registry.tick();
        assertTrue( mem.session( raw.id() ).isEmpty() );

        clock.advance( Duration.ofMinutes( 1 ) );
        final SessionRecord old = CollabSession.newRecord( UUID.randomUUID(), "Old", Optional.empty(), "dave",
            java.time.Clock.fixed( clock.instant().minus( Duration.ofDays( 40 ) ), java.time.ZoneOffset.UTC ) );
        mem.createSession( old );
        registry.tick();
        assertTrue( mem.session( old.id() ).isPresent(), "a minute after the last run: not yet" );
        clock.advance( Duration.ofHours( 1 ) );
        registry.tick();
        assertTrue( mem.session( old.id() ).isEmpty(), "an hour later" );
    }

    @Test
    void retentionSparesLiveAndPendingDrafts() {
        registry = registry( config( 30 ) );
        storedDraft( PAGE );   // edited at T0
        final SessionRecord marked = rawDraft( "Marked", true );
        clock.advance( Duration.ofDays( 31 ) );
        final Joined b = join( "bob" );   // loads the 31-day-old draft
        registry.tick();
        assertTrue( mem.session( b.response().sessionId() ).isPresent(), "someone is in it" );
        assertTrue( b.session().hasParticipant( b.response().participantId() ) );
        assertTrue( mem.session( marked.id() ).isPresent(), "a pending publish is never expired" );
    }

    @Test
    void retentionZeroKeepsEverything() {
        final UUID stored = storedDraft( "Stored" );
        final SessionRecord raw = rawDraft( "Raw", false );
        clock.advance( Duration.ofDays( 400 ) );
        registry.tick();
        clock.advance( Duration.ofHours( 2 ) );
        registry.tick();
        assertTrue( mem.session( stored ).isPresent() );
        assertTrue( mem.session( raw.id() ).isPresent() );
    }

    // ---- retention ages a draft by its real last edit (fix round 1, item 1) ------------------------------------

    @Test
    void retentionAgesADraftByItsRealLastEdit() {
        registry = registry( config( 30 ) );
        final Joined a = join( "alice" );                    // created at T0
        assertEquals( 200, type( a, "alice", "a" ).status() );
        leave( a, "alice" );
        idleOut();
        clock.set( T0.plus( Duration.ofDays( 1 ) ) );
        final UUID early = storedDraftEditedAt( "Early", Duration.ofDays( 1 ) );   // created +1d, edited +2d
        clock.set( T0.plus( Duration.ofDays( 20 ) ) );
        final Joined b = join( "bob" );                      // the draft of T0, loaded again
        assertEquals( a.response().sessionId(), b.response().sessionId() );
        assertEquals( 200, type( b, "bob", "b" ).status() );    // edited at T0+20d
        final Instant edited = clock.instant();
        leave( b, "bob" );
        idleOut();
        assertTrue( registry.live( a.response().sessionId() ).isEmpty() );

        clock.set( T0.plus( Duration.ofDays( 31 ) ) );
        registry.tick();

        assertTrue( mem.session( a.response().sessionId() ).isPresent(), "edited 11 days ago: kept" );
        final List< DraftListing > all = registry.drafts();
        assertEquals( edited, listingOf( all, a.response().sessionId() ).lastEditAt() );
        assertEquals( List.of( early, a.response().sessionId() ), all.stream().map( DraftListing::sessionId ).toList(),
            "sorted by the real last edit, not by creation" );
    }

    /** A stored-only draft on {@code page}, created now and edited {@code after} later, then idled out. */
    private UUID storedDraftEditedAt( final String page, final Duration after ) {
        final Joined j = join( page, "carol" );
        clock.advance( after );
        assertEquals( 200, type( j, "carol", "c" ).status() );
        leave( j, "carol" );
        idleOut();
        return j.response().sessionId();
    }

    @Test
    void retentionSurvivesACrashThatNeverStoredTheMarkers() {
        final Joined a = join( "alice" );                    // created at T0
        clock.advance( Duration.ofDays( 20 ) );
        assertEquals( 200, type( a, "alice", "x" ).status() );   // edited at T0+20d, the marker not yet stored
        assertTrue( mem.session( a.response().sessionId() ).orElseThrow().lastEditAt() == null );

        // the node dies: a new registry over the same store, the old one's memory gone
        registry = registry( config( 30 ) );
        clock.set( T0.plus( Duration.ofDays( 31 ) ) );
        registry.tick();

        assertTrue( mem.session( a.response().sessionId() ).isPresent(), "the logged edit is 11 days old: kept" );
        assertEquals( T0.plus( Duration.ofDays( 20 ) ),
            listingOf( registry.drafts(), a.response().sessionId() ).lastEditAt() );
    }

    @Test
    void idleEvictionStoresTheDraftMarkers() {
        final Joined a = join( "alice" );
        clock.advance( Duration.ofMinutes( 3 ) );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final Instant edited = clock.instant();
        leave( a, "alice" );
        idleOut();
        final SessionRecord stored = mem.session( a.response().sessionId() ).orElseThrow();
        assertEquals( edited, stored.lastEditAt() );
        assertTrue( stored.hasUnpublished() );
    }

    @Test
    void theRegistryNeverReadsWholeSessionsForItsListsAndIndex() {
        registry = registry( config( 30 ) );
        storedDraft( "Stored" );
        for( int i = 0; i < 3; i++ ) {
            clock.advance( Duration.ofHours( 1 ) );
            registry.tick();
        }
        registry.drafts();
        assertEquals( 0, calls( "sessions" ), "base bodies are never read in bulk" );
        assertTrue( calls( "unpublishedByPage" ) > 0 );
        assertTrue( calls( "draftEntries" ) > 0 );
    }

    // ---- the draft-active index (fix round 1, item 2: live sessions answer for themselves) --------------------

    @Test
    void draftActiveReadsNoStoreOnTheCallPath() {
        storedDraft( "Stored" );
        rawDraft( "Raw", false, true );
        rawDraft( "Clean", false, false );
        join( "alice" );
        clock.advance( DraftIndex.RELOAD_EVERY );
        registry.tick();   // warm-up: the index reloads
        storeDown.set( true );

        assertTrue( registry.draftActive( "Stored" ) );
        assertTrue( registry.draftActive( "Raw" ) );
        assertFalse( registry.draftActive( "Clean" ), "a stored draft without unpublished changes" );
        assertTrue( registry.draftActive( PAGE ) );
        assertFalse( registry.draftActive( "Nothing" ) );

        clock.advance( Duration.ofSeconds( 31 ) );
        registry.tick();   // the reload fails: the index is kept
        assertTrue( registry.draftActive( "Stored" ) );
        assertFalse( registry.draftActive( "Nothing" ) );
    }

    @Test
    void draftActiveFollowsLiveEditingRekeyAndPurge() {
        registry.tick();
        assertFalse( registry.draftActive( PAGE ) );
        final Joined a = join( "alice" );
        assertTrue( registry.draftActive( PAGE ), "someone is editing" );
        leave( a, "alice" );
        assertFalse( registry.draftActive( PAGE ), "opened and closed without typing: nobody, nothing unpublished" );

        final Joined b = join( "bob" );
        assertEquals( 200, type( b, "bob", "b" ).status() );
        leave( b, "bob" );
        assertTrue( registry.draftActive( PAGE ), "unpublished changes, nobody in it" );

        writer.rename( PAGE, "B" );
        registry.rekey( PAGE, "B" );
        assertFalse( registry.draftActive( PAGE ), "moved away" );
        assertTrue( registry.draftActive( "B" ), "moved here" );

        assertEquals( 204, registry.purge( a.response().sessionId() ).status() );
        assertFalse( registry.draftActive( "B" ), "purged" );

        final Joined c = join( "B", "carol" );
        leave( c, "carol" );
        assertFalse( registry.draftActive( "B" ) );
        idleOut();
        assertFalse( registry.draftActive( "B" ), "an unchanged draft deleted on idle eviction" );
    }

    @Test
    void draftActiveIsFalseAfterAPublishWithEveryoneGone() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        assertEquals( 200, a.session().publish( a.response().participantId(), "alice", a.session().view().head(), "" )
            .status() );
        leave( a, "alice" );
        assertFalse( registry.draftActive( PAGE ), "published, nobody editing" );
        clock.advance( DraftIndex.RELOAD_EVERY );
        idleOut();
        assertFalse( registry.draftActive( PAGE ), "and after it left memory" );
    }

    @Test
    void aStoredDraftWithChangesStaysActiveAfterItLeavesMemory() {
        storedDraft( "Stored" );
        assertTrue( registry.draftActive( "Stored" ), "told at once by the eviction" );
        clock.advance( DraftIndex.RELOAD_EVERY );
        registry.tick();
        assertTrue( registry.draftActive( "Stored" ), "and by the reload" );
    }

    @Test
    void draftActiveSeesADraftCreatedElsewhereAfterTheReload() {
        registry.tick();
        rawDraft( "Elsewhere", false, true );   // another node, say
        assertFalse( registry.draftActive( "Elsewhere" ), "not yet reloaded" );
        clock.advance( Duration.ofSeconds( 30 ) );
        registry.tick();
        assertTrue( registry.draftActive( "Elsewhere" ) );
    }

    // ---- draft state survives restarts (fix round 2) ----------------------------------------------------------

    @Test
    void closeStoresTheMarkersOfEveryLiveSession() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final Joined c = join( "Other", "carol" );
        assertEquals( 200, type( c, "carol", "c" ).status() );
        assertTrue( mem.session( a.response().sessionId() ).orElseThrow().lastEditAt() == null, "not yet stored" );

        registry.close();

        for( final Joined j : List.of( a, c ) ) {
            final SessionRecord stored = mem.session( j.response().sessionId() ).orElseThrow();
            assertTrue( stored.hasUnpublished() );
            assertEquals( T0, stored.lastEditAt() );
        }
    }

    @Test
    void closeStoresEachSessionOnItsOwn() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final Joined c = join( "Other", "carol" );
        assertEquals( 200, type( c, "carol", "c" ).status() );
        mem.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );

        final List< LogEvent > events = CollabLogCapture.warnings( registry::close );

        final long stored = List.of( a, c ).stream()
            .filter( j -> mem.session( j.response().sessionId() ).orElseThrow().hasUnpublished() ).count();
        assertEquals( 1, stored, "the other session's markers are still stored" );
        assertTrue( events.stream().anyMatch( e -> e.getLevel() == Level.WARN
            && e.getMessage().getFormattedMessage().contains( "draft markers not stored" ) ), events::toString );
    }

    @Test
    void draftActiveSurvivesACrashWhoseEditsAreOnlyInTheLog() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );   // no snapshot, no unload: the markers never stored

        registry = registry( config( 0 ) );   // the node died; a new one starts over the same store
        registry.tick();

        assertTrue( registry.draftActive( PAGE ), "the log holds an edit the stored flag never recorded" );
    }

    @Test
    void draftActiveStaysFalseAfterACrashThatFollowedAPublish() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        assertEquals( 200, a.session().publish( a.response().participantId(), "alice", a.session().view().head(), "" )
            .status() );

        registry = registry( config( 0 ) );
        registry.tick();

        assertFalse( registry.draftActive( PAGE ), "the publish stored the markers after the last edit" );
    }

    /** As {@link #config}, with snapshots every {@code snapshotEvery} updates and the given permission re-check. */
    private static CollabConfig snapshotting( final int snapshotEvery, final int recheckSeconds ) {
        return new CollabConfig( true, 20, 65536, 25, 3600, recheckSeconds, 100, snapshotEvery, 10, 0, 1000 );
    }

    @Test
    void aFailedMarkerWriteStopsTheTickTruncatingTheLog() {
        registry = registry( snapshotting( 2, 30 ) );
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "1" ).status() );
        mem.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );   // the snapshot at 2 stores no markers
        assertEquals( 200, type( a, "alice", "2" ).status() );
        assertTrue( mem.latestSnapshot( a.response().sessionId() ).orElseThrow().version() == 2 );
        assertEquals( null, a.session().pullOrPark( a.response().participantId(), "alice", 2, null, r -> { } ) );

        clock.advance( Duration.ofSeconds( 30 ) );
        registry.tick();   // the permission re-check truncates the log

        assertEquals( 2, mem.updatesSince( a.response().sessionId(), 0 ).size(),
            "the logged edit times are kept while the stored marker is stale" );
    }

    @Test
    void aFailedUnloadMarkerWriteSkipsTheIdleTruncation() {
        registry = registry( snapshotting( 2, 86_400 ) );   // no permission re-check (nor its truncation) meanwhile
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "1" ).status() );
        assertEquals( 200, type( a, "alice", "2" ).status() );
        leave( a, "alice" );
        clock.advance( Duration.ofMinutes( 1 ) );
        mem.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );   // the unload stores no markers

        idleOut();

        assertTrue( registry.live( a.response().sessionId() ).isEmpty() );
        assertEquals( 2, mem.updatesSince( a.response().sessionId(), 0 ).size(),
            "the idle truncation is skipped while the stored marker is stale" );
    }

    // ---- purge races and owed renames (fix round 1, items 4 and 5) --------------------------------------------

    @Test
    void aPublishStartedByAnEvictionCallbackNeverLeavesClientsToldPurgedWithTheDraftKept() throws Exception {
        final Joined bob = join( "bob" );
        final Joined alice = join( "alice" );
        assertEquals( 200, type( alice, "alice", "x" ).status() );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final List< CompletableFuture< PublishResponse > > started = new ArrayList<>();
        // bob's parked pull is answered as he is evicted; that moment, alice presses publish
        final PullWaiter racing = r -> {
            final CompletableFuture< PublishResponse > f = CompletableFuture.supplyAsync( () -> alice.session().publish(
                alice.response().participantId(), "alice", alice.session().view().head(), "" ) );
            started.add( f );
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos( 5 );
            while( !pause.entered() && !f.isDone() && System.nanoTime() < deadline ) {
                Thread.onSpinWait();
            }
        };
        assertEquals( null, bob.session().pullOrPark( bob.response().participantId(), "bob",
            bob.session().view().head(), null, racing ) );

        final PurgeOutcome out = registry.purge( alice.response().sessionId() );

        assertEquals( 1, started.size() );
        if( pause.entered() ) {
            pause.release();
        }
        final PublishResponse publish = started.get( 0 ).get( 10, TimeUnit.SECONDS );
        assertEquals( 204, out.status(), "every participant was evicted before anyone could start a publish: " + out );
        assertEquals( 403, publish.status(), "alice was already evicted" );
        assertTrue( mem.session( alice.response().sessionId() ).isEmpty() );
    }

    @Test
    void aPurgedDraftWithAnOwedRenameLeavesNothingBehind() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        mem.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        org.junit.jupiter.api.Assertions.assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "B" ) );
        writer.rename( PAGE, "B" );   // the rename into B is owed

        assertEquals( 204, registry.purge( a.response().sessionId() ).status() );

        final Joined b = join( "B", "bob" );   // not refused: the owed rename has nothing left to move
        assertFalse( a.response().sessionId().equals( b.response().sessionId() ) );
        assertEquals( BODY, b.response().doc(), "the page as it is, not the purged draft" );
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertTrue( mem.sessionForPage( PAGE ).isEmpty() );
        assertEquals( b.response().sessionId(), mem.sessionForPage( "B" ).orElseThrow().id(), "only bob's fresh draft" );
        assertFalse( b.session().frozen() );
        assertEquals( 1, mem.sessions().size() );
    }

    // ---- registry-wide tick steps ------------------------------------------------------------------------------

    @Test
    void oneStepsWarningDoesNotMuteAnother() {
        registry = registry( config( 30 ) );
        storeDown.set( true );   // the draft index reload and the retention sweep both list the drafts
        final List< LogEvent > events = CollabLogCapture.warnings( registry::tick );
        assertEquals( 1, CollabLogCapture.stepFailures( events, Level.WARN, "draft index" ), events::toString );
        assertEquals( 1, CollabLogCapture.stepFailures( events, Level.WARN, "draft retention" ), events::toString );

        clock.advance( Duration.ofHours( 1 ) );
        final List< LogEvent > later = CollabLogCapture.warnings( registry::tick );
        assertEquals( 1, CollabLogCapture.stepFailures( later, Level.WARN, "draft index" ), later::toString );
        assertEquals( 1, CollabLogCapture.stepFailures( later, Level.WARN, "draft retention" ), later::toString );
    }

    // ---- counts and the ledger ---------------------------------------------------------------------------------

    @Test
    void participantCountSumsLiveSessions() {
        assertEquals( 0, registry.participantCount() );
        join( "alice" );
        final Joined b = join( "bob" );
        join( "Other", "carol" );
        assertEquals( 3, registry.participantCount() );
        leave( b, "bob" );
        assertEquals( 2, registry.participantCount() );
    }

    @Test
    void contributorsByVersionDelegatesAndAnswersEmptyOnAStoreFailure() {
        mem.recordContributors( "cid-1", 2, Set.of( "bob", "alice" ), false );
        assertEquals( Map.of( 2, List.of( "alice", "bob" ) ), registry.contributorsByVersion( "cid-1" ) );

        final CollabStore broken = ( CollabStore ) Proxy.newProxyInstance( CollabStore.class.getClassLoader(),
            new Class< ? >[]{ CollabStore.class }, ( proxy, method, args ) -> {
                if( "contributorsByVersion".equals( method.getName() ) ) {
                    throw new IllegalStateException( "test: the ledger is unreadable" );
                }
                return method.invoke( mem, args );
            } );
        store = broken;
        assertEquals( Map.of(), registry( config( 0 ) ).contributorsByVersion( "cid-1" ) );
    }
}
