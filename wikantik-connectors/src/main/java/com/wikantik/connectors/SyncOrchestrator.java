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
package com.wikantik.connectors;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Drives a {@link SourceConnector} into derived pages: hash-dedup, tombstone, cursor-resume.
 *
 * <p>Each {@link #sync} call performs exactly one {@link SourceConnector#poll} round-trip — it does
 * NOT loop internally waiting for {@code batch.complete()}. A connector that needs several round-trips
 * to drain a full scan relies on its caller to invoke {@code sync} again on its own schedule, resuming
 * from the persisted cursor. This also guards against a connector that returns an un-advanced cursor on
 * a scan error (as {@link com.wikantik.connectors.filesystem.FilesystemSourceConnector} does, by design,
 * to signal "retry next scheduled run") — polling such a connector in a tight internal loop would spin
 * forever making zero progress.
 */
public final class SyncOrchestrator {

    private static final Logger LOG = LogManager.getLogger( SyncOrchestrator.class );
    private final SyncStateStore store;
    private final DerivedPageSink sink;

    public SyncOrchestrator( final SyncStateStore store, final DerivedPageSink sink ) {
        this.store = store;
        this.sink = sink;
    }

    public SyncReport sync( final SourceConnector connector ) {
        final String id = connector.connectorId();
        int created = 0, updated = 0, unchanged = 0, deleted = 0, failed = 0;
        final SyncCursor cursor = store.loadCursor( id ).orElse( null );

        final SyncBatch batch = connector.poll( cursor );
        final Set< String > seen = new HashSet<>();

        for ( final SourceItem item : batch.items() ) {
            seen.add( item.sourceUri() );
            if ( store.syncedHash( id, item.sourceUri() ).filter( item.contentHash()::equals ).isPresent() ) {
                unchanged++;
                continue;
            }
            final IngestOutcome out = sink.ingest( item );
            switch ( out.status() ) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case FAILED -> { failed++; LOG.warn( "Sync '{}': ingest FAILED for {}", id, item.sourceUri() ); }
            }
            if ( out.status() != IngestOutcome.Status.FAILED ) {
                store.recordSynced( id, item.sourceUri(), item.contentHash(), out.pageName(), item.aclRefs() );
            }
        }

        // explicit tombstones from the connector (incremental sources)
        for ( final String uri : batch.tombstonedUris() ) {
            deleted += tombstone( id, uri );
        }
        // derived tombstones: only on a COMPLETE batch, known URIs not seen this scan
        if ( batch.complete() ) {
            for ( final String uri : store.knownUris( id ) ) {
                if ( !seen.contains( uri ) && !batch.tombstonedUris().contains( uri ) ) {
                    deleted += tombstone( id, uri );
                }
            }
        }

        store.saveCursor( id, batch.nextCursor() );     // persist AFTER the batch → crash-resume point

        final SyncReport report = new SyncReport( created, updated, unchanged, deleted, failed );
        LOG.info( "Sync '{}' complete: {}", id, report );
        return report;
    }

    private int tombstone( final String id, final String uri ) {
        final var page = store.pageNameFor( id, uri );
        if ( page.isEmpty() ) return 0;
        sink.delete( page.get() );
        store.removeSynced( id, uri );
        return 1;
    }
}
