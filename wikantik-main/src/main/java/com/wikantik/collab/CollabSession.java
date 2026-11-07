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
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.LeaveResponse;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
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
import java.util.function.Function;

/**
 * One page's live collaboration session: the central authority of {@code @codemirror/collab} (docs/MultiEdit.MD
 * section 7). Join, push (7.2), publish (7.3, {@link PublishFlow}), external merge (7.4, {@link ExternalMerge}), pull
 * with long-poll parking (7.5, {@link Answers}), the in-memory ring with catch-up from the store (7.6, A10), snapshots,
 * log truncation and reload (7.7, E37). The state lives in a {@link Draft}.
 *
 * <p><b>Concurrency.</b> One {@link ReentrantLock} guards all state. The store is written while it is held (store
 * first, then memory, so both keep one order, section 12.4). Parked pulls ({@link PullWaiter}) are always completed
 * after the lock is released, from memory or the ring only: no store read follows a commit. A second lock,
 * {@code baseLock}, serialises every change of the base page version (publish start and completion, external merges,
 * the pending-publish rule) together with the page-store reads and diffs they need; it is always taken before the
 * session lock and never while holding it, so pushes and pulls are never held up by a page-store read.</p>
 *
 * <p><b>Persistence.</b> {@link #create} writes the session and a version-0 snapshot; every base change goes through
 * {@link CollabStore#commitBaseChange}, which writes a snapshot of the live head with it; and a snapshot is written
 * every {@code snapshot.everyUpdates} accepted updates. {@link #load} rebuilds doc, head, local and the ring from the
 * latest snapshot plus the log after it, then finishes or clears a pending publish (A2, E39) and merges the page
 * versions saved meanwhile (lazy reconciliation, E26). Participants and parked pulls are memory only: after a reload
 * everyone rejoins, under participant and client ids that carry a per-load tag (clock millis plus a per-store load
 * count), so a straggler request from before a restart can never be taken for a participant created after it.</p>
 *
 * <p><b>Publish</b> (section 7.3 with A1-A3, A5). {@link #startPublish} answers what it can before any save, reconciles
 * by content hash (the A1 backstop), snapshots V, S_V, M_V on base B and stores the pending-publish marker; the page
 * save then runs with no session lock held while editing continues; {@link #finishPublish} maps the save filters'
 * rewrite N over the edits W made during the save and commits the new base. While a publish is in its save, external
 * changes are queued and merged when it ends, each version against its predecessor. A save that persisted but whose
 * completion was lost is claimed only when the saved version's text hash and author are the publish's
 * ({@link #settle}, {@link #load}); any other version is an external write. After every publish and merge each
 * participant's {@code edit} is re-checked and those who lost it are evicted.</p>
 *
 * <p><b>Presence</b> (A12, ruling P7) rides on push and pull: a push without updates only records the selection and
 * skips the version check. Joins, leaves and selection changes move {@code presenceSeq}; a pull whose
 * {@code presenceSince} is older gets the full map of the other participants (display name from {@link CollabIdentity},
 * colour by join order) at once, and parked pulls are woken with it. Meta ops and the event stream
 * ({@code published}, {@code publish-failed}, {@code external-change}, {@code oversize}) ride on pull the same way
 * ({@link PullCursor}).</p>
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
    static final String NOT_SYNCED = "not-synced";
    static final String MERGED_RETRY = "publish-merged-retry";
    static final String IN_PROGRESS = "publish-in-progress";
    /** Loads per store, for the id tag (see the class comment). */
    private static final Map< CollabStore, AtomicInteger > LOADS = Collections.synchronizedMap( new WeakHashMap<>() );

    /** A read-only copy of the session state, for invariant checks, the registry and tests. */
    public record View( UUID id, String pageName, String doc, int head, String baseBody, ChangeSet local,
                        int basePageVersion, List< StoredUpdate > ring, boolean oversize, long oversizeCeiling,
                        long maxPageBytes, long metaSeq, Map< String, Object > meta, Instant lastEditAt,
                        Map< String, Selection > selections, int participants, long presenceSeq, boolean publishPending,
                        boolean reconcilePending ) {
    }

    private final UUID id;
    private final CollabPageWriter writer;
    private final CollabPermissions permissions;
    private final Clock clock;
    private final CollabConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    /** Serialises base changes; taken before {@link #lock}, never while holding it. */
    private final ReentrantLock baseLock = new ReentrantLock();
    private final Draft d;
    private final Roster roster;
    private final Answers answers;
    private final PublishFlow flow;
    /** Created here and not yet joined: the first join may seed initial text (A11). */
    private boolean fresh;

    private CollabSession( final SessionRecord record, final CollabPorts ports, final CollabConfig config,
                           final long maxPageBytes ) {
        this.id = record.id();
        this.writer = ports.writer();
        this.permissions = ports.permissions();
        this.clock = ports.clock();
        this.config = Objects.requireNonNull( config, "config" );
        this.d = new Draft( record, ports.store(), maxPageBytes, config.historyInMemoryUpdates() );
        final int load = LOADS.computeIfAbsent( ports.store(), k -> new AtomicInteger() ).incrementAndGet();
        this.roster = new Roster( Long.toString( clock.millis(), 36 ) + "." + load + "-", ports.identity(),
            Duration.ofSeconds( config.presenceTimeoutSeconds() ) );
        this.answers = new Answers( d, roster, new Waiters( id ) );
        this.flow = new PublishFlow( d, lock, writer, ports.identity(), answers,
            new ExternalMerge( d, lock, writer, ports.identity(), answers ) );
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
    public static CollabSession create( final SessionRecord rec, final CollabPorts ports, final CollabConfig config,
                                        final long maxPageBytes ) {
        final SessionRecord stored = SessionLoader.create( rec, ports.store() );
        final CollabSession s = new CollabSession( stored, ports, config, maxPageBytes );
        s.d.doc = stored.baseBody();
        s.d.local = ChangeSet.empty( s.d.doc.length() );
        s.d.meta = MetaJson.normalise( stored.baseMeta() );
        s.fresh = true;
        s.d.oversize.update( s.d.doc, true );
        return s;
    }

    /**
     * Rebuilds a session from the store (section 7.7, E37): doc and head from the latest snapshot plus the log after it,
     * meta from the snapshot plus the meta ops after it, local from the snapshot's local composed with the log (or
     * {@code TextDiff.changeSet(baseBody, doc)} when the snapshot belongs to another base), the ring from the log's tail.
     * Then the pending-publish rule (A2, E39) and lazy reconciliation with the page's later versions, in version order
     * (E26); a failure there is logged and left for {@link #settle}.
     *
     * @throws IllegalStateException when the session or its snapshot is missing, or the log is not contiguous from the
     *                               snapshot to the recorded head
     */
    public static CollabSession load( final UUID id, final CollabPorts ports, final CollabConfig config,
                                      final long maxPageBytes ) {
        final SessionLoader.Loaded l = SessionLoader.load( id, ports.store(), config.historyInMemoryUpdates() );
        final CollabSession s = new CollabSession( l.record(), ports, config, maxPageBytes );
        final Draft d = s.d;
        d.doc = l.doc();
        d.head = l.head();
        d.local = l.local();
        d.meta = MetaJson.normalise( l.meta() );
        d.metaSeq = l.record().metaSeq();
        d.lastEditAt = l.record().lastEditAt();
        d.lastSnapshotVersion = l.snapshotVersion();
        d.ring.addAll( l.ring() );
        d.feed.addMetaOps( ports.store().metaOpsSince( id, Math.max( 0, d.metaSeq - config.historyInMemoryUpdates() ) ) );
        d.oversize.update( d.doc, true );
        s.settle();
        return s;
    }

    // ---- identity --------------------------------------------------------------------------------------------

    public UUID id() {
        return id;
    }

    public String pageName() {
        lock.lock();
        try {
            return d.pageName();
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
        final List< Runnable > completions = new ArrayList<>();
        final JoinResponse out;
        lock.lock();
        try {
            if( fresh ) {
                d.seed( r, clock.instant() );
                fresh = false;
            }
            final Participant p = roster.add( r, clock.instant(), d.head, config.pushesPerSecond() );
            answers.presenceChanged( p.id, completions );
            out = new JoinResponse( id, p.id, p.clientId, d.head, d.doc, d.metaSeq, d.meta, d.record.basePageVersion(),
                roster.peers(), d.hasUnpublished(), d.lastEditAt );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    /**
     * The participant's own leave (section 6.2): only its author may leave it (A6, ruling P5; participant ids are visible
     * to every joiner). 409 {@code rejoin} when it is unknown or already gone. Its parked pull is answered.
     */
    public LeaveResponse leave( final String participantId, final String authorKey ) {
        final List< Runnable > completions = new ArrayList<>();
        final LeaveResponse out;
        lock.lock();
        try {
            final Gate.Refusal refused = Gate.identity( roster.get( participantId ), authorKey );
            if( refused == null ) {
                removeLocked( participantId, completions );
                out = LeaveResponse.OK;
            } else {
                out = new LeaveResponse( refused.status(), refused.reason() );
            }
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    /**
     * Server-side removal with no author check, for the registry and the permission re-check only (presence timeout,
     * revoked permission, deleted user): never call it on behalf of a request. Unknown ids are ignored.
     */
    public void evict( final String participantId ) {
        final List< Runnable > completions = new ArrayList<>();
        lock.lock();
        try {
            removeLocked( participantId, completions );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
    }

    private void removeLocked( final String participantId, final List< Runnable > completions ) {
        if( roster.remove( participantId, clock.instant() ) == null ) {
            return;
        }
        final Runnable parked = answers.removeParked( participantId );
        if( parked != null ) {
            completions.add( parked );
        }
        answers.presenceChanged( participantId, completions );
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
        final Instant now = clock.instant();
        final Gate.Refusal refused = Gate.push( p, authorKey, now, config.pushesPerSecond(),
            SessionRecord.FROZEN.equals( d.record.status() ) );
        if( refused != null ) {
            return PushResponse.refused( refused.status(), d.head, refused.reason() );
        }
        Gate.confirm( p, version, d.head );
        if( updates.isEmpty() ) {
            // presence-only push (ruling P7): no version check, the selection is placed by its own version
            if( keepSelection( p, selection ) ) {
                answers.presenceChanged( p.id, completions );
            }
            return PushResponse.accepted( d.head );
        }
        if( version != d.head ) {
            return PushResponse.refused( 409, d.head, STALE );
        }
        final PushCheck check = PushCheck.of( p, updates, d.doc, d.head, d.oversize.maxPageBytes() );
        if( check.refusal() != null ) {
            return PushResponse.refused( check.refusal().status(), d.head, check.refusal().reason() );
        }
        final List< StoredUpdate > accepted = check.accepted();
        try {
            d.store.appendUpdates( id, accepted );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): push from {} not stored, nothing applied: {}", id, d.pageName(),
                participantId, e.getMessage() );
            return PushResponse.refused( 503, d.head, STORE );
        }
        d.applyAccepted( accepted, check.next() );
        d.lastEditAt = now;
        d.oversize.update( d.doc, false );
        final Selection own = Presence.afterOwnPush( selection, version, accepted.size(), d.doc.length(), d.head );
        if( own != null ? Presence.set( p, own ) : keepSelection( p, selection ) ) {
            d.presenceSeq++;
        }
        if( d.snapshotDue( config.snapshotEveryUpdates() ) ) {
            d.snapshot( roster.lowestConfirmed( d.head ) );
        }
        answers.wakeAll( accepted, completions );
        return PushResponse.accepted( d.head );
    }

    // ---- pull ------------------------------------------------------------------------------------------------

    /**
     * Section 7.5 as one atomic step: answers at once when {@code since} is behind the head (from the ring, or the
     * store below it, A10), otherwise parks {@code waiter} and returns null. A participant's earlier parked pull is
     * then answered empty (every request is answered exactly once, F6). {@code selection} may be null.
     */
    public PullResponse pullOrPark( final String participantId, final String authorKey, final int since,
                                    final Selection selection, final PullWaiter waiter ) {
        return pullOrPark( participantId, authorKey, since, Long.MAX_VALUE, selection, waiter );
    }

    /**
     * As {@link #pullOrPark(String, String, int, Selection, PullWaiter)}, with the presence cursor: a pull at head whose
     * {@code presenceSince} is older than the presence sequence (not counting its own selection change) answers at once
     * with the presence map; a parked one is woken when another participant's presence changes.
     */
    public PullResponse pullOrPark( final String participantId, final String authorKey, final int since,
                                    final long presenceSince, final Selection selection, final PullWaiter waiter ) {
        return pullOrPark( participantId, authorKey, PullCursor.text( since, presenceSince ), selection, waiter );
    }

    /**
     * The full pull (section 6.2 as amended): text from {@code cursor.since()}, and the meta ops, presence and events
     * after their cursors. Answers at once when any stream is ahead of its cursor, else parks.
     */
    public PullResponse pullOrPark( final String participantId, final String authorKey, final PullCursor cursor,
                                    final Selection selection, final PullWaiter waiter ) {
        Objects.requireNonNull( cursor, "cursor" );
        Objects.requireNonNull( waiter, "waiter" );
        final int since = cursor.since();
        final List< Runnable > completions = new ArrayList<>();
        final PullResponse out;
        lock.lock();
        try {
            final Participant p = roster.get( participantId );
            final Gate.Refusal refused = Gate.identity( p, authorKey );
            if( refused != null ) {
                return PullResponse.refused( refused.status(), d.head, refused.reason() );
            }
            p.touch( clock.instant() );
            if( since < 0 || since > d.head ) {
                return PullResponse.refused( 400, d.head, "bad-since" );
            }
            p.confirm( since );
            final long seen = d.presenceSeq;
            if( keepSelection( p, selection ) ) {
                answers.presenceChanged( participantId, completions );
            }
            if( since < d.head ) {
                out = answers.catchUp( cursor, participantId );
            } else if( answers.ahead( cursor, seen ) ) {
                out = answers.answer( List.of(), participantId, cursor );
            } else {
                answers.park( participantId, cursor, clock.instant().plusSeconds( config.pollTimeoutSeconds() ), waiter,
                    completions );
                out = null;
            }
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    /**
     * The parked pulls whose long-poll timeout has passed, removed; each runnable answers one of them empty. The caller
     * runs them outside any lock.
     */
    public List< Runnable > expireWaiters() {
        lock.lock();
        try {
            return answers.expire( clock.instant() );
        } finally {
            lock.unlock();
        }
    }

    private boolean keepSelection( final Participant p, final Selection s ) {
        return Presence.keep( p, s, d.ring, d.doc.length(), d.head );
    }

    /**
     * A10: truncates the store's log below the lowest version a live participant has confirmed (the head when nobody is
     * here), and never above a pending publish's version, so S_V stays rebuildable for the pending-publish rule (E39);
     * the store clamps the cut to its newest snapshot at or below it. Returns the cut asked for, or -1 when the store
     * refused (logged).
     */
    public int truncateLog() {
        lock.lock();
        try {
            return d.truncate( roster.lowestConfirmed( d.head ) );
        } finally {
            lock.unlock();
        }
    }

    // ---- publish (section 7.3) ---------------------------------------------------------------------------------

    /**
     * Section 7.3 in one call: {@link #startPublish}, the page save with no session lock held, then
     * {@link #finishPublish} or {@link #failPublish}. The server publishes the head, which may include colleagues' last
     * keystrokes; {@code version} is the publisher's synced version and must be one the session issued.
     */
    public PublishResponse publish( final String participantId, final String authorKey, final int version,
                                    final String changeNote ) {
        final PublishStart st = startPublish( participantId, authorKey, version, changeNote );
        if( st.response() != null ) {
            return st.response();
        }
        final PublishOutcome saved;
        try {
            saved = writer.publish( st.ticket().request() );
        } catch( final WikiException | RuntimeException e ) {
            return failPublish( st.ticket(), e );
        } catch( final Error e ) {
            // a filter overflowed the stack, memory ran out, a class failed to link: the publish still ends
            abandonPublish( st.ticket(), e );
            throw e;
        }
        return finishPublish( st.ticket(), saved );
    }

    /**
     * The first half of a publish: the identity check, 423 {@code publish-in-progress} while another publish runs (E18),
     * 409 {@code not-synced} for a version the session never issued; then the pending-publish rule for an earlier
     * unfinished publish, the A1 backstop (409 {@code publish-merged-retry} when an unseen outside change was merged),
     * 200 {@code unchanged} (E21), 400 {@code empty} (E1), 413 {@code oversize}; else the pending-publish marker is
     * stored and the ticket returned. The caller must end a ticket with {@link #finishPublish} or {@link #failPublish}.
     */
    public PublishStart startPublish( final String participantId, final String authorKey, final int version,
                                      final String changeNote ) {
        final Participant p;
        lock.lock();
        try {
            p = roster.get( participantId );
            final Gate.Refusal refused = Gate.identity( p, authorKey );
            if( refused != null ) {
                return flow.answered( refused.status(), refused.reason() );
            }
            Gate.confirm( p, version, d.head );
            if( d.publishing ) {
                return flow.answered( 423, IN_PROGRESS );
            }
            if( version < 0 || version > d.head ) {
                return flow.answered( 409, NOT_SYNCED );
            }
            d.publishing = true;
        } finally {
            lock.unlock();
        }
        final List< Runnable > completions = new ArrayList<>();
        final String before = baseKey();
        PublishStart out = null;
        baseLock.lock();
        try {
            out = flow.start( p, changeNote == null ? "" : changeNote, completions );
        } finally {
            baseLock.unlock();
            if( out == null || out.response() != null ) {
                endPublish();
            }
        }
        completions.forEach( Runnable::run );
        if( !before.equals( baseKey() ) ) {
            recheckEdit();   // the A1 backstop merged an outside write (A5)
        }
        return out;
    }

    /** The base page version and content hash, to tell whether a step changed the base. */
    private String baseKey() {
        lock.lock();
        try {
            return d.baseKey();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The second half of a successful save (section 7.3): N (the filters' rewrite of S_V) is diffed outside the lock, W
     * (the edits made during the save) composed, {@code N.map(W)} appended as a server update, {@code local = W.map(N)}
     * on the new base, filter-made meta changes folded as server meta ops (stored before the base change), and the base
     * change committed with a snapshot of the live head. A store failure answers 503 and leaves the marker: the
     * pending-publish rule finishes it later.
     *
     * @throws IllegalStateException when {@code ticket} is not the publish in progress
     */
    public PublishResponse finishPublish( final PublishStart.Ticket ticket, final PublishOutcome saved ) {
        Objects.requireNonNull( saved, "saved" );
        return endTicket( ticket, completions -> flow.finish( ticket, saved, completions ) );
    }

    /**
     * A save that threw. If the page holds version B+1 by the publisher with S_V's text, the save persisted before it
     * failed and the publish is finished (A2); another version B+1 is an outside write, merged, and the answer is 409
     * {@code publish-merged-retry} (E20), as for a version conflict; otherwise the draft is unchanged, the others get a
     * {@code publish-failed} event, and the answer is 422 {@code writer-refused} carrying the exception (E19), or 500
     * {@code writer-failed} for a runtime exception.
     *
     * @throws IllegalStateException when {@code ticket} is not the publish in progress
     */
    public PublishResponse failPublish( final PublishStart.Ticket ticket, final Exception failure ) {
        Objects.requireNonNull( failure, "failure" );
        return endTicket( ticket, completions -> flow.failed( ticket, failure, completions ) );
    }

    /**
     * Ends a ticket whose save threw something that is not an {@link Exception} (a {@link StackOverflowError} from a
     * filter, say), or that its driver gives up on: it is handled as a save that failed with a runtime exception, so a
     * version it did persist is still claimed or merged, the publishing flag is reset and queued merges run. Answers 500
     * {@code writer-failed} unless the save turns out to have persisted. The caller rethrows an {@link Error}.
     *
     * @throws IllegalStateException when {@code ticket} is not the publish in progress
     */
    public PublishResponse abandonPublish( final PublishStart.Ticket ticket, final Throwable cause ) {
        Objects.requireNonNull( cause, "cause" );
        return failPublish( ticket, PublishFlow.asException( cause ) );
    }

    private PublishResponse endTicket( final PublishStart.Ticket t, final Function< List< Runnable >, PublishResponse > body ) {
        lock.lock();
        try {
            if( d.ticket == null || d.ticket != t ) {
                throw new IllegalStateException( "not the publish in progress on " + d.pageName() );
            }
        } finally {
            lock.unlock();
        }
        final List< Runnable > completions = new ArrayList<>();
        final String before = baseKey();
        final PublishResponse out;
        baseLock.lock();
        try {
            out = body.apply( completions );
        } catch( final RuntimeException | Error e ) {
            // the post-save half broke: the marker (if the save persisted) is left for settle(), and it is owed
            LOG.warn( "collab session {}: ending the publish of {} failed; settled later: {}", id, d.pageName(), e.toString() );
            flow.owe();
            throw e;
        } finally {
            baseLock.unlock();
            completions.forEach( Runnable::run );
            endPublish();
        }
        if( out.status() == 200 || !before.equals( baseKey() ) ) {
            recheckEdit();   // after a publish, or a merge of an outside write found by the failed save (A5)
        }
        return out;
    }

    /** Clears the publishing flag and runs the external merges queued meanwhile (A3). */
    private void endPublish() {
        final boolean drain;
        lock.lock();
        try {
            d.publishing = false;
            d.ticket = null;
            drain = d.mergeQueued;
            d.mergeQueued = false;
        } finally {
            lock.unlock();
        }
        if( drain ) {
            settle();
        }
    }

    // ---- external writes (section 7.4) -----------------------------------------------------------------------------

    /**
     * Section 7.4: merges the page's versions after the base up to {@code latest}, each diffed against its predecessor,
     * detected by content hash (A1), so a repeated or older event merges nothing and an overwrite without a new version
     * is still merged. While a publish is in its save the change is queued and merged when the publish ends (A3). Called
     * by the page listener (Phase 2), by {@link #load} and by publish (the backstop). Participants who lost {@code edit}
     * are evicted afterwards (A5). A failure is logged and retried by {@link #settle}.
     */
    public void reconcile( final PageState latest ) {
        reconcileTo( Objects.requireNonNull( latest, "latest" ) );
    }

    /**
     * Retries what the session owes the page store: a queued or failed merge (against the page's latest version) and a
     * pending publish whose completion was lost. The registry calls it on its tick.
     */
    public void settle() {
        final Optional< PageState > latest;
        try {
            latest = writer.latest( pageName() );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: reading the latest page to settle failed: {}", id, e.getMessage() );
            flow.owe();
            return;
        }
        reconcileTo( latest.orElse( null ) );
    }

    private void reconcileTo( final PageState target ) {
        if( queuedBehindPublish() ) {
            return;
        }
        final List< Runnable > completions = new ArrayList<>();
        boolean changed = false;
        baseLock.lock();
        try {
            if( !queuedBehindPublish() ) {
                changed = flow.reconcile( target, completions );
            }
        } finally {
            baseLock.unlock();
        }
        completions.forEach( Runnable::run );
        if( changed ) {
            recheckEdit();
        }
    }

    /** True (and queued) when a publish is running: its end merges (A3). */
    private boolean queuedBehindPublish() {
        lock.lock();
        try {
            return d.queueIfPublishing();
        } finally {
            lock.unlock();
        }
    }

    /** A5: every participant's {@code edit} is re-checked after a publish or merge; those who lost it are evicted. */
    private void recheckEdit() {
        final List< Participant > all;
        final String page;
        lock.lock();
        try {
            all = List.copyOf( roster.all() );
            page = d.pageName();
        } finally {
            lock.unlock();
        }
        EditRecheck.lost( id, all, permissions, page ).forEach( this::evict );
    }

    // ---- state -----------------------------------------------------------------------------------------------

    public View view() {
        lock.lock();
        try {
            return new View( id, d.pageName(), d.doc, d.head, d.record.baseBody(), d.local, d.record.basePageVersion(),
                d.ring.asList(), d.oversize.active(), d.oversize.ceiling(), d.oversize.maxPageBytes(), d.metaSeq,
                Collections.unmodifiableMap( new LinkedHashMap<>( d.meta ) ), d.lastEditAt,
                Collections.unmodifiableMap( roster.selectionsExcept( null ) ), roster.size(), d.presenceSeq,
                d.publishPending(), d.mergeQueued || d.mergeOwed );
        } finally {
            lock.unlock();
        }
    }
}
