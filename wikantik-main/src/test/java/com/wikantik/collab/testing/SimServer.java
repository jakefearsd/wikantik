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

    /** {@code status}: 200 accepted, 400 rejected, 409 stale / unknown participant, 413 oversize, 503 store failure. */
    record PushResult( int status, int version ) {
    }

    /**
     * {@code status}: 200, 400 (bad {@code since}), 409 (unknown participant), 410 (resync). {@code parked} true means
     * the pull is waiting and its answer arrives through {@link #drainWakeups()}.
     */
    record PullResult( int status, List< Update > updates, int version, boolean parked ) {
    }

    /** A parked pull's eventual answer. */
    record Wakeup( String participantId, PullResult result ) {
    }

    /** {@code status}: 200 (also "unchanged"), 400, 409, 422 (writer refused), 503. */
    record PublishResult( int status, int pageVersion ) {
    }

    Joined join( String authorKey );

    PushResult push( String participantId, int version, List< Update > updates );

    PullResult pull( String participantId, int since );

    PublishResult publish( String participantId, int version, String note );

    void leave( String participantId );

    /** The answers to parked pulls completed since the last call (by a push, a leave or a timeout), in order. */
    List< Wakeup > drainWakeups();

    InvariantView view();

    /** Advances time-driven behaviour (long-poll timeouts) from the clock. */
    void tick();
}
