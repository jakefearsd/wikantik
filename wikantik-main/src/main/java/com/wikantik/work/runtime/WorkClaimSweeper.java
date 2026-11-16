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
package com.wikantik.work.runtime;

import com.wikantik.work.WorkService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Background pass that returns lapsed claims to their queues. One daemon thread, woken every {@code interval}; a
 * pass that returns a full batch is followed at once by another (up to {@link #MAX_PASSES_PER_CYCLE}) so a backlog
 * drains without waiting a minute per 500 items. A failed pass is logged and the next one still runs.
 */
public final class WorkClaimSweeper implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( WorkClaimSweeper.class );

    /** How often the sweep runs. */
    public static final Duration INTERVAL = Duration.ofSeconds( 60 );

    /** The size of one store batch; a pass that returns this many probably left more behind. */
    static final int FULL_BATCH = 500;

    /** The most passes in one cycle, so an endless backlog cannot monopolise the thread. */
    static final int MAX_PASSES_PER_CYCLE = 10;

    private static final long JOIN_MILLIS = 5_000L;

    private final WorkService service;
    private final long intervalNanos;
    private final Object lock = new Object();
    private final Thread thread;
    private boolean closed;

    /** The gauge that reads 1 while the sweeper thread runs and 0 once it has stopped, for whatever reason. */
    public static final String ALIVE_GAUGE = "wikantik_work_sweeper_alive";

    private WorkClaimSweeper( final WorkService service, final Duration interval ) {
        this.service = service;
        this.intervalNanos = interval.toNanos();
        this.thread = new Thread( this::run, "work-claim-sweeper" );
        this.thread.setDaemon( true );
        this.thread.setUncaughtExceptionHandler( WorkClaimSweeper::stopped );
    }

    /**
     * Anything that escapes the loop (an {@link Error}; exceptions are caught per pass) ends the thread: say so loudly,
     * because claims then never lapse, and hand it on to the JVM's default handler as an uncaught throwable would be.
     */
    private static void stopped( final Thread t, final Throwable e ) {
        // LOG.error justified: claims stop expiring until a restart, which an operator must see and act on.
        LOG.error( "work claim sweeper stopped — claims will not expire until restart: {}", e.toString(), e );
        final Thread.UncaughtExceptionHandler fallback = Thread.getDefaultUncaughtExceptionHandler();
        if ( fallback != null ) {
            fallback.uncaughtException( t, e );
        }
    }

    /** Whether the sweeper thread is running (false once closed or stopped by an uncaught throwable). */
    public boolean alive() {
        return thread.isAlive();
    }

    /** Starts the sweeper thread. */
    public static WorkClaimSweeper start( final WorkService service, final Duration interval ) {
        final WorkClaimSweeper sweeper = new WorkClaimSweeper( service, interval );
        sweeper.thread.start();
        return sweeper;
    }

    private void run() {
        while ( !isClosed() ) {
            cycle();
            if ( !pause() ) {
                return;
            }
        }
    }

    /**
     * One cycle: lapsed claims are expired (draining a backlog, a full batch at a time), then claims whose holder's
     * sponsor lost standing are ended. The two are independent: a failure in one never skips the other.
     */
    private void cycle() {
        sweepExpired();
        if ( !isClosed() ) {
            attempt( "standing", () -> service.claims().sweepStanding() );
        }
    }

    private void sweepExpired() {
        for ( int pass = 0; pass < MAX_PASSES_PER_CYCLE && !isClosed(); pass++ ) {
            final Integer swept = attempt( "expiry", () -> service.claims().sweepExpiredClaims() );
            if ( swept == null || swept < FULL_BATCH ) {
                return;
            }
        }
    }

    /** One sweep; null when it failed (logged, and the next cycle tries again). */
    private Integer attempt( final String what, final Sweep sweep ) {
        try {
            return sweep.run();
        } catch ( final SQLException | RuntimeException e ) {
            if ( isClosed() ) {
                // Interrupted by close(): shutting down, not a failure.
                LOG.debug( "Work claim {} sweep interrupted by shutdown: {}", what, e.getMessage() );
            } else {
                LOG.warn( "Work claim {} sweep failed; the next pass will try again: {}", what, e.getMessage(), e );
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface Sweep {
        int run() throws SQLException;
    }

    /** Sleeps one interval; false when the sweeper was closed meanwhile. */
    private boolean pause() {
        final long deadline = System.nanoTime() + intervalNanos;
        synchronized ( lock ) {
            while ( !closed ) {
                final long remaining = deadline - System.nanoTime();
                if ( remaining <= 0 ) {
                    return true;
                }
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait( lock, remaining );
                } catch ( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    private boolean isClosed() {
        synchronized ( lock ) {
            return closed;
        }
    }

    /** Stops the thread and waits briefly for it; safe to call more than once. */
    @Override
    public void close() {
        synchronized ( lock ) {
            closed = true;
            lock.notifyAll();
        }
        if ( Thread.currentThread() == thread ) {
            return;
        }
        try {
            thread.join( JOIN_MILLIS );
            if ( thread.isAlive() ) {
                // A pass stuck in the database: interrupt it rather than leave the thread behind.
                thread.interrupt();
                thread.join( JOIN_MILLIS );
            }
            if ( thread.isAlive() ) {
                LOG.warn( "The work claim sweeper thread did not stop within {} ms", 2 * JOIN_MILLIS );
            }
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
        }
    }
}
