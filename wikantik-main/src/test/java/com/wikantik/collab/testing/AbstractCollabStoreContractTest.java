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
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void twoConcurrentAppendsAtTheSameVersionExactlyOneWins() throws Exception {
        final SessionRecord s = created( "PageA" );
        final CyclicBarrier gate = new CyclicBarrier( 2 );
        final ExecutorService pool = Executors.newFixedThreadPool( 2 );
        try {
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
            assertEquals( 1, wins );
        } finally {
            pool.shutdownNow();
        }
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
        return s.withBase( basePageVersion, "base@" + basePageVersion, Map.of( "v", basePageVersion ),
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
        assertEquals( Map.of( "v", 4 ), stored.baseMeta() );
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
}
