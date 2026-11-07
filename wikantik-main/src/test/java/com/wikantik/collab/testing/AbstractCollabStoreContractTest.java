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
package com.wikantik.collab.testing;

import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.Contribution;
import com.wikantik.collab.model.DraftEntry;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behavioural contract of {@link CollabStore}, one test per documented rule. Free of implementation specifics so the
 * Postgres store can run the very same tests (Phase 1).
 */
public abstract class AbstractCollabStoreContractTest {

    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final ChangeSet CS = ChangeSet.empty( 0 );

    protected abstract CollabStore newStore();

    protected CollabStore store;

    @BeforeEach
    void freshStore() {
        store = newStore();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static SessionRecord session( final String page ) {
        return new SessionRecord( UUID.randomUUID(), page, SessionRecord.ACTIVE, 3, "base body", Map.of( "k", "v" ),
            "hash-3", 0, 0L, false, null, null, null, "alice", T0, T0 );
    }

    private SessionRecord created( final String page ) {
        final SessionRecord s = session( page );
        store.createSession( s );
        return s;
    }

    private static StoredUpdate upd( final int version ) {
        return new StoredUpdate( version, StoredUpdate.TEXT, "c1", "alice", CS );
    }

    private static List< StoredUpdate > upds( final int from, final int to ) {
        final List< StoredUpdate > l = new ArrayList<>();
        for( int v = from; v <= to; v++ ) {
            l.add( upd( v ) );
        }
        return l;
    }

    private static StoredMetaOp op( final long seq ) {
        return new StoredMetaOp( seq, "alice", "title", "t" + seq );
    }

    private static Snapshot snap( final int version ) {
        return new Snapshot( version, 0L, 3, "body@" + version, Map.of(), CS );
    }

    private int head( final SessionRecord s ) {
        return store.session( s.id() ).orElseThrow().headVersion();
    }

    // ---- create / read -------------------------------------------------------------------------------------

    @Test
    void createdSessionIsReadableByIdAndByPage() {
        final SessionRecord s = created( "PageA" );
        assertEquals( s, store.session( s.id() ).orElseThrow() );
        assertEquals( s, store.sessionForPage( "PageA" ).orElseThrow() );
    }

    @Test
    void readersReturnEmptyForUnknowns() {
        final UUID unknown = UUID.randomUUID();
        assertTrue( store.session( unknown ).isEmpty() );
        assertTrue( store.sessionForPage( "Nope" ).isEmpty() );
        assertTrue( store.updatesSince( unknown, 0 ).isEmpty() );
        assertTrue( store.metaOpsSince( unknown, 0 ).isEmpty() );
        assertTrue( store.latestSnapshot( unknown ).isEmpty() );
    }

    @Test
    void aSecondSessionForThePageIsRefused() {
        created( "PageA" );
        assertThrows( IllegalStateException.class, () -> store.createSession( session( "PageA" ) ) );
    }

    @Test
    void aFrozenSessionStillBlocksASecondSessionAndIsFoundByPage() {
        final SessionRecord s = created( "PageA" );
        store.updateSession( frozen( s ) );
        assertEquals( SessionRecord.FROZEN, store.sessionForPage( "PageA" ).orElseThrow().status() );
        assertThrows( IllegalStateException.class, () -> store.createSession( session( "PageA" ) ) );
    }

    private static SessionRecord frozen( final SessionRecord s ) {
        return new SessionRecord( s.id(), s.pageName(), SessionRecord.FROZEN, s.basePageVersion(), s.baseBody(),
            s.baseMeta(), s.baseContentHash(), s.headVersion(), s.metaSeq(), s.hasUnpublished(), s.pendingPublishVersion(),
            s.pendingPublishBaseVersion(), s.pendingPublishAuthor(), s.createdBy(), s.createdAt(), s.lastEditAt() );
    }

    // ---- updateSession -------------------------------------------------------------------------------------

    @Test
    void updateSessionChangesTheMutableFields() {
        final SessionRecord s = created( "PageA" );
        final Instant later = T0.plusSeconds( 60 );
        final SessionRecord changed = new SessionRecord( s.id(), "PageB", SessionRecord.FROZEN, 9, "new base",
            Map.of( "a", "b" ), "hash-9", 0, 0L, true, 7, 8, "bob", "alice", T0, later );
        store.updateSession( changed );
        assertEquals( changed, store.session( s.id() ).orElseThrow() );
        assertTrue( store.sessionForPage( "PageA" ).isEmpty() );
        assertEquals( s.id(), store.sessionForPage( "PageB" ).orElseThrow().id() );
    }

    @Test
    void updateSessionNeverMovesTheHead() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 3 ) );
        store.appendMetaOps( s.id(), List.of( op( 1 ), op( 2 ) ) );
        final SessionRecord attempt = new SessionRecord( s.id(), "PageA", SessionRecord.ACTIVE, 3, "base body",
            Map.of(), "hash-3", 99, 99L, false, null, null, null, "mallory", T0.plusSeconds( 1 ), T0.plusSeconds( 5 ) );
        store.updateSession( attempt );
        final SessionRecord stored = store.session( s.id() ).orElseThrow();
        assertEquals( 3, stored.headVersion() );
        assertEquals( 2L, stored.metaSeq() );
        assertEquals( "alice", stored.createdBy() );
        assertEquals( T0, stored.createdAt() );
        assertEquals( T0.plusSeconds( 5 ), stored.lastEditAt() );
    }

    @Test
    void updateSessionRenamingOntoAPageWithASessionIsRefused() {
        created( "PageA" );
        final SessionRecord b = created( "PageB" );
        final SessionRecord clash = new SessionRecord( b.id(), "PageA", b.status(), b.basePageVersion(), b.baseBody(),
            b.baseMeta(), b.baseContentHash(), 0, 0L, false, null, null, null, b.createdBy(), b.createdAt(),
            b.lastEditAt() );
        assertThrows( IllegalStateException.class, () -> store.updateSession( clash ) );
        assertEquals( "PageB", store.session( b.id() ).orElseThrow().pageName() );
    }

    @Test
    void updateSessionOnTheSamePageNameIsAllowed() {
        final SessionRecord s = created( "PageA" );
        store.updateSession( s.withBase( 8, "other", Map.of(), "hash-8" ) );
        assertEquals( 8, store.session( s.id() ).orElseThrow().basePageVersion() );
    }

    // ---- appendUpdates -------------------------------------------------------------------------------------

    @Test
    void appendUpdatesAdvancesTheHeadByTheListSizeAndUpdatesSinceReturnsThemAscending() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 3 ) );
        store.appendUpdates( s.id(), upds( 4, 5 ) );
        assertEquals( 5, head( s ) );
        assertEquals( upds( 1, 5 ), store.updatesSince( s.id(), 0 ) );
        assertEquals( upds( 3, 5 ), store.updatesSince( s.id(), 2 ) );
        assertTrue( store.updatesSince( s.id(), 5 ).isEmpty() );
    }

    @Test
    void appendUpdatesRejectsAGapOrRepeat() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 2 ) );
        assertThrows( IllegalStateException.class, () -> store.appendUpdates( s.id(), upds( 4, 4 ) ) );
        assertThrows( IllegalStateException.class, () -> store.appendUpdates( s.id(), upds( 2, 2 ) ) );
        assertEquals( 2, head( s ) );
        assertEquals( 2, store.updatesSince( s.id(), 0 ).size() );
    }

    @Test
    void appendUpdatesIsAtomic() {
        final SessionRecord s = created( "PageA" );
        assertThrows( IllegalStateException.class,
            () -> store.appendUpdates( s.id(), List.of( upd( 1 ), upd( 3 ) ) ) );
        assertEquals( 0, head( s ) );
        assertTrue( store.updatesSince( s.id(), 0 ).isEmpty() );
    }

    @Test
    void appendEmptyUpdatesIsANoOp() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), List.of() );
        assertEquals( 0, head( s ) );
        assertTrue( store.updatesSince( s.id(), 0 ).isEmpty() );
    }

    @Test
    void appendUpdatesOnAnUnknownSessionThrowsIllegalArgument() {
        assertThrows( IllegalArgumentException.class, () -> store.appendUpdates( UUID.randomUUID(), upds( 1, 1 ) ) );
    }

    @Test
    void appendEmptyUpdatesOnAnUnknownSessionThrowsIllegalArgument() {
        assertThrows( IllegalArgumentException.class, () -> store.appendUpdates( UUID.randomUUID(), List.of() ) );
    }

    @Test
    void aDuplicateSessionIdIsRefusedEvenForAnotherPage() {
        final SessionRecord s = created( "PageA" );
        final SessionRecord sameId = new SessionRecord( s.id(), "PageB", SessionRecord.ACTIVE, 3, "b", Map.of(), "h", 0,
            0L, false, null, null, null, "alice", T0, T0 );
        assertThrows( IllegalStateException.class, () -> store.createSession( sameId ) );
        assertEquals( s, store.session( s.id() ).orElseThrow() );
        assertTrue( store.sessionForPage( "PageB" ).isEmpty() );
    }

    /** The race is repeated: a single round can pass by luck of scheduling. */
    private static final int CONCURRENCY_ROUNDS = 100;

    @Test
    void twoConcurrentAppendsAtTheSameVersionExactlyOneWins() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool( 2 );
        try {
            for( int round = 0; round < CONCURRENCY_ROUNDS; round++ ) {
                raceTwoAppends( pool, created( "Race" + round ) );
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void raceTwoAppends( final ExecutorService pool, final SessionRecord s ) throws Exception {
        final CyclicBarrier gate = new CyclicBarrier( 2 );
        final List< Future< Boolean > > results = new ArrayList<>();
        for( int i = 0; i < 2; i++ ) {
            final String client = "c" + i;
            final Callable< Boolean > task = () -> {
                gate.await();
                try {
                    store.appendUpdates( s.id(), List.of( new StoredUpdate( 1, StoredUpdate.TEXT, client, "alice", CS ) ) );
                    return true;
                } catch( final IllegalStateException expected ) {
                    return false;
                }
            };
            results.add( pool.submit( task ) );
        }
        int wins = 0;
        for( final Future< Boolean > f : results ) {
            try {
                if( f.get() ) {
                    wins++;
                }
            } catch( final ExecutionException e ) {
                throw new AssertionError( "unexpected failure type", e.getCause() );
            }
        }
        assertEquals( 1, wins, "round for " + s.pageName() );
        assertEquals( 1, head( s ) );
        assertEquals( 1, store.updatesSince( s.id(), 0 ).size() );
    }

    // ---- meta ops ------------------------------------------------------------------------------------------

    @Test
    void appendMetaOpsAdvancesMetaSeqAndMetaOpsSinceReturnsThemAscending() {
        final SessionRecord s = created( "PageA" );
        store.appendMetaOps( s.id(), List.of( op( 1 ), op( 2 ) ) );
        store.appendMetaOps( s.id(), List.of( op( 3 ) ) );
        assertEquals( 3L, store.session( s.id() ).orElseThrow().metaSeq() );
        assertEquals( List.of( op( 1 ), op( 2 ), op( 3 ) ), store.metaOpsSince( s.id(), 0 ) );
        assertEquals( List.of( op( 3 ) ), store.metaOpsSince( s.id(), 2 ) );
    }

    @Test
    void appendMetaOpsRejectsAGapOrRepeatAtomically() {
        final SessionRecord s = created( "PageA" );
        store.appendMetaOps( s.id(), List.of( op( 1 ) ) );
        assertThrows( IllegalStateException.class, () -> store.appendMetaOps( s.id(), List.of( op( 3 ) ) ) );
        assertThrows( IllegalStateException.class, () -> store.appendMetaOps( s.id(), List.of( op( 1 ) ) ) );
        assertThrows( IllegalStateException.class, () -> store.appendMetaOps( s.id(), List.of( op( 2 ), op( 4 ) ) ) );
        assertEquals( 1L, store.session( s.id() ).orElseThrow().metaSeq() );
        assertEquals( 1, store.metaOpsSince( s.id(), 0 ).size() );
    }

    @Test
    void appendEmptyMetaOpsIsANoOpAndUnknownSessionThrows() {
        final SessionRecord s = created( "PageA" );
        store.appendMetaOps( s.id(), List.of() );
        assertEquals( 0L, store.session( s.id() ).orElseThrow().metaSeq() );
        assertThrows( IllegalArgumentException.class, () -> store.appendMetaOps( UUID.randomUUID(), List.of( op( 1 ) ) ) );
    }

    @Test
    void metaOpValuesRoundTripIdenticallyInBothStores() {   // the Phase 1 Task 1 parity deferral
        final SessionRecord s = created( "PageA" );
        final Map< String, Object > nested = new java.util.LinkedHashMap<>();
        nested.put( "n", 2 );
        nested.put( "inner", Map.of( "x", 1.5 ) );
        final Object[] in = { 3, 5L, 2.5, java.util.Date.from( Instant.parse( "2026-01-15T00:00:00Z" ) ),
            java.util.Date.from( Instant.parse( "2026-01-15T10:30:00Z" ) ), List.of( 1, "a" ), nested, "s", true, null };
        final Object[] out = { 3L, 5L, 2.5, "2026-01-15", "2026-01-15T10:30:00Z", List.of( 1L, "a" ),
            Map.of( "n", 2L, "inner", Map.of( "x", 1.5 ) ), "s", true, null };
        final List< StoredMetaOp > ops = new ArrayList<>();
        for( int i = 0; i < in.length; i++ ) {
            ops.add( new StoredMetaOp( i + 1, "alice", "f" + i, in[ i ] ) );
        }
        store.appendMetaOps( s.id(), ops );
        final List< StoredMetaOp > back = store.metaOpsSince( s.id(), 0 );
        assertEquals( in.length, back.size() );
        for( int i = 0; i < in.length; i++ ) {
            // equals is type-strict for numbers (Long 3 is not Integer 3), element by element inside lists and maps
            assertEquals( out[ i ], back.get( i ).value(), "value " + i + " (" + in[ i ] + ")" );
        }
    }

    // ---- batch (a push's text and meta) --------------------------------------------------------------------

    @Test
    void appendBatchStoresBothAndAdvancesBothCounters() {
        final SessionRecord s = created( "PageA" );
        store.appendBatch( s.id(), upds( 1, 2 ), List.of( op( 1 ) ) );
        store.appendBatch( s.id(), List.of(), List.of( op( 2 ) ) );
        store.appendBatch( s.id(), upds( 3, 3 ), List.of() );
        store.appendBatch( s.id(), List.of(), List.of() );
        final SessionRecord after = store.session( s.id() ).orElseThrow();
        assertEquals( 3, after.headVersion() );
        assertEquals( 2L, after.metaSeq() );
        assertEquals( upds( 1, 3 ), store.updatesSince( s.id(), 0 ) );
        assertEquals( List.of( op( 1 ), op( 2 ) ), store.metaOpsSince( s.id(), 0 ) );
    }

    @Test
    void appendBatchIsAtomic() {
        final SessionRecord s = created( "PageA" );
        store.appendBatch( s.id(), upds( 1, 1 ), List.of( op( 1 ) ) );
        // valid text, meta gap: nothing stored
        assertThrows( IllegalStateException.class, () -> store.appendBatch( s.id(), upds( 2, 2 ), List.of( op( 3 ) ) ) );
        // text gap, valid meta: nothing stored
        assertThrows( IllegalStateException.class, () -> store.appendBatch( s.id(), upds( 3, 3 ), List.of( op( 2 ) ) ) );
        final SessionRecord after = store.session( s.id() ).orElseThrow();
        assertEquals( 1, after.headVersion() );
        assertEquals( 1L, after.metaSeq() );
        assertEquals( 1, store.updatesSince( s.id(), 0 ).size() );
        assertEquals( 1, store.metaOpsSince( s.id(), 0 ).size() );
    }

    @Test
    void appendBatchOnAnUnknownSessionThrowsIllegalArgument() {
        assertThrows( IllegalArgumentException.class, () -> store.appendBatch( UUID.randomUUID(), upds( 1, 1 ),
            List.of( op( 1 ) ) ) );
        assertThrows( IllegalArgumentException.class, () -> store.appendBatch( UUID.randomUUID(), List.of(), List.of() ) );
    }

    // ---- a draft reset: text, meta and the snapshot in one step ------------------------------------------------

    private static Snapshot snapAt( final int version, final long metaSeq ) {
        return new Snapshot( version, metaSeq, 3, "reset@" + version, Map.of(), CS, Map.of() );
    }

    @Test
    void appendWithSnapshotStoresAllThreeInOneStep() {
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), new Snapshot( 0, 0L, 3, "b", Map.of(), CS, threeAuthorsOutOfKeyOrder() ) );
        store.appendWithSnapshot( s.id(), upds( 1, 2 ), List.of( op( 1 ) ), snapAt( 2, 1 ) );
        final SessionRecord after = store.session( s.id() ).orElseThrow();
        assertEquals( 2, after.headVersion() );
        assertEquals( 1L, after.metaSeq() );
        assertEquals( upds( 1, 2 ), store.updatesSince( s.id(), 0 ) );
        assertEquals( List.of( op( 1 ) ), store.metaOpsSince( s.id(), 0 ) );
        final Snapshot latest = store.latestSnapshot( s.id() ).orElseThrow();
        assertEquals( snapAt( 2, 1 ), latest );
        assertTrue( latest.contributors().isEmpty() );
    }

    @Test
    void appendWithSnapshotWithNoChangesWritesTheSnapshotAtTheHead() {
        final SessionRecord s = created( "PageA" );
        store.appendBatch( s.id(), upds( 1, 1 ), List.of( op( 1 ) ) );
        store.appendWithSnapshot( s.id(), List.of(), List.of(), snapAt( 1, 1 ) );
        assertEquals( snapAt( 1, 1 ), store.latestSnapshot( s.id() ).orElseThrow() );
        assertEquals( 1, store.session( s.id() ).orElseThrow().headVersion() );
    }

    @Test
    void appendWithSnapshotRejectsAMisplacedSnapshotOrAGapAndStoresNothing() {
        final SessionRecord s = created( "PageA" );
        store.appendBatch( s.id(), upds( 1, 1 ), List.of( op( 1 ) ) );
        assertThrows( IllegalStateException.class,
            () -> store.appendWithSnapshot( s.id(), upds( 2, 2 ), List.of( op( 2 ) ), snapAt( 1, 2 ) ), "not the new head" );
        assertThrows( IllegalStateException.class,
            () -> store.appendWithSnapshot( s.id(), upds( 2, 2 ), List.of( op( 2 ) ), snapAt( 2, 1 ) ), "not the new seq" );
        assertThrows( IllegalStateException.class,
            () -> store.appendWithSnapshot( s.id(), upds( 3, 3 ), List.of(), snapAt( 2, 1 ) ), "a text gap" );
        assertThrows( IllegalStateException.class,
            () -> store.appendWithSnapshot( s.id(), List.of(), List.of( op( 3 ) ), snapAt( 1, 2 ) ), "a meta gap" );
        final SessionRecord after = store.session( s.id() ).orElseThrow();
        assertEquals( 1, after.headVersion() );
        assertEquals( 1L, after.metaSeq() );
        assertEquals( 1, store.updatesSince( s.id(), 0 ).size() );
        assertEquals( 1, store.metaOpsSince( s.id(), 0 ).size() );
        assertTrue( store.latestSnapshot( s.id() ).isEmpty() );
    }

    @Test
    void appendWithSnapshotOnAnUnknownSessionThrowsIllegalArgument() {
        assertThrows( IllegalArgumentException.class,
            () -> store.appendWithSnapshot( UUID.randomUUID(), List.of(), List.of(), snapAt( 0, 0 ) ) );
    }

    // ---- snapshots -----------------------------------------------------------------------------------------

    @Test
    void latestSnapshotIsTheHighestVersion() {
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.writeSnapshot( s.id(), snap( 10 ) );
        store.writeSnapshot( s.id(), snap( 7 ) );
        assertEquals( 10, store.latestSnapshot( s.id() ).orElseThrow().version() );
    }

    @Test
    void snapshotAtOrBelowIsTheNewestNotAboveTheVersion() {   // E39: S_V is rebuilt from it plus the log (A2, A10)
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.writeSnapshot( s.id(), snap( 10 ) );
        assertEquals( 5, store.snapshotAtOrBelow( s.id(), 9 ).orElseThrow().version() );
        assertEquals( 10, store.snapshotAtOrBelow( s.id(), 10 ).orElseThrow().version() );
        assertEquals( 10, store.snapshotAtOrBelow( s.id(), 99 ).orElseThrow().version() );
        assertTrue( store.snapshotAtOrBelow( s.id(), 4 ).isEmpty() );
        assertTrue( store.snapshotAtOrBelow( UUID.randomUUID(), 4 ).isEmpty() );
    }

    @Test
    void writeSnapshotAtAnExistingVersionReplacesIt() {
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.writeSnapshot( s.id(), new Snapshot( 5, 2L, 3, "replaced", Map.of(), CS ) );
        final Snapshot latest = store.latestSnapshot( s.id() ).orElseThrow();
        assertEquals( "replaced", latest.body() );
        assertEquals( 2L, latest.metaSeq() );
    }

    @Test
    void writeSnapshotOnAnUnknownSessionThrows() {
        assertThrows( IllegalArgumentException.class, () -> store.writeSnapshot( UUID.randomUUID(), snap( 1 ) ) );
    }

    // ---- truncation ----------------------------------------------------------------------------------------

    @Test
    void truncateDeletesUpdatesFoldedIntoTheSnapshotAndKeepsTheRest() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 10 ) );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.truncateUpdatesBelow( s.id(), 6 );
        assertEquals( upds( 6, 10 ), store.updatesSince( s.id(), 0 ) );
    }

    @Test
    void truncateWithNoSnapshotAtOrBelowTheCutDeletesNothing() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 10 ) );
        store.writeSnapshot( s.id(), snap( 8 ) );
        store.truncateUpdatesBelow( s.id(), 6 );
        assertEquals( upds( 1, 10 ), store.updatesSince( s.id(), 0 ) );
        assertEquals( 8, store.latestSnapshot( s.id() ).orElseThrow().version() );
        final SessionRecord t = created( "PageB" );
        store.appendUpdates( t.id(), upds( 1, 3 ) );
        store.truncateUpdatesBelow( t.id(), 100 );
        assertEquals( upds( 1, 3 ), store.updatesSince( t.id(), 0 ), "no snapshot at all: nothing is deleted" );
    }

    @Test
    void truncateNeverChangesTheHead() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 10 ) );
        store.writeSnapshot( s.id(), snap( 10 ) );
        store.truncateUpdatesBelow( s.id(), 100 );
        assertEquals( 10, head( s ) );
        assertTrue( store.updatesSince( s.id(), 0 ).isEmpty() );
        store.appendUpdates( s.id(), upds( 11, 11 ) );
        assertEquals( 11, head( s ) );
    }

    @Test
    void truncateDropsSnapshotsOlderThanTheNewestAtOrBelowTheCut() {
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), snap( 0 ) );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.writeSnapshot( s.id(), snap( 10 ) );
        store.truncateUpdatesBelow( s.id(), 7 );
        // snapshot 5 is the newest at or below 7; 0 is gone, 10 stays, so the latest is unchanged
        assertEquals( 10, store.latestSnapshot( s.id() ).orElseThrow().version() );
        store.truncateUpdatesBelow( s.id(), 10 );
        assertEquals( 10, store.latestSnapshot( s.id() ).orElseThrow().version() );
    }

    @Test
    void truncateKeepsTheNewestSnapshotAtOrBelowTheCut() {
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.truncateUpdatesBelow( s.id(), 20 );
        assertEquals( 5, store.latestSnapshot( s.id() ).orElseThrow().version() );
    }

    @Test
    void truncateBelowACutPastTheSnapshotKeepsTheLogAfterItReloadable() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 19 ) );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.truncateUpdatesBelow( s.id(), 20 );
        final Snapshot base = store.latestSnapshot( s.id() ).orElseThrow();
        assertEquals( 5, base.version() );
        // snapshot 5 + the retained log is a contiguous path to the head: a reload is possible
        assertEquals( upds( 6, 19 ), store.updatesSince( s.id(), base.version() ) );
        assertEquals( upds( 6, 19 ), store.updatesSince( s.id(), 0 ) );
        assertEquals( 19, head( s ) );
    }

    @Test
    void truncateOnAnUnknownSessionThrows() {
        assertThrows( IllegalArgumentException.class, () -> store.truncateUpdatesBelow( UUID.randomUUID(), 1 ) );
    }

    @Test
    void updatesSinceReturnsOnlyTheRetainedSuffixSoACallerCanDetectTheGap() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 10 ) );
        store.writeSnapshot( s.id(), snap( 5 ) );
        store.truncateUpdatesBelow( s.id(), 6 );
        final List< StoredUpdate > got = store.updatesSince( s.id(), 2 );
        assertEquals( 6, got.get( 0 ).version() );
        assertTrue( got.get( 0 ).version() > 2 + 1 );
    }

    // ---- commitBaseChange ----------------------------------------------------------------------------------

    private static StoredUpdate serverUpd( final int version ) {
        return new StoredUpdate( version, StoredUpdate.SERVER, "server", null, CS );
    }

    private static Snapshot baseSnap( final int version, final int basePageVersion ) {
        return new Snapshot( version, 0L, basePageVersion, "base@" + basePageVersion, Map.of(), CS );
    }

    private static SessionRecord rebased( final SessionRecord s, final int basePageVersion ) {
        return s.withBase( basePageVersion, "base@" + basePageVersion, Map.of( "v", "v" + basePageVersion ),
            "hash-" + basePageVersion ).withPending( null, null, null );
    }

    @Test
    void commitBaseChangeAppendsWritesTheSnapshotAndUpdatesTheSession() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 2 ) );
        store.updateSession( s.withPending( 4, 3, "alice" ) );
        store.commitBaseChange( s.id(), List.of( serverUpd( 3 ) ), baseSnap( 3, 4 ), rebased( s, 4 ) );
        final SessionRecord stored = store.session( s.id() ).orElseThrow();
        assertEquals( 3, stored.headVersion() );
        assertEquals( 4, stored.basePageVersion() );
        assertEquals( "base@4", stored.baseBody() );
        assertEquals( "hash-4", stored.baseContentHash() );
        assertEquals( Map.of( "v", "v4" ), stored.baseMeta() );
        assertEquals( null, stored.pendingPublishVersion() );
        assertEquals( null, stored.pendingPublishAuthor() );
        assertEquals( serverUpd( 3 ), store.updatesSince( s.id(), 2 ).get( 0 ) );
        assertEquals( baseSnap( 3, 4 ), store.latestSnapshot( s.id() ).orElseThrow() );
    }

    @Test
    void commitBaseChangeWithNoServerUpdatesSnapshotsAtTheCurrentHead() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 2 ) );
        store.commitBaseChange( s.id(), List.of(), baseSnap( 2, 4 ), rebased( s, 4 ) );
        assertEquals( 2, head( s ) );
        assertEquals( 4, store.session( s.id() ).orElseThrow().basePageVersion() );
        assertEquals( 2, store.latestSnapshot( s.id() ).orElseThrow().version() );
    }

    @Test
    void commitBaseChangeKeepsTheImmutableFields() {
        final SessionRecord s = created( "PageA" );
        store.appendMetaOps( s.id(), List.of( op( 1 ) ) );
        final SessionRecord attempt = new SessionRecord( s.id(), "PageA", SessionRecord.ACTIVE, 4, "base@4", Map.of(),
            "hash-4", 77, 77L, false, null, null, null, "mallory", T0.plusSeconds( 9 ), T0.plusSeconds( 9 ) );
        store.commitBaseChange( s.id(), List.of(), baseSnap( 0, 4 ), attempt );
        final SessionRecord stored = store.session( s.id() ).orElseThrow();
        assertEquals( 0, stored.headVersion() );
        assertEquals( 1L, stored.metaSeq() );
        assertEquals( "alice", stored.createdBy() );
        assertEquals( T0, stored.createdAt() );
    }

    private void assertUntouched( final SessionRecord s, final SessionRecord before, final int headBefore ) {
        assertEquals( before, store.session( s.id() ).orElseThrow() );
        assertEquals( headBefore, store.updatesSince( s.id(), 0 ).size() );
        assertTrue( store.latestSnapshot( s.id() ).isEmpty() );
    }

    @Test
    void commitBaseChangeRejectsAGapAndChangesNothing() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 2 ) );
        final SessionRecord before = store.session( s.id() ).orElseThrow();
        assertThrows( IllegalStateException.class,
            () -> store.commitBaseChange( s.id(), List.of( serverUpd( 4 ) ), baseSnap( 4, 4 ), rebased( s, 4 ) ) );
        assertUntouched( s, before, 2 );
    }

    @Test
    void commitBaseChangeRejectsASnapshotNotAtTheNewHeadAndChangesNothing() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 2 ) );
        final SessionRecord before = store.session( s.id() ).orElseThrow();
        assertThrows( IllegalStateException.class,
            () -> store.commitBaseChange( s.id(), List.of( serverUpd( 3 ) ), baseSnap( 2, 4 ), rebased( s, 4 ) ) );
        assertUntouched( s, before, 2 );
    }

    @Test
    void commitBaseChangeRejectsASnapshotForAnotherBaseAndChangesNothing() {
        final SessionRecord s = created( "PageA" );
        final SessionRecord before = store.session( s.id() ).orElseThrow();
        assertThrows( IllegalArgumentException.class,
            () -> store.commitBaseChange( s.id(), List.of( serverUpd( 1 ) ), baseSnap( 1, 5 ), rebased( s, 4 ) ) );
        assertUntouched( s, before, 0 );
    }

    @Test
    void commitBaseChangeRejectsAClientUpdateAndChangesNothing() {
        final SessionRecord s = created( "PageA" );
        final SessionRecord before = store.session( s.id() ).orElseThrow();
        assertThrows( IllegalArgumentException.class,
            () -> store.commitBaseChange( s.id(), List.of( upd( 1 ) ), baseSnap( 1, 4 ), rebased( s, 4 ) ) );
        assertUntouched( s, before, 0 );
    }

    /** The session update is the last of the three steps: its failure must undo the append and the snapshot. */
    @Test
    void commitBaseChangeIsAtomicWhenTheSessionUpdateFails() {
        created( "PageB" );
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 2 ) );
        final SessionRecord before = store.session( s.id() ).orElseThrow();
        final SessionRecord renamedOntoB = new SessionRecord( s.id(), "PageB", SessionRecord.ACTIVE, 4, "base@4",
            Map.of(), "hash-4", 0, 0L, false, null, null, null, "alice", T0, T0 );
        assertThrows( IllegalStateException.class,
            () -> store.commitBaseChange( s.id(), List.of( serverUpd( 3 ) ), baseSnap( 3, 4 ), renamedOntoB ) );
        assertUntouched( s, before, 2 );
        assertEquals( 2, head( s ) );
    }

    @Test
    void commitBaseChangeRejectsARecordForAnotherSessionOrAnUnknownSession() {
        final SessionRecord s = created( "PageA" );
        final SessionRecord other = created( "PageB" );
        assertThrows( IllegalArgumentException.class,
            () -> store.commitBaseChange( s.id(), List.of(), baseSnap( 0, 4 ), rebased( other, 4 ) ) );
        assertEquals( 3, store.session( s.id() ).orElseThrow().basePageVersion() );
        final UUID unknown = UUID.randomUUID();
        assertThrows( IllegalArgumentException.class,
            () -> store.commitBaseChange( unknown, List.of(), baseSnap( 0, 4 ), rebased( s, 4 ) ) );
    }

    // ---- delete --------------------------------------------------------------------------------------------

    @Test
    void deleteSessionRemovesItsLogsAndFreesThePageName() {
        final SessionRecord s = created( "PageA" );
        store.appendUpdates( s.id(), upds( 1, 2 ) );
        store.appendMetaOps( s.id(), List.of( op( 1 ) ) );
        store.writeSnapshot( s.id(), snap( 2 ) );
        store.deleteSession( s.id() );
        assertTrue( store.session( s.id() ).isEmpty() );
        assertTrue( store.sessionForPage( "PageA" ).isEmpty() );
        assertTrue( store.updatesSince( s.id(), 0 ).isEmpty() );
        assertTrue( store.metaOpsSince( s.id(), 0 ).isEmpty() );
        assertTrue( store.latestSnapshot( s.id() ).isEmpty() );
        final SessionRecord again = session( "PageA" );
        store.createSession( again );
        assertEquals( again.id(), store.sessionForPage( "PageA" ).orElseThrow().id() );
        assertFalse( store.updatesSince( again.id(), 0 ).iterator().hasNext() );
    }

    @Test
    void deleteSessionOnAnUnknownSessionThrows() {
        assertThrows( IllegalArgumentException.class, () -> store.deleteSession( UUID.randomUUID() ) );
    }

    @Test
    void mutatorsOnAnUnknownSessionThrowIllegalArgument() {
        assertThrows( IllegalArgumentException.class, () -> store.updateSession( session( "Ghost" ) ) );
    }

    // ---- snapshot contributors (Phase 3, V067) -------------------------------------------------------------

    private static Map< String, Contribution > threeAuthorsOutOfKeyOrder() {
        final Map< String, Contribution > c = new LinkedHashMap<>();
        c.put( "mia", new Contribution( 1, 2, Contribution.NONE, Contribution.NONE ) );
        c.put( "ann", new Contribution( Contribution.NONE, Contribution.NONE, 1, 1 ) );
        c.put( "zoe", new Contribution( 2, 2, 2, 3 ) );
        return c;
    }

    @Test
    void snapshotContributorsRoundTripInOrder() {
        final SessionRecord s = created( "PageA" );
        final Map< String, Contribution > contributors = threeAuthorsOutOfKeyOrder();
        store.writeSnapshot( s.id(), new Snapshot( 2, 3L, 3, "body@2", Map.of(), CS, contributors ) );
        final Snapshot latest = store.latestSnapshot( s.id() ).orElseThrow();
        assertEquals( contributors, latest.contributors() );
        assertEquals( List.of( "mia", "ann", "zoe" ), List.copyOf( latest.contributors().keySet() ) );
        assertEquals( List.of( "mia", "ann", "zoe" ),
            List.copyOf( store.snapshotAtOrBelow( s.id(), 5 ).orElseThrow().contributors().keySet() ) );
    }

    @Test
    void commitBaseChangeWritesTheSnapshotsContributors() {
        final SessionRecord s = created( "PageA" );
        final Map< String, Contribution > contributors = threeAuthorsOutOfKeyOrder();
        store.commitBaseChange( s.id(), List.of(), new Snapshot( 0, 0L, 4, "b", Map.of(), CS, contributors ),
            s.withBase( 4, "b", Map.of(), "hash-4" ) );
        assertEquals( List.of( "mia", "ann", "zoe" ),
            List.copyOf( store.latestSnapshot( s.id() ).orElseThrow().contributors().keySet() ) );
    }

    @Test
    void aSnapshotWithoutContributorsReadsAsEmpty() {
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), snap( 0 ) );
        assertTrue( store.latestSnapshot( s.id() ).orElseThrow().contributors().isEmpty() );
    }

    @Test
    void rewritingASnapshotReplacesItsContributors() {
        final SessionRecord s = created( "PageA" );
        store.writeSnapshot( s.id(), new Snapshot( 0, 0L, 3, "b", Map.of(), CS, threeAuthorsOutOfKeyOrder() ) );
        store.writeSnapshot( s.id(), snap( 0 ) );
        assertTrue( store.latestSnapshot( s.id() ).orElseThrow().contributors().isEmpty() );
    }

    // ---- page-version contributor ledger (Phase 3, R4) -----------------------------------------------------

    @Test
    void recordContributorsReplacesTheVersionsRows() {
        store.recordContributors( "cid-1", 1, Set.of( "xavier" ), false );
        store.recordContributors( "cid-1", 2, Set.of( "alice", "bob" ), false );
        store.recordContributors( "cid-1", 2, Set.of( "carol" ), false );
        assertEquals( Map.of( 1, List.of( "xavier" ), 2, List.of( "carol" ) ), store.contributorsByVersion( "cid-1" ) );
    }

    @Test
    void recordContributorsWithNoAuthorsStillClearsTheVersion() {
        store.recordContributors( "cid-1", 2, Set.of( "alice" ), false );
        store.recordContributors( "cid-1", 2, Set.of(), false );
        assertTrue( store.contributorsByVersion( "cid-1" ).isEmpty() );
    }

    @Test
    void recordContributorsIsIdempotent() {
        store.recordContributors( "cid-1", 3, Set.of( "alice", "bob" ), false );
        store.recordContributors( "cid-1", 3, Set.of( "alice", "bob" ), false );
        assertEquals( Map.of( 3, List.of( "alice", "bob" ) ), store.contributorsByVersion( "cid-1" ) );
        store.recordContributors( "cid-1", 1, Set.of( "carol" ), true );
        store.recordContributors( "cid-1", 1, Set.of( "carol" ), true );
        assertEquals( Map.of( 1, List.of( "carol" ) ), store.contributorsByVersion( "cid-1" ) );
    }

    @Test
    void createdClearsEveryEarlierRowOfTheCanonicalId() {
        store.recordContributors( "cid-1", 1, Set.of( "alice" ), true );
        store.recordContributors( "cid-1", 2, Set.of( "bob" ), false );
        store.recordContributors( "cid-1", 3, Set.of( "carol" ), false );
        store.recordContributors( "cid-2", 1, Set.of( "dave" ), true );
        store.recordContributors( "cid-1", 1, Set.of( "erin" ), true );
        assertEquals( Map.of( 1, List.of( "erin" ) ), store.contributorsByVersion( "cid-1" ) );
        assertEquals( Map.of( 1, List.of( "dave" ) ), store.contributorsByVersion( "cid-2" ),
            "another canonical id is untouched" );
    }

    @Test
    void contributorsByVersionIsSortedPerVersion() {
        store.recordContributors( "cid-1", 10, Set.of( "zoe", "Bob", "amy" ), false );
        store.recordContributors( "cid-1", 2, Set.of( "mia", "eve" ), false );
        final Map< Integer, List< String > > byVersion = store.contributorsByVersion( "cid-1" );
        assertEquals( List.of( 2, 10 ), List.copyOf( byVersion.keySet() ), "versions ascend" );
        assertEquals( List.of( "Bob", "amy", "zoe" ), byVersion.get( 10 ) );
        assertEquals( List.of( "eve", "mia" ), byVersion.get( 2 ) );
        assertTrue( store.contributorsByVersion( "cid-unknown" ).isEmpty() );
    }

    @Test
    void aBlankCanonicalIdIsRejected() {
        assertThrows( IllegalArgumentException.class, () -> store.recordContributors( " ", 1, Set.of( "a" ), false ) );
        assertThrows( IllegalArgumentException.class, () -> store.recordContributors( "", 1, Set.of( "a" ), true ) );
        assertThrows( IllegalArgumentException.class, () -> store.recordContributors( null, 1, Set.of( "a" ), false ) );
        assertThrows( IllegalArgumentException.class, () -> store.recordContributors( "cid-1", 0, Set.of( "a" ), false ),
            "page versions are 1-based" );
        assertThrows( IllegalArgumentException.class, () -> store.recordContributors( "cid-1", 1, Set.of( " " ), false ),
            "a blank author key" );
        assertTrue( store.contributorsByVersion( "cid-1" ).isEmpty(), "a refused call stores nothing" );
    }

    @Test
    void deleteSessionKeepsTheLedger() {
        final SessionRecord s = created( "PageA" );
        store.recordContributors( "cid-1", 4, Set.of( "alice", "bob" ), false );
        store.deleteSession( s.id() );
        assertEquals( Map.of( 4, List.of( "alice", "bob" ) ), store.contributorsByVersion( "cid-1" ) );
    }

    // ---- session listing (Phase 3) -------------------------------------------------------------------------

    private static SessionRecord sessionAt( final String page, final Instant createdAt, final Instant lastEditAt ) {
        return new SessionRecord( UUID.randomUUID(), page, SessionRecord.ACTIVE, 3, "base body", Map.of(), "hash-3", 0,
            0L, false, null, null, null, "alice", createdAt, lastEditAt );
    }

    @Test
    void sessionsListsActiveAndFrozenOldestEditFirst() {
        assertTrue( store.sessions().isEmpty() );
        final SessionRecord a = sessionAt( "PageA", T0.plusSeconds( 3600 ), T0.plusSeconds( 5 * 3600 ) );
        final SessionRecord b = sessionAt( "PageB", T0.plusSeconds( 2 * 3600 ), null );
        final SessionRecord c = sessionAt( "PageC", T0, T0.plusSeconds( 3 * 3600 ) );
        store.createSession( a );
        store.createSession( b );
        store.createSession( c );
        store.updateSession( frozen( b ) );
        final List< SessionRecord > listed = store.sessions();
        assertEquals( List.of( b.id(), c.id(), a.id() ), listed.stream().map( SessionRecord::id ).toList(),
            "ordered by lastEditAt, an unedited session by createdAt" );
        assertEquals( SessionRecord.FROZEN, listed.get( 0 ).status() );
        assertEquals( a, listed.get( 2 ) );
    }

    // ---- light draft reads (Phase 3 Task 4 fix round 1) ----------------------------------------------------

    @Test
    void draftEntriesCarryTheRecordWithoutBodiesOldestEditFirst() {
        assertTrue( store.draftEntries().isEmpty() );
        final SessionRecord a = sessionAt( "PageA", T0.plusSeconds( 3600 ), T0.plusSeconds( 5 * 3600 ) );
        final SessionRecord b = sessionAt( "PageB", T0.plusSeconds( 2 * 3600 ), null );
        final SessionRecord c = sessionAt( "PageC", T0, T0.plusSeconds( 3 * 3600 ) )
            .withPending( 4, 3, "carol" );
        store.createSession( a );
        store.createSession( b );
        store.createSession( c );
        store.updateSession( frozen( b ) );
        final List< DraftEntry > listed = store.draftEntries();
        assertEquals( List.of( b.id(), c.id(), a.id() ), listed.stream().map( DraftEntry::id ).toList() );
        assertEquals( new DraftEntry( b.id(), "PageB", SessionRecord.FROZEN, false, false, "alice", b.createdAt(),
            null ), listed.get( 0 ) );
        assertTrue( listed.get( 1 ).publishPending() );
        assertEquals( T0.plusSeconds( 5 * 3600 ), listed.get( 2 ).lastEditAt() );
        assertEquals( T0.plusSeconds( 2 * 3600 ), listed.get( 0 ).lastActivity(), "never edited: its creation" );
    }

    @Test
    void aDraftsLastEditIsTheLaterOfItsMarkerAndItsNewestLoggedEdit() {
        final SessionRecord s = created( "PageA" );   // marker T0
        final Instant before = Instant.now().minusSeconds( 5 );

        store.appendUpdates( s.id(), List.of( new StoredUpdate( 1, StoredUpdate.SERVER, "server", null, CS ) ) );
        store.appendMetaOps( s.id(), List.of( new StoredMetaOp( 1, null, "title", "merged" ) ) );
        assertEquals( T0, store.draftEntries().get( 0 ).lastEditAt(), "an unauthored server update or meta op (a merge,"
            + " a publish rewrite, a discard) is not an edit" );

        store.appendUpdates( s.id(), List.of( upd( 2 ) ) );
        final Instant afterText = store.draftEntries().get( 0 ).lastEditAt();
        assertFalse( afterText.isBefore( before ), "a logged text edit newer than a stale marker: " + afterText );

        final SessionRecord other = created( "PageB" );
        store.appendMetaOps( other.id(), List.of( op( 1 ) ) );
        final DraftEntry b = store.draftEntries().stream().filter( e -> e.id().equals( other.id() ) ).findFirst()
            .orElseThrow();
        assertFalse( b.lastEditAt().isBefore( before ), "an authored meta op is an edit" );

        final Instant future = Instant.now().plusSeconds( 365L * 24 * 3600 ).truncatedTo( ChronoUnit.MILLIS );
        store.updateSession( new SessionRecord( s.id(), "PageA", SessionRecord.ACTIVE, 3, "base body", Map.of( "k", "v" ),
            "hash-3", 0, 0L, true, null, null, null, "alice", T0, future ) );
        final DraftEntry a = store.draftEntries().stream().filter( e -> e.id().equals( s.id() ) ).findFirst()
            .orElseThrow();
        assertEquals( future, a.lastEditAt(), "a marker newer than the log wins" );
        assertTrue( a.hasUnpublished() );
    }

    @Test
    void unpublishedByPageNamesEveryDraftWithItsStoredFlag() {
        assertTrue( store.unpublishedByPage().isEmpty() );
        final SessionRecord a = created( "PageA" );
        final SessionRecord b = created( "PageB" );
        store.updateSession( new SessionRecord( b.id(), "PageB", SessionRecord.FROZEN, 3, "base body", Map.of( "k", "v" ),
            "hash-3", 0, 0L, true, null, null, null, "alice", T0, T0 ) );
        assertEquals( Map.of( "PageA", false, "PageB", true ), store.unpublishedByPage() );
        store.deleteSession( a.id() );
        assertEquals( Map.of( "PageB", true ), store.unpublishedByPage() );
    }

    @Test
    void aSeedsAuthoredServerUpdateIsAnEdit() {
        final SessionRecord s = created( "PageA" );   // marker T0
        final Instant before = Instant.now().minusSeconds( 5 );
        store.appendUpdates( s.id(), List.of( new StoredUpdate( 1, StoredUpdate.SERVER, "server", "alice", CS ) ) );
        assertFalse( store.draftEntries().get( 0 ).lastEditAt().isBefore( before ),
            "a seed is stored as a server update with its creator's key: an edit" );
    }

    @Test
    void unpublishedByPageCountsEditsTheStoredMarkerNeverRecorded() {
        final SessionRecord stale = created( "Stale" );   // flag false, marker T0: a crash after later edits
        store.appendUpdates( stale.id(), List.of( upd( 1 ) ) );
        final SessionRecord metaOnly = sessionAt( "MetaOnly", T0, null );   // never edited, by its marker
        store.createSession( metaOnly );
        store.appendMetaOps( metaOnly.id(), List.of( op( 1 ) ) );
        final SessionRecord merged = created( "Merged" );
        store.appendUpdates( merged.id(), List.of( new StoredUpdate( 1, StoredUpdate.SERVER, "server", null, CS ) ) );
        store.appendMetaOps( merged.id(), List.of( new StoredMetaOp( 1, null, "title", "merged" ) ) );
        final SessionRecord recorded = created( "Recorded" );
        store.appendUpdates( recorded.id(), List.of( upd( 1 ) ) );
        final Instant later = Instant.now().plusSeconds( 3600 ).truncatedTo( ChronoUnit.MILLIS );
        store.updateSession( new SessionRecord( recorded.id(), "Recorded", SessionRecord.ACTIVE, 3, "base body",
            Map.of( "k", "v" ), "hash-3", 0, 0L, false, null, null, null, "alice", T0, later ) );

        assertEquals( Map.of( "Stale", true, "MetaOnly", true, "Merged", false, "Recorded", false ),
            store.unpublishedByPage(), "an authored edit newer than the marker (or with no marker) is unpublished;"
                + " a merge is not an edit, and an edit the marker already recorded follows the stored flag" );
    }
}
