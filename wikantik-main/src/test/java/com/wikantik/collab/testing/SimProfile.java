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
 * <p>{@link Kind#EXTERNAL_SAVE} and {@link Kind#CRASH_RECOVER} are defined but weighted 0 everywhere: the stub ignores
 * external saves until Phase 1 and crash recovery is wired by Task 14 (ruling R1). Publish-time store faults and
 * {@code persistThenFail} are not offered at all against the stub (Task 10 ruling).</p>
 */
public enum SimProfile {

    /** Reliable network, small edits, occasional publishes. */
    CALM( weights( 30, 14, 14, 40, 0, 0, 3, 0, 2, 0 ), 20, 60, 0.0, "none", 64 * 1024 ),
    /** Drops, duplicates, timeouts; a 300-byte page limit so 413, oversize state and resync are exercised. */
    LOSSY( weights( 25, 12, 12, 30, 9, 9, 5, 0, 2, 0 ), 10, 24, 0.0, "none", 300 ),
    /** Agent-style traffic: large block edits and whole-document replaces (E7) mixed with normal typing. */
    AGENT_HEAVY( weights( 30, 14, 12, 34, 3, 3, 3, 0, 3, 0 ), 12, 30, 0.35, "none", 64 * 1024 ),
    /** Frequent publishes through a save filter that rewrites text (E16/E17). */
    PUBLISH_STORM( weights( 25, 12, 12, 32, 2, 2, 3, 0, 14, 0 ), 14, 30, 0.05, "strip-trailing-spaces", 64 * 1024 );

    /** The things a step can do. {@link #TIMEOUT} is never drawn: the scheduler forces it when a request runs out of steps. */
    public enum Kind { LOCAL_EDIT, SEND_PUSH, SEND_PULL, DELIVER, DROP, DUPLICATE, ADVANCE_CLOCK, EXTERNAL_SAVE, PUBLISH,
        CRASH_RECOVER, TIMEOUT }

    private final Map< Kind, Integer > weights;
    private final int pushTimeoutSteps;
    private final int pullTimeoutSteps;
    private final double bigEditChance;
    private final String rewrite;
    private final long maxPageBytes;

    SimProfile( final Map< Kind, Integer > weights, final int pushTimeoutSteps, final int pullTimeoutSteps,
                final double bigEditChance, final String rewrite, final long maxPageBytes ) {
        this.weights = weights;
        this.pushTimeoutSteps = pushTimeoutSteps;
        this.pullTimeoutSteps = pullTimeoutSteps;
        this.bigEditChance = bigEditChance;
        this.rewrite = rewrite;
        this.maxPageBytes = maxPageBytes;
    }

    private static Map< Kind, Integer > weights( final int local, final int push, final int pull, final int deliver,
                                                 final int drop, final int duplicate, final int clock, final int external,
                                                 final int publish, final int crash ) {
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
}
