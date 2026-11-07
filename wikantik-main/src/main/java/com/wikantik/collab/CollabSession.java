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

import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.text.TextDiff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One page's live collaboration session: the central authority of {@code @codemirror/collab} (docs/MultiEdit.MD
 * section 7). Join, push (7.2), pull with long-poll parking (7.5), the in-memory ring with catch-up from the store
 * (7.6, A10), snapshots, log truncation and reload (7.7, E37).
 *
 * <p><b>Concurrency.</b> One {@link ReentrantLock} guards all state. The store is written while it is held (store
 * first, then memory, so both keep one order, section 12.4). Parked pulls ({@link PullWaiter}) are always completed
 * after the lock is released, from memory or the ring only: no store read follows a commit.</p>
 *
 * <p><b>Persistence.</b> {@link #create} writes the session and a version-0 snapshot; every base change goes through
 * {@link CollabStore#commitBaseChange}, which writes a snapshot with it; and a snapshot is written every
 * {@code snapshot.everyUpdates} accepted updates. {@link #load} rebuilds doc, head, local and the ring from the latest
 * snapshot plus the log after it. Participants and parked pulls are memory only: after a reload everyone rejoins, under
 * participant and client ids that carry a per-load tag (clock millis plus a per-store load count), so a straggler
 * request from before a restart can never be taken for a participant created after it.</p>
 *
 * <p><b>Publish</b> here is the Phase 0 stub's naive form (the save runs under the lock; no pending-publish marker),
 * kept so the harness can drive publishes; Task 4 replaces it with section 7.3.</p>
 */
public final class CollabSession {

    private static final Logger LOG = LogManager.getLogger( CollabSession.class );

    /** The clientID of server-originated updates (publish rewrites, initial text, external merges). */
    public static final String SERVER_CLIENT = "server";
    static final String REJOIN = "rejoin";
    static final String STALE = "stale-base";
    static final String NOT_YOURS = "not-your-participant";
    static final String REJECTED = "rejected";
    static final String STORE = "store";
    /** Loads per store, for the id tag (see the class comment). */
    private static final Map< CollabStore, AtomicInteger > LOADS = Collections.synchronizedMap( new WeakHashMap<>() );

    /** A read-only copy of the session state, for invariant checks, the registry and tests. */
    public record View( UUID id, String pageName, String doc, int head, String baseBody, ChangeSet local,
                        int basePageVersion, List< StoredUpdate > ring, boolean oversize, long oversizeCeiling,
                        long maxPageBytes, long metaSeq, Map< String, Object > meta, Instant lastEditAt,
                        Map< String, Selection > selections, int participants ) {
    }

    private final UUID id;
    private final CollabStore store;
    private final CollabPageWriter writer;
    private final Clock clock;
    private final CollabConfig config;
    private final Oversize oversize;
    private final ReentrantLock lock = new ReentrantLock();

    private SessionRecord record;
    private String doc;
    private int head;
    private final UpdateRing ring;
    private ChangeSet local;
    private Map< String, Object > meta;
    private long metaSeq;
    private Instant lastEditAt;
    private int lastSnapshotVersion;
    /** Created here and not yet joined: the first join may seed initial text (A11). */
    private boolean fresh;

    private final Roster roster;
    private final Waiters waiters = new Waiters();

    private CollabSession( final SessionRecord record, final CollabStore store, final CollabPageWriter writer,
                           final Clock clock, final CollabConfig config, final long maxPageBytes ) {
        this.id = record.id();
        this.record = record;
        this.store = Objects.requireNonNull( store, "store" );
        this.writer = Objects.requireNonNull( writer, "writer" );
        this.clock = Objects.requireNonNull( clock, "clock" );
        this.config = Objects.requireNonNull( config, "config" );
        this.oversize = new Oversize( maxPageBytes );
        this.ring = new UpdateRing( config.historyInMemoryUpdates() );
        final int load = LOADS.computeIfAbsent( store, k -> new AtomicInteger() ).incrementAndGet();
        this.roster = new Roster( Long.toString( clock.millis(), 36 ) + "." + load + "-" );
    }

    // ---- creation and reload ---------------------------------------------------------------------------------

    /**
     * The record of a new session on {@code pageName}, based on its latest saved state (LF-normalised, A9), or on an
     * empty body at base version 0 ("must not exist", A11) when the page does not exist yet.
     */
    public static SessionRecord newRecord( final UUID id, final String pageName, final Optional< PageState > latest,
                                           final String createdBy, final Clock clock ) {
        final String body = latest.map( p -> CollabText.toLf( p.body() ) ).orElse( "" );
        return new SessionRecord( id, pageName, SessionRecord.ACTIVE, latest.map( PageState::version ).orElse( 0 ), body,
            latest.map( PageState::meta ).orElse( Map.of() ), CollabText.contentHash( body ), 0, 0, false, null, null,
            null, createdBy, clock.instant(), null );
    }

    /**
     * Creates the session in the store with its version-0 snapshot. The base body is normalised to LF (A9).
     *
     * @param maxPageBytes the page size limit ({@code wikantik.api.maxPageBytes}) behind section 7.2's 413
     * @throws IllegalArgumentException when the record is not at head and metaSeq 0
     * @throws IllegalStateException    when the page already has a session (from the store)
     */
    public static CollabSession create( final SessionRecord rec, final CollabStore store, final CollabPageWriter writer,
                                        final Clock clock, final CollabConfig config, final long maxPageBytes ) {
        final SessionRecord stored = SessionLoader.create( rec, store );
        final CollabSession s = new CollabSession( stored, store, writer, clock, config, maxPageBytes );
        s.doc = stored.baseBody();
        s.local = ChangeSet.empty( s.doc.length() );
        s.meta = MetaJson.normalise( stored.baseMeta() );
        s.fresh = true;
        s.oversize.update( s.doc, true );
        return s;
    }

    /**
     * Rebuilds a session from the store (section 7.7, E37): doc and head from the latest snapshot plus the log after it,
     * meta from the snapshot plus the meta ops after it, local from the snapshot's local composed with the log (or
     * {@code TextDiff.changeSet(baseBody, doc)} when the snapshot belongs to another base), the ring from the log's tail.
     *
     * @throws IllegalStateException when the session or its snapshot is missing, or the log is not contiguous from the
     *                               snapshot to the recorded head
     */
    public static CollabSession load( final UUID id, final CollabStore store, final CollabPageWriter writer,
                                      final Clock clock, final CollabConfig config, final long maxPageBytes ) {
        final SessionLoader.Loaded l = SessionLoader.load( id, store, config.historyInMemoryUpdates() );
        final CollabSession s = new CollabSession( l.record(), store, writer, clock, config, maxPageBytes );
        s.doc = l.doc();
        s.head = l.head();
        s.local = l.local();
        s.meta = MetaJson.normalise( l.meta() );
        s.metaSeq = l.record().metaSeq();
        s.lastEditAt = l.record().lastEditAt();
        s.lastSnapshotVersion = l.snapshotVersion();
        s.ring.addAll( l.ring() );
        s.oversize.update( s.doc, true );
        return s;
    }

    // ---- identity --------------------------------------------------------------------------------------------

    public UUID id() {
        return id;
    }

    public String pageName() {
        lock.lock();
        try {
            return record.pageName();
        } finally {
            lock.unlock();
        }
    }

    /** For tests: true while the calling thread holds the session lock. */
    boolean lockHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    // ---- join and leave --------------------------------------------------------------------------------------

    /**
     * Adds a participant bound to {@code r.authorKey()} (A6). The first join into a freshly created session applies
     * {@code initialText}/{@code initialMeta} (A11) as a server update and meta ops; a store failure while doing so
     * propagates with nothing applied in memory that the store does not hold, and the next join retries.
     */
    public JoinResponse join( final JoinRequest r ) {
        lock.lock();
        try {
            if( fresh ) {
                seed( r );
                fresh = false;
            }
            final Participant p = roster.add( r, clock.instant(), head, config.pushesPerSecond() );
            return new JoinResponse( id, p.id, p.clientId, head, doc, metaSeq, meta, record.basePageVersion(),
                roster.peers(), hasUnpublished(), lastEditAt );
        } finally {
            lock.unlock();
        }
    }

    private void seed( final JoinRequest r ) {
        if( r.initialText() != null ) {
            final String text = CollabText.toLf( r.initialText() );
            if( CollabText.hasLoneSurrogate( text ) ) {
                throw new IllegalArgumentException( "initialText holds a lone UTF-16 surrogate" );
            }
            if( !text.equals( doc ) ) {
                final StoredUpdate u = new StoredUpdate( head + 1, StoredUpdate.SERVER, SERVER_CLIENT, r.authorKey(),
                    TextDiff.changeSet( doc, text ) );
                store.appendUpdates( id, List.of( u ) );
                applyAccepted( List.of( u ), text );
                lastEditAt = clock.instant();
                oversize.update( doc, true );
            }
        }
        if( r.initialMeta() != null ) {
            final List< StoredMetaOp > ops = SessionLoader.metaOps( meta, MetaJson.normalise( r.initialMeta() ), metaSeq,
                r.authorKey() );
            if( !ops.isEmpty() ) {
                store.appendMetaOps( id, ops );
                ops.forEach( op -> SessionLoader.applyMetaOp( meta, op ) );
                metaSeq += ops.size();
            }
        }
    }

    /** Removes the participant; its parked pull, if any, is answered empty. Unknown ids are ignored. */
    public void leave( final String participantId ) {
        final List< Runnable > completions = new ArrayList<>();
        lock.lock();
        try {
            roster.remove( participantId );
            final Runnable parked = waiters.remove( participantId, empty() );
            if( parked != null ) {
                completions.add( parked );
            }
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
    }

    // ---- push ------------------------------------------------------------------------------------------------

    public PushResponse push( final String participantId, final String authorKey, final int version,
                              final List< Update > updates ) {
        return push( participantId, authorKey, version, updates, null );
    }

    /**
     * Section 7.2: validates the whole batch, appends it to the store, then applies it in memory; on a store failure
     * nothing changes (503, E35). Waiters are answered after the lock is released.
     */
    public PushResponse push( final String participantId, final String authorKey, final int version,
                              final List< Update > updates, final Selection selection ) {
        final List< Runnable > completions = new ArrayList<>();
        final PushResponse out;
        lock.lock();
        try {
            out = pushLocked( participantId, authorKey, version, updates, selection, completions );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    private PushResponse pushLocked( final String participantId, final String authorKey, final int version,
                                     final List< Update > updates, final Selection selection,
                                     final List< Runnable > completions ) {
        final Participant p = roster.get( participantId );
        if( p == null ) {
            return PushResponse.refused( 409, head, REJOIN );
        }
        if( !p.authorKey.equals( authorKey ) ) {
            return PushResponse.refused( 403, head, NOT_YOURS );
        }
        final Instant now = clock.instant();
        p.touch( now );
        if( !p.tryAcquirePush( now, config.pushesPerSecond() ) ) {
            return PushResponse.refused( 429, head, "rate-limited" );
        }
        if( SessionRecord.FROZEN.equals( record.status() ) ) {
            return PushResponse.refused( 409, head, "frozen" );
        }
        if( version >= 0 && version <= head ) {
            p.confirm( version );
        }
        if( version != head ) {
            return PushResponse.refused( 409, head, STALE );
        }
        final PushCheck check = PushCheck.of( p, updates, doc, head, oversize.maxPageBytes() );
        if( check.refusal() != null ) {
            return PushResponse.refused( check.refusal().status(), head, check.refusal().reason() );
        }
        final List< StoredUpdate > accepted = check.accepted();
        if( accepted.isEmpty() ) {
            keepSelection( p, selection );
            return PushResponse.accepted( head );
        }
        try {
            store.appendUpdates( id, accepted );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): push from {} not stored, nothing applied: {}", id, record.pageName(),
                participantId, e.getMessage() );
            return PushResponse.refused( 503, head, STORE );
        }
        applyAccepted( accepted, check.next() );
        lastEditAt = now;
        oversize.update( doc, false );
        final Selection own = Presence.afterOwnPush( selection, version, accepted.size(), doc.length(), head );
        if( own != null ) {
            p.selection( own );
        } else {
            keepSelection( p, selection );
        }
        maybeSnapshot();
        wakeAll( accepted, completions );
        return PushResponse.accepted( head );
    }

    private void applyAccepted( final List< StoredUpdate > accepted, final String next ) {
        ChangeSet l = local;
        for( final StoredUpdate u : accepted ) {
            l = l.compose( u.changes() );
        }
        ring.addAll( accepted );
        local = l;
        doc = next;
        head += accepted.size();
    }

    // ---- pull ------------------------------------------------------------------------------------------------

    /**
     * Section 7.5 as one atomic step: answers at once when {@code since} is behind the head (from the ring, or the
     * store below it, A10), otherwise parks {@code waiter} and returns null. A participant's earlier parked pull is
     * then answered empty (every request is answered exactly once, F6). {@code selection} may be null.
     */
    public PullResponse pullOrPark( final String participantId, final String authorKey, final int since,
                                    final Selection selection, final PullWaiter waiter ) {
        Objects.requireNonNull( waiter, "waiter" );
        Runnable displaced = null;
        final PullResponse out;
        lock.lock();
        try {
            final Participant p = roster.get( participantId );
            if( p == null ) {
                return PullResponse.refused( 409, head, REJOIN );
            }
            if( !p.authorKey.equals( authorKey ) ) {
                return PullResponse.refused( 403, head, NOT_YOURS );
            }
            p.touch( clock.instant() );
            if( since < 0 || since > head ) {
                return PullResponse.refused( 400, head, "bad-since" );
            }
            p.confirm( since );
            keepSelection( p, selection );
            if( since < head ) {
                out = catchUp( since, participantId );
            } else {
                displaced = waiters.park( participantId, since, clock.instant().plusSeconds( config.pollTimeoutSeconds() ),
                    waiter, empty() );
                out = null;
            }
        } finally {
            lock.unlock();
        }
        if( displaced != null ) {
            displaced.run();
        }
        return out;
    }

    /** Catch-up for a pull: the ring when it reaches back far enough, else the store; 410 when the store truncated. */
    private PullResponse catchUp( final int since, final String participantId ) {
        if( ring.covers( since ) ) {
            return answer( ring.after( since ), participantId );
        }
        final List< StoredUpdate > log;
        try {
            log = SessionLoader.contiguousSince( store, id, since, head );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): catch-up read from {} failed: {}", id, record.pageName(), since,
                e.getMessage() );
            return PullResponse.refused( 503, head, STORE );
        }
        return log == null ? PullResponse.refused( 410, head, "resync" ) : answer( log, participantId );
    }

    private PullResponse answer( final List< StoredUpdate > updates, final String participantId ) {
        final List< Update > out = new ArrayList<>( updates.size() );
        for( final StoredUpdate u : updates ) {
            out.add( new Update( u.clientId(), u.changes() ) );
        }
        return new PullResponse( 200, out, head, null, roster.selectionsExcept( participantId ) );
    }

    private PullResponse empty() {
        return new PullResponse( 200, List.of(), head, null, Map.of() );
    }

    /**
     * Answers every parked pull after {@code batch} was applied, from memory only: the ring, or the batch itself when it
     * was longer than the ring (amendment: no store read after a commit). Every waiter parked at the previous head.
     */
    private void wakeAll( final List< StoredUpdate > batch, final List< Runnable > completions ) {
        completions.addAll( waiters.drainAll( ( participantId, since ) -> {
            if( ring.covers( since ) ) {
                return answer( ring.after( since ), participantId );
            }
            if( !batch.isEmpty() && batch.get( 0 ).version() <= since + 1 ) {
                return answer( batch.stream().filter( u -> u.version() > since ).toList(), participantId );
            }
            LOG.warn( "collab session {}: parked pull of {} from {} is not in memory; answering resync", id, participantId,
                since );
            return PullResponse.refused( 410, head, "resync" );
        } ) );
    }

    /**
     * The parked pulls whose long-poll timeout has passed, removed; each runnable answers one of them empty. The caller
     * runs them outside any lock.
     */
    public List< Runnable > expireWaiters() {
        lock.lock();
        try {
            final PullResponse timedOut = empty();
            return waiters.expire( clock.instant(), participantId -> timedOut );
        } finally {
            lock.unlock();
        }
    }

    // ---- presence --------------------------------------------------------------------------------------------

    private void keepSelection( final Participant p, final Selection s ) {
        Presence.keep( p, s, ring, doc.length(), head );
    }

    // ---- snapshots and truncation ------------------------------------------------------------------------------

    /** Every {@code snapshot.everyUpdates}: a snapshot, the draft markers, then truncation. Failures only warn (E36). */
    private void maybeSnapshot() {
        if( head - lastSnapshotVersion < config.snapshotEveryUpdates() ) {
            return;
        }
        try {
            store.writeSnapshot( id, new Snapshot( head, metaSeq, record.basePageVersion(), doc, meta, local ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): snapshot at {} failed; retried on the next push: {}", id, record.pageName(),
                head, e.getMessage() );
            return;
        }
        lastSnapshotVersion = head;
        try {
            record = SessionLoader.draftMarkers( record, hasUnpublished(), lastEditAt );
            store.updateSession( record );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): draft markers not stored: {}", id, record.pageName(), e.getMessage() );
        }
        truncateLocked();
    }

    /**
     * A10: truncates the store's log below the lowest version a live participant has confirmed (the head when nobody is
     * here); the store clamps the cut to its newest snapshot at or below it. Returns the cut asked for, or -1 when the
     * store refused (logged).
     */
    public int truncateLog() {
        lock.lock();
        try {
            return truncateLocked();
        } finally {
            lock.unlock();
        }
    }

    private int truncateLocked() {
        final int cut = roster.lowestConfirmed( head );
        try {
            store.truncateUpdatesBelow( id, cut );
            return cut;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): truncation below {} failed: {}", id, record.pageName(), cut, e.getMessage() );
            return -1;
        }
    }

    // ---- publish (naive until Task 4) --------------------------------------------------------------------------

    /**
     * The Phase 0 stub's naive publish, kept until Task 4: requires {@code version == head}, saves {@code doc} under
     * the lock, then commits the base change (a server update when the save filters rewrote the text, plus the
     * snapshot) through {@link CollabStore#commitBaseChange}. A store failure after the page save answers 503 and
     * leaves memory at the old base; Task 4's pending-publish marker makes that recoverable (E39).
     */
    public PublishResponse publish( final String participantId, final String authorKey, final int version,
                                    final String changeNote ) {
        final List< Runnable > completions = new ArrayList<>();
        final PublishResponse out;
        lock.lock();
        try {
            out = publishLocked( participantId, authorKey, version, changeNote, completions );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    private PublishResponse publishLocked( final String participantId, final String authorKey, final int version,
                                           final String changeNote, final List< Runnable > completions ) {
        final Participant p = roster.get( participantId );
        final PublishResponse refused = refusePublish( p, authorKey, version );
        if( refused != null ) {
            return refused;
        }
        final int base = record.basePageVersion();
        final PublishOutcome saved;
        try {
            saved = writer.publish( new PublishRequest( record.pageName(), doc, meta, base, p.authorKey,
                changeNote == null ? "" : changeNote ) );
        } catch( final VersionConflictException e ) {
            LOG.warn( "collab session {}: publish of {} refused: {}", id, record.pageName(), e.getMessage() );
            return new PublishResponse( 409, base, STALE, false );
        } catch( final WikiException e ) {
            LOG.warn( "collab session {}: publish of {} refused: {}", id, record.pageName(), e.getMessage() );
            return new PublishResponse( 422, base, "writer-refused", false );
        }
        return commitPublished( saved, completions );
    }

    /** The answers a publish gets before anything is saved, or null when it may go ahead. */
    private PublishResponse refusePublish( final Participant p, final String authorKey, final int version ) {
        final int base = record.basePageVersion();
        if( p == null ) {
            return new PublishResponse( 409, base, REJOIN, false );
        }
        if( !p.authorKey.equals( authorKey ) ) {
            return new PublishResponse( 403, base, NOT_YOURS, false );
        }
        if( version >= 0 && version <= head ) {
            p.confirm( version );
        }
        if( version != head ) {
            return new PublishResponse( 409, base, STALE, false );
        }
        if( !hasUnpublished() ) {
            return new PublishResponse( 200, base, null, true );
        }
        return doc.isEmpty() ? new PublishResponse( 400, base, "empty", false ) : null;
    }

    /**
     * After the page save: the new base, with a server update when the save filters rewrote the text, committed with
     * its snapshot in one store step; memory follows only when that step succeeded.
     */
    private PublishResponse commitPublished( final PublishOutcome saved, final List< Runnable > completions ) {
        final String newBase = CollabText.toLf( saved.savedBody() );
        final List< StoredUpdate > extra = newBase.equals( doc ) ? List.of()
            : List.of( new StoredUpdate( head + 1, StoredUpdate.SERVER, SERVER_CLIENT, null,
                TextDiff.changeSet( doc, newBase ) ) );
        final SessionRecord updated = SessionLoader.draftMarkers( record.withBase( saved.version(), newBase, saved.savedMeta(),
            CollabText.contentHash( newBase ) ), false, lastEditAt );
        try {
            store.commitBaseChange( id, extra, new Snapshot( head + extra.size(), metaSeq, saved.version(), newBase,
                saved.savedMeta(), ChangeSet.empty( newBase.length() ) ), updated );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: {} was saved as version {} but the base change was not stored: {}", id,
                record.pageName(), saved.version(), e.getMessage() );
            return new PublishResponse( 503, record.basePageVersion(), STORE, false );
        }
        if( !extra.isEmpty() ) {
            applyAccepted( extra, newBase );
            wakeAll( extra, completions );
        }
        record = updated;
        meta = MetaJson.normalise( saved.savedMeta() );
        local = ChangeSet.empty( newBase.length() );
        lastSnapshotVersion = head;
        oversize.update( doc, true );
        return new PublishResponse( 200, saved.version(), null, false );
    }

    // ---- state -----------------------------------------------------------------------------------------------

    private boolean hasUnpublished() {
        return !local.empty() || !meta.equals( MetaJson.normalise( record.baseMeta() ) );
    }

    public View view() {
        lock.lock();
        try {
            final Map< String, Selection > selections = roster.selectionsExcept( null );
            return new View( id, record.pageName(), doc, head, record.baseBody(), local, record.basePageVersion(),
                ring.asList(), oversize.active(), oversize.ceiling(), oversize.maxPageBytes(), metaSeq,
                Collections.unmodifiableMap( new LinkedHashMap<>( meta ) ), lastEditAt,
                Collections.unmodifiableMap( selections ), roster.size() );
        } finally {
            lock.unlock();
        }
    }
}
