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
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.SyncReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Operable facade over the Phase-1 sync stack: manual trigger, status, and a periodic scheduler. */
public final class ConnectorRuntime {

    private static final Logger LOG = LogManager.getLogger( ConnectorRuntime.class );

    /** No-op recorder for the backward-compat ctor: run history is opt-in, not mandatory. */
    private static final RunRecorder NOOP_RECORDER = new RunRecorder() {
        @Override public long start( final String connectorId, final String trigger ) { return -1L; }
        @Override public void finish( final long runId, final SyncReport report ) { }
        @Override public void fail( final long runId, final String error ) { }
    };

    // Rebuilt at runtime by swapRegistry() (Task 11) once connectors move from static properties
    // wiring to DB-backed configs — volatile so an in-flight sync's snapshot read is never torn.
    private volatile ConnectorRegistry registry;
    private final SyncOrchestrator orchestrator;
    private final ConnectorStatusReader statusReader;
    private final RunRecorder runRecorder;
    private final boolean syncingEnabled;
    /** Per-connector sync locks: a manual /admin sync must never run concurrently with a scheduled
     *  sync of the SAME connector — interleaved tombstone/re-sync passes can strand sync-state rows
     *  pointing at deleted pages. Different connectors sync freely in parallel. */
    private final ConcurrentMap< String, ReentrantLock > syncLocks = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    public ConnectorRuntime( final ConnectorRegistry registry, final SyncOrchestrator orchestrator,
                             final ConnectorStatusReader statusReader ) {
        this( registry, orchestrator, statusReader, NOOP_RECORDER, true );
    }

    public ConnectorRuntime( final ConnectorRegistry registry, final SyncOrchestrator orchestrator,
                             final ConnectorStatusReader statusReader, final RunRecorder runRecorder,
                             final boolean syncingEnabled ) {
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.statusReader = statusReader;
        this.runRecorder = runRecorder;
        this.syncingEnabled = syncingEnabled;
    }

    /** Manual trigger — equivalent to {@code syncNow( connectorId, "manual" )}. */
    public SyncReport syncNow( final String connectorId ) {
        return syncNow( connectorId, "manual" );
    }

    /**
     * @throws ConnectorsDisabledException when the operator kill switch has syncing disabled.
     * @throws SyncInProgressException when a sync of this connector is already running.
     */
    public SyncReport syncNow( final String connectorId, final String trigger ) {
        if ( !syncingEnabled ) {
            throw new ConnectorsDisabledException(
                "connector syncing disabled by operator (wikantik.connectors.enabled=false)" );
        }
        final SourceConnector c = registry.get( connectorId ).orElseThrow(
            () -> new IllegalArgumentException( "unknown connector: " + connectorId ) );
        final ReentrantLock lock = syncLocks.computeIfAbsent( connectorId, k -> new ReentrantLock() );
        if ( !lock.tryLock() ) {
            throw new SyncInProgressException( connectorId );
        }
        try {
            // start() inside the lock's try/finally: if the run store throws (e.g. a wrapped
            // SQLException) the lock must still be released or this connector is permanently
            // stuck returning SyncInProgressException until restart.
            final long runId = runRecorder.start( connectorId, trigger );
            try {
                final SyncReport report = orchestrator.sync( c );
                runRecorder.finish( runId, report );
                return report;
            } catch ( final RuntimeException e ) {
                runRecorder.fail( runId, e.getMessage() );
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Hot-swap the registry (e.g. after a DB-backed config change) — takes effect for the next
     *  {@link #syncNow} call; in-flight syncs keep the {@code SourceConnector} reference they already
     *  hold, and the per-connector lock map is keyed by id so it survives the swap unaffected. */
    public void swapRegistry( final ConnectorRegistry next ) {
        this.registry = next;
    }

    public ConnectorRegistry registry() { return registry; }

    public boolean syncingEnabled() { return syncingEnabled; }

    public ConnectorStatus status( final String connectorId ) {
        if ( registry.get( connectorId ).isEmpty() ) {
            throw new IllegalArgumentException( "unknown connector: " + connectorId );
        }
        return statusReader.read( connectorId, registry.typeOf( connectorId ) );
    }

    public List< ConnectorStatus > list() {
        final List< ConnectorStatus > out = new ArrayList<>();
        for ( final String id : registry.ids() ) out.add( statusReader.read( id, registry.typeOf( id ) ) );
        return out;
    }

    /** Supplies the sync interval (in hours) for one connector id — {@code <= 0} means "manual only,
     *  never due". Backed by {@code ConnectorConfigService::intervalHoursFor} in production. */
    @FunctionalInterface
    public interface IntervalProvider {
        long intervalHoursFor( String connectorId );
    }

    /** Starts a 60s-period due-tick: every minute, checks every registered connector against its
     *  own interval (via {@code intervals}) and syncs the ones that are due. Replaces the old
     *  single fixed-rate scheduler so each connector can carry its own interval. */
    public synchronized void startDueTickScheduler( final IntervalProvider intervals ) {
        if ( isSchedulerRunning() ) {
            // idempotent restart — shut the prior executor down first so a second start
            // (e.g. a startup retry) can't orphan a live scheduled task
            LOG.warn( "connector sync scheduler already running — restarting" );
            stop();
        }
        executor = Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-connector-sync-scheduler" );
            t.setDaemon( true );
            return t;
        } );
        executor.scheduleAtFixedRate( () -> syncDue( intervals, Instant.now() ), 60, 60, TimeUnit.SECONDS );
        LOG.info( "connector due-tick scheduler started (60s tick, {} connectors)", registry.ids().size() );
    }

    /** Syncs every registered connector whose interval has elapsed as of {@code now}. A connector
     *  with interval {@code <= 0} is manual-only and always skipped; one that has never run
     *  ({@code lastRun == null}, or an unparseable {@code lastRun}) is always due. Returns the
     *  number of connectors actually synced. Package-visible for testing. */
    int syncDue( final IntervalProvider intervals, final Instant now ) {
        int ran = 0;
        for ( final String id : registry.ids() ) {
            final long intervalHours = intervals.intervalHoursFor( id );
            if ( intervalHours <= 0 ) continue;
            if ( !isDue( id, intervalHours, now ) ) continue;
            try {
                final SyncReport r = syncNow( id, "scheduled" );
                LOG.info( "scheduled connector sync '{}': {}", id, r );
                ran++;
            } catch ( final SyncInProgressException e ) {
                // a manual sync holds the lock — skip this cycle rather than queue behind it
                LOG.info( "scheduled connector sync '{}' skipped: {}", id, e.getMessage() );
            } catch ( final RuntimeException e ) {
                LOG.warn( "scheduled connector sync '{}' failed: {}", id, e.getMessage() );
            }
        }
        return ran;
    }

    private boolean isDue( final String id, final long intervalHours, final Instant now ) {
        final String lastRun = statusReader.read( id, registry.typeOf( id ) ).lastRun();
        if ( lastRun == null ) return true;
        try {
            return !Instant.parse( lastRun ).plus( intervalHours, ChronoUnit.HOURS ).isAfter( now );
        } catch ( final DateTimeParseException e ) {
            LOG.warn( "connector '{}' has unparseable lastRun '{}' — treating as never-ran (due): {}",
                id, lastRun, e.getMessage() );
            return true;
        }
    }

    public boolean isSchedulerRunning() { return executor != null && !executor.isShutdown(); }

    public synchronized void stop() {
        if ( executor != null ) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
