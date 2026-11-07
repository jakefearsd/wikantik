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

import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.text.ChangeSet;

import java.util.List;
import java.util.Map;

/**
 * The protocol surface the deterministic simulator drives. {@link StubCollabAuthority} implements it for Phase 0;
 * Phase 1's real {@code CollabSession} is adapted to the same surface so every simulated scenario runs against both.
 * Calls are synchronous and single-threaded from the simulator's point of view; "async" answers (parked pulls) arrive
 * through {@link #drainWakeups()}.
 */
public interface SimServer {

    /** One client edit (or, in a pull answer, one accepted update). */
    record Update( String clientId, ChangeSet changes ) {
    }

    /**
     * Result of {@link #join}. Phase 2: the metadata and its sequence, the event cursor the joiner starts from, and
     * whether the draft is frozen (its page deleted). A server without them (the stub) answers empty metadata at 0, 0,
     * false.
     */
    record Joined( String participantId, String clientId, int version, String doc, long metaSeq, Map< String, Object > meta,
                   long eventSeq, boolean frozen ) {
        public Joined {
            meta = meta == null ? Map.of() : meta;
        }

        public Joined( final String participantId, final String clientId, final int version, final String doc ) {
            this( participantId, clientId, version, doc, 0, Map.of(), 0, false );
        }
    }

    /**
     * {@code status}: 200 accepted, 400 rejected, 403 (participant belongs to another author), 409 stale / unknown
     * participant, 413 oversize, 503 store failure. {@code reason} is null on 200; otherwise: 400 {@code rejected};
     * 403 {@code not-your-participant}; 409 {@code stale-base}, {@code rejoin} or {@code frozen} (reserved for Phase 2);
     * 413 {@code oversize}; 503 {@code store} (append failed, nothing applied) or {@code poisoned}. Phase 2: 403
     * {@code evicted} with {@code detail} naming why; {@code metaSeq} is the meta sequence after an accepted push.
     */
    record PushResult( int status, int version, String reason, long metaSeq, String detail ) {
        public PushResult( final int status, final int version, final String reason ) {
            this( status, version, reason, 0, null );
        }
    }

    /**
     * {@code status}: 200, 400 {@code bad-since}, 403 {@code not-your-participant}, 409 {@code rejoin} (unknown
     * participant), 410 {@code resync}, 503 {@code poisoned}. {@code parked} true means the pull is waiting and its answer
     * arrives through {@link #drainWakeups()}. Phase 2: the meta ops after the request's meta cursor up to
     * {@code metaSeq}, the events after its event cursor up to {@code eventSeq}, and 403 {@code evicted} with
     * {@code detail}; a server without them (the stub) answers none.
     */
    record PullResult( int status, List< Update > updates, int version, boolean parked, String reason,
                       List< MetaOp > metaOps, long metaSeq, List< CollabEvent > events, long eventSeq, String detail ) {
        public PullResult {
            metaOps = metaOps == null ? List.of() : metaOps;
            events = events == null ? List.of() : events;
        }

        public PullResult( final int status, final List< Update > updates, final int version, final boolean parked,
                           final String reason ) {
            this( status, updates, version, parked, reason, List.of(), 0, List.of(), 0, null );
        }
    }

    /** A parked pull's eventual answer. */
    record Wakeup( String participantId, PullResult result ) {
    }

    /**
     * {@code status}: 200 ({@code unchanged} true when there was nothing to save, E21), 400 {@code empty}, 403
     * {@code not-your-participant}, 409 {@code rejoin} / {@code stale-base} (the stub) / {@code not-synced} /
     * {@code publish-merged-retry} (the session), 413 {@code oversize}, 422 {@code writer-refused}, 423
     * {@code publish-in-progress}, 500 {@code writer-failed}, 503 {@code store} or {@code poisoned} (including the
     * publish whose post-save failure poisoned the stub).
     */
    record PublishResult( int status, int pageVersion, String reason, boolean unchanged ) {
    }

    /**
     * Joins as {@code authorKey}. The participant is bound to that author (A6): later calls naming it must pass the same
     * {@code authorKey} or get 403 {@code not-your-participant}. {@code priorClientId} (nullable) is the clientID the
     * caller held before: it is reissued when the server still knows it as this author's and no live participant holds
     * it (section 7.2 "same clientID"), so the client keeps its collab state. {@code initialText}/{@code initialMeta}
     * (nullable, A11) are used only by a join that follows the session's creation.
     */
    Joined join( String authorKey, String priorClientId, String initialText, Map< String, Object > initialMeta );

    /** A plain join: no prior clientID, no initial content. */
    default Joined join( final String authorKey ) {
        return join( authorKey, null, null, null );
    }

    PushResult push( String participantId, String authorKey, int version, List< Update > updates );

    PullResult pull( String participantId, String authorKey, int since );

    /**
     * A push with A14's meta edits and A12's piggybacked selection (nullable). The default (the stub) has neither: it
     * ignores the selection and refuses meta edits, which the scheduler never sends it.
     */
    default PushResult push( final String participantId, final String authorKey, final int version,
                             final List< Update > updates, final List< MetaEdit > metaEdits, final Selection selection ) {
        if( !metaEdits.isEmpty() ) {
            throw new UnsupportedOperationException( "this server has no metadata" );
        }
        return push( participantId, authorKey, version, updates );
    }

