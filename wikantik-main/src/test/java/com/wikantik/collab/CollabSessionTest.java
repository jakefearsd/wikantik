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

import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.LeaveResponse;
import com.wikantik.collab.model.PresenceEntry;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The real session against the Phase 0 doubles: section 7.2 push, 7.5 pull, 7.6 ring/snapshots, 7.7 load, plus the
 * Phase 0 final-review amendments (prior clientID, initial text, atomic pull-or-park, truncation, push limiter).
 */
class CollabSessionTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    /** Public names for two logins; everyone else falls back to the join's display name. */
    private static final CollabIdentity IDENTITY = key -> switch( key ) {
        case "ada" -> "Ada Lovelace";
        case "raj" -> "Raj Patel";
        default -> null;
    };

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private CollabSession session;

    /** Records every completion; a second completion of one waiter fails the test (F6: exactly once). */
    private static final class Waiter implements PullWaiter {
        final List< PullResponse > got = new ArrayList<>();
        CollabSession checkUnlocked;

        @Override
        public void complete( final PullResponse r ) {
            if( checkUnlocked != null ) {
                assertFalse( CollabSession.Hooks.lockHeld( checkUnlocked ), "waiters are completed outside the session lock" );
            }
            if( !got.isEmpty() ) {
                fail( "waiter completed twice: " + got + " then " + r );
            }
            got.add( r );
        }

        PullResponse only() {
            assertEquals( 1, got.size(), "completions" );
            return got.get( 0 );
        }
    }

    /** Every participant may edit (section 7.3's permission re-check is CollabSessionPublishTest's). */
    static final CollabPermissions ALLOW = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    private CollabPorts ports( final CollabStore st, final CollabPageWriter w ) {
        return new CollabPorts( st, w, IDENTITY, ALLOW, clock );
    }

    static CollabConfig config( final int ring, final int snapshotEvery, final int pushesPerSecond ) {
        return new CollabConfig( true, 20, 65536, 25, 45, 30, ring, snapshotEvery, 10, 0, pushesPerSecond );
    }

    private CollabSession create( final String page, final ScriptedPageWriter w, final CollabConfig c, final long max ) {
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( page.getBytes() ), page,
            w.latest( page ), "creator", clock );
        return CollabSession.create( rec, ports( store, w ), c, max );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        clock = new MutableClock( T0 );
        session = create( PAGE, writer, config( 3, 500, 1000 ), 1000 );
    }

    private JoinResponse join( final String author ) {
        return session.join( JoinRequest.of( author, null, author.toUpperCase() ) );
    }

    private static Update typeAt( final JoinResponse j, final String doc, final int at, final String text ) {
        return new Update( j.clientId(), ChangeSet.replace( doc.length(), at, at, text ) );
    }

    private PushResponse push( final JoinResponse j, final String author, final int version, final Update... u ) {
        return session.push( j.participantId(), author, version, List.of( u ) );
    }

    private PullResponse pull( final JoinResponse j, final String author, final int since, final Waiter w ) {
        return session.pullOrPark( j.participantId(), author, since, null, w );
    }

    private void checkInvariants() {
        final CollabSession.View v = session.view();
        assertEquals( v.doc(), v.local().apply( v.baseBody() ), "invariant 1" );
        assertEquals( v.head(), store.session( v.id() ).orElseThrow().headVersion(), "invariant 2" );
    }

    // ---- join -----------------------------------------------------------------------------------------------

    @Test
    void createWritesAV0SnapshotAndJoinReturnsDocAndVersion() {
        final Snapshot v0 = store.latestSnapshot( session.id() ).orElseThrow();
        assertEquals( 0, v0.version() );
        assertEquals( BODY, v0.body() );
        assertEquals( 1, v0.basePageVersion() );
        assertTrue( v0.local().empty() );
        final JoinResponse j = join( "alice" );
        assertEquals( BODY, j.doc() );
        assertEquals( 0, j.version() );
        assertEquals( session.id(), j.sessionId() );
        assertEquals( 1, j.basePageVersion() );
        assertEquals( Map.of( "title", "T" ), j.metadata() );
        assertEquals( 1, j.participants().size() );
        assertEquals( "ALICE", j.participants().get( 0 ).displayName() );
        assertFalse( j.unpublished() );
        assertNull( j.lastEditAt() );
        checkInvariants();
    }

    @Test
    void twoTabsOfOneUserAreTwoParticipants() {   // A6, review focus 4
        final JoinResponse tab1 = join( "alice" );
        final JoinResponse tab2 = join( "alice" );
        assertNotEquals( tab1.participantId(), tab2.participantId() );
        assertNotEquals( tab1.clientId(), tab2.clientId() );
        assertEquals( 2, tab2.participants().size() );
        // neither tab may push with the other's clientID, even though both belong to alice
        final PushResponse spoof = push( tab2, "alice", 0, typeAt( tab1, BODY, 0, "X" ) );
        assertEquals( 400, spoof.status() );
        assertEquals( 0, session.view().head() );
        assertEquals( 200, push( tab1, "alice", 0, typeAt( tab1, BODY, 0, "X" ) ).status() );
        assertEquals( 200, push( tab2, "alice", 1, typeAt( tab2, "X" + BODY, 0, "Y" ) ).status() );
        // a prior clientID held by a live participant of another author is never reissued
        final JoinResponse bob = session.join( new JoinRequest( "bob", tab1.clientId(), null, "B", null, null ) );
        assertNotEquals( tab1.clientId(), bob.clientId() );
        // the same author's rejoin with it replaces that (stale) entry and gets it back (A13; only its own tab knows it)
        final JoinResponse tab3 = session.join( new JoinRequest( "alice", tab1.clientId(), null, "A", null, null ) );
        assertEquals( tab1.clientId(), tab3.clientId() );
        assertEquals( 409, push( tab1, "alice", 2, typeAt( tab1, "YX" + BODY, 0, "Z" ) ).status() );
        checkInvariants();
    }

    @Test
    void aRejoinGetsItsPriorClientIdBackWhenTheSessionStillKnowsIt() {   // 7.2 "same clientID"
        final JoinResponse a = join( "alice" );
        assertEquals( 200, session.leave( a.participantId(), "alice" ).status() );
        final JoinResponse again = session.join( new JoinRequest( "alice", a.clientId(), null, "A", null, null ) );
        assertEquals( a.clientId(), again.clientId() );
        assertNotEquals( a.participantId(), again.participantId() );
        // the old participant stays gone
        assertEquals( 409, push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) ).status() );
        assertEquals( 200, push( again, "alice", 0, typeAt( again, BODY, 0, "X" ) ).status() );
    }

    @Test
    void aPriorClientIdOfAnotherAuthorOrUnknownIsNotReissued() {
        final JoinResponse a = join( "alice" );
        assertEquals( 200, session.leave( a.participantId(), "alice" ).status() );
        final JoinResponse m = session.join( new JoinRequest( "mallory", a.clientId(), null, "M", null, null ) );
        assertNotEquals( a.clientId(), m.clientId() );
        final JoinResponse u = session.join( new JoinRequest( "alice", "never-issued", null, "A", null, null ) );
        assertNotEquals( "never-issued", u.clientId() );
        // still reissuable to its own author after the refused attempts
        assertEquals( a.clientId(), session.join( new JoinRequest( "alice", a.clientId(), null, "A", null, null ) ).clientId() );
    }

    @Test
    void initialTextSeedsOnlyTheJoinThatFollowsCreation() {   // A11
        final JoinResponse first = session.join( new JoinRequest( "alice", null, null, "A", "template\n",
            Map.of( "title", "New" ) ) );
        assertEquals( "template\n", first.doc() );
        assertEquals( 1, first.version() );
        assertEquals( Map.of( "title", "New" ), first.metadata() );
        assertEquals( 1, first.metaSeq() );
        assertTrue( first.unpublished() );
        final StoredUpdate seeded = store.updatesSince( session.id(), 0 ).get( 0 );
        assertEquals( StoredUpdate.SERVER, seeded.kind() );
        // a later join's initial text is ignored
        final JoinResponse second = session.join( new JoinRequest( "bob", null, null, "B", "other\n", Map.of() ) );
        assertEquals( "template\n", second.doc() );
        assertEquals( 1, second.version() );
        checkInvariants();
    }

    @Test
    void initialMetaIsComparedAfterJsonNormalisation() {   // A14: an Integer from YAML equals a Long from JSON
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( "Num", "x\n", Map.of( "count", 3 ) );
        final CollabSession s = create( "Num", w, config( 3, 500, 1000 ), 1000 );
        final JoinResponse j = s.join( new JoinRequest( "alice", null, null, "A", null, Map.of( "count", 3L ) ) );
        assertEquals( 0, j.metaSeq() );
        assertFalse( j.unpublished() );
        assertEquals( 200, s.publish( j.participantId(), "alice", 0, "n" ).status() );
        assertTrue( s.publish( j.participantId(), "alice", 0, "n" ).unchanged() );
    }

    @Test
    void initialTextEqualToTheBaseAppendsNothing() {
        final JoinResponse first = session.join( new JoinRequest( "alice", null, null, "A", BODY, Map.of( "title", "T" ) ) );
        assertEquals( 0, first.version() );
        assertEquals( 0, first.metaSeq() );
        assertTrue( store.updatesSince( session.id(), 0 ).isEmpty() );
    }

    // ---- push -----------------------------------------------------------------------------------------------

    @Test
    void pushAcceptedAdvancesHeadAndComposesLocal() {
        final JoinResponse j = join( "alice" );
        final PushResponse r = push( j, "alice", 0, typeAt( j, BODY, 0, "X" ) );
        assertEquals( 200, r.status() );
        assertEquals( 1, r.version() );
        assertNull( r.reason() );
        assertEquals( "X" + BODY, session.view().doc() );
        assertFalse( session.view().local().empty() );
        assertEquals( "X" + BODY, session.view().ring().get( 0 ).changes().apply( BODY ) );
        assertEquals( T0, session.view().lastEditAt() );
        checkInvariants();
    }

    @Test
    void e8_stalePushIs409() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) );
        final PushResponse r = push( b, "bob", 0, typeAt( b, BODY, 0, "Y" ) );
        assertEquals( 409, r.status() );
        assertEquals( "stale-base", r.reason() );
        assertEquals( 1, r.version() );
        assertEquals( "X" + BODY, session.view().doc() );
    }

    @Test
    void e10_duplicatedPushAppliedOnce() {
        final JoinResponse a = join( "alice" );
        final Update u = typeAt( a, BODY, 0, "X" );
        assertEquals( 200, push( a, "alice", 0, u ).status() );
        assertEquals( 409, push( a, "alice", 0, u ).status() );
        assertEquals( "X" + BODY, session.view().doc() );
        assertEquals( 1, session.view().head() );
    }

    @Test
    void e14_anotherParticipantsClientIdIs400AndChangesNothing() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final PushResponse r = push( b, "bob", 0, typeAt( a, BODY, 0, "X" ) );
        assertEquals( 400, r.status() );
        assertEquals( "rejected", r.reason() );
        assertEquals( BODY, session.view().doc() );
        assertTrue( store.updatesSince( session.id(), 0 ).isEmpty() );
    }

    @Test
    void e15_lengthMismatchIs400AndUnchanged() {
        final JoinResponse a = join( "alice" );
        final PushResponse r = push( a, "alice", 0, new Update( a.clientId(), ChangeSet.replace( 3, 0, 0, "X" ) ) );
        assertEquals( 400, r.status() );
        assertEquals( BODY, session.view().doc() );
        assertEquals( 0, session.view().head() );
    }

    @Test
    void aLoneSurrogateOrASplitPairIs400() {   // E2, 7.2
        final JoinResponse a = join( "alice" );
        assertEquals( 400, push( a, "alice", 0, typeAt( a, BODY, 0, "\uD83D" ) ).status() );
        assertEquals( 200, push( a, "alice", 0, typeAt( a, BODY, 0, "😀" ) ).status() );
        final String doc = session.view().doc();
        assertEquals( 400, push( a, "alice", 1, new Update( a.clientId(), ChangeSet.replace( doc.length(), 1, 1, "x" ) ) ).status() );
        assertEquals( doc, session.view().doc() );
    }

    @Test
    void oversizePushIs413ButShrinkingIsAllowed() {
        final JoinResponse a = join( "alice" );
        final PushResponse big = push( a, "alice", 0, typeAt( a, BODY, 0, "x".repeat( 1000 ) ) );
        assertEquals( 413, big.status() );
        assertEquals( "oversize", big.reason() );
        assertEquals( 0, session.view().head() );
        assertFalse( session.view().oversize() );
        assertEquals( 200, push( a, "alice", 0, typeAt( a, BODY, 0, "x".repeat( 900 ) ) ).status() );
    }

    @Test
    void aSessionLoadedOverTheLimitAllowsOnlyNonGrowingPushes() {   // oversize state, 7.4
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( "P2", "y".repeat( 20 ), Map.of() );
        final CollabSession tight = create( "P2", w, config( 3, 500, 1000 ), 10 );
        assertTrue( tight.view().oversize() );
        final JoinResponse t = tight.join( JoinRequest.of( "alice", null, null ) );
        final String d = tight.view().doc();
        assertEquals( 413, tight.push( t.participantId(), "alice", 0, List.of( typeAt( t, d, 0, "z" ) ) ).status() );
        assertEquals( 200, tight.push( t.participantId(), "alice", 0,
            List.of( new Update( t.clientId(), ChangeSet.replace( d.length(), 0, 1, "z" ) ) ) ).status() );
        assertEquals( 200, tight.push( t.participantId(), "alice", 1,
            List.of( new Update( t.clientId(), ChangeSet.replace( d.length(), 0, 3, "" ) ) ) ).status() );
        assertEquals( 18, tight.view().oversizeCeiling() );   // "y" x 20 plus the trailing line end, less 3
    }

    @Test
    void multiUpdatePushChainsAndAssignsContiguousVersions() {
        final JoinResponse a = join( "alice" );
        final Update u1 = typeAt( a, BODY, 0, "X" );
        final Update u2 = new Update( a.clientId(), ChangeSet.replace( BODY.length() + 1, 0, 0, "Y" ) );
        final PushResponse r = push( a, "alice", 0, u1, u2 );
        assertEquals( 200, r.status() );
        assertEquals( 2, r.version() );
        assertEquals( "YX" + BODY, session.view().doc() );
        assertEquals( List.of( 1, 2 ), store.updatesSince( session.id(), 0 ).stream().map( StoredUpdate::version ).toList() );
        checkInvariants();
    }

    @Test
    void e35_aStoreFailureIs503AndMemoryUnchangedThenRetrySucceeds() {
        final JoinResponse a = join( "alice" );
        store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 1 );
        final Update u = typeAt( a, BODY, 0, "X" );
        final PushResponse failed = push( a, "alice", 0, u );
        assertEquals( 503, failed.status() );
        assertEquals( "store", failed.reason() );
        assertEquals( BODY, session.view().doc() );
        assertEquals( 0, session.view().head() );
        assertTrue( session.view().ring().isEmpty() );
        assertTrue( session.view().local().empty() );
        assertEquals( 200, push( a, "alice", 0, u ).status() );
        checkInvariants();
    }

    @Test
    void unknownParticipantIs409RejoinAndAnotherAuthorIs403() {   // A6
        final PushResponse unknown = session.push( "nobody", "x", 0, List.of() );
        assertEquals( 409, unknown.status() );
        assertEquals( "rejoin", unknown.reason() );
        assertEquals( "rejoin", session.pullOrPark( "nobody", "x", 0, null, new Waiter() ).reason() );
        final JoinResponse a = join( "alice" );
        final PushResponse r = push( a, "mallory", 0, typeAt( a, BODY, 0, "X" ) );
        assertEquals( 403, r.status() );
        assertEquals( "not-your-participant", r.reason() );
        assertEquals( 403, session.pullOrPark( a.participantId(), "mallory", 0, null, new Waiter() ).status() );
        assertEquals( "not-your-participant", session.publish( a.participantId(), "mallory", 0, "n" ).reason() );
        assertEquals( 0, session.view().head() );
    }

    @Test
    void pushesOverThePerParticipantRateAre429() {   // A8
        final CollabSession limited = create( "Limited", seeded( "Limited", "" ), config( 3, 500, 2 ), 1000 );
        final JoinResponse a = limited.join( JoinRequest.of( "alice", null, null ) );
        final JoinResponse b = limited.join( JoinRequest.of( "bob", null, null ) );
        assertEquals( 200, limited.push( a.participantId(), "alice", 0, List.of() ).status() );
        assertEquals( 200, limited.push( a.participantId(), "alice", 0, List.of() ).status() );
        final PushResponse third = limited.push( a.participantId(), "alice", 0,
            List.of( new Update( a.clientId(), ChangeSet.replace( 1, 0, 0, "x" ) ) ) );
        assertEquals( 429, third.status() );
        assertEquals( "rate-limited", third.reason() );
        assertEquals( 0, limited.view().head() );
        // per participant: bob is unaffected
        assertEquals( 200, limited.push( b.participantId(), "bob", 0, List.of() ).status() );
        clock.advance( Duration.ofMillis( 500 ) );
        assertEquals( 200, limited.push( a.participantId(), "alice", 0, List.of() ).status() );
        assertEquals( 429, limited.push( a.participantId(), "alice", 0, List.of() ).status() );
    }

    private static ScriptedPageWriter seeded( final String page, final String body ) {
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( page, body, Map.of() );
        return w;
    }

    // ---- pull -----------------------------------------------------------------------------------------------

    @Test
    void pullBehindAnswersImmediately() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) );
        final Waiter w = new Waiter();
        final PullResponse r = pull( b, "bob", 0, w );
        assertEquals( 200, r.status() );
        assertEquals( 1, r.version() );
        assertEquals( 1, r.updates().size() );
        assertEquals( a.clientId(), r.updates().get( 0 ).clientId() );
        assertTrue( w.got.isEmpty(), "an immediate answer never touches the waiter" );
    }

    @Test
    void pullAtHeadParksAndAPushWakesItOutsideTheLock() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final Waiter w = new Waiter();
        w.checkUnlocked = session;
        assertNull( pull( b, "bob", 0, w ) );
        assertTrue( w.got.isEmpty() );
        push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) );
        final PullResponse r = w.only();
        assertEquals( 200, r.status() );
        assertEquals( 1, r.updates().size() );
        assertEquals( 1, r.version() );
    }

    @Test
    void aParkedPullTimesOutEmptyThroughExpireWaiters() {
        final JoinResponse b = join( "bob" );
        final Waiter w = new Waiter();
        assertNull( pull( b, "bob", 0, w ) );
        clock.advance( Duration.ofSeconds( 24 ) );
        assertTrue( session.expireWaiters().isEmpty() );
        clock.advance( Duration.ofSeconds( 2 ) );
        final List< Runnable > expired = session.expireWaiters();
        assertEquals( 1, expired.size() );
        assertTrue( w.got.isEmpty(), "expireWaiters only collects; the caller completes outside the lock" );
        expired.forEach( Runnable::run );
        assertEquals( 200, w.only().status() );
        assertTrue( w.only().updates().isEmpty() );
        assertTrue( session.expireWaiters().isEmpty() );
    }

    @Test
    void e32_leavingCompletesTheParkedWaiterOnce() {
        final JoinResponse b = join( "bob" );
        final Waiter w = new Waiter();
        pull( b, "bob", 0, w );
        assertEquals( 200, session.leave( b.participantId(), "bob" ).status() );
        assertEquals( 200, w.only().status() );
        clock.advance( Duration.ofSeconds( 30 ) );
        session.expireWaiters().forEach( Runnable::run );
        assertEquals( 1, w.got.size() );
    }

    @Test
    void aSecondParkedPullDisplacesTheFirstWhichIsAnsweredEmpty() {
        final JoinResponse b = join( "bob" );
        final Waiter first = new Waiter();
        final Waiter second = new Waiter();
        assertNull( pull( b, "bob", 0, first ) );
        assertNull( pull( b, "bob", 0, second ) );
        assertTrue( first.only().updates().isEmpty() );
        assertTrue( second.got.isEmpty() );
        clock.advance( Duration.ofSeconds( 30 ) );
        session.expireWaiters().forEach( Runnable::run );
        assertEquals( 1, second.got.size() );
    }

    @Test
    void aBadSinceIs400() {
        final JoinResponse b = join( "bob" );
        assertEquals( "bad-since", pull( b, "bob", 1, new Waiter() ).reason() );
        assertEquals( 400, pull( b, "bob", -1, new Waiter() ).status() );
    }

    @Test
    void catchUpBelowTheRingIsServedFromTheStore() {   // A10, E12
        final JoinResponse a = join( "alice" );
        String doc = BODY;
        for( int i = 0; i < 5; i++ ) {
            assertEquals( 200, push( a, "alice", i, typeAt( a, doc, 0, "a" ) ).status() );
            doc = "a" + doc;
        }
        assertEquals( 3, session.view().ring().size() );
        assertEquals( 3, session.view().ring().get( 0 ).version() );
        final PullResponse r = pull( a, "alice", 0, new Waiter() );
        assertEquals( 200, r.status() );
        assertEquals( 5, r.updates().size() );
        String replay = BODY;
        for( final Update u : r.updates() ) {
            replay = u.changes().apply( replay );
        }
        assertEquals( doc, replay );
    }

    @Test
    void a410OnlyWhenTheStoreTruncatedBelowSince() {   // A10
        final JoinResponse a = join( "alice" );
        String doc = BODY;
        for( int i = 0; i < 5; i++ ) {
            push( a, "alice", i, typeAt( a, doc, 0, "a" ) );
            doc = "a" + doc;
        }
        store.writeSnapshot( session.id(), new Snapshot( 3, 0, 1, "aaa" + BODY, Map.of(), ChangeSet.replace( BODY.length(), 0, 0, "aaa" ) ) );
        store.truncateUpdatesBelow( session.id(), 4 );
        assertEquals( 4, store.updatesSince( session.id(), 0 ).get( 0 ).version() );
        // version 2 is below the ring (3..5) and the store no longer holds 3: resync
        final PullResponse gone = pull( a, "alice", 1, new Waiter() );
        assertEquals( 410, gone.status() );
        assertEquals( "resync", gone.reason() );
        // version 2 is still served: the ring starts at 3
        assertEquals( 3, pull( a, "alice", 2, new Waiter() ).updates().size() );
        assertEquals( 200, pull( a, "alice", 3, new Waiter() ).status() );
    }

    @Test
    void aWaiterIsAnsweredFromMemoryEvenWhenTheBatchExceedsTheRing() {
        // the ring holds 3; a batch of 5 wakes a waiter parked at 0: its slice must come from memory, and reads after the
        // commit must never reach the store (which here refuses every read once the session is loaded)
        final ReadFailingStore failing = new ReadFailingStore( store );
        final CollabSession s = CollabSession.load( session.id(), ports( failing, writer ), config( 3, 500, 1000 ), 1000 );
        final JoinResponse a = s.join( JoinRequest.of( "alice", null, null ) );
        final JoinResponse b = s.join( JoinRequest.of( "bob", null, null ) );
        final Waiter w = new Waiter();
        assertNull( s.pullOrPark( b.participantId(), "bob", 0, null, w ) );
        failing.failReads = true;
        final List< Update > batch = new ArrayList<>();
        String doc = BODY;
        for( int i = 0; i < 5; i++ ) {
            batch.add( typeAt( a, doc, 0, "z" ) );
            doc = "z" + doc;
        }
        assertEquals( 200, s.push( a.participantId(), "alice", 0, batch ).status() );
        assertEquals( 5, w.only().updates().size() );
        assertEquals( 5, w.only().version() );
        assertEquals( doc, s.view().doc() );
    }

    /** Delegates to an in-memory store; reads throw once {@link #failReads} is set. */
    private static final class ReadFailingStore implements CollabStore {
        private final CollabStore d;
        boolean failReads;

        ReadFailingStore( final CollabStore d ) {
            this.d = d;
        }

        private void check() {
            if( failReads ) {
                throw new IllegalStateException( "read refused (test)" );
            }
        }

        @Override public void createSession( final SessionRecord s ) { d.createSession( s ); }
        @Override public Optional< SessionRecord > session( final UUID id ) { check(); return d.session( id ); }
        @Override public Optional< SessionRecord > sessionForPage( final String p ) { check(); return d.sessionForPage( p ); }
        @Override public void updateSession( final SessionRecord s ) { d.updateSession( s ); }
        @Override public void appendUpdates( final UUID id, final List< StoredUpdate > u ) { d.appendUpdates( id, u ); }
        @Override public List< StoredUpdate > updatesSince( final UUID id, final int v ) { check(); return d.updatesSince( id, v ); }
        @Override public void appendMetaOps( final UUID id, final List< com.wikantik.collab.model.StoredMetaOp > o ) { d.appendMetaOps( id, o ); }
        @Override public void appendBatch( final UUID id, final List< StoredUpdate > u, final List< com.wikantik.collab.model.StoredMetaOp > o ) { d.appendBatch( id, u, o ); }
        @Override public List< com.wikantik.collab.model.StoredMetaOp > metaOpsSince( final UUID id, final long s ) { check(); return d.metaOpsSince( id, s ); }
        @Override public void commitBaseChange( final UUID id, final List< StoredUpdate > u, final Snapshot s, final SessionRecord r ) { d.commitBaseChange( id, u, s, r ); }
        @Override public void writeSnapshot( final UUID id, final Snapshot s ) { d.writeSnapshot( id, s ); }
        @Override public Optional< Snapshot > latestSnapshot( final UUID id ) { check(); return d.latestSnapshot( id ); }
        @Override public Optional< Snapshot > snapshotAtOrBelow( final UUID id, final int v ) { check(); return d.snapshotAtOrBelow( id, v ); }
        @Override public void truncateUpdatesBelow( final UUID id, final int cut ) { d.truncateUpdatesBelow( id, cut ); }
        @Override public void deleteSession( final UUID id ) { d.deleteSession( id ); }
        @Override public List< SessionRecord > sessions() { check(); return d.sessions(); }
        @Override public void recordContributors( final String c, final int v, final java.util.Set< String > k, final boolean created ) { d.recordContributors( c, v, k, created ); }
        @Override public java.util.Map< Integer, List< String > > contributorsByVersion( final String c ) { check(); return d.contributorsByVersion( c ); }
    }

    // ---- presence -------------------------------------------------------------------------------------------

    @Test
    void aSelectionRidesOnPushAndPullAndIsMappedToHead() {   // A12
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        // bob's caret at 5 (after "hello") at version 0, nothing unconfirmed
        assertNull( session.pullOrPark( b.participantId(), "bob", 0, new Selection( 5, 5, 0, 0 ), new Waiter() ) );
        assertEquals( new Selection( 5, 5, 0, 0 ), session.view().selections().get( b.participantId() ) );
        // alice inserts "XY" at 0 with her selection after it
        assertEquals( 200, session.push( a.participantId(), "alice", 0, List.of( typeAt( a, BODY, 0, "XY" ) ),
            new Selection( 2, 2, 0, 1 ) ).status() );
        // alice's selection covered exactly the pushed update: now in head coordinates
        assertEquals( new Selection( 2, 2, 1, 0 ), session.view().selections().get( a.participantId() ) );
        // bob pulls from 0 again with his caret: mapped through the update to head
        final PullResponse r = session.pullOrPark( b.participantId(), "bob", 0, 0, new Selection( 5, 5, 0, 0 ), new Waiter() );
        assertEquals( new Selection( 7, 7, 1, 0 ), session.view().selections().get( b.participantId() ) );
        assertEquals( new Selection( 2, 2, 1, 0 ), r.presence().get( a.participantId() ).selection() );
        assertFalse( r.presence().containsKey( b.participantId() ), "a participant's own selection is not echoed" );
        // an out-of-range selection is ignored, not an error
        assertEquals( 200, session.pullOrPark( b.participantId(), "bob", 0, new Selection( 999, 0, 0, 0 ), new Waiter() ).status() );
        assertEquals( new Selection( 7, 7, 1, 0 ), session.view().selections().get( b.participantId() ) );
        assertEquals( 200, session.leave( a.participantId(), "alice" ).status() );
        assertFalse( session.view().selections().containsKey( a.participantId() ) );
    }

    // ---- snapshots, truncation, load --------------------------------------------------------------------------

    @Test
    void snapshotCadence() {
        final CollabSession s = create( "Cad", seeded( "Cad", "" ), config( 3, 3, 1000 ), 1000 );
        final JoinResponse a = s.join( JoinRequest.of( "alice", null, null ) );
        String doc = s.view().doc();
        for( int i = 0; i < 7; i++ ) {
            assertEquals( 200, s.push( a.participantId(), "alice", i,
                List.of( new Update( a.clientId(), ChangeSet.replace( doc.length(), 0, 0, "x" ) ) ) ).status() );
            doc = "x" + doc;
            final int expected = ( i + 1 ) / 3 * 3;
            assertEquals( expected, store.latestSnapshot( s.id() ).orElseThrow().version(), "after push " + ( i + 1 ) );
        }
        final Snapshot six = store.latestSnapshot( s.id() ).orElseThrow();
        assertEquals( "xxxxxx\n", six.body() );
        assertEquals( "xxxxxx\n", six.local().apply( "\n" ) );
    }

    @Test
    void e36_aFailedSnapshotWriteLeavesTheSessionRunningAndIsRetried() {
        final CollabSession s = create( "Cad", seeded( "Cad", "" ), config( 3, 2, 1000 ), 1000 );
        final JoinResponse a = s.join( JoinRequest.of( "alice", null, null ) );
        store.failOn( InMemoryCollabStore.Op.WRITE_SNAPSHOT, 1 );
        String doc = s.view().doc();
        for( int i = 0; i < 3; i++ ) {
            assertEquals( 200, s.push( a.participantId(), "alice", i,
                List.of( new Update( a.clientId(), ChangeSet.replace( doc.length(), 0, 0, "x" ) ) ) ).status() );
            doc = "x" + doc;
        }
        // the write at 2 failed; the next push retried it at 3
        assertEquals( 3, store.latestSnapshot( s.id() ).orElseThrow().version() );
        final CollabSession reloaded = CollabSession.load( s.id(), ports( store, writer ), config( 3, 2, 1000 ), 1000 );
        assertEquals( "xxx\n", reloaded.view().doc() );
    }

    @Test
    void truncationKeepsEveryLiveParticipantsVersion() {   // A10
        final CollabSession s = create( "Tr", seeded( "Tr", "" ), config( 2, 2, 1000 ), 1000 );
        final JoinResponse a = s.join( JoinRequest.of( "alice", null, null ) );
        final JoinResponse b = s.join( JoinRequest.of( "bob", null, null ) );   // confirmed 0
        String doc = s.view().doc();
        for( int i = 0; i < 6; i++ ) {
            assertEquals( 200, s.push( a.participantId(), "alice", i,
                List.of( new Update( a.clientId(), ChangeSet.replace( doc.length(), 0, 0, "x" ) ) ) ).status() );
            doc = "x" + doc;
        }
        // bob still confirms version 0, so nothing was truncated
        assertEquals( 1, store.updatesSince( s.id(), 0 ).get( 0 ).version() );
        assertEquals( 6, s.pullOrPark( b.participantId(), "bob", 0, null, new Waiter() ).updates().size() );
        // bob now confirms 4: the cut clamps to the newest snapshot at or below 4
        s.pullOrPark( b.participantId(), "bob", 4, null, new Waiter() );
        s.truncateLog();
        assertEquals( 5, store.updatesSince( s.id(), 0 ).get( 0 ).version() );
        assertEquals( 200, s.pullOrPark( b.participantId(), "bob", 4, null, new Waiter() ).status() );
        // once bob leaves, alice's confirmed version (5, her last push) is the cut
        assertEquals( 200, s.leave( b.participantId(), "bob" ).status() );
        assertEquals( 5, s.truncateLog() );
        final CollabSession reloaded = CollabSession.load( s.id(), ports( store, writer ), config( 2, 2, 1000 ), 1000 );
        assertEquals( doc, reloaded.view().doc() );
        assertEquals( 6, reloaded.view().head() );
    }

    @Test
    void loadRebuildsTheCommittedState() {   // E37
        final CollabSession s = create( "Ld", seeded( "Ld", "base\n" ), config( 3, 4, 1000 ), 1000 );
        final JoinResponse a = s.join( new JoinRequest( "alice", null, null, "A", null, null ) );
        String doc = "base\n";
        for( int i = 0; i < 6; i++ ) {
            assertEquals( 200, s.push( a.participantId(), "alice", i,
                List.of( new Update( a.clientId(), ChangeSet.replace( doc.length(), doc.length(), doc.length(), "l" + i ) ) ) ).status() );
            doc = doc + "l" + i;
        }
        store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 1 );
        assertEquals( 503, s.push( a.participantId(), "alice", 6,
            List.of( new Update( a.clientId(), ChangeSet.replace( doc.length(), 0, 0, "lost" ) ) ) ).status() );
        final CollabSession.View committed = s.view();

        final CollabSession r = CollabSession.load( s.id(), ports( store, writer ), config( 3, 4, 1000 ), 1000 );

        final CollabSession.View v = r.view();
        assertEquals( committed.id(), v.id() );
        assertEquals( "Ld", v.pageName() );
        assertEquals( doc, v.doc() );
        assertEquals( committed.head(), v.head() );
        assertEquals( committed.baseBody(), v.baseBody() );
        assertEquals( committed.basePageVersion(), v.basePageVersion() );
        assertEquals( committed.ring(), v.ring() );
        assertEquals( committed.local().apply( committed.baseBody() ), v.local().apply( v.baseBody() ) );
        // participants are not persisted: the old one must rejoin, under a fresh participant id
        assertEquals( 409, r.push( a.participantId(), "alice", 6, List.of() ).status() );
        final JoinResponse again = r.join( new JoinRequest( "alice", a.clientId(), null, "A", null, null ) );
        assertNotEquals( a.participantId(), again.participantId() );
        assertNotEquals( a.clientId(), again.clientId(), "a reloaded session does not know pre-crash clientIDs" );
        assertEquals( 200, r.push( again.participantId(), "alice", 6,
            List.of( new Update( again.clientId(), ChangeSet.replace( doc.length(), 0, 0, "Z" ) ) ) ).status() );
    }

    @Test
    void loadWithoutASessionOrSnapshotFails() {
        assertThrows( IllegalStateException.class,
            () -> CollabSession.load( UUID.randomUUID(), ports( store, writer ), config( 3, 500, 1000 ), 1000 ) );
    }

    @Test
    void createNormalisesLineEndingsAndRefusesANonZeroHead() {
        final ScriptedPageWriter w = seeded( "Crlf", "a\r\nb" );
        final CollabSession s = create( "Crlf", w, config( 3, 500, 1000 ), 1000 );
        assertEquals( "a\nb\n", s.view().doc() );
        assertEquals( CollabText.contentHash( "a\nb\n" ), store.session( s.id() ).orElseThrow().baseContentHash() );
        final SessionRecord moved = CollabSession.newRecord( UUID.randomUUID(), "Other", Optional.empty(), "c", clock ).withHead( 3 );
        assertThrows( IllegalArgumentException.class, () -> CollabSession.create( moved, ports( store, w ), config( 3, 500, 1000 ), 1000 ) );
    }

    @Test
    void aNewPageSessionHasBaseVersionZero() {   // A11
        final SessionRecord rec = CollabSession.newRecord( UUID.randomUUID(), "Fresh", writer.latest( "Fresh" ), "c", clock );
        assertEquals( 0, rec.basePageVersion() );
        assertEquals( "", rec.baseBody() );
    }

    // ---- publish (section 7.3; the full matrix is CollabSessionPublishTest) -------------------------------------

    @Test
    void publishSavesTheDocAndCommitsTheBaseChangeWithASnapshot() {
        final JoinResponse a = join( "alice" );
        push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) );
        final PublishResponse r = session.publish( a.participantId(), "alice", 1, "note" );
        assertEquals( 200, r.status() );
        assertEquals( 2, r.pageVersion() );
        assertFalse( r.unchanged() );
        assertEquals( "X" + BODY, session.view().baseBody() );
        assertTrue( session.view().local().empty() );
        final Snapshot snap = store.latestSnapshot( session.id() ).orElseThrow();
        assertEquals( 1, snap.version() );
        assertEquals( 2, snap.basePageVersion() );
        assertEquals( 2, store.session( session.id() ).orElseThrow().basePageVersion() );
        checkInvariants();
    }

    @Test
    void e17_aFilterRewriteIsAServerUpdateEveryoneReceives() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final Waiter w = new Waiter();
        push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) );
        assertNull( pull( b, "bob", 1, w ) );
        writer.rewriteOnSave( s -> s.replace( "world", "WORLD" ) );
        assertEquals( 200, session.publish( a.participantId(), "alice", 1, "n" ).status() );
        assertEquals( "Xhello\nWORLD\n", session.view().doc() );
        assertEquals( 2, session.view().head() );
        assertEquals( "server", w.only().updates().get( 0 ).clientId() );
        assertEquals( 2, store.latestSnapshot( session.id() ).orElseThrow().version() );
        checkInvariants();
    }

    @Test
    void publishEdgeCases() {   // E1, E21, not-synced, writer refusal
        final JoinResponse a = join( "alice" );
        final PublishResponse unchanged = session.publish( a.participantId(), "alice", 0, "n" );
        assertTrue( unchanged.unchanged() );
        assertEquals( 1, unchanged.pageVersion() );
        push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) );
        assertEquals( "not-synced", session.publish( a.participantId(), "alice", 2, "n" ).reason() );
        writer.failNextPublish( new VersionConflictException( PAGE, 1, 2, false ) );
        final PublishResponse conflict = session.publish( a.participantId(), "alice", 1, "n" );
        assertEquals( 409, conflict.status() );
        assertEquals( "publish-merged-retry", conflict.reason() );
        writer.failNextPublish( new com.wikantik.api.exceptions.WikiException( "refused" ) );
        assertEquals( 422, session.publish( a.participantId(), "alice", 1, "n" ).status() );
        assertEquals( "X" + BODY, session.view().doc() );
        push( a, "alice", 1, new Update( a.clientId(), ChangeSet.replace( BODY.length() + 1, 0, BODY.length() + 1, "" ) ) );
        assertEquals( 400, session.publish( a.participantId(), "alice", 2, "n" ).status() );
        assertEquals( 1, writer.latest( PAGE ).orElseThrow().version() );
    }

    // ---- fix round 1 ----------------------------------------------------------------------------------------------

    @Test
    void leaveIsBoundToTheAuthorAndEvictIsTheServersOwnPath() {   // A6, ruling P5
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final LeaveResponse forged = session.leave( a.participantId(), "bob" );
        assertEquals( 403, forged.status() );
        assertEquals( "not-your-participant", forged.reason() );
        assertEquals( 200, push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) ).status(), "alice is still here" );
        final LeaveResponse unknown = session.leave( "nobody", "x" );
        assertEquals( 409, unknown.status() );
        assertEquals( "rejoin", unknown.reason() );
        assertEquals( 200, session.leave( a.participantId(), "alice" ).status() );
        assertEquals( 409, session.leave( a.participantId(), "alice" ).status(), "leaving twice: already gone" );
        session.evict( b.participantId() );
        assertEquals( 0, session.view().participants() );
        assertEquals( "evicted", push( b, "bob", 1, typeAt( b, "X" + BODY, 0, "Y" ) ).reason(),
            "an eviction leaves a tombstone (Phase 2)" );
        session.timeOut( "nobody" );   // unknown ids are ignored
    }

    /** A waiter whose completion throws (a broken response, say). */
    private static final class ThrowingWaiter implements PullWaiter {
        int calls;

        @Override
        public void complete( final PullResponse r ) {
            calls++;
            throw new IllegalStateException( "response already committed (test)" );
        }
    }

    @Test
    void aThrowingWaiterNeitherStrandsTheOthersNorFailsACommittedPush() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final JoinResponse c = join( "carol" );
        final JoinResponse d = join( "dave" );
        final ThrowingWaiter broken = new ThrowingWaiter();
        final Waiter wc = new Waiter();
        final Waiter wd = new Waiter();
        assertNull( pull( c, "carol", 0, wc ) );
        assertNull( session.pullOrPark( b.participantId(), "bob", 0, null, broken ) );
        assertNull( pull( d, "dave", 0, wd ) );
        final PushResponse r = push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) );
        assertEquals( 200, r.status(), "the push was committed: its answer stands" );
        assertEquals( 1, broken.calls );
        assertEquals( 1, wc.only().updates().size() );
        assertEquals( 1, wd.only().updates().size() );
        // the displacement, leave and timeout paths are guarded the same way
        final ThrowingWaiter displaced = new ThrowingWaiter();
        assertNull( session.pullOrPark( b.participantId(), "bob", 1, null, displaced ) );
        assertNull( session.pullOrPark( b.participantId(), "bob", 1, null, new Waiter() ) );
        assertEquals( 1, displaced.calls );
        final ThrowingWaiter leaving = new ThrowingWaiter();
        assertNull( session.pullOrPark( c.participantId(), "carol", 1, null, leaving ) );
        assertEquals( 200, session.leave( c.participantId(), "carol" ).status() );
        assertEquals( 1, leaving.calls );
        final ThrowingWaiter late = new ThrowingWaiter();
        assertNull( session.pullOrPark( d.participantId(), "dave", 1, null, late ) );
        clock.advance( Duration.ofSeconds( 30 ) );
        session.expireWaiters().forEach( Runnable::run );
        assertEquals( 1, late.calls );
    }

    @Test
    void aPresenceOnlyPushSkipsTheVersionCheckAndPlacesItsSelectionByItsOwnVersion() {   // ruling P7
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        assertEquals( 200, push( a, "alice", 0, typeAt( a, BODY, 0, "X" ) ).status() );
        // bob is still at version 0: no stale-base for a push without updates
        final PushResponse r = session.push( b.participantId(), "bob", 0, List.of(), new Selection( 5, 5, 0, 0 ) );
        assertEquals( 200, r.status() );
        assertEquals( 1, r.version() );
        assertEquals( new Selection( 6, 6, 1, 0 ), session.view().selections().get( b.participantId() ) );
        // an update-carrying push from a stale version is still a stale-base 409
        assertEquals( "stale-base", push( b, "bob", 0, typeAt( b, BODY, 0, "Y" ) ).reason() );
    }

    @Test
    void presenceCarriesNameColourAndASequenceAndWakesParkedPulls() {   // A12, ruling P7, 8.3
        final JoinResponse ada = join( "ada" );
        final JoinResponse raj = join( "raj" );
        final long afterJoins = session.view().presenceSeq();
        assertTrue( afterJoins >= 2, "joins bump the presence sequence" );
        // raj has seen nothing yet: a pull at head with an older presenceSince answers at once
        final PullResponse first = session.pullOrPark( raj.participantId(), "raj", 0, 0, null, new Waiter() );
        assertEquals( 200, first.status() );
        assertEquals( afterJoins, first.presenceSeq() );
        // the full map of the others: ada is there although she never placed a cursor
        assertEquals( java.util.Set.of( ada.participantId() ), first.presence().keySet() );
        assertNull( first.presence().get( ada.participantId() ).selection() );
        assertNull( first.presence().get( ada.participantId() ).anchor() );
        // raj parks with the current sequence; ada's selection wakes him with her entry
        final Waiter w = new Waiter();
        assertNull( session.pullOrPark( raj.participantId(), "raj", 0, afterJoins, null, w ) );
        assertEquals( 200, session.push( ada.participantId(), "ada", 0, List.of(), new Selection( 1, 3, 0, 0 ) ).status() );
        final PullResponse woken = w.only();
        assertTrue( woken.updates().isEmpty() );
        assertEquals( afterJoins + 1, woken.presenceSeq() );
        final PresenceEntry e = woken.presence().get( ada.participantId() );
        assertEquals( "Ada Lovelace", e.displayName() );
        assertEquals( ada.participants().get( 0 ).color(), e.color() );
        assertEquals( new Selection( 1, 3, 0, 0 ), e.selection() );
        assertFalse( woken.presence().containsKey( raj.participantId() ), "the requester is never in its own map" );
        // the same selection again changes nothing: a parked pull stays parked
        final Waiter quiet = new Waiter();
        assertNull( session.pullOrPark( raj.participantId(), "raj", 0, afterJoins + 1, null, quiet ) );
        session.push( ada.participantId(), "ada", 0, List.of(), new Selection( 1, 3, 0, 0 ) );
        assertTrue( quiet.got.isEmpty() );
        // a presenceSince at the sequence answers an empty map
        assertEquals( 200, push( ada, "ada", 0, typeAt( ada, BODY, 0, "Z" ) ).status() );
        assertTrue( quiet.only().presence().isEmpty() );
        // a leave bumps the sequence too
        session.leave( ada.participantId(), "ada" );
        assertEquals( afterJoins + 2, session.view().presenceSeq() );
    }

    @Test
    void coloursFollowJoinOrderNotTheLogin() {   // 8.3
        final JoinResponse first = join( "raj" );
        final JoinResponse second = join( "ada" );
        final ScriptedPageWriter w = seeded( "Other", "x\n" );
        final CollabSession other = create( "Other", w, config( 3, 500, 1000 ), 1000 );
        final JoinResponse adaFirst = other.join( JoinRequest.of( "ada", null, null ) );
        assertEquals( first.participants().get( 0 ).color(), adaFirst.participants().get( 0 ).color() );
        assertNotEquals( second.participants().get( 1 ).color(), adaFirst.participants().get( 0 ).color() );
        assertEquals( "Raj Patel", first.participants().get( 0 ).displayName() );
    }

    @Test
    void aRetiredClientIdIsForgottenAfterTheIdleEvictionPeriod() {   // kept that long for an A13 re-login
        final JoinResponse a = join( "alice" );
        session.leave( a.participantId(), "alice" );
        clock.advance( Duration.ofMinutes( 10 ).plusSeconds( 1 ) );
        assertNotEquals( a.clientId(),
            session.join( new JoinRequest( "alice", a.clientId(), null, "A", null, null ) ).clientId() );
    }

    // ---- fix round 2 ----------------------------------------------------------------------------------------------

    @Test
    void aParticipantWithoutASelectionIsInEveryoneElsesPresence() {
        final JoinResponse ada = join( "ada" );
        final JoinResponse raj = join( "raj" );
        final PullResponse r = session.pullOrPark( raj.participantId(), "raj", 0, 0, null, new Waiter() );
        final PresenceEntry e = r.presence().get( ada.participantId() );
        assertEquals( "Ada Lovelace", e.displayName() );
        assertEquals( ada.participants().get( 0 ).color(), e.color() );
        assertNull( e.anchor() );
        assertNull( e.head() );
        assertNull( e.version() );
        assertNull( e.unconfirmed() );
        assertFalse( r.presence().containsKey( raj.participantId() ) );
    }

    @Test
    void aJoinCarriesTheContributorsOfTheUnpublishedDraft() {
        final JoinResponse first = join( "ada" );
        assertEquals( List.of(), first.contributors(), "a clean draft has none" );
        final PushResponse r = push( first, "ada", 0, typeAt( first, BODY, 0, "x" ) );
        assertEquals( 200, r.status() );
        final JoinResponse second = join( "raj" );
        assertEquals( List.of( "Ada Lovelace" ), second.contributors() );
        assertTrue( second.unpublished() );
        assertEquals( List.of( "ada" ), session.view().contributorKeys() );
        assertTrue( session.view().unpublished() );
    }

    @Test
    void aMissingDisplayNameNeverFallsBackToTheLogin() {   // logins are private; public identity is the display name
        final JoinResponse anon = session.join( JoinRequest.of( "carol@example.com", null, null ) );
        final JoinResponse blank = session.join( JoinRequest.of( "dave@example.com", null, "  " ) );
        final String first = anon.participants().get( 0 ).displayName();
        final String second = blank.participants().get( 1 ).displayName();
        assertFalse( first.contains( "carol" ), first );
        assertFalse( second.contains( "dave" ), second );
        assertEquals( "Editor 1", first );
        assertEquals( "Editor 2", second );
        final JoinResponse raj = join( "raj" );
        final PullResponse r = session.pullOrPark( raj.participantId(), "raj", 0, 0, null, new Waiter() );
        assertEquals( "Editor 1", r.presence().get( anon.participantId() ).displayName() );
    }
}
