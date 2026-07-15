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
import java.util.Objects;
import java.util.Set;

/**
 * Drives a {@link SourceConnector} into derived pages: hash-dedup, tombstone, cursor-resume.
 *
 * <p>Each {@link #sync} call drains the connector across as many {@link SourceConnector#poll} round-trips
 * as it takes to reach {@code batch.complete()}, honoring the {@code SyncBatch} pagination contract
 * ({@code nextCursor} + {@code complete}) so a connector can paginate a large source within one
 * {@code sync()} call. To guard against a connector that returns an un-advanced cursor on a scan error
 * (as {@link com.wikantik.connectors.filesystem.FilesystemSourceConnector} does, by design, to signal
 * "retry next scheduled run"), the loop stops as soon as an incomplete batch's {@code nextCursor} is equal
 * to the cursor it was polled with — that would otherwise spin forever making zero progress.
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
        SyncCursor cursor = store.loadCursor( id ).orElse( null );
        // accumulated across ALL batches of this drain — a paginating full-corpus connector's early
        // batches are not "absent from the source" just because the final batch didn't repeat them
        final Set< String > seen = new HashSet<>();
        final Set< String > explicitTombstones = new HashSet<>();
        boolean drainedComplete = false;

        while ( true ) {
            final SyncBatch batch = connector.poll( cursor );

            final ItemCounts counts = processBatchItems( id, batch, seen );
            created += counts.created();
            updated += counts.updated();
            unchanged += counts.unchanged();
            failed += counts.failed();

            deleted += applyExplicitTombstones( id, batch, explicitTombstones );

            final SyncCursor next = batch.nextCursor();
            store.saveCursor( id, next );     // persist AFTER processing this batch → crash-resume point

            if ( batch.complete() ) {
                drainedComplete = true;
                break;
            }
            if ( Objects.equals( next, cursor ) ) {
                // incomplete batch with a non-advancing cursor (e.g. scan error) — stop here rather than
                // spinning forever making zero progress; the caller's next scheduled sync() will retry.
                LOG.warn( "Sync '{}': incomplete batch with non-advancing cursor — stopping to avoid infinite loop", id );
                break;
            }
            cursor = next;
        }

        deleted += applyDerivedTombstones( id, connector, drainedComplete, seen, explicitTombstones );

        final SyncReport report = new SyncReport( created, updated, unchanged, deleted, failed );
        LOG.info( "Sync '{}' complete: {}", id, report );
        return report;
    }

    /** Ingests every item in one batch, tallying outcome counts and recording the source URI as
     *  {@code seen} so a later full-corpus reconcile knows it is still present at the source. */
    private ItemCounts processBatchItems( final String id, final SyncBatch batch, final Set< String > seen ) {
        int created = 0, updated = 0, unchanged = 0, failed = 0;
        for ( final SourceItem item : batch.items() ) {
            seen.add( item.sourceUri() );
            if ( store.syncedHash( id, item.sourceUri() ).filter( item.contentHash()::equals ).isPresent() ) {
                unchanged++;
                continue;
            }
            final IngestOutcome out = sink.ingest( id, item );
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
        return new ItemCounts( created, updated, unchanged, failed );
    }

    /** Applies the connector's explicit tombstones for this batch (incremental sources), recording
     *  each into {@code explicitTombstones} so a later full-corpus reconcile does not double-delete. */
    private int applyExplicitTombstones( final String id, final SyncBatch batch, final Set< String > explicitTombstones ) {
        int deleted = 0;
        for ( final String uri : batch.tombstonedUris() ) {
            explicitTombstones.add( uri );
            deleted += tombstone( id, uri );
        }
        return deleted;
    }

    /** Derived tombstones: only after a fully-drained (complete) sync of a full-corpus connector,
     *  judged against {@code seen} accumulated across the WHOLE drain. Guard: a full-corpus connector
     *  reporting ZERO items while the store knows items is far more likely an upstream outage
     *  (site down, API failure, missing credential) than a true total wipe — refuse to mass-delete
     *  every derived page; the next healthy sync reconciles genuine deletions. */
    private int applyDerivedTombstones( final String id, final SourceConnector connector, final boolean drainedComplete,
                                        final Set< String > seen, final Set< String > explicitTombstones ) {
        int deleted = 0;
        if ( drainedComplete && connector.reflectsFullCorpus() ) {
            final var known = store.knownUris( id );
            if ( seen.isEmpty() && !known.isEmpty() ) {
                LOG.warn( "Sync '{}': full-corpus connector returned an empty snapshot but {} URIs are known — "
                    + "refusing derived tombstones (likely upstream outage, not a true wipe). "
                    + "Clear the connector's sync state to force removal.", id, known.size() );
            } else {
                for ( final String uri : known ) {
                    if ( !seen.contains( uri ) && !explicitTombstones.contains( uri ) ) {
                        deleted += tombstone( id, uri );
                    }
                }
            }
        }
        return deleted;
    }

    private int tombstone( final String id, final String uri ) {
        final var page = store.pageNameFor( id, uri );
        if ( page.isEmpty() ) return 0;
        sink.delete( page.get() );
        store.removeSynced( id, uri );
        return 1;
    }

    private record ItemCounts( int created, int updated, int unchanged, int failed ) {}
}
