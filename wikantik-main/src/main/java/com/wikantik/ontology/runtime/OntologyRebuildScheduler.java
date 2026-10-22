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
package com.wikantik.ontology.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Periodically triggers a full ontology rebuild (backstop for missed/uneventful changes) and a
 * TDB2 compaction (bounds on-disk store size — copy-on-write B+Trees never shrink on their own).
 * Both ticks run on the same single-thread executor: each just calls a non-blocking
 * {@code OntologyRebuildCoordinator} trigger method that hands off to its own daemon thread, so
 * there is no need for a second thread pool.
 *
 * <p>Compaction is scheduled from the store's persisted last-compaction time, not from JVM start:
 * production restarts on every deploy, far more often than the weekly interval, so a timer that
 * restarts with the JVM never fires. The first compaction runs once the interval since the last
 * one has elapsed — at the earliest {@link #COMPACTION_GRACE} after startup, so startup and the
 * startup-if-empty rebuild settle first — and each later one is self-scheduled.</p>
 */
public final class OntologyRebuildScheduler {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildScheduler.class );

    /** Earliest a compaction may run after startup, and the retry delay when a rebuild is in the way. */
    static final Duration COMPACTION_GRACE = Duration.ofMinutes( 5 );

    /** Delay from startup to the first reconciliation rebuild, after startup work and any rebuild-if-empty. */
    static final Duration REBUILD_GRACE = Duration.ofMinutes( 10 );

    private final OntologyRebuildCoordinator coordinator;
    private final long rebuildIntervalHours;
    private final long compactionIntervalHours;
    private final Clock clock;
    private final Supplier< ScheduledExecutorService > executorFactory;
    private volatile ScheduledExecutorService executor;

    public OntologyRebuildScheduler( final OntologyRebuildCoordinator coordinator,
                                      final long rebuildIntervalHours,
                                      final long compactionIntervalHours ) {
        this( coordinator, rebuildIntervalHours, compactionIntervalHours, Clock.systemUTC(),
                () -> Executors.newSingleThreadScheduledExecutor( r -> {
                    final Thread t = new Thread( r, "wikantik-ontology-rebuild-scheduler" );
                    t.setDaemon( true );
                    return t;
                } ) );
    }

    OntologyRebuildScheduler( final OntologyRebuildCoordinator coordinator,
                              final long rebuildIntervalHours,
                              final long compactionIntervalHours,
                              final Clock clock,
                              final Supplier< ScheduledExecutorService > executorFactory ) {
        this.coordinator = coordinator;
        this.rebuildIntervalHours = rebuildIntervalHours;
        this.compactionIntervalHours = compactionIntervalHours;
        this.clock = clock;
        this.executorFactory = executorFactory;
    }

    /**
     * Delay until the first compaction after startup: {@code lastCompaction + interval - now},
     * never sooner than {@link #COMPACTION_GRACE} and never later than one interval (a record in
     * the future, e.g. after a clock change, must not postpone compaction indefinitely). No record
     * means overdue.
     */
    static Duration firstCompactionDelay( final Optional< Instant > lastCompaction,
                                          final Duration interval,
                                          final Instant now ) {
        if ( lastCompaction.isEmpty() ) {
            return COMPACTION_GRACE;
        }
        final Duration remaining = Duration.between( now, lastCompaction.get().plus( interval ) );
        if ( remaining.compareTo( COMPACTION_GRACE ) < 0 ) {
            return COMPACTION_GRACE;
        }
        return remaining.compareTo( interval ) > 0 ? interval : remaining;
    }

    /** Starts the timer(s) (no-op when both intervals are <= 0). The first rebuild is one interval out. */
    public void start() {
        if ( rebuildIntervalHours <= 0 && compactionIntervalHours <= 0 ) {
            LOG.info( "ontology rebuild/compaction scheduler disabled (rebuild={}h, compaction={}h)",
                    rebuildIntervalHours, compactionIntervalHours );
            return;
        }
        executor = executorFactory.get();
        if ( rebuildIntervalHours > 0 ) {
            // First run shortly after startup: a JVM restarted on every deploy never reached a first
            // run one interval out. A rebuild of unchanged content is cheap and commits nothing.
            executor.scheduleAtFixedRate( this::runRebuildOnce, REBUILD_GRACE.toMinutes(),
                    TimeUnit.HOURS.toMinutes( rebuildIntervalHours ), TimeUnit.MINUTES );
            LOG.info( "ontology rebuild scheduler started (first run in {}, then every {}h)", REBUILD_GRACE, rebuildIntervalHours );
        } else {
            LOG.info( "ontology rebuild scheduler disabled (interval={}h)", rebuildIntervalHours );
        }
        if ( compactionIntervalHours > 0 ) {
            final Optional< Instant > last = coordinator.lastCompactedAt();
            final Duration first = firstCompactionDelay( last, compactionInterval(), clock.instant() );
            scheduleCompaction( first );
            LOG.info( "ontology compaction scheduler started (every {}h; last compaction {}; next in {})",
                    compactionIntervalHours, last.map( Instant::toString ).orElse( "never recorded" ), first );
        } else {
            LOG.info( "ontology compaction scheduler disabled (interval={}h)", compactionIntervalHours );
        }
    }

    private Duration compactionInterval() {
        return Duration.ofHours( compactionIntervalHours );
    }

    private void scheduleCompaction( final Duration delay ) {
        final ScheduledExecutorService ex = executor;
        if ( ex == null || ex.isShutdown() ) {
            return;
        }
        try {
            ex.schedule( this::runCompactionTick, delay.toMillis(), TimeUnit.MILLISECONDS );
        } catch ( final RejectedExecutionException e ) {
            LOG.warn( "ontology compaction not rescheduled (scheduler stopping?): {}", e.getMessage() );
        }
    }

    /** One scheduled rebuild tick — triggers a rebuild, swallowing the "already running" conflict. */
    void runRebuildOnce() {
        try {
            coordinator.triggerRebuild();
            LOG.info( "scheduled ontology rebuild triggered" );
        } catch ( final OntologyRebuildCoordinator.ConflictException e ) {
            LOG.info( "scheduled ontology rebuild skipped — already running" );
        } catch ( final OntologyRebuildCoordinator.DisabledException e ) {
            LOG.info( "scheduled ontology rebuild skipped — disabled" );
        } catch ( final RuntimeException e ) {
            LOG.warn( "scheduled ontology rebuild failed: {}", e.getMessage(), e );
        }
    }

    /** Self-rescheduling compaction tick: runs one compaction attempt, then books the next. */
    void runCompactionTick() {
        scheduleCompaction( runCompactionOnce() );
    }

    /**
     * One compaction attempt; returns the delay until the next one. A rebuild or compaction already
     * running only postpones it by {@link #COMPACTION_GRACE} — a due (possibly long-overdue)
     * compaction is not pushed back a whole interval by a coincident rebuild.
     */
    Duration runCompactionOnce() {
        try {
            coordinator.triggerCompaction();
            LOG.info( "scheduled ontology compaction triggered" );
        } catch ( final OntologyRebuildCoordinator.ConflictException e ) {
            LOG.info( "scheduled ontology compaction postponed {} — rebuild or compaction already running",
                    COMPACTION_GRACE );
            return COMPACTION_GRACE;
        } catch ( final OntologyRebuildCoordinator.DisabledException e ) {
            LOG.info( "scheduled ontology compaction skipped — disabled" );
        } catch ( final RuntimeException e ) {
            LOG.warn( "scheduled ontology compaction failed: {}", e.getMessage(), e );
        }
        return compactionInterval();
    }

    public void stop() {
        final ScheduledExecutorService ex = executor;
        if ( ex != null ) {
            ex.shutdownNow();
        }
    }
}
