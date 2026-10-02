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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** In-memory registry of import jobs: one running job per user, a global cap, results kept for {@link #RETENTION}. */
public final class ImportJobRegistry implements AutoCloseable {

    public static final Duration RETENTION = Duration.ofHours( 1 );

    private final int maxConcurrent;
    private final Clock clock;
    private final Executor executor;
    private final Map< String, VaultImportJob > jobs = new LinkedHashMap<>();

    public ImportJobRegistry( final int maxConcurrent, final Clock clock, final Executor executor ) {
        this.maxConcurrent = maxConcurrent;
        this.clock = clock;
        this.executor = executor;
    }

    /** A registry running jobs on a cached pool of daemon threads named {@code obsidian-import-N}. */
    public static ImportJobRegistry create( final int maxConcurrent ) {
        final AtomicInteger n = new AtomicInteger();
        final ExecutorService pool = Executors.newCachedThreadPool( r -> {
            final Thread t = new Thread( r, "obsidian-import-" + n.incrementAndGet() );
            t.setDaemon( true );
            return t;
        } );
        return new ImportJobRegistry( maxConcurrent, Clock.systemUTC(), pool );
    }

    /** Throws if {@code owner} could not start a job right now. */
    public synchronized void ensureCanStart( final String owner ) throws ImportJobConflictException {
        long running = 0;
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

    /** Registers the job built by {@code factory} (given its new id) and queues it for execution. */
    public synchronized VaultImportJob start( final String owner, final Function< String, VaultImportJob > factory )
            throws ImportJobConflictException {
        evictExpired();
        ensureCanStart( owner );
        final String id = UUID.randomUUID().toString();
        final VaultImportJob job = factory.apply( id );
        jobs.put( id, job );
        try {
            executor.execute( job );
        } catch ( final RejectedExecutionException e ) {
            jobs.remove( id );
            job.discardUpload();
            throw new IllegalStateException( "import executor rejected the job", e );
        }
        return job;
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

    private void evictExpired() {
        final Instant cutoff = clock.instant().minus( RETENTION );
        jobs.values().removeIf( j -> !j.isRunning() && j.finishedAt() != null && j.finishedAt().isBefore( cutoff ) );
    }

    @Override
    public synchronized void close() {
        if ( executor instanceof ExecutorService es ) {
            es.shutdownNow();
        }
        jobs.values().forEach( VaultImportJob::discardUpload );
    }
}
