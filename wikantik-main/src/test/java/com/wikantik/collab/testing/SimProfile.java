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

import java.util.EnumMap;
import java.util.Map;

/**
 * Action weights and knobs for one simulated regime (F1). The scheduler picks each step's action with probability
 * proportional to its weight among the actions that are possible at that moment.
 *
 * <p>{@link Kind#EXTERNAL_SAVE}, {@link Kind#RELEASE_PUBLISH}, {@link Kind#STORE_FAULT} and
 * {@link Kind#PERSIST_THEN_FAIL} are weighted 0 in every profile: the stub has no external merge and no pending
 * publish, so only the session's overlay ({@code CollabSessionSimServer.OVERLAY}) weights them.
 * {@link Kind#CRASH_RECOVER} (F8, ruling R1) is weighted in {@link #LOSSY} and {@link #TIGHT}; {@link Kind#LEAVE} (the server drops a
 * participant, as the presence timeout will) in {@link #LOSSY}, {@link #AGENT_HEAVY} and {@link #TIGHT}; {@link Kind#TRUNCATE}
 * (the server's A10 log truncation) in {@link #TIGHT} only. A server factory's weight overlay
 * ({@link SimScheduler.Overlay}) may weight any action for that server alone. Publish-time store faults and
 * {@code persistThenFail} are never offered against the stub (Task 10 ruling).</p>
 */
public enum SimProfile {

    /** Reliable network, small edits, occasional publishes. */
    CALM( weights( 30, 14, 14, 40, 0, 0, 3, 0, 2, 0, 0, 0 ), 20, 60, 0.0, "none", 64 * 1024, 4 ),
    /** Drops, duplicates, timeouts; a 300-byte page limit so 413, oversize state and resync are exercised. */
    LOSSY( weights( 25, 12, 12, 30, 9, 9, 5, 0, 2, 1, 2, 0 ), 10, 24, 0.0, "none", 300, 4 ),
    /** Agent-style traffic: large block edits and whole-document replaces (E7) mixed with normal typing. */
    AGENT_HEAVY( weights( 30, 14, 12, 34, 3, 3, 3, 0, 3, 0, 1, 0 ), 12, 30, 0.35, "none", 64 * 1024, 4 ),
    /** Frequent publishes through a save filter that rewrites text (E16/E17). */
    PUBLISH_STORM( weights( 25, 12, 12, 32, 2, 2, 3, 0, 14, 0, 0, 0 ), 14, 30, 0.05, "strip-trailing-spaces", 64 * 1024, 4 ),
    /**
     * A small ring and a small page limit with leaves, crashes and A10 truncation, so 410 resyncs (a dropped client whose
     * version the log no longer reaches), 413 resyncs and reloads after truncation are routine rather than rare (replaces
     * Phase 0's hand-picked LOSSY resync seeds).
     */
    TIGHT( weights( 24, 12, 12, 30, 5, 5, 3, 0, 4, 1, 4, 5 ), 10, 24, 0.1, "none", 200, 2 );

    /**
     * The things a step can do. {@link #TIMEOUT} is never drawn: the scheduler forces it when a request runs out of steps.
     * {@link #LEAVE} is the server dropping a participant without telling its client (presence timeout); a client that
     * resyncs sends its own leave over the network instead.
     */
    public enum Kind { LOCAL_EDIT, SEND_PUSH, SEND_PULL, DELIVER, DROP, DUPLICATE, ADVANCE_CLOCK, EXTERNAL_SAVE, PUBLISH,
        CRASH_RECOVER, LEAVE, TRUNCATE, RELEASE_PUBLISH, STORE_FAULT, PERSIST_THEN_FAIL, TIMEOUT }

    private final Map< Kind, Integer > weights;
    private final int pushTimeoutSteps;
    private final int pullTimeoutSteps;
    private final double bigEditChance;
    private final String rewrite;
    private final long maxPageBytes;
    private final int ringSize;

    SimProfile( final Map< Kind, Integer > weights, final int pushTimeoutSteps, final int pullTimeoutSteps,
                final double bigEditChance, final String rewrite, final long maxPageBytes, final int ringSize ) {
        this.weights = weights;
        this.pushTimeoutSteps = pushTimeoutSteps;
        this.pullTimeoutSteps = pullTimeoutSteps;
        this.bigEditChance = bigEditChance;
        this.rewrite = rewrite;
        this.maxPageBytes = maxPageBytes;
        this.ringSize = ringSize;
    }

    private static Map< Kind, Integer > weights( final int local, final int push, final int pull, final int deliver,
                                                 final int drop, final int duplicate, final int clock, final int external,
                                                 final int publish, final int crash, final int leave, final int truncate ) {
        final Map< Kind, Integer > m = new EnumMap<>( Kind.class );
        m.put( Kind.LOCAL_EDIT, local );
        m.put( Kind.SEND_PUSH, push );
        m.put( Kind.SEND_PULL, pull );
        m.put( Kind.DELIVER, deliver );
        m.put( Kind.DROP, drop );
        m.put( Kind.DUPLICATE, duplicate );
        m.put( Kind.ADVANCE_CLOCK, clock );
        m.put( Kind.EXTERNAL_SAVE, external );
        m.put( Kind.PUBLISH, publish );
        m.put( Kind.CRASH_RECOVER, crash );
        m.put( Kind.LEAVE, leave );
        m.put( Kind.TRUNCATE, truncate );
        // weighted by a server's overlay only (the session's): a pending publish's release, a one-shot publish-time store
        // fault, and a save that persists and then fails (E39)
        m.put( Kind.RELEASE_PUBLISH, 0 );
        m.put( Kind.STORE_FAULT, 0 );
        m.put( Kind.PERSIST_THEN_FAIL, 0 );
        m.put( Kind.TIMEOUT, 0 );
        return m;
    }

    public int weight( final Kind k ) {
        return weights.get( k );
    }

    /** Steps a push may stay unanswered before the client gives up on it and retries. */
    public int pushTimeoutSteps() {
        return pushTimeoutSteps;
    }

    /** Steps a pull (parked or not) may stay unanswered before the client gives up on it and pulls again. */
    public int pullTimeoutSteps() {
        return pullTimeoutSteps;
    }

    /** Chance that a local edit is a large block edit rather than a keystroke-sized one. */
    public double bigEditChance() {
        return bigEditChance;
    }

    /** The named save-filter rewrite the page writer applies on publish (see {@link SimEnv#rewrite(String)}). */
    public String rewrite() {
        return rewrite;
    }

    public long maxPageBytes() {
        return maxPageBytes;
    }

    /** The server's in-memory ring size (small, so pulls regularly fall through the ring to the store, E12). */
    public int ringSize() {
        return ringSize;
    }
}
