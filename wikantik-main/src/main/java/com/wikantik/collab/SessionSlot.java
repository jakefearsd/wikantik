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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One page's place in {@link CollabSessionRegistry}. Its lock ({@link #lock}) serialises opening, joining and evicting
 * the page's session; the idle clock is guarded by it, and {@code closed} is written under it (read by the tick
 * without it). The session, the re-check clock, the settle clock and the step warning clocks are read without it.
 */
final class SessionSlot {

    /**
     * The safety-net settle of a session with participants: the page listener delivers saves at once, so the tick
     * reads the page only this often, for a missed event (Phase 1 Task 5 ruling, after Phase 2's listener).
     */
    static final Duration SETTLE_EVERY = Duration.ofSeconds( 15 );

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger storeFailures = new AtomicInteger();
    private final TickLog tickLog = new TickLog();
    private volatile CollabSession session;
    private volatile Instant lastRecheck;
    private volatile boolean closed;
    /** When the tick last read the page to settle (null: not since opening). Tick thread only. */
    private volatile Instant lastSettle;
    /** When the orphan sweep first found the session's page missing (null: present). Tick thread only. */
    private volatile Instant absentSince;
    private Instant emptySince;

    void lock() {
        lock.lock();
    }

    /** False when another thread holds the slot (a join opening or joining it). */
    boolean tryLock() {
        return lock.tryLock();
    }

    void unlock() {
        lock.unlock();
    }

    /** The session, or null until opened. */
    CollabSession session() {
        return session;
    }

    /** Under the lock: the session is open; the re-check clock starts now. */
    void opened( final CollabSession s, final Instant now ) {
        session = s;
        lastRecheck = now;
    }

    /** Authoritative under the lock; a hint without it (the tick skips closed slots). */
    boolean closed() {
        return closed;
    }

    /** Under the lock: no join may use this slot any more. */
    void close() {
        closed = true;
    }

    /** Under the lock: a participant joined, so the idle clock stops. */
    void joined() {
        emptySince = null;
    }

    /**
     * Under the lock, on each tick of an opened slot: true once the session has been without participants for
     * {@code idle}, counted from the first tick that found it empty after the last join.
     */
    boolean idle( final Instant now, final Duration idle ) {
        if( !CollabSession.Hooks.members( session ).isEmpty() ) {
            emptySince = null;
            return false;
        }
        if( emptySince == null ) {
            emptySince = now;
            return false;
        }
        return !now.isBefore( emptySince.plus( idle ) );
    }

    /** True (and the clock restarts) when {@code seconds} have passed since the last re-check. Tick thread only. */
    boolean recheckDue( final Instant now, final int seconds ) {
        if( now.isBefore( lastRecheck.plusSeconds( seconds ) ) ) {
            return false;
        }
        lastRecheck = now;
        return true;
    }

    /**
     * Whether an opened slot's session is settled on this tick (tick thread only): it owes the page store a publish or a
     * merge, or the orphan sweep found its page missing (checked every tick until the grace resolves), or it has
     * participants and {@link #SETTLE_EVERY} has passed since the last settle read (the first tick after opening
     * settles). An empty session owing nothing is never settled.
     */
    boolean needsSettle( final Instant now ) {
        if( absentSince != null || CollabSession.Hooks.owesThePageStore( session ) ) {
            return true;
        }
        return !CollabSession.Hooks.members( session ).isEmpty()
            && ( lastSettle == null || !now.isBefore( lastSettle.plus( SETTLE_EVERY ) ) );
    }

    /** The settle read the page at {@code now} (tick thread only); a read that failed is not recorded. */
    void settled( final Instant now ) {
        lastSettle = now;
    }

    /**
     * The orphan sweep (tick thread only): true once the page has been found missing on every sweep for {@code grace},
     * counted from the first sweep that found it missing.
     */
    boolean absentFor( final Instant now, final Duration grace ) {
        if( absentSince == null ) {
            absentSince = now;
            return false;
        }
        return !now.isBefore( absentSince.plus( grace ) );
    }

    /** The orphan sweep found the page (or a reason not to look): its grace starts over. Tick thread only. */
    void present() {
        absentSince = null;
    }

    /**
     * Freezes the opened session under the slot lock (its page was deleted); returns the parked-pull completions for the
     * caller to run after the lock is released, or null when the slot is not opened or closed.
     */
    List< Runnable > freezeIfLive() {
        lock.lock();
        try {
            return closed || session == null ? null : CollabSession.Hooks.freeze( session );
        } finally {
            lock.unlock();
        }
    }

    /** Counts one 503 {@code store}; true when {@code limit} have come in a row. */
    boolean storeFailed( final int limit ) {
        return storeFailures.incrementAndGet() >= limit;
    }

    /** A success breaks the run of store failures. */
    void storeSucceeded() {
        storeFailures.set( 0 );
    }

    /**
     * Runs one registry tick step for this slot's session {@code s}. Nothing escapes; a failure is logged by
     * {@link TickLog} (a warning clock per step; a repeating {@link Error} also once per window) and the next tick
     * retries.
     */
    void step( final CollabSession s, final Instant now, final String what, final Runnable r ) {
        tickLog.run( "collab session " + s.id(), what, now, r );
    }
}
