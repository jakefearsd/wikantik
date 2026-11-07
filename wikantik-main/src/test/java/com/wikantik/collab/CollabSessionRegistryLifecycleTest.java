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
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 Task 5: the registry's lifecycle additions — eviction by author, wiki session and sign-in, the requested edit
 * re-check, settle-all, rekey refusal, backoff and chains, the orphan sweep (Task 3 review, binding a-e).
 */
class CollabSessionRegistryLifecycleTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    /** Edit and sign-in by wiki session; counts nothing. */
    private static final class Permissions implements CollabPermissions {
        final Set< Object > noEdit = new HashSet<>();
        final Set< Object > signedOut = new HashSet<>();
        final Set< Object > throwing = new HashSet<>();
        final Map< Object, String > authorOf = new java.util.HashMap<>();

        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return !noEdit.contains( participantSession );
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean signedIn( final Object participantSession ) {
            if( throwing.contains( participantSession ) ) {
                throw new IllegalStateException( "test: session store down for " + participantSession );
            }
            return !signedOut.contains( participantSession );
        }

        @Override
        public String authorKeyOf( final Object participantSession ) {
            return authorOf.get( participantSession );
        }
    }

    /** The scripted writer with read faults: unreadable pages throw, and one existence check can be held. */
    private static final class HookedWriter implements CollabPageWriter {
        final ScriptedPageWriter d;
        final Set< String > unreadable = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile String holdExistsOf;
        final java.util.concurrent.CountDownLatch existsHeld = new java.util.concurrent.CountDownLatch( 1 );
        final java.util.concurrent.CountDownLatch releaseExists = new java.util.concurrent.CountDownLatch( 1 );

        HookedWriter( final ScriptedPageWriter d ) {
            this.d = d;
        }

        private void check( final String pageName ) {
            if( unreadable.contains( pageName ) ) {
                throw new PageReadException( "test: the page store cannot read " + pageName );
            }
        }

        @Override
        public Optional< com.wikantik.collab.model.PageState > latest( final String pageName ) {
            check( pageName );
            return d.latest( pageName );
        }

        @Override
        public Optional< com.wikantik.collab.model.PageState > version( final String pageName, final int version ) {
            check( pageName );
            return d.version( pageName, version );
        }

        /** The answer is read first, then held: the page may move while the caller still acts on it. */
        @Override
        public boolean exists( final String pageName ) {
            check( pageName );
            final boolean out = d.latest( pageName ).isPresent();
            if( pageName.equals( holdExistsOf ) ) {
                holdExistsOf = null;
                existsHeld.countDown();
                try {
                    if( !releaseExists.await( 10, TimeUnit.SECONDS ) ) {
                        throw new IllegalStateException( "test: the held existence check was never released" );
                    }
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException( e );
                }
            }
            return out;
        }

        @Override
        public com.wikantik.collab.model.PublishOutcome publish( final com.wikantik.collab.model.PublishRequest request )
                throws com.wikantik.api.exceptions.WikiException {
            return d.publish( request );
        }
    }

    /** The in-memory store, counting the updates that try to move a session to another page name. */
    private static final class CountingStore implements CollabStore {
        final InMemoryCollabStore d = new InMemoryCollabStore();
        final AtomicInteger renameAttempts = new AtomicInteger();
        final Map< String, AtomicInteger > lookups = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void createSession( final SessionRecord s ) {
            d.createSession( s );
        }

        @Override
        public Optional< SessionRecord > session( final UUID id ) {
            return d.session( id );
        }

        @Override
        public Optional< SessionRecord > sessionForPage( final String pageName ) {
            lookups.computeIfAbsent( pageName, k -> new AtomicInteger() ).incrementAndGet();
            return d.sessionForPage( pageName );
        }

        @Override
        public void updateSession( final SessionRecord s ) {
            if( !d.session( s.id() ).map( SessionRecord::pageName ).orElse( s.pageName() ).equals( s.pageName() ) ) {
                renameAttempts.incrementAndGet();
            }
            d.updateSession( s );
        }

        @Override
        public void appendUpdates( final UUID id, final List< StoredUpdate > updates ) {
            d.appendUpdates( id, updates );
        }

        @Override
        public List< StoredUpdate > updatesSince( final UUID id, final int sinceVersion ) {
            return d.updatesSince( id, sinceVersion );
        }

        @Override
        public void appendMetaOps( final UUID id, final List< StoredMetaOp > ops ) {
            d.appendMetaOps( id, ops );
        }

        @Override
        public void appendBatch( final UUID id, final List< StoredUpdate > updates, final List< StoredMetaOp > ops ) {
            d.appendBatch( id, updates, ops );
        }

        @Override
        public List< StoredMetaOp > metaOpsSince( final UUID id, final long sinceSeq ) {
            return d.metaOpsSince( id, sinceSeq );
        }

        @Override
        public void commitBaseChange( final UUID id, final List< StoredUpdate > serverUpdates, final Snapshot snapshot,
                                      final SessionRecord updated ) {
            d.commitBaseChange( id, serverUpdates, snapshot, updated );
        }

        @Override
        public void writeSnapshot( final UUID id, final Snapshot s ) {
            d.writeSnapshot( id, s );
        }

        @Override
        public Optional< Snapshot > latestSnapshot( final UUID id ) {
            return d.latestSnapshot( id );
        }

        @Override
        public Optional< Snapshot > snapshotAtOrBelow( final UUID id, final int version ) {
            return d.snapshotAtOrBelow( id, version );
        }

        @Override
        public void truncateUpdatesBelow( final UUID id, final int cut ) {
            d.truncateUpdatesBelow( id, cut );
        }

        @Override
        public void deleteSession( final UUID id ) {
            d.deleteSession( id );
        }

        @Override
        public List< SessionRecord > sessions() {
            return d.sessions();
        }

        @Override
        public void recordContributors( final String canonicalId, final int pageVersion, final Set< String > authorKeys,
                                        final boolean created ) {
            d.recordContributors( canonicalId, pageVersion, authorKeys, created );
        }

        @Override
        public Map< Integer, List< String > > contributorsByVersion( final String canonicalId ) {
            return d.contributorsByVersion( canonicalId );
        }
    }

    private CountingStore store;
    private ScriptedPageWriter writer;
    private HookedWriter hooked;
    private MutableClock clock;
    private Permissions permissions;
    private CollabSessionRegistry registry;

    @BeforeEach
    void setUp() {
        store = new CountingStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        clock = new MutableClock( T0 );
        permissions = new Permissions();
        // a long presence timeout: these tests move the clock across the orphan grace with participants still present
        hooked = new HookedWriter( writer );
        registry = new CollabSessionRegistry( store, hooked, permissions, key -> null, clock,
            new CollabConfig( true, 20, 65536, 25, 3600, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
    }

    private Joined join( final String page, final String author, final Object wikiSession ) {
        return assertInstanceOf( Joined.class, registry.join( page,
            new JoinRequest( author, null, wikiSession, author.toUpperCase(), null, null ) ) );
    }

    private Joined join( final String author ) {
        return join( PAGE, author, author );
    }

    private static PushResponse type( final Joined j, final String author, final String text ) {
        final CollabSession.View v = j.session().view();
        return j.session().push( j.response().participantId(), author, v.head(),
            List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) );
    }

    /** A stored-only draft with changes on {@code page} (joined, typed, left, idled out of memory). */
    private UUID storedDraft( final String page ) {
        final Joined j = join( page, "carol", "carol" );
        assertEquals( 200, type( j, "carol", "c" ).status() );
        assertEquals( 200, j.session().leave( j.response().participantId(), "carol" ).status() );
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertTrue( registry.liveForPage( page ).isEmpty() );
        return j.response().sessionId();
    }

    private static void assertEvicted( final PushResponse r, final String why ) {
        assertEquals( 403, r.status() );
        assertEquals( CollabSession.EVICTED, r.reason() );
        assertEquals( why, r.detail() );
    }

    // ---- lookups ---------------------------------------------------------------------------------------------------

    @Test
    void hasDraftReadsTheStore() {
        assertFalse( registry.hasDraft( PAGE ) );
        storedDraft( PAGE );
        assertTrue( registry.hasDraft( PAGE ) );
        assertFalse( registry.hasDraft( "Other" ) );
    }

    // ---- eviction ----------------------------------------------------------------------------------------------------

    @Test
    void evictAuthorReachesBothTabs() {
        final Joined tab1 = join( PAGE, "alice", new Object() );
        final Joined tab2 = join( PAGE, "alice", new Object() );
        final Joined bob = join( "bob" );
        writer.seed( "Other", "o\n", Map.of() );
        final Joined other = join( "Other", "alice", new Object() );

        assertEquals( 3, registry.evictAuthor( "alice" ) );

        assertEvicted( type( tab1, "alice", "x" ), CollabSession.ACCOUNT_DELETED );
        assertEvicted( type( tab2, "alice", "x" ), CollabSession.ACCOUNT_DELETED );
        assertEvicted( type( other, "alice", "x" ), CollabSession.ACCOUNT_DELETED );
        assertEquals( 200, type( bob, "bob", "b" ).status() );
        assertEquals( 0, registry.evictAuthor( "nobody" ) );
    }

    @Test
    void evictSignedOutEvictsOnlyThatWikiSessionsParticipants() {
        final Object expired = new Object();
        final Joined a1 = join( PAGE, "alice", expired );
        final Joined a2 = join( PAGE, "alice", new Object() );
        writer.seed( "Other", "o\n", Map.of() );
        final Joined a3 = join( "Other", "alice", expired );

        assertEquals( 2, registry.evictSignedOut( expired ) );
        assertEquals( 0, registry.evictSignedOut( null ) );

        assertEvicted( type( a1, "alice", "x" ), CollabSession.SIGNED_OUT );
        assertEvicted( type( a3, "alice", "x" ), CollabSession.SIGNED_OUT );
        assertEquals( 200, type( a2, "alice", "y" ).status() );
    }

    @Test
    void evictSignedOutAllAsksThePermissionsPort() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        final Joined c = join( "carol" );
        permissions.signedOut.add( "alice" );
        permissions.throwing.add( "carol" );   // a check that throws keeps the participant: A13 keeps edits anyway

        assertEquals( 1, registry.evictSignedOutAll() );

        assertEvicted( type( a, "alice", "x" ), CollabSession.SIGNED_OUT );
        assertEquals( 200, type( b, "bob", "y" ).status() );
        assertEquals( 200, type( c, "carol", "z" ).status() );
    }

    @Test
    void aSignedOutParticipantKeepsItsClientIdForTheRelogin() {   // A13
        final Joined a = join( "alice" );
        permissions.signedOut.add( "alice" );
        registry.evictSignedOutAll();
        clock.advance( Duration.ofMinutes( 5 ) );
        final Joined again = assertInstanceOf( Joined.class, registry.join( PAGE,
            new JoinRequest( "alice", a.response().clientId(), "alice2", "ALICE", null, null ) ) );
        assertEquals( a.response().clientId(), again.response().clientId() );
    }

    @Test
    void requestEditRecheckRunsOnTheNextTickOnly() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        permissions.noEdit.add( "bob" );
        registry.requestEditRecheck();
        assertEquals( 200, type( b, "bob", "x" ).status(), "nothing until the tick" );
        registry.tick();
        assertEvicted( type( b, "bob", "x" ), CollabSession.LOST_EDIT );
        permissions.noEdit.add( "alice" );
        registry.tick();
        assertEquals( 200, type( a, "alice", "y" ).status(), "one request, one re-check" );
    }

    // ---- settle-all ------------------------------------------------------------------------------------------------

    @Test
    void settleAllReconcilesEveryLiveSession() {
        final Joined a = join( "alice" );
        writer.seed( "Other", "o\n", Map.of() );
        final Joined o = join( "Other", "bob", "bob" );
        writer.overwriteWithoutVersion( PAGE, BODY + "p\n" );
        writer.overwriteWithoutVersion( "Other", "o\nq\n" );

        registry.settleAll();

        assertEquals( BODY + "p\n", a.session().view().doc() );
        assertEquals( "o\nq\n", o.session().view().doc() );
    }

    // ---- rekey: refusal, backoff, chains (binding b, c) ------------------------------------------------------------

    @Test
    void rekeyOntoADraftFreezesTheOrphan() {
        final UUID target = storedDraft( "Target" );
        final Joined a = join( "alice" );
        writer.rename( PAGE, "Target" );   // the page store moved; Target's page was gone, its draft was not

        assertThrows( IllegalStateException.class, () -> registry.rekey( PAGE, "Target" ) );

        assertEquals( PAGE, a.session().pageName() );
        assertTrue( a.session().frozen() );
        assertEquals( SessionRecord.FROZEN, store.session( a.response().sessionId() ).orElseThrow().status() );
        assertEquals( "Target", store.session( target ).orElseThrow().pageName() );
        assertEquals( SessionRecord.ACTIVE, store.session( target ).orElseThrow().status(), "the target's draft untouched" );
        // a join of the target joins its own draft, and never retries the refused rename on the way
        final int before = store.renameAttempts.get();
        assertEquals( target, join( "Target", "bob", "bob" ).response().sessionId() );
        assertEquals( before, store.renameAttempts.get() );
    }

    @Test
    void aRefusedRekeyIsRetriedWithBackoffNotEveryTick() {
        storedDraft( "Target" );
        join( "alice" );
        writer.rename( PAGE, "Target" );
        assertThrows( IllegalStateException.class, () -> registry.rekey( PAGE, "Target" ) );
        final int before = store.lookups.get( "Target" ).get();

        for( int i = 0; i <= 10; i++ ) {
            registry.tick();
            clock.advance( Duration.ofSeconds( 1 ) );
        }

        final int retries = store.lookups.get( "Target" ).get() - before;
        assertTrue( retries >= 2, "still retried: " + retries );
        assertTrue( retries <= 4, "backed off rather than once per tick: " + retries );
        assertEquals( 0, store.renameAttempts.get(), "a refusal is seen before the store: no unique violation, ever" );
    }

    @Test
    void aRefusedRekeyLandsOnceTheTargetsDraftIsGone() {
        final UUID target = storedDraft( "Target" );
        final Joined a = join( "alice" );
        writer.rename( PAGE, "Target" );
        assertThrows( IllegalStateException.class, () -> registry.rekey( PAGE, "Target" ) );
        store.deleteSession( target );   // an administrator purged the stale draft

        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();

        assertEquals( "Target", a.session().pageName() );
        assertSame( a.session(), registry.liveForPage( "Target" ).orElseThrow() );
        assertFalse( a.session().frozen(), "its page exists under the new name: the draft is editable again" );
    }

    @Test
    void aChainedRenameMovesTheDraftToTheEnd() {
        final Joined a = join( "alice" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "B" ) );
        writer.rename( PAGE, "B" );
        writer.rename( "B", "C" );

        registry.rekey( "B", "C" );

        assertEquals( "C", a.session().pageName() );
        assertSame( a.session(), registry.liveForPage( "C" ).orElseThrow() );
        assertTrue( store.sessionForPage( "B" ).isEmpty() );
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertEquals( "C", a.session().pageName(), "never moved back onto the vanished B" );
    }

    @Test
    void aChainedRenameWhoseRetryFailsNeverOpensASecondSessionForTheEnd() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "B" ) );
        writer.rename( PAGE, "B" );
        writer.rename( "B", "C" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );

        registry.rekey( "B", "C" );   // B itself has no draft; the chained move of PAGE's draft to C fails again

        assertEquals( PAGE, a.session().pageName() );
        final Joined c = join( "C", "bob", "bob" );
        assertSame( a.session(), c.session(), "the join of C lands the owed rename first" );
        assertEquals( "x" + BODY, c.response().doc() );
        assertTrue( store.sessionForPage( "B" ).isEmpty() );
        assertTrue( store.sessionForPage( PAGE ).isEmpty() );
    }

    @Test
    void aDeleteOfAPendingRenamesTargetFreezesTheDraftWhereItIs() {
        final Joined a = join( "alice" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "B" ) );
        writer.rename( PAGE, "B" );
        writer.delete( "B" );

        registry.freeze( "B" );

        assertTrue( a.session().frozen() );
        assertEquals( PAGE, a.session().pageName() );
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertEquals( PAGE, a.session().pageName(), "the owed rename was dropped with the delete" );
        assertTrue( store.sessionForPage( "B" ).isEmpty() );
    }

    @Test
    void aRetryNeverMovesADraftOntoAVanishedPage() {
        final Joined a = join( "alice" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "B" ) );
        writer.rename( PAGE, "B" );
        writer.delete( "B" );   // its delete event was missed

        clock.advance( Duration.ofSeconds( 1 ) );
        registry.tick();
        assertEquals( PAGE, a.session().pageName() );
        assertTrue( store.sessionForPage( "B" ).isEmpty() );
        assertFalse( a.session().frozen(), "not yet: the sweep waits out its grace" );

        clock.advance( CollabSessionRegistry.ORPHAN_GRACE );
        registry.tick();
        assertTrue( a.session().frozen(), "no rename owed any more: the sweep freezes the orphan" );
    }

    // ---- the orphan sweep (binding a) ----------------------------------------------------------------------------

    @Test
    void theSweepFreezesADraftWhosePageVanishedWithoutAnEvent() {
        final Joined a = join( "alice" );
        writer.delete( PAGE );   // a missed delete
        registry.tick();
        assertFalse( a.session().frozen() );
        clock.advance( CollabSessionRegistry.ORPHAN_GRACE.minusSeconds( 1 ) );
        registry.tick();
        assertFalse( a.session().frozen(), "a rename's event may still be on its way" );
        clock.advance( Duration.ofSeconds( 1 ) );
        registry.tick();
        assertTrue( a.session().frozen() );
        assertEquals( SessionRecord.FROZEN, store.session( a.response().sessionId() ).orElseThrow().status() );
    }

    @Test
    void thePageComingBackResetsTheSweep() {
        final Joined a = join( "alice" );
        writer.delete( PAGE );
        registry.tick();
        clock.advance( Duration.ofSeconds( 30 ) );
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        registry.tick();
        writer.delete( PAGE );
        clock.advance( Duration.ofSeconds( 30 ) );
        registry.tick();
        clock.advance( Duration.ofSeconds( 30 ) );
        registry.tick();
        assertFalse( a.session().frozen(), "absent for 30 s only since it came back" );
    }

    @Test
    void theSweepNeverFreezesANewPagesDraftOrOneWithARenameOwed() {
        final Joined fresh = join( "NewPage", "alice", "alice" );
        assertEquals( 0, fresh.response().basePageVersion() );
        final Joined renamed = join( "bob" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "Moved" ) );
        writer.rename( PAGE, "Moved" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );   // the first retry fails too

        registry.tick();
        clock.advance( CollabSessionRegistry.ORPHAN_GRACE );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );   // and the next
        registry.tick();

        assertFalse( fresh.session().frozen(), "a new page's draft has no page yet by design" );
        assertFalse( renamed.session().frozen(), "a rename out of it is still owed" );
    }

    @Test
    void aStoredDraftWhosePageVanishedIsFrozenOnceLoaded() {
        final UUID id = storedDraft( PAGE );
        writer.delete( PAGE );   // no event
        final Joined a = join( "alice" );   // a join on a missing page: createPages, and the stored draft loads
        assertEquals( id, a.response().sessionId() );
        registry.tick();
        clock.advance( CollabSessionRegistry.ORPHAN_GRACE );
        registry.tick();
        assertTrue( a.session().frozen() );
    }

    // ---- freeze racing a publish (binding e) ------------------------------------------------------------------------

    @Test
    void aFreezeLandingDuringAPausedPublishSave() throws Exception {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final CompletableFuture< PublishResponse > first = CompletableFuture.supplyAsync(
            () -> a.session().publish( a.response().participantId(), "alice", 1, "" ) );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );
        writer.delete( PAGE );

        // the listener's freeze must not wait for the save (no base lock is held across it)
        CompletableFuture.runAsync( () -> registry.freeze( PAGE ) ).get( 5, TimeUnit.SECONDS );
        assertTrue( a.session().frozen() );
        pause.release();

        final PublishResponse r = first.get( 10, TimeUnit.SECONDS );
        assertEquals( 409, r.status(), "the save found its page gone" );
        assertEquals( "publish-merged-retry", r.reason() );
        assertTrue( a.session().frozen(), "the failed publish never unfreezes" );
        assertTrue( writer.latest( PAGE ).isEmpty(), "and never recreates the page" );

        final PublishResponse retry = a.session().publish( a.response().participantId(), "alice",
            a.session().view().head(), "" );
        assertEquals( 200, retry.status(), "the retry is a recreate (createPages checked)" );
        assertEquals( "xhello\r\nworld\r\n", writer.latest( PAGE ).orElseThrow().body() );
        assertFalse( a.session().frozen() );
    }

    // ---- fix round 1: reads that fail are unknown, never missing (item 1) ------------------------------------------

    @Test
    void aTransientReadErrorNeverFreezesNorRunsTheGraceClock() {
        final Joined a = join( "alice" );
        writer.delete( PAGE );
        hooked.unreadable.add( PAGE );
        for( int i = 0; i < 3; i++ ) {
            registry.tick();
            clock.advance( CollabSessionRegistry.ORPHAN_GRACE );
        }
        registry.tick();
        assertFalse( a.session().frozen(), "the page store could not tell: unknown, not missing" );

        hooked.unreadable.remove( PAGE );
        registry.tick();   // the first sweep that sees the page missing: the grace starts now
        clock.advance( CollabSessionRegistry.ORPHAN_GRACE.minusSeconds( 1 ) );
        registry.tick();
        assertFalse( a.session().frozen(), "the unreadable minutes did not count towards the grace" );
        clock.advance( Duration.ofSeconds( 1 ) );
        registry.tick();
        assertTrue( a.session().frozen() );
    }

    @Test
    void aTransientReadErrorKeepsTheRenameOwed() {
        final Joined a = join( "alice" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "B" ) );
        writer.rename( PAGE, "B" );
        hooked.unreadable.add( "B" );

        for( int i = 0; i < 3; i++ ) {
            clock.advance( CollabSessionRegistry.ORPHAN_GRACE );
            registry.tick();
        }
        assertEquals( PAGE, a.session().pageName() );
        assertFalse( a.session().frozen(), "still owed: the sweep leaves it alone" );

        hooked.unreadable.remove( "B" );
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertEquals( "B", a.session().pageName(), "the owed rename landed once the page store could tell" );
    }

    // ---- fix round 1: renames are serialised (item 3) ---------------------------------------------------------------

    @Test
    void aTickRetryRacingAChainedRenameLeavesTheDraftAtTheEnd() throws Exception {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "x" ).status() );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( RuntimeException.class, () -> registry.rekey( PAGE, "B" ) );   // PAGE -> B owed
        writer.rename( PAGE, "B" );

        // the tick's retry finds B present, then is held before it moves the draft
        hooked.holdExistsOf = "B";
        final CompletableFuture< Void > tick = CompletableFuture.runAsync( registry::tick );
        assertTrue( hooked.existsHeld.await( 10, TimeUnit.SECONDS ) );
        // meanwhile B is renamed to C and the listener delivers it; one store write fails on the way
        writer.rename( "B", "C" );
        store.d.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        final CompletableFuture< Void > listener = CompletableFuture.runAsync( () -> {
            try {
                registry.rekey( "B", "C" );
            } catch( final RuntimeException e ) {
                // remembered by the registry, as the listener would log it
            }
        } );
        try {
            listener.get( 300, TimeUnit.MILLISECONDS );
        } catch( final java.util.concurrent.TimeoutException e ) {
            // serialised: the listener waits for the tick's retry
        }
        hooked.releaseExists.countDown();
        tick.get( 10, TimeUnit.SECONDS );
        listener.get( 10, TimeUnit.SECONDS );
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();

        assertEquals( "C", a.session().pageName(), "the draft followed the chain to its end" );
        assertSame( a.session(), registry.liveForPage( "C" ).orElseThrow() );
        assertTrue( registry.liveForPage( "B" ).isEmpty() );
        assertTrue( store.sessionForPage( "B" ).isEmpty(), "no draft stranded under the vanished B" );
        assertEquals( "C", store.session( a.response().sessionId() ).orElseThrow().pageName() );
    }

    // ---- fix round 1: evictions are isolated per session (item 4) ---------------------------------------------------

    @Test
    void oneSessionFailingNeverSkipsTheOthers() {
        final Joined a = join( "alice" );
        writer.seed( "Other", "o\n", Map.of() );
        final Joined o = join( "Other", "alice", "alice" );
        final SessionSlot bad = new SessionSlot();
        bad.opened( a.session(), T0 );
        final SessionSlot good = new SessionSlot();
        good.opened( o.session(), T0 );

        final int n = SessionSweeps.forEachLive( List.of( bad, good ), s -> {
            if( s == a.session() ) {
                throw new IllegalStateException( "test: this session's sweep fails" );
            }
            return 1;
        } );

        assertEquals( 1, n, "the failing session is skipped and logged; the next one still runs" );
    }

    // ---- fix round 1: logout scope (ruling Q13) ---------------------------------------------------------------------

    @Test
    void logoutEvictsOnlyThoseSignedInAtJoinWhoAreNowSignedOut() {
        final Joined alice = join( "alice" );
        permissions.signedOut.add( "anon" );
        final Joined anon = join( PAGE, "anon", "anon" );   // joined without signing in, and still not
        permissions.signedOut.add( "alice" );

        assertEquals( 1, registry.evictSignedOutAll() );

        assertEvicted( type( alice, "alice", "x" ), CollabSession.SIGNED_OUT );
        assertEquals( 200, type( anon, "anon", "y" ).status(), "never signed in: someone else's logout is not theirs" );
    }

    @Test
    void anEditorWhoJoinedAnonymouslyAndLoggedInLaterIsKept() {
        permissions.signedOut.add( "anon" );
        final Joined anon = join( PAGE, "anon", "anon" );
        permissions.signedOut.remove( "anon" );
        permissions.authorOf.put( "anon", "anon-login" );   // now signed in under their own login: accepted, kept
        assertEquals( 0, registry.evictSignedOutAll() );
        assertEquals( 200, type( anon, "anon", "y" ).status() );
    }

    @Test
    void aWikiSessionNowSignedInAsSomeoneElseIsEvictedOnLogout() {
        final Object shared = new Object();
        final Joined alice = join( PAGE, "alice", shared );
        permissions.authorOf.put( shared, "bob" );   // alice logged out, bob logged in on the same session

        assertEquals( 1, registry.evictSignedOutAll() );

        assertEvicted( type( alice, "alice", "x" ), CollabSession.SIGNED_OUT );
    }

    @Test
    void sessionExpiryEvictsEvenAnAnonymousJoiner() {
        permissions.signedOut.add( "anon" );
        final Joined anon = join( PAGE, "anon", "anon" );
        assertEquals( 1, registry.evictSignedOut( "anon" ) );
        assertEvicted( type( anon, "anon", "y" ), CollabSession.SIGNED_OUT );
    }

    // ---- retired clientIDs are bounded (binding d) -----------------------------------------------------------------

    @Test
    void retiredClientIdsAreCappedLikeTombstones() {
        final Roster roster = new Roster( "t-", key -> null, Duration.ofSeconds( 45 ), Duration.ofDays( 1 ) );
        final int n = Roster.MAX_RETIRED_CLIENTS + 10;
        final String[] clientIds = new String[ n ];
        for( int i = 0; i < n; i++ ) {
            final Participant p = roster.add( new JoinRequest( "a" + i, null, null, null, null, null ), T0, 0, 10 );
            clientIds[ i ] = p.clientId;
            roster.remove( p.id, T0 );
        }
        final Participant oldest = roster.add( new JoinRequest( "a0", clientIds[ 0 ], null, null, null, null ), T0, 0,
            10 );
        assertNotEquals( clientIds[ 0 ], oldest.clientId, "the oldest retired clientID was dropped" );
        final Participant newest = roster.add( new JoinRequest( "a" + ( n - 1 ), clientIds[ n - 1 ], null, null, null,
            null ), T0, 0, 10 );
        assertEquals( clientIds[ n - 1 ], newest.clientId, "a recent one is still reclaimed" );
    }
}
