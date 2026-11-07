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
        mem = new InMemoryCollabStore();
        store = ( CollabStore ) Proxy.newProxyInstance( CollabStore.class.getClassLoader(),
            new Class< ? >[]{ CollabStore.class }, ( proxy, method, args ) -> {
                final String name = method.getName();
                if( storeDown.get() && ( "sessions".equals( name ) || "sessionForPage".equals( name ) ) ) {
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
        clock = new MutableClock( T0 );
        registry = registry( config( 0 ) );
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
        final SessionRecord rec = CollabSession.newRecord( UUID.randomUUID(), page, Optional.empty(), "dave", clock );
        final SessionRecord out = pendingPublish ? rec.withPending( 1, 0, "dave" ) : rec;
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

    // ---- the draft-active index --------------------------------------------------------------------------------

    @Test
    void draftActiveReadsNoStoreOnTheCallPath() {
        storedDraft( "Stored" );
        rawDraft( "Raw", false );
        join( "alice" );
        clock.advance( DraftIndex.RELOAD_EVERY );
        registry.tick();   // warm-up: the index reloads
        storeDown.set( true );

        assertTrue( registry.draftActive( "Stored" ) );
        assertTrue( registry.draftActive( "Raw" ) );
        assertTrue( registry.draftActive( PAGE ) );
        assertFalse( registry.draftActive( "Nothing" ) );

        clock.advance( Duration.ofSeconds( 31 ) );
        registry.tick();   // the reload fails: the index is kept
        assertTrue( registry.draftActive( "Stored" ) );
        assertFalse( registry.draftActive( "Nothing" ) );
    }

    @Test
    void draftActiveFollowsCreateRekeyAndPurge() {
        registry.tick();
        assertFalse( registry.draftActive( PAGE ) );
        final Joined a = join( "alice" );
        assertTrue( registry.draftActive( PAGE ), "created" );
        leave( a, "alice" );
        assertTrue( registry.draftActive( PAGE ), "stored, nobody in it" );

        writer.rename( PAGE, "B" );
        registry.rekey( PAGE, "B" );
        assertFalse( registry.draftActive( PAGE ), "moved away" );
        assertTrue( registry.draftActive( "B" ), "moved here" );

        assertEquals( 204, registry.purge( a.response().sessionId() ).status() );
        assertFalse( registry.draftActive( "B" ), "purged" );

        final Joined c = join( "B", "carol" );
        leave( c, "carol" );
        assertTrue( registry.draftActive( "B" ) );
        idleOut();
        assertFalse( registry.draftActive( "B" ), "an unchanged draft deleted on idle eviction" );
    }

    @Test
    void draftActiveSeesADraftCreatedElsewhereAfterTheReload() {
        registry.tick();
        rawDraft( "Elsewhere", false );   // another node, say
        assertFalse( registry.draftActive( "Elsewhere" ), "not yet reloaded" );
        clock.advance( Duration.ofSeconds( 30 ) );
        registry.tick();
        assertTrue( registry.draftActive( "Elsewhere" ) );
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
