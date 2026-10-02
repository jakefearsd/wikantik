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
package com.wikantik.importer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** In-memory registry of import jobs: one running job per user, a global cap, results kept for {@link #RETENTION}. */
public final class ImportJobRegistry implements AutoCloseable {

    public static final Duration RETENTION = Duration.ofHours( 1 );
    /** How long {@link #close()} waits for interrupted jobs to stop. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds( 10 );
    private static final Logger LOG = LogManager.getLogger( ImportJobRegistry.class );

    private final int maxConcurrent;
    private final Clock clock;
    private final Executor executor;
    private final Map< String, VaultImportJob > jobs = new LinkedHashMap<>();
    /** Owners holding a slot for an apply that is still re-planning (counted like running jobs). */
    private final Set< String > reserved = new HashSet<>();
    private final Semaphore plans;

    public ImportJobRegistry( final int maxConcurrent, final Clock clock, final Executor executor ) {
        this( maxConcurrent, ImportLimits.DEFAULT_MAX_CONCURRENT_PLANS, clock, executor );
    }

    public ImportJobRegistry( final int maxConcurrent, final int maxConcurrentPlans, final Clock clock,
                              final Executor executor ) {
        this.maxConcurrent = maxConcurrent;
        this.plans = new Semaphore( maxConcurrentPlans );
        this.clock = clock;
        this.executor = executor;
    }

    /** A registry running jobs on a cached pool of daemon threads named {@code obsidian-import-N}. */
    public static ImportJobRegistry create( final int maxConcurrent ) {
        return create( maxConcurrent, ImportLimits.DEFAULT_MAX_CONCURRENT_PLANS );
    }

    /** As {@link #create(int)}, with at most {@code maxConcurrentPlans} plans computed at once. */
    public static ImportJobRegistry create( final int maxConcurrent, final int maxConcurrentPlans ) {
        final AtomicInteger n = new AtomicInteger();
        final ExecutorService pool = Executors.newCachedThreadPool( r -> {
            final Thread t = new Thread( r, "obsidian-import-" + n.incrementAndGet() );
            t.setDaemon( true );
            return t;
        } );
        return new ImportJobRegistry( maxConcurrent, maxConcurrentPlans, Clock.systemUTC(), pool );
    }

    /** Throws if {@code owner} could not start a job right now (running jobs and held reservations both count). */
    public synchronized void ensureCanStart( final String owner ) throws ImportJobConflictException {
        if ( reserved.contains( owner ) ) {
            throw new ImportJobConflictException( ImportJobConflictException.Reason.USER_RUNNING,
                "an import is already in progress for you" );
        }
        long running = reserved.size();
        for ( final VaultImportJob j : jobs.values() ) {
            if ( j.isRunning() ) {
                running++;
                if ( j.owner().equals( owner ) ) {
                    throw new ImportJobConflictException( ImportJobConflictException.Reason.USER_RUNNING,
                        "an import is already running for you" );
                }
            }
        }
        if ( running >= maxConcurrent ) {
            throw new ImportJobConflictException( ImportJobConflictException.Reason.CAPACITY,
                "too many imports in progress; try again shortly" );
        }
    }

    /**
     * Takes a plan permit ({@code wikantik.import.maxConcurrentPlans}) for a dry run or an apply's re-plan.
     *
     * @throws ImportJobConflictException (CAPACITY) when every permit is in use
     */
    public PlanPermit acquirePlan() throws ImportJobConflictException {
        if ( !plans.tryAcquire() ) {
            throw new ImportJobConflictException( ImportJobConflictException.Reason.CAPACITY,
                "too many import plans in progress; try again shortly" );
        }
        return new PlanPermit();
    }

    /**
     * Holds {@code owner}'s job slot before the apply re-plans, so concurrent applies are refused up front instead of
     * all re-planning. Start the job through the reservation, or close it to give the slot back.
     */
    public synchronized Reservation reserve( final String owner ) throws ImportJobConflictException {
        evictExpired();
        ensureCanStart( owner );
        reserved.add( owner );
        return new Reservation( owner );
    }

    /** Registers the job built by {@code factory} (given its new id) and queues it for execution. */
    public VaultImportJob start( final String owner, final Function< String, VaultImportJob > factory )
            throws ImportJobConflictException {
        try ( Reservation r = reserve( owner ) ) {
            return r.start( factory );
        }
    }

    private synchronized VaultImportJob startReserved( final String owner,
                                                       final Function< String, VaultImportJob > factory ) {
        reserved.remove( owner );
        final String id = UUID.randomUUID().toString();
        final VaultImportJob job = factory.apply( id );
        job.useClock( clock );
        jobs.put( id, job );
        try {
            executor.execute( () -> {
                try {
                    job.run();
                } finally {
                    evictExpiredNow();
                }
            } );
        } catch ( final RejectedExecutionException e ) {
            abandon( id, job );
            throw new IllegalStateException( "import executor rejected the job", e );
        } catch ( final RuntimeException e ) {
            abandon( id, job );
            throw e;
        }
        return job;
    }

    private synchronized void release( final String owner ) {
        reserved.remove( owner );
    }

    private void abandon( final String id, final VaultImportJob job ) {
        jobs.remove( id );
        job.discardUpload();
    }

    /** Number of jobs held (running or retained); a test seam. */
    synchronized int size() {
        return jobs.size();
    }

    public synchronized Optional< VaultImportJob > find( final String jobId ) {
        evictExpired();
        return Optional.ofNullable( jobs.get( jobId ) );
    }

    /** The owner's newest job that is running or finished within {@link #RETENTION}. */
    public synchronized Optional< VaultImportJob > current( final String owner ) {
        evictExpired();
        return jobs.values().stream().filter( j -> j.owner().equals( owner ) )
            .max( Comparator.comparing( VaultImportJob::createdAt ) );
    }

    /** Opportunistic eviction when a job finishes, so retained results expire without further requests. */
    private synchronized void evictExpiredNow() {
        evictExpired();
    }

    private void evictExpired() {
        final Instant cutoff = clock.instant().minus( RETENTION );
        jobs.values().removeIf( j -> !j.isRunning() && j.finishedAt() != null && j.finishedAt().isBefore( cutoff ) );
    }

    /**
     * Interrupts running jobs, waits up to {@link #CLOSE_WAIT} for them to stop, then deletes every spooled upload.
     * The wait happens outside the registry lock: a finishing job takes that lock to evict expired results.
     */
    @Override
    public void close() {
        if ( executor instanceof ExecutorService es ) {
            es.shutdownNow();
            try {
                if ( !es.awaitTermination( CLOSE_WAIT.toSeconds(), TimeUnit.SECONDS ) ) {
                    LOG.warn( "Obsidian import jobs still running {} s after shutdown; their uploads are deleted anyway",
                              CLOSE_WAIT.toSeconds() );
                }
            } catch ( final InterruptedException e ) {
                LOG.warn( "Interrupted while waiting for Obsidian import jobs to stop: {}", e.getMessage(), e );
                Thread.currentThread().interrupt();
            }
        }
        synchronized ( this ) {
            jobs.values().forEach( VaultImportJob::discardUpload );
        }
    }

    /** One of the {@code wikantik.import.maxConcurrentPlans} permits; closing it more than once releases it once. */
    public final class PlanPermit implements AutoCloseable {
        private final AtomicBoolean held = new AtomicBoolean( true );

        private PlanPermit() {
        }

        @Override
        public void close() {
            if ( held.compareAndSet( true, false ) ) {
                plans.release();
            }
        }
    }

    /** A held job slot for one owner: {@link #start} it once, or {@link #close} it to release the slot. */
    public final class Reservation implements AutoCloseable {
        private final String owner;
        private boolean open = true;

        private Reservation( final String owner ) {
            this.owner = owner;
        }

        /** Starts the job in this slot. */
        public VaultImportJob start( final Function< String, VaultImportJob > factory ) {
            synchronized ( ImportJobRegistry.this ) {
                if ( !open ) {
                    throw new IllegalStateException( "reservation already used or released" );
                }
                open = false;
                return startReserved( owner, factory );
            }
        }

        @Override
        public void close() {
            synchronized ( ImportJobRegistry.this ) {
                if ( open ) {
                    open = false;
                    release( owner );
                }
            }
        }
    }
}
