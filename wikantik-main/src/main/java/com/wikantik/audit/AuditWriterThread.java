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
package com.wikantik.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.List;

/** Single consumer that drains the audit queue and persists batches. The
 *  repository's append() assigns seq + chain hashes atomically (under a Postgres
 *  advisory lock in the JDBC impl), so this thread is the only writer. */
public final class AuditWriterThread extends Thread {

    private static final Logger LOG = LogManager.getLogger( AuditWriterThread.class );
    private static final int MAX_BATCH = 256;
    private static final long POLL_MILLIS = 200;

    private final DefaultAuditService service;
    private final AuditRepository repository;
    static final int MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BACKOFF_MILLIS = 250;

    private final long backoffMillis;
    private volatile boolean running = true;

    public AuditWriterThread( final DefaultAuditService service, final AuditRepository repository ) {
        this( service, repository, DEFAULT_BACKOFF_MILLIS );
    }

    AuditWriterThread( final DefaultAuditService service, final AuditRepository repository, final long backoffMillis ) {
        super( "audit-writer" );
        setDaemon( true );
        this.service = service;
        this.repository = repository;
        this.backoffMillis = backoffMillis;
    }

    /** Persists a batch, retrying with backoff; returns false if it had to be dropped. Order is kept
     *  because the whole batch is re-appended (the append is one transaction) before anything newer. */
    boolean persist( final List<AuditEntry> batch ) {
        RuntimeException last = null;
        for ( int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++ ) {
            try {
                repository.append( batch );
                return true;
            } catch ( final RuntimeException e ) {
                last = e;
                LOG.warn( "Audit writer failed to persist a batch of {} (attempt {}/{})",
                        batch.size(), attempt, MAX_ATTEMPTS, e );
            }
            if ( attempt < MAX_ATTEMPTS ) {
                try {
                    Thread.sleep( backoffMillis * attempt );
                } catch ( final InterruptedException ie ) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        final AuditEntry first = batch.get( 0 );
        final AuditEntry end = batch.get( batch.size() - 1 );
        // LOG.error justified: audit entries are permanently lost after exhausting retries
        LOG.error( "Audit batch of {} entries DROPPED after retries; first={}@{} last={}@{}",
                batch.size(), first.eventType(), first.eventTime(), end.eventType(), end.eventTime(), last );
        return false;
    }

    @Override
    public void run() {
        while ( running ) {
            try {
                final AuditEntry first = service.pollOne( POLL_MILLIS );
                if ( first == null ) continue;
                final List<AuditEntry> batch = new ArrayList<>();
                batch.add( first );
                service.drainTo( batch, MAX_BATCH - 1 );
                persist( batch );
            } catch ( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Best-effort final drain on shutdown.
        try {
            final List<AuditEntry> tail = new ArrayList<>();
            if ( service.drainTo( tail, MAX_BATCH ) > 0 ) persist( tail );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Audit writer failed final drain on shutdown", e );
        }
    }

    public void shutdownWriter() { running = false; interrupt(); }
}
