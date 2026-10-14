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
package com.wikantik.auth.identity;

import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.AccountSummary;
import com.wikantik.auth.user.UserDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds the {@link AccountSnapshot} of one {@link DefaultPublicIdentity}: serves it, expires it, reloads it from the user
 * database, and keeps a failing database from being hammered.
 *
 * <p>A snapshot is fresh while its TTL has not elapsed on the injected {@link Clock} and no {@link #invalidate} has happened
 * since its load began. One thread reloads at a time. While it does, a snapshot that has merely outlived its TTL is still
 * served to everyone else, so a slow database never stalls page rendering; callers wait only when there is nothing to serve
 * or the snapshot was invalidated, so a write is visible to the next read.</p>
 *
 * <p>A failed reload keeps the previous snapshot (stale beats unknown) and starts a backoff measured from the failure; callers
 * already queued behind the failing load honour it instead of each repeating the query. With no snapshot at all {@link #current}
 * returns {@code null}. A {@code null} database means "no accounts": an empty snapshot.</p>
 */
final class AccountSnapshotCache {

    private static final Logger LOG = LogManager.getLogger( AccountSnapshotCache.class );

    private final UserDatabase db;
    private final Clock clock;
    private final Duration ttl;
    private final Duration retryAfterFailure;

    private volatile AccountSnapshot snapshot;
    private volatile Instant retryAt;

    /**
     * Bumped by every {@link #invalidate}. A snapshot is only fresh while its generation is the current one, so an
     * invalidation also reaches a load that was already in flight (its result may predate the write) — and the superseded
     * snapshot stays available as the fallback should the reload fail.
     */
    private final AtomicLong generation = new AtomicLong();

    /** Held by the one thread that is (re)loading the snapshot. */
    private final ReentrantLock loadLock = new ReentrantLock();

    AccountSnapshotCache( final UserDatabase db, final Clock clock, final Duration ttl, final Duration retryAfterFailure ) {
        this.db = db;
        this.clock = Objects.requireNonNull( clock, "clock" );
        this.ttl = ttl;
        this.retryAfterFailure = retryAfterFailure;
    }

    /** Marks the snapshot stale (and clears any failure backoff): the next {@link #current} reloads. */
    void invalidate() {
        generation.incrementAndGet();
        retryAt = null;
    }

    /** The snapshot to answer from, reloading it when stale; the previous one if the reload failed, {@code null} if none was ever loaded. */
    AccountSnapshot current() {
        final Instant now = clock.instant();
        final AccountSnapshot held = snapshot;
        if ( fresh( held, now ) ) {
            return held;
        }
        if ( backingOff( now ) ) {
            return held;   // a recent load failed: keep serving the stale snapshot (or none) without asking again
        }
        if ( held != null && held.generation() == generation.get() ) {
            // Only outlived its TTL: nothing was written since, so let one thread refresh it and answer the rest from it.
            if ( !loadLock.tryLock() ) {
                return held;
            }
        } else {
            loadLock.lock();   // nothing to serve, or invalidated: wait for the refreshed snapshot
        }
        try {
            return reload();
        } finally {
            loadLock.unlock();
        }
    }

    private boolean fresh( final AccountSnapshot held, final Instant now ) {
        return held != null && now.isBefore( held.expires() ) && held.generation() == generation.get();
    }

    private boolean backingOff( final Instant now ) {
        final Instant notBefore = retryAt;
        return notBefore != null && now.isBefore( notBefore );
    }

    /** Called with {@link #loadLock} held. */
    private AccountSnapshot reload() {
        // Re-check against a fresh reading of the clock: this thread may have waited a long time for the lock, and the
        // load it waited on may have finished — or failed, in which case the backoff it started must hold for the queue.
        final Instant started = clock.instant();
        final AccountSnapshot held = snapshot;
        if ( fresh( held, started ) || backingOff( started ) ) {
            return held;
        }
        final long loading = generation.get();
        try {
            final Collection< AccountSummary > accounts = db == null ? List.of() : db.accountSummaries();
            final AccountSnapshot loaded = AccountSnapshot.build( clock.instant().plus( ttl ), loading, accounts );
            snapshot = loaded;
            retryAt = null;
            warnWhenAmbiguousNamesChanged( held, loaded );
            return loaded;
        } catch ( final WikiSecurityException | RuntimeException e ) {
            retryAt = clock.instant().plus( retryAfterFailure );   // from the failure, not from when a slow load began
            LOG.warn( "Could not load the account snapshot; {}: {}",
                    held == null ? "showing neutral labels" : "serving the previous snapshot", e.getMessage(), e );
            return held;
        }
    }

    /** Logged when the set changes, not on every 60 s rebuild: a legacy duplicate would otherwise warn once a minute forever. */
    private static void warnWhenAmbiguousNamesChanged( final AccountSnapshot before, final AccountSnapshot after ) {
        final Set< String > previous = before == null ? Set.of() : before.ambiguousNames();
        if ( !after.ambiguousNames().isEmpty() && !after.ambiguousNames().equals( previous ) ) {
            LOG.warn( "Display names held by more than one account will not resolve to a login: {}", after.ambiguousNames() );
        }
    }
}
