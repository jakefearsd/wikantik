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
import com.wikantik.collab.model.DraftSummary;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.LeaveResponse;
import com.wikantik.collab.model.MetaEdit;
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
 * <p><b>Presence</b> (A12, ruling P7) rides on push and pull: a push without updates only records the selection (and
 * any meta edits, A14) and skips the version check. Joins, leaves and selection changes move {@code presenceSeq}; a
 * pull whose {@code presenceSince} is older gets the full map of the other participants (display name from
 * {@link CollabIdentity}, colour by join order) at once, and parked pulls are woken with it. Meta ops and the event stream
 * ({@code published}, {@code publish-failed}, {@code external-change}, {@code oversize}) ride on pull the same way
 * ({@link PullCursor}).</p>
 *
 * <p><b>Lifecycle</b> (Phase 2, {@link Lifecycle}): joins, leaves, timeouts and evictions emit participant events; an
 * eviction carries a reason ({@link #LOST_EDIT}, {@link #SIGNED_OUT}, {@link #ACCOUNT_DELETED}) and leaves a bounded
 * tombstone, so the participant's later calls answer 403 {@code evicted} with that reason. {@link #rekey} follows a
 * rename ({@code page-renamed}; former names still resolve) and {@link #freeze} a delete ({@code page-deleted}): a
 * frozen draft refuses pushes and discards (409 {@code frozen}), serves pulls, and is published (recreating the page,
 * base version 0) only by a publisher with {@code createPages} (403 {@code no-create}); a page that exists again
 * unfreezes it as one {@code external-change} with {@code recreated: true}.</p>
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
    /** 409 reason: the page was deleted under the draft. */
    static final String FROZEN = "frozen";
    /** 403 reason: a frozen draft is published (recreating its page) only with {@code createPages}. */
    static final String NO_CREATE = "no-create";
    /** 403 reason (and event type) of an evicted participant; the answer's {@code detail} names why. */
    public static final String EVICTED = "evicted";
    /** Eviction reason: the participant no longer holds {@code edit} on the page (A5). */
    public static final String LOST_EDIT = "lost-edit";
    /** Eviction reason: the participant's wiki session logged out or expired (A13). */
    public static final String SIGNED_OUT = "signed-out";
    /** Eviction reason: the participant's account was deleted. */
    public static final String ACCOUNT_DELETED = "account-deleted";
    /** Loads per store, for the id tag (see the class comment). */
    private static final Map< CollabStore, AtomicInteger > LOADS = Collections.synchronizedMap( new WeakHashMap<>() );

    /**
     * A read-only copy of the session state, for invariant checks, the registry and tests. {@code contributorKeys} are
     * the author keys with contributions since the last publish (first-contribution order; never shown to users), and
     * {@code unpublished} whether the draft differs from its base.
     */
    public record View( UUID id, String pageName, String doc, int head, String baseBody, ChangeSet local,
                        int basePageVersion, List< StoredUpdate > ring, boolean oversize, long oversizeCeiling,
                        long maxPageBytes, long metaSeq, Map< String, Object > meta, Instant lastEditAt,
                        Map< String, Selection > selections, int participants, long presenceSeq, boolean publishPending,
                        boolean reconcilePending, List< String > contributorKeys, boolean unpublished ) {
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
    private final SessionPush pusher;
    private final Lifecycle life;
    /** The audit and metrics ports (section 9, 12.3); guarded, so neither throws. */
    private final CollabAudit audit;
    private final CollabMetrics metrics;
    /** Created here and not yet joined: the first join may seed initial text (A11). */
    private boolean fresh;

    private CollabSession( final SessionRecord record, final CollabPorts ports, final CollabConfig config,
                           final long maxPageBytes ) {
        this.id = record.id();
        this.writer = ports.writer();
        this.permissions = ports.permissions();
        this.clock = ports.clock();
        this.audit = ports.audit();
        this.metrics = ports.metrics();
        this.config = Objects.requireNonNull( config, "config" );
        this.d = new Draft( record, ports.store(), maxPageBytes, config.historyInMemoryUpdates() );
        final int load = LOADS.computeIfAbsent( ports.store(), k -> new AtomicInteger() ).incrementAndGet();
        this.roster = new Roster( Long.toString( clock.millis(), 36 ) + "." + load + "-", ports.identity(),
            Duration.ofSeconds( config.presenceTimeoutSeconds() ), Duration.ofMinutes( config.memoryIdleEvictMinutes() ) );
        this.answers = new Answers( d, roster, new Waiters( id ), metrics );
        this.life = new Lifecycle( d, roster, answers, audit );
        this.pusher = new SessionPush( d, roster, answers, config );
        this.flow = new PublishFlow( d, lock, writer, ports.identity(), answers,
            new ExternalMerge( d, lock, writer, ports.identity(), answers, metrics ), audit );
        d.follow = ( oldHead, applied, newHead ) -> Presence.follow( roster.all(), oldHead, applied, newHead );
        d.names = ports.identity()::displayName;
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
            null, null, null, null, createdBy, clock.instant(), null );
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
        d.contributors = l.contributors();
        d.ring.addAll( l.ring() );
        d.feed.addMetaOps( ports.store().metaOpsSince( id, Math.max( 0, d.metaSeq - config.historyInMemoryUpdates() ) ) );
        d.oversize.update( d.doc, true );
        if( d.frozen() ) {
            s.life.announceDeleted( new ArrayList<>() );   // nobody is parked yet; a joiner is shown it (A13)
        }
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

    // ---- join and leave --------------------------------------------------------------------------------------

    /**
     * Adds a participant bound to {@code r.authorKey()} (A6). The first join into a freshly created session applies
     * {@code initialText}/{@code initialMeta} (A11) as a server update and meta ops; a store failure while doing so
     * propagates with nothing applied in memory that the store does not hold, and the next join retries.
     */
    public JoinResponse join( final JoinRequest r ) {
        final List< Runnable > completions = new ArrayList<>();
        final JoinResponse out;
        final boolean signedIn = signedInNow( r );
        lock.lock();
        try {
            final Instant now = clock.instant();
            if( fresh ) {
                d.seed( r, now );
                fresh = false;
            }
            life.replaceStale( r, now, completions );
            final Participant p = roster.add( r, now, d.head, config.pushesPerSecond() );
            p.signedInAtJoin( signedIn );
            life.joined( p, completions );
            out = new JoinResponse( id, p.id, p.clientId, d.head, d.doc, d.metaSeq, d.meta, d.record.basePageVersion(),
                roster.peers(), d.hasUnpublished(), d.lastEditAt, life.joinEventSeq(), d.frozen(), d.contributorNames() );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    /** Whether the joiner's wiki session is signed in (ruling Q13); a check that throws counts as not signed in. */
    private boolean signedInNow( final JoinRequest r ) {
        try {
            return permissions.signedIn( r.wikiSession() );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the sign-in check of {} failed at join; a logout will not evict it: {}", id,
                r.authorKey(), e.toString() );
            return false;
        }
    }

    public LeaveResponse leave( final String participantId, final String authorKey ) {
        final List< Runnable > completions = new ArrayList<>();
        final LeaveResponse out;
        lock.lock();
        try {
            final Instant now = clock.instant();
            final Gate.Refusal refused = Gate.identity( roster, participantId, authorKey, now );
            if( refused == null ) {
                life.left( participantId, now, completions );
                out = LeaveResponse.OK;
            } else {
                out = new LeaveResponse( refused.status(), refused.reason(), refused.detail() );
            }
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    /** {@link #evict(String, String)} for {@link #LOST_EDIT}. */
    public void evict( final String participantId ) {
        evict( participantId, LOST_EDIT );
    }

    /**
     * Server-side eviction with no author check, for the registry and the permission re-check only (revoked permission,
     * signed out, deleted user): never call it on behalf of a request. Its later requests answer 403 {@code evicted}
     * with {@code reason} as the detail while its tombstone is kept (the presence timeout); its parked pull is answered
     * so at once; {@code evicted} and {@code participant-left} events are emitted. Unknown ids are ignored.
     */
    public void evict( final String participantId, final String reason ) {
        Objects.requireNonNull( reason, "reason" );
        final List< Runnable > completions = new ArrayList<>();
        lock.lock();
        try {
            life.evict( participantId, reason, clock.instant(), completions );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
    }

    /**
     * The presence timeout for one participant (a simulated or scheduled timeout): it leaves with reason
     * {@code timeout} and no tombstone; its parked pull and next request answer 409 {@code rejoin}. Unknown ids are
     * ignored.
     */
    public void timeOut( final String participantId ) {
        final List< Runnable > completions = new ArrayList<>();
        lock.lock();
        try {
            life.timeOut( participantId, clock.instant(), completions );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
    }

    /** True while {@code participantId} is a live participant (an evicted or departed one is not). */
    public boolean hasParticipant( final String participantId ) {
        lock.lock();
        try {
            return roster.get( participantId ) != null;
        } finally {
            lock.unlock();
        }
    }

    /** The meta sequence (the HTTP push answer carries it). */
    public long metaSeq() {
        lock.lock();
        try {
            return d.metaSeq;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The draft summary (section 6.2's {@code GET /draft}): base and head bodies and meta, the contributors' public
     * names, unpublished and the last edit, as one snapshot under the lock; the caller diffs. Identity-checked like any
     * request (403 {@code not-your-participant}, 409 {@code rejoin}, 403 {@code evicted} with its reason) and marks the
     * participant seen; neither version-checked nor rate-limited.
     */
    public DraftSummary draftSummary( final String participantId, final String authorKey ) {
        lock.lock();
        try {
            return Gate.summary( roster, participantId, authorKey, clock.instant(), d );
        } finally {
            lock.unlock();
        }
    }

    // ---- discard (section 6.2) ---------------------------------------------------------------------------------

    /**
     * Section 6.2's discard: resets the draft to the latest page version. The page's newer versions are merged first
     * ({@link #settle}), then the draft text is replaced by the base as one server update and the meta restored by server
     * meta ops, committed with a snapshot; a {@code draft-discarded} event names who did it. 403/409 as for any request
     * (A6); 423 {@code publish-in-progress} while a publish runs or its marker is stored; 503 {@code store} when the store
     * refused (nothing changed). {@code version} is the head after the call.
     */
    public PushResponse discard( final String participantId, final String authorKey ) {
        final Participant p;
        lock.lock();
        try {
            final Gate.Refusal refused = Gate.identity( roster, participantId, authorKey, clock.instant() );
            if( refused != null ) {
                return PushResponse.refused( refused.status(), d.head, refused.reason(), refused.detail() );
            }
            p = roster.get( participantId );
            p.touch( clock.instant() );
            final Gate.Refusal busy = Gate.discard( d.frozen(), d.publishPending() );
            if( busy != null ) {
                return PushResponse.refused( busy.status(), d.head, busy.reason() );
            }
        } finally {
            lock.unlock();
        }
        settle();
        final List< Runnable > completions = new ArrayList<>();
        final PushResponse out;
        baseLock.lock();
        try {
            out = new DraftDiscard( d, lock, audit ).reset( p, answers, completions );
        } finally {
            baseLock.unlock();
        }
        completions.forEach( Runnable::run );
        return out;
    }

    // ---- push ------------------------------------------------------------------------------------------------

    public PushResponse push( final String participantId, final String authorKey, final int version,
                              final List< Update > updates ) {
        return push( participantId, authorKey, version, updates, List.of(), null );
    }

    public PushResponse push( final String participantId, final String authorKey, final int version,
                              final List< Update > updates, final Selection selection ) {
        return push( participantId, authorKey, version, updates, List.of(), selection );
    }

    /**
     * Section 7.2 with A14's meta edits ({@link SessionPush}): validates the whole push (text and every meta edit),
     * stores the text and the resolved meta ops in one step, then applies them in memory; on a store failure nothing
     * changes (503, E35); a refusal applies nothing. A push without text skips the version check. Waiters are answered
     * after the lock is released.
     */
    public PushResponse push( final String participantId, final String authorKey, final int version,
                              final List< Update > updates, final List< MetaEdit > metaEdits,
                              final Selection selection ) {
        Objects.requireNonNull( updates, "updates" );
        Objects.requireNonNull( metaEdits, "metaEdits" );
        final Instant started = clock.instant();
        final List< Runnable > completions = new ArrayList<>();
        final PushResponse out;
        lock.lock();
        try {
            out = pusher.push( participantId, authorKey, version, updates, metaEdits, selection, clock.instant(),
                completions );
        } finally {
            lock.unlock();
        }
        completions.forEach( Runnable::run );
        metrics.push( GuardedMetrics.pushResult( out ) );
        metrics.pushLatency( Duration.between( started, clock.instant() ).toNanos() );
        return out;
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
            final Gate.Refusal refused = Gate.identity( roster, participantId, authorKey, clock.instant() );
            if( refused != null ) {
                return PullResponse.refused( refused.status(), d.head, refused.reason(), refused.detail() );
            }
            final Participant p = roster.get( participantId );
            p.touch( clock.instant() );
            if( since < 0 || since > d.head ) {
                return PullResponse.refused( 400, d.head, "bad-since" );
            }
            p.confirm( since );
            final long seen = d.presenceSeq;
            if( Presence.keep( p, selection, d.ring, d.doc.length(), d.head ) ) {
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
        return publish( participantId, authorKey, version, changeNote, null );
    }

    /** As {@link #publish(String, String, int, String)}, saving with the editor's {@code markupSyntax} (null: none). */
    public PublishResponse publish( final String participantId, final String authorKey, final int version,
                                    final String changeNote, final String markupSyntax ) {
        final PublishStart st = startPublish( participantId, authorKey, version, changeNote, markupSyntax );
        if( st.response() != null ) {
            metrics.publish( GuardedMetrics.publishResult( st.response() ) );   // answered before any save
            return st.response();
        }
        final PublishOutcome saved;
        try {
            saved = writer.publish( st.ticket().request() );
        } catch( final WikiException | RuntimeException e ) {
            return failPublish( st.ticket(), e );
        } catch( final Error e ) {
            // a filter overflowed the stack, memory ran out, a class failed to link: the publish still ends
            Suppressing.run( e, () -> abandonPublish( st.ticket(), e ) );
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
        return startPublish( participantId, authorKey, version, changeNote, null );
    }

    /** As {@link #startPublish(String, String, int, String)}; the save request carries {@code markupSyntax}. */
    public PublishStart startPublish( final String participantId, final String authorKey, final int version,
                                      final String changeNote, final String markupSyntax ) {
        final Participant p;
        final boolean frozen;
        lock.lock();
        try {
            final Gate.Refusal refused = Gate.identity( roster, participantId, authorKey, clock.instant() );
            if( refused != null ) {
                return flow.answered( refused.status(), refused.reason(), refused.detail() );
            }
            p = roster.get( participantId );
            frozen = d.frozen();
        } finally {
            lock.unlock();
        }
        // a frozen draft recreates its page: the publisher needs createPages, checked uncached (A5, E28)
        if( EditRecheck.recreateRefused( frozen, id, p, permissions, pageName() ) ) {
            return flow.answered( 403, NO_CREATE );
        }
        lock.lock();
        try {
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
        final String before = flow.baseKey();
        PublishStart out = null;
        baseLock.lock();
        try {
            // frozen here means createPages was checked; a freeze landing after it is answered 409 frozen at the save
            out = flow.start( p, changeNote == null ? "" : changeNote, markupSyntax, frozen, completions );
        } finally {
            baseLock.unlock();
            if( out == null || out.response() != null ) {
                if( flow.endPublish() ) {
                    settle();   // the external merges queued meanwhile (A3)
                }
            }
        }
        completions.forEach( Runnable::run );
        if( !before.equals( flow.baseKey() ) ) {
            recheckEdits();   // the A1 backstop merged an outside write (A5)
        }
        return out;
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
     * A save that threw. If it reported the version it wrote ({@link PublishReadBackException}) and that version is
     * the publisher's, or else if the page holds version B+1 by the publisher with S_V's text, the save persisted
     * before it failed and the publish is finished (A2, ruling D); another version is an outside write, merged, and the
     * answer is 409 {@code publish-merged-retry} (E20), as for a version conflict; otherwise the draft is unchanged,
     * the others get a {@code publish-failed} event, and the answer is 422 {@code writer-refused} carrying the
     * exception (E19), or 500 {@code writer-failed} for a runtime exception.
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
        final String before = flow.baseKey();
        final PublishResponse out;
        baseLock.lock();
        try {
            out = body.apply( completions );
        } catch( final RuntimeException | Error e ) {
            // the post-save half broke: the marker (if the save persisted) is left for settle(), and it is owed
            LOG.warn( "collab session {}: ending the publish of {} failed; settled later: {}", id, d.pageName(), e.toString() );
            flow.owe();
            metrics.publish( CollabMetrics.FAILED );
            throw e;
        } finally {
            baseLock.unlock();
            completions.forEach( Runnable::run );
            if( flow.endPublish() ) {
                settle();   // the external merges queued meanwhile (A3)
            }
        }
        metrics.publish( GuardedMetrics.publishResult( out ) );
        if( out.status() == 200 || !before.equals( flow.baseKey() ) ) {
            // after a publish, or a merge of an outside write found by the failed save (A5)
            Hooks.auditIfOrphaned( this, out, recheckEdits() );
        }
        return out;
    }

    // ---- external writes (section 7.4) -----------------------------------------------------------------------------

    /**
     * Section 7.4: merges the page's versions after the base up to {@code latest}, each diffed against its predecessor,
     * detected by content hash (A1), so a repeated or stale event merges nothing, an overwrite without a new version is
     * still merged, and a page recreated under the draft (its versions restarted below the base) is merged. While a
     * publish is in its save the change is queued and merged when the publish ends (A3). Called by the page listener
     * (Phase 2), by {@link #load} and by publish (the backstop). Participants who lost {@code edit} are evicted afterwards
     * (A5). A failure is logged and retried by {@link #settle}.
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
        if( flow.queuedBehindPublish() ) {
            return;
        }
        final List< Runnable > completions = new ArrayList<>();
        boolean changed = false;
        baseLock.lock();
        try {
            if( !flow.queuedBehindPublish() ) {
                changed = flow.reconcile( target, completions );
            }
        } finally {
            baseLock.unlock();
        }
        completions.forEach( Runnable::run );
        if( changed ) {
            recheckEdits();
        }
    }

    /**
     * A5: every participant's {@code edit} is re-checked (after a publish or merge, and on the registry's cadence); those
     * who lost it are evicted with {@link #LOST_EDIT}. The checks run with no session lock held. Returns how many were
     * found to have lost it.
     */
    public int recheckEdits() {
        final List< Participant > all;
        final String page;
        lock.lock();
        try {
            all = List.copyOf( roster.all() );
            page = d.pageName();
        } finally {
            lock.unlock();
        }
        final List< String > lost = EditRecheck.lost( id, all, permissions, page );
        lost.forEach( pid -> evict( pid, LOST_EDIT ) );
        return lost.size();
    }

    // ---- rename and freeze (section 7.4, A1) -----------------------------------------------------------------------

    /**
     * The page was renamed: the session moves to {@code newPageName} in the store, then in memory, emits
     * {@code page-renamed {to}} and wakes parked pulls; the old name still resolves ({@link #answersTo}). Under the base
     * lock, so no publish completion or merge replaces the record meanwhile.
     *
     * @throws IllegalStateException with the session unchanged, when the store refuses (the new name has a draft)
     */
    public void rekey( final String newPageName ) {
        Objects.requireNonNull( newPageName, "newPageName" );
        Hooks.requireLockOrder( lock );
        Hooks.rekey( this, newPageName ).forEach( Runnable::run );
    }


    /**
     * The page was deleted: the status becomes {@code frozen} in the store and in memory, {@code page-deleted} is emitted
     * and parked pulls are woken. Pushes and discards then answer 409 {@code frozen}, pulls are served, and a publish by
     * someone with {@code createPages} recreates the page. A page that exists again unfreezes it on {@link #settle}.
     */
    public void freeze() {
        Hooks.requireLockOrder( lock );
        Hooks.freeze( this ).forEach( Runnable::run );
    }

    /** True while the draft's page is deleted. */
    public boolean frozen() {
        lock.lock();
        try {
            return d.frozen();
        } finally {
            lock.unlock();
        }
    }

    /** True when {@code pageName} is the session's current page name or one it held before a rename. */
    public boolean answersTo( final String pageName ) {
        lock.lock();
        try {
            return life.answersTo( pageName );
        } finally {
            lock.unlock();
        }
    }

    // ---- state -----------------------------------------------------------------------------------------------

    public View view() {
        lock.lock();
        try {
            return new View( id, d.pageName(), d.doc, d.head, d.record.baseBody(), d.local, d.record.basePageVersion(),
                d.ring.asList(), d.oversize.active(), d.oversize.ceiling(), d.oversize.maxPageBytes(), d.metaSeq,
                Collections.unmodifiableMap( new LinkedHashMap<>( d.meta ) ), d.lastEditAt,
                Collections.unmodifiableMap( roster.selectionsExcept( null ) ), roster.size(), d.presenceSeq,
                d.publishPending(), d.mergeQueued || d.mergeOwed, d.contributors.authors(), d.hasUnpublished() );
        } finally {
            lock.unlock();
        }
    }

    // ---- registry hooks (Task 5) -------------------------------------------------------------------------------

    /**
     * What {@link CollabSessionRegistry} needs from a session beyond the request API: its participants, whether it has
     * unpublished changes, and whether a join replaces a stale entry. Static and nested, apart from the session's own
     * logic; every method takes the session lock.
     */
    static final class Hooks {

        /** One participant as the registry sees it, for presence timeouts, permission re-checks and author eviction. */
        record Member( String participantId, String authorKey, Object wikiSession, Instant lastSeen,
                       boolean signedInAtJoin ) {
        }

        private Hooks() {
        }

        /** The live participants, in join order. */
        static List< Member > members( final CollabSession s ) {
            s.lock.lock();
            try {
                return s.roster.all().stream().map( p -> new Member( p.id, p.authorKey, p.wikiSession, p.lastSeen(),
                    p.signedInAtJoin() ) )
                    .toList();
            } finally {
                s.lock.unlock();
            }
        }

        /**
         * True when the draft differs from its base page version (section 7.7), decided from memory: the stored
         * {@code has_unpublished} flag is written only at snapshot cadence and is never trusted.
         */
        static boolean hasUnpublishedChanges( final CollabSession s ) {
            s.lock.lock();
            try {
                return s.d.hasUnpublished();
            } finally {
                s.lock.unlock();
            }
        }

        /**
         * True while the session owes the page store something only it can finish: a publish in its save or a stored
         * pending-publish marker, or a queued or failed external merge ({@link #settle} retries both). Such a session
         * must stay in memory.
         */
        static boolean owesThePageStore( final CollabSession s ) {
            s.lock.lock();
            try {
                return s.d.publishPending() || s.d.mergeQueued || s.d.mergeOwed;
            } finally {
                s.lock.unlock();
            }
        }

        /**
         * True when the draft is based on a page that existed (base version above 0) and is not frozen: only such a
         * draft is orphaned by its page going missing. A new page's draft (base 0) has no page by design.
         */
        static boolean basedOnAPage( final CollabSession s ) {
            s.lock.lock();
            try {
                return !s.d.frozen() && s.d.record.basePageVersion() > 0;
            } finally {
                s.lock.unlock();
            }
        }

        /**
         * True while this session object's publish is in its save (between start and finish/fail). A reload then would
         * give the page two writers: the reloaded session settling the marker and this object finishing the publish.
         */
        static boolean publishing( final CollabSession s ) {
            s.lock.lock();
            try {
                return s.d.publishing;
            } finally {
                s.lock.unlock();
            }
        }

        /**
         * The presence timeout (E13): participants not seen since {@code horizon} leave ({@code participant-left},
         * reason {@code timeout}) without a tombstone; their next request rejoins (409). Returns how many left.
         */
        static int expirePresence( final CollabSession s, final Instant horizon ) {
            final List< Runnable > completions = new ArrayList<>();
            final int n;
            s.lock.lock();
            try {
                n = s.life.expire( horizon, s.clock.instant(), completions );
            } finally {
                s.lock.unlock();
            }
            completions.forEach( Runnable::run );
            return n;
        }

        /** Everyone leaves without a tombstone (the session leaves memory); parked pulls answer 409 {@code rejoin}. */
        static void dropAll( final CollabSession s ) {
            final List< Runnable > completions = new ArrayList<>();
            s.lock.lock();
            try {
                s.life.dropAll( s.clock.instant(), completions );
            } finally {
                s.lock.unlock();
            }
            completions.forEach( Runnable::run );
        }

        /**
         * Evicts every participant with {@code reason} (a tombstone each; parked pulls answer 403 {@code evicted}) in one
         * step under the session lock, unless a publish is running or its marker is stored: then nobody is evicted and
         * false is returned. No publish can start in between, so a purge never tells clients {@code purged} and then
         * keeps the draft for a publish that started meanwhile. Completions run after the lock is released.
         */
        static boolean evictAllUnlessPublishing( final CollabSession s, final String reason ) {
            final List< Runnable > completions = new ArrayList<>();
            final int n;
            s.lock.lock();
            try {
                if( s.d.publishPending() ) {
                    return false;
                }
                final List< Participant > all = List.copyOf( s.roster.all() );
                for( final Participant p : all ) {
                    s.life.evict( p.id, reason, s.clock.instant(), completions );
                }
                n = all.size();
            } finally {
                s.lock.unlock();
            }
            if( n > 0 ) {
                LOG.info( "collab session {}: {} participant(s) evicted ({})", s.id, n, reason );
            }
            completions.forEach( Runnable::run );
            return true;
        }

        /** Stores the draft markers from memory (the session is leaving memory); a failure only warns. */
        static void storeMarkers( final CollabSession s ) {
            s.lock.lock();
            try {
                s.d.storeMarkers();
            } finally {
                s.lock.unlock();
            }
        }

        /**
         * {@link CollabSession#rekey} under the base lock (a rename must not interleave with a base change), returning
         * the parked-pull completions for the caller to run once it holds no lock (the registry also holds the slot).
         *
         * @throws IllegalStateException with the session unchanged, when the store refuses (the new name has a draft)
         */
        static List< Runnable > rekey( final CollabSession s, final String newPageName ) {
            final List< Runnable > completions = new ArrayList<>();
            s.baseLock.lock();
            try {
                s.lock.lock();
                try {
                    s.life.rekey( newPageName, completions );
                } finally {
                    s.lock.unlock();
                }
            } finally {
                s.baseLock.unlock();
            }
            return completions;
        }

        /** {@link CollabSession#freeze} under the base lock, returning the completions as {@link #rekey} does. */
        static List< Runnable > freeze( final CollabSession s ) {
            final List< Runnable > completions = new ArrayList<>();
            s.baseLock.lock();
            try {
                s.lock.lock();
                try {
                    s.life.freeze( completions );
                } finally {
                    s.lock.unlock();
                }
            } finally {
                s.baseLock.unlock();
            }
            return completions;
        }

        /** The base lock is taken before the session lock, never under it: rename and freeze refuse otherwise. */
        static void requireLockOrder( final ReentrantLock sessionLock ) {
            if( sessionLock.isHeldByCurrentThread() ) {
                throw new IllegalStateException( "collab: rename or freeze called under the session lock" );
            }
        }

        /** For tests: true while the calling thread holds the session lock. */
        static boolean lockHeld( final CollabSession s ) {
            return s.lock.isHeldByCurrentThread();
        }

        /** For tests: true while the calling thread holds the session's base lock. */
        static boolean baseLockHeld( final CollabSession s ) {
            return s.baseLock.isHeldByCurrentThread();
        }

        /**
         * Plan decision 7, after a publish's re-check (A5): when it answered 200, evicted {@code lost} participants and
         * left nobody while unpublished changes remain, the draft is orphaned: audited, not frozen (an administrator
         * can purge it; anyone with {@code edit} can still open it).
         */
        static void auditIfOrphaned( final CollabSession s, final PublishResponse out, final int lost ) {
            if( out.status() != 200 || lost == 0 ) {
                return;
            }
            final boolean orphaned;
            final String page;
            s.lock.lock();
            try {
                orphaned = s.roster.size() == 0 && s.d.hasUnpublished();
                page = s.d.pageName();
            } finally {
                s.lock.unlock();
            }
            if( orphaned ) {
                s.audit.orphaned( s.id, page );
            }
        }

        /**
         * The participant count without the session lock (a volatile the roster keeps), for the participants gauge: a
         * scrape must never wait for a session that is busy.
         */
        static int participantsLockFree( final CollabSession s ) {
            return s.roster.sizeLockFree();
        }

        /** True when {@code r} is a rejoin that replaces its own stale entry (it takes no new seat). */
        static boolean replacesStale( final CollabSession s, final JoinRequest r ) {
            s.lock.lock();
            try {
                return s.roster.staleEntryOf( r ) != null;
            } finally {
                s.lock.unlock();
            }
        }
    }
}
