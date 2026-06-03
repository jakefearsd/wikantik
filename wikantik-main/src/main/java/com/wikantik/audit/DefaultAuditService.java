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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultAuditService implements AuditService {

    private static final Logger LOG = LogManager.getLogger( DefaultAuditService.class );

    private final AuditRepository repository;
    private final BlockingQueue<AuditEntry> queue;
    private final AtomicLong dropped = new AtomicLong();

    public DefaultAuditService( final AuditRepository repository, final int queueCapacity ) {
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>( queueCapacity );
    }

    @Override
    public void record( final AuditEntry entry ) {
        if ( !queue.offer( entry ) ) {
            final long n = dropped.incrementAndGet();
            // Never swallow: log the drop with context (per project rules).
            LOG.warn( "Audit queue full; dropped audit entry type={} (total dropped={})",
                      entry.eventType(), n );
        }
    }

    /** Drains up to maxBatch entries into out; returns the count drained.
     *  Called only by the writer thread. */
    int drainTo( final List<AuditEntry> out, final int maxBatch ) {
        return queue.drainTo( out, maxBatch );
    }

    /** Blocks until one entry is available or the timeout elapses; used by the
     *  writer to avoid busy-spinning. */
    AuditEntry pollOne( final long millis ) throws InterruptedException {
        return queue.poll( millis, java.util.concurrent.TimeUnit.MILLISECONDS );
    }

    AuditRepository repository() { return repository; }

    @Override public List<PersistedAuditEntry> query( final AuditQuery q ) { return repository.query( q ); }
    @Override public Optional<Long> verifyChain( long from, long to ) { return repository.verifyChain( from, to ); }
    @Override public long droppedCount() { return dropped.get(); }
}
