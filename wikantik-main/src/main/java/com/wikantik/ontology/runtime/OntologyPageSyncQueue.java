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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Takes ontology page sync off the saving thread — the page analog of {@link OntologyEntitySync}.
 *
 * <p>Page events are recorded per slug in O(1) and applied through {@link OntologyPageSync} by a
 * single daemon thread {@code coalesceMs} after the first pending event. A save re-projects the
 * page's CURRENT state when applied, so N saves of one page within the window cost one
 * projection. Per slug, a delete cancels any earlier queued save and is applied before any later
 * one, and a rename cancels the old slug's queued save — so a stale queued save can never
 * resurrect a page that was deleted or renamed away. Failures are logged per slug; the nightly
 * full rebuild reconciles anything missed.</p>
 */
public final class OntologyPageSyncQueue implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( OntologyPageSyncQueue.class );

    private final OntologyPageSync sync;
    private final long coalesceMs;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final Map< String, Pending > pending = new LinkedHashMap<>();
    private boolean drainScheduled;
    private ScheduledFuture< ? > scheduledDrain;

    /** What is owed for one slug; applied in field order: deletes, forget, then save. */
    private static final class Pending {
        private boolean delete;
        private final Set< String > deletedCanonicalIds = new LinkedHashSet<>();
        private boolean forget;
        private boolean save;
    }

    public OntologyPageSyncQueue( final OntologyPageSync sync, final long coalesceMs ) {
        this( sync, coalesceMs, Executors.newSingleThreadScheduledExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-ontology-page-sync" );
            t.setDaemon( true );
            return t;
        } ) );
    }

    OntologyPageSyncQueue( final OntologyPageSync sync, final long coalesceMs,
                           final ScheduledExecutorService scheduler ) {
        this.sync = sync;
        this.coalesceMs = coalesceMs;
        this.scheduler = scheduler;
    }

    /** The page was saved: re-project it (from its state at drain time). */
    public void saved( final String slug ) {
        mark( slug, p -> p.save = true );
    }

    /** The page was deleted: drop its graph unless its canonical_id still lives (rename). */
    public void deleted( final String slug ) {
        String resolved = null;
        try {
            resolved = sync.canonicalIdFor( slug );
        } catch ( final RuntimeException e ) {
            LOG.warn( "page-sync: could not resolve canonical_id for deleted '{}' (resolving again at drain): {}",
                    slug, e.getMessage() );
        }
        final String canonicalId = resolved;
        mark( slug, p -> {
            p.delete = true;
            if ( canonicalId != null ) {
                p.deletedCanonicalIds.add( canonicalId );
            }
            p.save = false;
        } );
    }

    /** The page was renamed: re-project under the new slug; a queued save of the old slug is stale. */
    public void renamed( final String oldSlug, final String newSlug ) {
        synchronized ( lock ) {
            mark( oldSlug, p -> {
                p.forget = true;
                p.save = false;
            } );
            mark( newSlug, p -> p.save = true );
        }
    }

    private void mark( final String slug, final Consumer< Pending > change ) {
        if ( slug == null ) {
            return;
        }
        synchronized ( lock ) {
            change.accept( pending.computeIfAbsent( slug, k -> new Pending() ) );
            if ( !drainScheduled ) {
                try {
                    scheduledDrain = scheduler.schedule( this::drainNow, coalesceMs, TimeUnit.MILLISECONDS );
                    drainScheduled = true;
                } catch ( final RejectedExecutionException e ) {
                    LOG.warn( "page-sync: drain of '{}' rejected (executor shutting down?): {}", slug, e.getMessage() );
                }
            }
        }
    }

    /** One drain, synchronously. Scheduled-task body; package-private for deterministic tests. */
    void drainNow() {
        final Map< String, Pending > batch;
        synchronized ( lock ) {
            batch = new LinkedHashMap<>( pending );
            pending.clear();
            drainScheduled = false;
        }
        for ( final Map.Entry< String, Pending > e : batch.entrySet() ) {
            final String slug = e.getKey();
            final Pending p = e.getValue();
            if ( p.delete ) {
                if ( p.deletedCanonicalIds.isEmpty() ) {
                    apply( "delete", slug, () -> sync.onPageDeleted( slug, null ) );
                }
                for ( final String canonicalId : p.deletedCanonicalIds ) {
                    apply( "delete", slug, () -> sync.onPageDeleted( slug, canonicalId ) );
                }
            }
            if ( p.forget ) {
                sync.forget( slug );
            }
            if ( p.save ) {
                apply( "save", slug, () -> sync.onPageSaved( slug ) );
            }
        }
    }

    /** One step of a drain; a failure is logged and the remaining steps and slugs still run. */
    private static void apply( final String action, final String slug, final Runnable step ) {
        try {
            step.run();
        } catch ( final RuntimeException ex ) {
            LOG.warn( "page-sync: ontology {} of '{}' failed (nightly rebuild reconciles): {}",
                    action, slug, ex.getMessage(), ex );
        }
    }

    /**
     * Drain-and-stop: the pending drain is pulled forward rather than waited out (it may be up to
     * {@code coalesceMs} away), then a 5s await before forcing, mirroring {@link OntologyEntitySync#close()}.
     * The single worker thread keeps the pulled-forward drain ordered after one already running.
     */
    @Override
    public void close() {
        synchronized ( lock ) {
            if ( scheduledDrain != null ) {
                scheduledDrain.cancel( false );
            }
        }
        try {
            scheduler.execute( this::drainNow );
        } catch ( final RejectedExecutionException e ) {
            LOG.warn( "page-sync: final drain rejected (already closed?): {}", e.getMessage() );
        }
        scheduler.shutdown();
        try {
            if ( !scheduler.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "page-sync scheduler did not drain within 5s; forcing shutdown" );
                scheduler.shutdownNow();
            }
        } catch ( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
}
