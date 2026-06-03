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
    private volatile boolean running = true;

    public AuditWriterThread( final DefaultAuditService service, final AuditRepository repository ) {
        super( "audit-writer" );
        setDaemon( true );
        this.service = service;
        this.repository = repository;
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
                repository.append( batch );
            } catch ( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                break;
            } catch ( final RuntimeException e ) {
                // Never swallow: a persistence failure is logged with context.
                // The batch is lost (documented v1 limit); loop continues.
                LOG.warn( "Audit writer failed to persist a batch", e );
            }
        }
        // Best-effort final drain on shutdown.
        try {
            final List<AuditEntry> tail = new ArrayList<>();
            if ( service.drainTo( tail, MAX_BATCH ) > 0 ) repository.append( tail );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Audit writer failed final drain on shutdown", e );
        }
    }

    public void shutdownWriter() { running = false; interrupt(); }
}
