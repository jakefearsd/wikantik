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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One page's place in {@link CollabSessionRegistry}. Its lock ({@link #lock}) serialises opening, joining and evicting
 * the page's session; {@code closed} and the idle clock are guarded by it. The session and the re-check clock are read
 * without it.
 */
final class SessionSlot {

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger storeFailures = new AtomicInteger();
    private volatile CollabSession session;
    private volatile Instant lastRecheck;
    private boolean closed;
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

    /** Under the lock. */
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

    /** Counts one 503 {@code store}; true when {@code limit} have come in a row. */
    boolean storeFailed( final int limit ) {
        return storeFailures.incrementAndGet() >= limit;
    }

    /** A success breaks the run of store failures. */
    void storeSucceeded() {
        storeFailures.set( 0 );
    }
}
