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

import com.wikantik.collab.CollabSessionRegistry.Disabled;
import com.wikantik.collab.CollabSessionRegistry.JoinOutcome;
import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.CollabSessionRegistry.Refused;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 5: the registry of live sessions (section 7.7, A5, A10 Minor, A13) against the Phase 0 doubles. The wiki session
 * of every join is the author key itself, so the permission double can revoke by author.
 */
class CollabSessionRegistryTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    /** Edit and create rights by wiki session (here: the author key); counts the edit checks. */
    private static final class Permissions implements CollabPermissions {
        final Set< String > noEdit = new HashSet<>();
        final Set< String > noCreate = new HashSet<>();
        final Set< String > throwing = new HashSet<>();
        final AtomicInteger editChecks = new AtomicInteger();

        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            editChecks.incrementAndGet();
            if( throwing.contains( participantSession ) ) {
                throw new IllegalStateException( "authorization backend down for " + participantSession );
            }
            return !noEdit.contains( participantSession );
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return !noCreate.contains( participantSession );
        }
    }

    /** Records completions; a second completion fails the test (F6, E32). */
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

    /** The scripted writer, counting latest() reads and throwing an Error for one page on demand. */
    private static final class FlakyWriter implements CollabPageWriter {
        final CollabPageWriter d;
        final AtomicInteger latestReads = new AtomicInteger();
        volatile String explodeOn;
        /** When set, version() signals {@code versionEntered} and then waits for this latch (holding the base lock). */
        volatile java.util.concurrent.CountDownLatch blockVersion;
        final java.util.concurrent.CountDownLatch versionEntered = new java.util.concurrent.CountDownLatch( 1 );
        /** Run once, after the first latest() read of a page (a rename landing between a join's read and its slot). */
        volatile Runnable afterLatest;

        FlakyWriter( final CollabPageWriter d ) {
            this.d = d;
        }

        @Override
        public Optional< PageState > latest( final String pageName ) {
            latestReads.incrementAndGet();
            if( pageName.equals( explodeOn ) ) {
                throw new StackOverflowError( "test: latest(" + pageName + ")" );
            }
            final Optional< PageState > out = d.latest( pageName );
            final Runnable hook = afterLatest;
            if( hook != null ) {
                afterLatest = null;
                hook.run();
            }
            return out;
        }

        @Override
        public Optional< PageState > version( final String pageName, final int version ) {
            final java.util.concurrent.CountDownLatch block = blockVersion;
            if( block != null ) {
                versionEntered.countDown();
                try {
                    if( !block.await( 10, java.util.concurrent.TimeUnit.SECONDS ) ) {
                        throw new IllegalStateException( "test: version() was never released" );
                    }
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException( e );
                }
            }
            return d.version( pageName, version );
        }

        @Override
        public PublishOutcome publish( final PublishRequest request ) throws WikiException {
            return d.publish( request );
        }
    }

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private FlakyWriter flaky;
    private MutableClock clock;
    private Permissions permissions;
    private CollabSessionRegistry registry;

    /** maxParticipants, pollTimeout, presenceTimeout, recheck, snapshotEvery, idleMinutes as given; the rest default. */
    private static CollabConfig config( final int maxParticipants, final int pollTimeout, final int presenceTimeout,
                                        final int recheckSeconds, final int snapshotEvery, final int idleMinutes ) {
        return new CollabConfig( true, maxParticipants, 65536, pollTimeout, presenceTimeout, recheckSeconds, 100,
            snapshotEvery, idleMinutes, 0, 1000 );
    }

    private CollabSessionRegistry registry( final CollabConfig c ) {
        return new CollabSessionRegistry( store, flaky, permissions, key -> null, clock, c, 1 << 20 );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        flaky = new FlakyWriter( writer );
        clock = new MutableClock( T0 );
        permissions = new Permissions();
        registry = registry( config( 20, 25, 45, 30, 500, 10 ) );
    }

    private static JoinRequest req( final String author ) {
        return new JoinRequest( author, null, author, author.toUpperCase(), null, null );
    }

    private Joined join( final String author ) {
        return assertInstanceOf( Joined.class, registry.join( PAGE, req( author ) ) );
    }

    private static PushResponse type( final Joined j, final String author, final String text ) {
        final CollabSession.View v = j.session().view();
        return j.session().push( j.response().participantId(), author, v.head(),
            List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) );
    }

    private void leave( final Joined j, final String author ) {
        assertEquals( 200, j.session().leave( j.response().participantId(), author ).status() );
    }

    /** One tick to notice the session is empty, then the idle time, then the evicting tick. */
    private void idleOut() {
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
    }

    // ---- join -----------------------------------------------------------------------------------------------

    @Test
    void theFirstJoinCreatesTheSessionAndLaterJoinsShareIt() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        assertSame( a.session(), b.session() );
        assertEquals( a.response().sessionId(), b.response().sessionId() );
        assertSame( a.session(), registry.live( a.response().sessionId() ).orElseThrow() );
        assertSame( a.session(), registry.liveForPage( PAGE ).orElseThrow() );
        assertEquals( BODY, a.response().doc() );
        assertEquals( 1, a.response().basePageVersion() );
        assertEquals( "alice", store.sessionForPage( PAGE ).orElseThrow().createdBy() );
        assertTrue( registry.live( UUID.randomUUID() ).isEmpty() );
    }

    @Test
    void aJoinLoadsAStoredDraft() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "draft " ).status() );
        leave( a, "alice" );
        idleOut();
        assertTrue( registry.live( a.response().sessionId() ).isEmpty() );
        final Joined again = join( "bob" );
        assertNotEquals( a.session(), again.session() );
        assertEquals( a.response().sessionId(), again.response().sessionId() );
        assertEquals( "draft " + BODY, again.response().doc() );
        assertTrue( again.response().unpublished() );
    }

    @Test
    void aNewPageGetsASessionAtBaseVersionZero() {
        final Joined j = assertInstanceOf( Joined.class, registry.join( "Fresh", req( "alice" ) ) );
        assertEquals( 0, j.response().basePageVersion() );
        assertEquals( "", j.response().doc() );
    }

    @Test
    void derivedPagesAreRefused() {
        writer.seed( "Derived", "from a pdf\n", Map.of( "derived_from", "report.pdf" ) );
        assertEquals( new Refused( 409, "derived-page" ), registry.join( "Derived", req( "alice" ) ) );
        assertTrue( store.sessionForPage( "Derived" ).isEmpty() );
    }

    @Test
    void noEditIs403() {
        permissions.noEdit.add( "mallory" );
        assertEquals( new Refused( 403, "forbidden" ), registry.join( PAGE, req( "mallory" ) ) );
        assertTrue( store.sessionForPage( PAGE ).isEmpty() );
    }

    @Test
    void aNewPageAlsoNeedsCreate() {
        permissions.noCreate.add( "alice" );
        assertEquals( new Refused( 403, "forbidden" ), registry.join( "Fresh", req( "alice" ) ) );
        // an existing page does not
        join( "alice" );
    }

    @Test
    void sessionFullIs429() {
        registry = registry( config( 2, 25, 45, 30, 500, 10 ) );
        join( "alice" );
        join( "bob" );
        assertEquals( new Refused( 429, "session-full" ), registry.join( PAGE, req( "carol" ) ) );
        assertEquals( 2, registry.liveForPage( PAGE ).orElseThrow().view().participants() );
    }

    @Test
    void disablingRefusesJoinsButKeepsLiveSessionsAndDrafts() {
        final Joined a = join( "alice" );
        assertTrue( registry.isEnabled() );
        registry.setEnabled( false );
        assertFalse( registry.isEnabled() );
        assertInstanceOf( Disabled.class, registry.join( PAGE, req( "bob" ) ) );
        assertSame( a.session(), registry.live( a.response().sessionId() ).orElseThrow() );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        registry.setEnabled( true );
        join( "bob" );
    }

    @Test
    void theKillSwitchStartsFromTheConfiguration() {
        final CollabConfig off = new CollabConfig( false, 20, 65536, 25, 45, 30, 100, 500, 10, 0, 1000 );
        final CollabSessionRegistry dark = registry( off );
        assertFalse( dark.isEnabled() );
        assertInstanceOf( Disabled.class, dark.join( PAGE, req( "alice" ) ) );
    }

    @Test
    void aStoreFailureWhileOpeningIs503AndTheNextJoinWorks() {
        store.failOn( InMemoryCollabStore.Op.CREATE_SESSION, 1 );
        assertEquals( new Refused( 503, "store" ), registry.join( PAGE, req( "alice" ) ) );
        assertTrue( registry.liveForPage( PAGE ).isEmpty() );
        join( "alice" );
    }

    @Test
    void aStoreFailureWhileSeedingIs503AndTheNextJoinSeeds() {
        store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 1 );
        final JoinRequest seeded = new JoinRequest( "alice", null, "alice", "A", "template\n", null );
        assertEquals( new Refused( 503, "store" ), registry.join( "Fresh", seeded ) );
        final Joined j = assertInstanceOf( Joined.class, registry.join( "Fresh", seeded ) );
        assertEquals( "template\n", j.response().doc() );
    }

    @Test
    void aMalformedInitialTextIs400() {
        final JoinRequest bad = new JoinRequest( "alice", null, "alice", "A", "lone \uD800 surrogate", null );
        assertEquals( new Refused( 400, "rejected" ), registry.join( "Fresh", bad ) );
    }

    // ---- presence and waiters --------------------------------------------------------------------------------

    @Test
    void e13_aParticipantExpiredByPresenceTimeoutRejoinsWithoutLosingEdits() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        clock.advance( Duration.ofSeconds( 30 ) );
        assertEquals( 200, type( b, "bob", "b" ).status() );   // bob stays seen
        clock.advance( Duration.ofSeconds( 16 ) );
        registry.tick();
        assertEquals( 1, a.session().view().participants(), "alice timed out, bob did not" );
        // alice's unsent edit meets 409 rejoin, she rejoins under her old clientID and pushes it again
        final CollabSession.View v = a.session().view();
        final Update pending = new Update( a.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, "a" ) );
        final PushResponse refused = a.session().push( a.response().participantId(), "alice", v.head(), List.of( pending ) );
        assertEquals( 409, refused.status() );
        assertEquals( "rejoin", refused.reason() );
        final Joined again = assertInstanceOf( Joined.class, registry.join( PAGE,
            new JoinRequest( "alice", a.response().clientId(), "alice", "A", null, null ) ) );
        assertEquals( a.response().clientId(), again.response().clientId() );
        assertEquals( 200, again.session().push( again.response().participantId(), "alice", again.response().version(),
            List.of( pending ) ).status() );
        assertEquals( "ab" + BODY, again.session().view().doc() );
    }

    @Test
    void e32_aParkedPullTimesOutOnTheTickAndIsAnsweredOnce() {
        final Joined a = join( "alice" );
        final Waiter w = new Waiter();
        assertNull( a.session().pullOrPark( a.response().participantId(), "alice", 0, null, w ) );
        clock.advance( Duration.ofSeconds( 24 ) );
        registry.tick();
        assertTrue( w.got.isEmpty() );
        clock.advance( Duration.ofSeconds( 2 ) );
        registry.tick();
        assertEquals( 1, w.got.size() );
        assertEquals( 200, w.got.get( 0 ).status() );
        assertTrue( w.got.get( 0 ).updates().isEmpty() );
        registry.tick();   // never a second answer
        assertEquals( 1, w.got.size() );
    }

    @Test
    void e32_theParkedPullOfAnExpiredParticipantIsAnsweredOnce() {
        registry = registry( config( 20, 60, 45, 30, 500, 10 ) );
        final Joined a = join( "alice" );
        final Waiter w = new Waiter();
        assertNull( a.session().pullOrPark( a.response().participantId(), "alice", 0, null, w ) );
        clock.advance( Duration.ofSeconds( 46 ) );
        registry.tick();
        assertEquals( 0, a.session().view().participants() );
        assertEquals( 1, w.got.size() );
        clock.advance( Duration.ofSeconds( 20 ) );
        registry.tick();
        assertEquals( 1, w.got.size() );
    }

    @Test
    void e32_aPushWakesAParkedPullOnce() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        final Waiter w = new Waiter();
        assertNull( a.session().pullOrPark( a.response().participantId(), "alice", 0, Long.MAX_VALUE, null, w ) );
        assertEquals( 200, type( b, "bob", "b" ).status() );
        assertEquals( 1, w.got.size() );
        assertEquals( 1, w.got.get( 0 ).updates().size() );
        clock.advance( Duration.ofSeconds( 30 ) );
        registry.tick();
        assertEquals( 1, w.got.size() );
    }

    // ---- permissions (A5) -------------------------------------------------------------------------------------

    @Test
    void e30_editRightsAreRecheckedOnTheCadence() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        assertEquals( 200, type( a, "alice", "mine " ).status() );
        permissions.noEdit.add( "alice" );
        final int checksAtJoin = permissions.editChecks.get();
        clock.advance( Duration.ofSeconds( 29 ) );
        registry.tick();
        assertEquals( checksAtJoin, permissions.editChecks.get(), "no re-check before the cadence" );
        assertEquals( 2, a.session().view().participants() );
        clock.advance( Duration.ofSeconds( 1 ) );
        registry.tick();
        assertTrue( permissions.editChecks.get() > checksAtJoin );
        assertEquals( 1, a.session().view().participants(), "alice lost edit and was evicted" );
        assertEquals( CollabSession.LOST_EDIT, type( a, "alice", "a" ).detail(), "evicted: lost edit" );
        assertEquals( 200, type( b, "bob", "b" ).status() );
        // her rejoin is refused while she lacks edit
        assertEquals( new Refused( 403, "forbidden" ), registry.join( PAGE, req( "alice" ) ) );
        // her synced edits stay in the shared draft: only unsent edits are lost on revocation (A13)
        assertEquals( "bmine " + BODY, b.session().view().doc() );
    }

    @Test
    void aThrowingEditCheckFailsClosedAndTheRecheckGoesOn() {
        final Joined a = join( "alice" );
        join( "bob" );
        final Joined c = join( "carol" );
        permissions.throwing.add( "alice" );   // alice's check throws ...
        permissions.noEdit.add( "bob" );       // ... and bob, checked after her, lost edit
        clock.advance( Duration.ofSeconds( 30 ) );
        assertDoesNotThrow( registry::tick );
        assertEquals( 1, a.session().view().participants(), "alice (check failed) and bob (no edit) are evicted" );
        assertEquals( 200, type( c, "carol", "c" ).status() );
    }

    @Test
    void aThrowingEditCheckOnJoinIs403() {
        permissions.throwing.add( "mallory" );
        assertEquals( new Refused( 403, "forbidden" ), registry.join( PAGE, req( "mallory" ) ) );
    }

    @Test
    void noEditIsRefusedBeforeTheDerivedPageCheck() {
        writer.seed( "Derived", "from a pdf\n", Map.of( "derived_from", "report.pdf" ) );
        permissions.noEdit.add( "mallory" );
        assertEquals( new Refused( 403, "forbidden" ), registry.join( "Derived", req( "mallory" ) ) );
    }

    // ---- the tick survives anything ----------------------------------------------------------------------------

    @Test
    void anErrorInOneSessionDoesNotStopTheTickOrTheOtherSessions() {
        final List< Joined > quiet = new ArrayList<>();
        for( int i = 0; i < 4; i++ ) {
            writer.seed( "Quiet" + i, "q\n", Map.of() );
            quiet.add( assertInstanceOf( Joined.class, registry.join( "Quiet" + i, req( "q" + i ) ) ) );
        }
        writer.seed( "Bad", "bad\n", Map.of() );
        final Joined bad = assertInstanceOf( Joined.class, registry.join( "Bad", req( "eve" ) ) );
        clock.advance( Duration.ofSeconds( 40 ) );
        assertEquals( 200, type( bad, "eve", "e" ).status() );   // eve stays present, so her session is settled
        flaky.explodeOn = "Bad";
        clock.advance( Duration.ofSeconds( 6 ) );
        assertDoesNotThrow( registry::tick );
        for( final Joined q : quiet ) {
            assertEquals( 0, q.session().view().participants(), "every quiet participant timed out" );
        }
        assertEquals( 1, bad.session().view().participants() );
        assertDoesNotThrow( registry::tick );
    }

    @Test
    void emptySessionsWithNothingOwedAreNotSettled() {
        final Joined a = join( "alice" );
        registry.tick();
        final int withAParticipant = flaky.latestReads.get();
        registry.tick();
        assertTrue( flaky.latestReads.get() > withAParticipant, "a session with participants is settled" );
        leave( a, "alice" );
        final int empty = flaky.latestReads.get();
        registry.tick();
        registry.tick();
        assertEquals( empty, flaky.latestReads.get(), "an empty session owing nothing is not settled" );
    }

    @Test
    void evictAuthorRemovesThatUsersParticipantsFromEverySession() {
        final Joined a1 = join( "alice" );
        final Joined a2 = join( "alice" );
        final Joined b = join( "bob" );
        final Joined other = assertInstanceOf( Joined.class, registry.join( "Other", req( "alice" ) ) );
        registry.evictAuthor( "alice" );
        assertEquals( 1, a1.session().view().participants() );
        assertEquals( 0, other.session().view().participants() );
        final PushResponse gone = type( a2, "alice", "a" );
        assertEquals( 403, gone.status() );
        assertEquals( CollabSession.EVICTED, gone.reason() );
        assertEquals( CollabSession.ACCOUNT_DELETED, gone.detail(), "a deleted user's tabs learn why" );
        assertEquals( 200, type( b, "bob", "b" ).status() );
    }

    // ---- idle eviction (section 7.7, A10 Minor) ---------------------------------------------------------------

    @Test
    void idleEvictionDeletesADraftWithoutChanges() {
        final Joined a = join( "alice" );
        leave( a, "alice" );
        registry.tick();
        clock.advance( Duration.ofMinutes( 9 ) );
        registry.tick();
        assertTrue( registry.live( a.response().sessionId() ).isPresent(), "not idle long enough" );
        clock.advance( Duration.ofMinutes( 1 ) );
        registry.tick();
        assertTrue( registry.live( a.response().sessionId() ).isEmpty() );
        assertTrue( store.sessionForPage( PAGE ).isEmpty() );
        assertEquals( 0, registry.liveCount() );
    }

    @Test
    void idleEvictionKeepsADraftWithChanges() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        leave( a, "alice" );
        idleOut();
        assertTrue( registry.live( a.response().sessionId() ).isEmpty() );
        assertTrue( store.sessionForPage( PAGE ).isPresent() );
    }

    @Test
    void aSessionWithParticipantsIsNeverIdle() {
        final Joined a = join( "alice" );
        for( int i = 0; i < 20; i++ ) {   // 800 s of activity, each step inside the presence timeout
            clock.advance( Duration.ofSeconds( 40 ) );
            assertEquals( 200, type( a, "alice", "x" ).status() );
            registry.tick();
        }
        assertSame( a.session(), registry.live( a.response().sessionId() ).orElseThrow() );
        assertEquals( 1, a.session().view().participants() );
    }

    @Test
    void aJoinRestartsTheIdleClock() {
        final Joined a = join( "alice" );
        leave( a, "alice" );
        registry.tick();
        clock.advance( Duration.ofMinutes( 9 ) );
        leave( join( "bob" ), "bob" );
        clock.advance( Duration.ofMinutes( 1 ) );
        registry.tick();
        assertTrue( registry.live( a.response().sessionId() ).isPresent(), "idle counts from after bob's join" );
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertTrue( registry.live( a.response().sessionId() ).isEmpty() );
    }

    @Test
    void theStoredUnpublishedFlagIsNotTrusted() {
        // has_unpublished is persisted only at snapshot cadence: the store's flag may be stale either way
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        final SessionRecord stored = store.session( id ).orElseThrow();
        store.updateSession( SessionLoader.draftMarkers( stored, true, T0 ) );
        leave( a, "alice" );
        idleOut();
        assertTrue( store.session( id ).isEmpty(), "no changes in memory: deleted despite the stored flag" );
        // and after a reload: the reloaded session decides from its rebuilt local, not the flag
        final Joined b = join( "bob" );
        final UUID id2 = b.response().sessionId();
        store.updateSession( SessionLoader.draftMarkers( store.session( id2 ).orElseThrow(), true, T0 ) );
        forceReload( id2 );
        final Joined c = join( "carol" );
        assertEquals( id2, c.response().sessionId() );
        assertNotEquals( b.session(), c.session() );
        leave( c, "carol" );
        idleOut();
        assertTrue( store.session( id2 ).isEmpty() );
    }

    private void forceReload( final UUID id ) {
        for( int i = 0; i < CollabSessionRegistry.STORE_FAILURE_LIMIT; i++ ) {
            registry.observe( id, 503, "store" );
        }
        assertTrue( registry.live( id ).isEmpty() );
    }

    // ---- what the session owes the page store (Task 4's settle) ------------------------------------------------

    /** An external save whose merge fails in the store: the session owes it (reconcilePending). */
    private void oweAMerge( final CollabSession s ) {
        writer.externalSave( PAGE, BODY + "external\n", Map.of( "title", "T" ), "agent" );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        s.reconcile( writer.latest( PAGE ).orElseThrow() );
        assertTrue( s.view().reconcilePending(), "the merge is owed" );
    }

    @Test
    void theTickSettlesAnOwedMerge() {
        final Joined a = join( "alice" );
        final CollabSession s = a.session();
        oweAMerge( s );
        registry.tick();
        assertFalse( s.view().reconcilePending() );
        assertEquals( 2, s.view().basePageVersion() );
        assertEquals( BODY + "external\n", s.view().doc() );
    }

    @Test
    void idleEvictionWaitsForAnOwedMerge() {
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        leave( a, "alice" );
        oweAMerge( a.session() );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );   // the tick's retry fails too
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        registry.tick();
        assertTrue( a.session().view().reconcilePending() );
        assertTrue( registry.live( id ).isPresent(), "a session that owes a merge stays in memory" );
        registry.tick();   // the retry lands; idle long enough, nothing unpublished: evicted and deleted
        assertTrue( registry.live( id ).isEmpty() );
        assertTrue( store.session( id ).isEmpty() );
    }

    @Test
    void idleEvictionWaitsForAPublishInFlight() throws Exception {
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final PublishStart st = a.session().startPublish( a.response().participantId(), "alice",
            a.session().view().head(), "note" );
        assertNull( st.response(), "the publish is in its save" );
        leave( a, "alice" );
        idleOut();
        assertTrue( registry.live( id ).isPresent(), "a session mid-publish stays in memory" );
        assertEquals( 200, a.session().finishPublish( st.ticket(), writer.publish( st.ticket().request() ) ).status() );
        registry.tick();
        assertTrue( registry.live( id ).isEmpty(), "published, idle and unchanged: evicted" );
        assertTrue( store.session( id ).isEmpty() );
        assertEquals( "x" + BODY, writer.latest( PAGE ).orElseThrow().body().replace( "\r\n", "\n" ) );
    }

    // ---- the ambiguous-commit wedge ---------------------------------------------------------------------------

    @Test
    void repeatedStoreFailuresEvictTheSessionSoTheNextJoinReloadsIt() {
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        // an append that committed but was reported failed: the store is one ahead of memory, every push is now a 503
        store.appendUpdates( id, List.of( new StoredUpdate( 1, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT, null,
            ChangeSet.replace( BODY.length(), 0, 0, "lost " ) ) ) );
        for( int i = 0; i < CollabSessionRegistry.STORE_FAILURE_LIMIT; i++ ) {
            final PushResponse r = type( a, "alice", "x" );
            assertEquals( 503, r.status() );
            assertTrue( registry.live( id ).isPresent(), "still live after " + i + " failures" );
            registry.observe( id, r.status(), r.reason() );
        }
        assertTrue( registry.live( id ).isEmpty() );
        assertEquals( 0, a.session().view().participants(), "participants of the wedged session are dropped" );
        assertTrue( store.session( id ).isPresent(), "the draft is kept" );
        final Joined again = join( "alice" );
        assertEquals( id, again.response().sessionId() );
        assertEquals( "lost " + BODY, again.response().doc() );
        assertEquals( 200, type( again, "alice", "x" ).status() );
    }

    @Test
    void aSuccessResetsTheStoreFailureCount() {
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        for( int round = 0; round < 3; round++ ) {
            for( int i = 1; i < CollabSessionRegistry.STORE_FAILURE_LIMIT; i++ ) {
                registry.observe( id, 503, "store" );
            }
            registry.observe( id, 409, "stale-base" );   // neither resets nor counts
            registry.observe( id, 200, null );
        }
        registry.observe( id, 503, "overloaded" );   // only store failures count
        registry.observe( UUID.randomUUID(), 503, "store" );   // unknown ids are ignored
        assertTrue( registry.live( id ).isPresent() );
    }

    @Test
    void theWedgeEvictionWaitsForAPublishInItsSave() throws Exception {
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final PublishStart st = a.session().startPublish( a.response().participantId(), "alice",
            a.session().view().head(), "note" );
        assertNull( st.response() );
        assertTrue( CollabSession.Hooks.publishing( a.session() ) );
        for( int i = 0; i < CollabSessionRegistry.STORE_FAILURE_LIMIT; i++ ) {
            registry.observe( id, 503, "store" );
        }
        assertSame( a.session(), registry.live( id ).orElseThrow(), "no reload while this object's publish is in its save" );
        assertEquals( 200, a.session().finishPublish( st.ticket(), flaky.publish( st.ticket().request() ) ).status() );
        assertFalse( CollabSession.Hooks.publishing( a.session() ) );
        registry.observe( id, 503, "store" );   // still failing after the publish: now it goes
        assertTrue( registry.live( id ).isEmpty() );
    }

    @Test
    void theWedgeEvictionDoesNotWaitForAnOwedMerge() {
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        oweAMerge( a.session() );
        for( int i = 0; i < CollabSessionRegistry.STORE_FAILURE_LIMIT; i++ ) {
            registry.observe( id, 503, "store" );
        }
        assertTrue( registry.live( id ).isEmpty(), "the reload settles the owed merge" );
        final Joined again = join( "bob" );
        assertEquals( BODY + "external\n", again.response().doc() );
    }

    @Test
    void aDraftReloadedWithEditsAfterItsLastSnapshotIsKept() {
        final Joined a = join( "alice" );
        final UUID id = a.response().sessionId();
        assertEquals( 200, type( a, "alice", "x" ).status() );
        forceReload( id );
        assertFalse( store.session( id ).orElseThrow().hasUnpublished(), "the flag was never written (snapshot cadence)" );
        final Joined b = join( "bob" );
        assertNotEquals( a.session(), b.session() );
        leave( b, "bob" );
        idleOut();
        assertTrue( registry.live( id ).isEmpty() );
        assertTrue( store.session( id ).isPresent(), "the reloaded draft has edits: kept" );
    }

    // ---- truncation on the tick (A10) -------------------------------------------------------------------------

    @Test
    void theTickTruncatesTheLogOnceALaggingParticipantLeaves() {
        registry = registry( config( 20, 25, 45, 30, 2, 10 ) );
        final Joined lagging = join( "alice" );
        final Joined b = join( "bob" );
        for( int i = 0; i < 4; i++ ) {
            assertEquals( 200, type( b, "bob", "x" ).status() );
        }
        final UUID id = b.response().sessionId();
        assertEquals( 4, store.updatesSince( id, 0 ).size(), "alice confirmed only version 0" );
        assertNull( b.session().pullOrPark( b.response().participantId(), "bob", 4, null, new Waiter() ) );
        leave( lagging, "alice" );
        clock.advance( Duration.ofSeconds( 30 ) );
        registry.tick();
        assertTrue( store.updatesSince( id, 0 ).isEmpty(), "cut at the head, clamped to the snapshot at 4" );
    }

    // ---- rename and freeze (Phase 2's listener) ---------------------------------------------------------------

    @Test
    void rekeyMovesALiveSessionToTheNewName() {
        final Joined a = join( "alice" );
        registry.rekey( PAGE, "Renamed" );
        assertTrue( registry.liveForPage( PAGE ).isEmpty() );
        assertSame( a.session(), registry.liveForPage( "Renamed" ).orElseThrow() );
        assertEquals( "Renamed", a.session().pageName() );
        assertEquals( "Renamed", store.session( a.response().sessionId() ).orElseThrow().pageName() );
        writer.rename( PAGE, "Renamed" );
        assertSame( a.session(), assertInstanceOf( Joined.class, registry.join( "Renamed", req( "bob" ) ) ).session() );
    }

    @Test
    void rekeyMovesAStoredDraft() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        leave( a, "alice" );
        idleOut();
        registry.rekey( PAGE, "Renamed" );
        assertTrue( store.sessionForPage( PAGE ).isEmpty() );
        writer.rename( PAGE, "Renamed" );
        final Joined again = assertInstanceOf( Joined.class, registry.join( "Renamed", req( "bob" ) ) );
        assertEquals( a.response().sessionId(), again.response().sessionId() );
        assertEquals( "x" + BODY, again.response().doc() );
    }

    @Test
    void rekeyOntoALiveSessionIsRefused() {
        join( "alice" );
        writer.seed( "Other", "other\n", Map.of() );
        assertInstanceOf( Joined.class, registry.join( "Other", req( "bob" ) ) );
        assertThrows( IllegalStateException.class, () -> registry.rekey( PAGE, "Other" ) );
        assertTrue( registry.liveForPage( PAGE ).isPresent() );
        registry.rekey( "Nothing", "Else" );   // no session: nothing to do
    }

    @Test
    void freezeStopsPushesAndIsStored() {
        final Joined a = join( "alice" );
        final Waiter w = new Waiter();
        final PullResponse first = a.session().pullOrPark( a.response().participantId(), "alice",
            new com.wikantik.collab.model.PullCursor( 0, 0, 0, 0 ), null, new Waiter() );
        assertNull( a.session().pullOrPark( a.response().participantId(), "alice",
            new com.wikantik.collab.model.PullCursor( 0, first.metaSeq(), first.presenceSeq(), first.eventSeq() ), null,
            w ) );
        registry.freeze( PAGE );
        assertEquals( 1, w.got.size(), "the freeze wakes the parked pull" );
        assertTrue( w.got.get( 0 ).events().stream().anyMatch( e -> e.type().equals( "page-deleted" ) ) );
        final PushResponse r = type( a, "alice", "x" );
        assertEquals( 409, r.status() );
        assertEquals( "frozen", r.reason() );
        assertEquals( SessionRecord.FROZEN, store.session( a.response().sessionId() ).orElseThrow().status() );
    }

    @Test
    void freezeMarksAStoredDraft() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        leave( a, "alice" );
        idleOut();
        registry.freeze( PAGE );
        assertEquals( SessionRecord.FROZEN, store.session( a.response().sessionId() ).orElseThrow().status() );
        registry.freeze( "Nothing" );
    }

    @Test
    void aRejoinWithItsPriorClientIdReplacesItsStaleEntryInsteadOf429() {
        registry = registry( config( 1, 25, 45, 30, 500, 10 ) );
        final Joined a = join( "alice" );
        final JoinRequest rejoin = new JoinRequest( "alice", a.response().clientId(), "alice", "ALICE", null, null );
        final Joined again = assertInstanceOf( Joined.class, registry.join( PAGE, rejoin ) );
        assertEquals( a.response().clientId(), again.response().clientId() );
        assertEquals( 1, again.session().view().participants() );
        // another author naming that clientID is a new participant, so the session is full
        assertEquals( new Refused( 429, "session-full" ), registry.join( PAGE,
            new JoinRequest( "bob", a.response().clientId(), "bob", "BOB", null, null ) ) );
    }

    @Test
    void rekeyRenamesTheSessionWithoutHoldingThePageMap() throws Exception {
        final Joined a = join( "alice" );
        writer.externalSave( PAGE, BODY + "two\n", Map.of( "title", "T" ), "x" );
        writer.externalSave( PAGE, BODY + "three\n", Map.of( "title", "T" ), "x" );
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch( 1 );
        flaky.blockVersion = release;
        // a merge walking version 2 holds the session's base lock while it reads the page store
        final java.util.concurrent.CompletableFuture< Void > merge = java.util.concurrent.CompletableFuture.runAsync(
            () -> a.session().settle() );
        assertTrue( flaky.versionEntered.await( 10, java.util.concurrent.TimeUnit.SECONDS ) );
        final java.util.concurrent.CompletableFuture< Void > rename = java.util.concurrent.CompletableFuture.runAsync(
            () -> registry.rekey( PAGE, "Renamed" ) );
        try {
            // the rename waits on the base lock; lookups of other pages must not wait behind it
            final java.util.concurrent.CompletableFuture< Boolean > lookup = java.util.concurrent.CompletableFuture
                .supplyAsync( () -> registry.liveForPage( "Elsewhere" ).isEmpty() );
            assertTrue( lookup.get( 5, java.util.concurrent.TimeUnit.SECONDS ), "the page map is free" );
        } finally {
            flaky.blockVersion = null;
            release.countDown();
        }
        merge.get( 10, java.util.concurrent.TimeUnit.SECONDS );
        rename.get( 10, java.util.concurrent.TimeUnit.SECONDS );
        assertSame( a.session(), registry.liveForPage( "Renamed" ).orElseThrow() );
        assertTrue( registry.liveForPage( PAGE ).isEmpty() );
        assertEquals( "Renamed", a.session().pageName() );
    }

    @Test
    void aJoinRacingARekeyNeverReopensTheOldPageWithAStaleBase() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        leave( a, "alice" );
        idleOut();
        // bob's join reads PAGE's latest; then, before it takes the page's slot, the page is renamed away
        flaky.afterLatest = () -> {
            writer.rename( PAGE, "Renamed" );
            registry.rekey( PAGE, "Renamed" );
        };
        final Joined b = assertInstanceOf( Joined.class, registry.join( PAGE, req( "bob" ) ) );
        assertNotEquals( a.response().sessionId(), b.response().sessionId() );
        assertEquals( 0, b.response().basePageVersion(), "PAGE no longer exists: a new page, not the old base" );
        assertEquals( "", b.response().doc() );
        assertEquals( "Renamed", store.session( a.response().sessionId() ).orElseThrow().pageName() );
    }

    @Test
    void aFailedRekeyIsRememberedAndRetriedOnTheTick() {
        final Joined a = join( "alice" );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "Renamed" ) );
        assertEquals( PAGE, a.session().pageName(), "the store refused: still on the old name" );
        writer.rename( PAGE, "Renamed" );

        registry.tick();

        assertEquals( "Renamed", a.session().pageName() );
        assertSame( a.session(), registry.liveForPage( "Renamed" ).orElseThrow() );
        assertEquals( "Renamed", store.session( a.response().sessionId() ).orElseThrow().pageName() );
    }

    @Test
    void aJoinOfTheNewNameDuringAPendingRekeyJoinsTheMovedSession() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "Renamed" ) );
        writer.rename( PAGE, "Renamed" );

        final Joined b = assertInstanceOf( Joined.class, registry.join( "Renamed", req( "bob" ) ) );

        assertEquals( a.response().sessionId(), b.response().sessionId(), "never a second session for the page" );
        assertEquals( "x" + BODY, b.response().doc() );
        assertEquals( "Renamed", a.session().pageName() );
    }

    @Test
    void aJoinOfTheNewNameWhileTheRekeyStillFailsIsRefusedNotDuplicated() {
        final Joined a = join( "alice" );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "Renamed" ) );
        writer.rename( PAGE, "Renamed" );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );

        assertEquals( new Refused( 503, "store" ), registry.join( "Renamed", req( "bob" ) ) );
        assertTrue( store.sessionForPage( "Renamed" ).isEmpty(), "no second session was created" );

        registry.tick();   // the retry lands
        assertSame( a.session(), assertInstanceOf( Joined.class, registry.join( "Renamed", req( "bob" ) ) ).session() );
    }

    @Test
    void registryRekeyAndFreezeCompleteParkedPullsOutsideTheSlotLock() throws Exception {
        final Joined a = join( "alice" );
        final List< String > blocked = new ArrayList<>();
        final PullWaiter checking = r -> {
            // another thread's join needs the page's slot lock: it must not wait for this completion
            final java.util.concurrent.CompletableFuture< JoinOutcome > other = java.util.concurrent.CompletableFuture
                .supplyAsync( () -> registry.join( a.session().pageName(), req( "carol" ) ) );
            try {
                other.get( 3, java.util.concurrent.TimeUnit.SECONDS );
            } catch( final java.util.concurrent.TimeoutException e ) {
                blocked.add( r.events().toString() );
            } catch( final Exception e ) {
                blocked.add( e.toString() );
            }
        };
        park( a, checking );
        registry.rekey( PAGE, "Renamed" );
        writer.rename( PAGE, "Renamed" );
        park( a, checking );
        writer.delete( "Renamed" );
        registry.freeze( "Renamed" );
        assertEquals( List.of(), blocked, "a parked pull was completed while the slot lock was held" );
    }

    /** Parks {@code a}'s pull at every current cursor with {@code w}. */
    private static void park( final Joined a, final PullWaiter w ) {
        final String pid = a.response().participantId();
        final PullResponse first = a.session().pullOrPark( pid, "alice",
            new com.wikantik.collab.model.PullCursor( 0, 0, 0, 0 ), null, new Waiter() );
        assertNull( a.session().pullOrPark( pid, "alice", new com.wikantik.collab.model.PullCursor( 0, first.metaSeq(),
            first.presenceSeq(), first.eventSeq() ), null, w ) );
    }

    @Test
    void joinResponsesComeFromTheSession() {
        final JoinOutcome o = registry.join( PAGE, req( "alice" ) );
        final JoinResponse r = assertInstanceOf( Joined.class, o ).response();
        assertEquals( 1, r.participants().size() );
        assertEquals( "ALICE", r.participants().get( 0 ).displayName() );
    }
}
