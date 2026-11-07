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

    /** Result of {@link #join}. */
    record Joined( String participantId, String clientId, int version, String doc ) {
    }

    /**
     * {@code status}: 200 accepted, 400 rejected, 403 (participant belongs to another author), 409 stale / unknown
     * participant, 413 oversize, 503 store failure. {@code reason} is null on 200; otherwise: 400 {@code rejected};
     * 403 {@code not-your-participant}; 409 {@code stale-base}, {@code rejoin} or {@code frozen} (reserved for Phase 2);
     * 413 {@code oversize}; 503 {@code store} (append failed, nothing applied) or {@code poisoned}.
     */
    record PushResult( int status, int version, String reason ) {
    }

    /**
     * {@code status}: 200, 400 {@code bad-since}, 403 {@code not-your-participant}, 409 {@code rejoin} (unknown
     * participant), 410 {@code resync}, 503 {@code poisoned}. {@code parked} true means the pull is waiting and its answer
     * arrives through {@link #drainWakeups()}.
     */
    record PullResult( int status, List< Update > updates, int version, boolean parked, String reason ) {
    }

    /** A parked pull's eventual answer. */
    record Wakeup( String participantId, PullResult result ) {
    }

    /**
     * {@code status}: 200 ({@code unchanged} true when there was nothing to save, E21), 400 {@code empty}, 403
     * {@code not-your-participant}, 409 {@code rejoin} / {@code stale-base}, 422 {@code writer-refused}, 503
     * {@code poisoned} (including the publish whose post-save failure poisoned the stub).
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

    PublishResult publish( String participantId, String authorKey, int version, String note );

    void leave( String participantId );

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