    /**
     * A pull with all its cursors (text, meta ops, presence, events) and the piggybacked selection (nullable). The
     * default (the stub) serves the text cursor alone.
     */
    default PullResult pull( final String participantId, final String authorKey, final PullCursor cursor,
                             final Selection selection ) {
        return pull( participantId, authorKey, cursor.since() );
    }

    PublishResult publish( String participantId, String authorKey, int version, String note );

    /**
     * A publish as {@link #beginPublish} starts it: answered at once ({@code result}) or held inside the page save
     * ({@code pending}, an opaque handle for {@link #releasePublish}). Exactly one is set.
     */
    record PublishStart( PublishResult result, Object pending ) {
        public PublishStart {
            if( ( result == null ) == ( pending == null ) ) {
                throw new IllegalArgumentException( "exactly one of result and pending is set" );
            }
        }
    }

    /**
     * The pending form of {@link #publish} (Phase 1 plan amendment, Task 4): a server whose publish unlocks during the
     * save (section 7.3) returns a pending handle, and the scheduler's {@code RELEASE_PUBLISH} step completes it later,
     * so pushes, pulls and external saves interleave with a publish inside the save. The default (the stub, whose
     * publish holds its lock across the save) answers at once.
     */
    default PublishStart beginPublish( final String participantId, final String authorKey, final int version,
                                       final String note ) {
        return new PublishStart( publish( participantId, authorKey, version, note ), null );
    }

    /** Completes a publish {@link #beginPublish} left pending: the page save runs now, then the post-save half. */
    default PublishResult releasePublish( final Object pending ) {
        throw new UnsupportedOperationException( "this server has no pending publishes" );
    }

    /**
     * F8 for a publish inside its save when the server crashes: {@code persist} true means the page store committed
     * the save before the process died (the E39 shape), false that it never ran. The server's post-save half never runs.
     */
    default void abandonPublish( final Object pending, final boolean persist ) {
        throw new UnsupportedOperationException( "this server has no pending publishes" );
    }

    /**
     * The event the page listener delivers for an outside save that wrote {@code version} (section 7.4). The default
     * ignores it (the stub has no external merge; its {@code EXTERNAL_SAVE} weight is 0).
     */
    default void externalSaved( final int version ) {
    }

    /** True when the server has no external change queued or owed (a merge that failed and must be retried). */
    default boolean reconciled() {
        return true;
    }

    /**
     * Retries whatever the server owes the page store: a queued or failed merge, an unfinished publish (the
     * pending-publish rule). Production does this on the registry tick; the simulator calls it at quiescence.
     */
    default void settle() {
    }

    /** True when the server merges outside saves ({@link #externalSaved}); the concurrency harness then makes some. */
    default boolean acceptsExternalSaves() {
        return false;
    }

    /** Result of {@link #leave}: 200; 403 {@code not-your-participant}; 409 {@code rejoin} (unknown or gone). */
    record Left( int status, String reason ) {
    }

    /** The client's own leave: only the participant's author may leave it (A6, ruling P5). */
    Left leave( String participantId, String authorKey );

    /** The server drops a participant itself (presence timeout), with no author check; its client is not told. */
    void evict( String participantId );

    /**
     * Phase 2: the server removes a participant for {@code reason}: null is the presence timeout ({@link #evict(String)});
     * {@code signed-out} and {@code lost-edit} (its author's {@code edit} grant revoked, found by the re-check) leave a
     * tombstone, so its later requests answer 403 {@code evicted} with that reason. The stub supports only null.
     */
    default void evict( final String participantId, final String reason ) {
        if( reason != null ) {
            throw new UnsupportedOperationException( "this server has no evictions with a reason" );
        }
        evict( participantId );
    }

    /** The listener's event for a rename of the page to {@code to}, after the page store renamed it (section 7.4, A1). */
    default void renamed( final String to ) {
        throw new UnsupportedOperationException( "this server does not follow renames" );
    }

    /** The listener's event for the page's deletion, after the page store deleted it (section 7.4): the draft freezes. */
    default void deleted() {
        throw new UnsupportedOperationException( "this server does not follow deletes" );
    }

    /** True while the draft is frozen (its page deleted). */
    default boolean frozen() {
        return false;
    }

    /**
     * True when the server has Phase 2's lifecycle (presence, metadata, tombstoned evictions, rename, delete): the
     * scheduler then draws a reason for each {@code LEAVE}, keeps a pull parked for every client at quiescence (so its
     * last selection is held) and checks invariants 8 and 9 there. Execution reads it from the server, so a replay
     * behaves as the run did.
     */
    default boolean lifecycle() {
        return false;
    }

    /** The answers to parked pulls completed since the last call (by a push, a leave or a timeout), in order. */
    List< Wakeup > drainWakeups();

    InvariantView view();

    /** Advances time-driven behaviour (long-poll timeouts) from the clock. */
    void tick();

    /**
     * A10 log truncation: cuts the store's log below the lowest version a live participant has confirmed (the store
     * clamps the cut to a snapshot), so a lagging client's pull below both the ring and the store answers 410.
     */
    void truncate();
}
