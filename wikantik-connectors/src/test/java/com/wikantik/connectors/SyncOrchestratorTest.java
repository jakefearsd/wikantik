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
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncOrchestratorTest {

    /** In-memory SyncStateStore. */
    static final class FakeStore implements SyncStateStore {
        final Map< String, String > cursor = new HashMap<>();
        final Map< String, String > hash = new LinkedHashMap<>();      // uri -> hash
        final Map< String, String > page = new HashMap<>();            // uri -> pageName
        public Optional< SyncCursor > loadCursor( String id ) { return Optional.ofNullable( cursor.get( id ) ).map( SyncCursor::new ); }
        public void saveCursor( String id, SyncCursor c ) { cursor.put( id, c == null ? null : c.value() ); }
        public Optional< String > syncedHash( String id, String uri ) { return Optional.ofNullable( hash.get( uri ) ); }
        public void recordSynced( String id, String uri, String h, String pn, List< String > acl ) { hash.put( uri, h ); page.put( uri, pn ); }
        public Optional< String > pageNameFor( String id, String uri ) { return Optional.ofNullable( page.get( uri ) ); }
        public List< String > knownUris( String id ) { return new ArrayList<>( hash.keySet() ); }
        public void removeSynced( String id, String uri ) { hash.remove( uri ); page.remove( uri ); }
        public void purge( String id ) { }
        public List< SyncStateStore.SyncedItem > items( String id ) { return List.of(); }
    }
    /** Records ingest/delete calls; page name = uri. */
    static final class FakeSink implements DerivedPageSink {
        final List< String > ingested = new ArrayList<>();
        final List< String > deleted = new ArrayList<>();
        String lastConnectorId;
        public IngestOutcome ingest( String connectorId, SourceItem i ) { lastConnectorId = connectorId; ingested.add( i.sourceUri() ); return new IngestOutcome( i.sourceUri(), IngestOutcome.Status.CREATED ); }
        public void delete( String pageName ) { deleted.add( pageName ); }
    }
    static SourceItem item( String uri, String hash ) { return new SourceItem( uri, new byte[0], "text/markdown", Map.of(), List.of(), hash ); }
    static SourceConnector single( SyncBatch batch ) {
        return new SourceConnector() {
            public String connectorId() { return "c1"; }
            public SyncBatch poll( SyncCursor cur ) { return batch; }
        };
    }

    @Test void ingestsNewItemsAndPersistsCursor() {
        FakeStore store = new FakeStore(); FakeSink sink = new FakeSink();
        SyncReport r = new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ), item( "file:b.md", "h2" ) ), List.of(), new SyncCursor( "cur1" ), true ) ) );
        assertEquals( List.of( "file:a.md", "file:b.md" ), sink.ingested );
        assertEquals( "cur1", store.cursor.get( "c1" ) );
        assertEquals( 2, r.created() );
        assertEquals( "c1", sink.lastConnectorId );
    }

    @Test void skipsUnchangedByHash() {
        FakeStore store = new FakeStore(); store.hash.put( "file:a.md", "h1" ); store.page.put( "file:a.md", "file:a.md" );
        FakeSink sink = new FakeSink();
        SyncReport r = new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true ) ) );
        assertTrue( sink.ingested.isEmpty() );
        assertEquals( 1, r.unchanged() );
    }

    @Test void derivesTombstonesFromKnownUrisNotSeenThisScan() {
        FakeStore store = new FakeStore();
        store.hash.put( "file:gone.md", "hg" ); store.page.put( "file:gone.md", "Gone" );   // previously synced
        FakeSink sink = new FakeSink();
        new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true ) ) );
        assertEquals( List.of( "Gone" ), sink.deleted );                     // deleted-at-source
        assertTrue( store.hash.containsKey( "file:a.md" ) && !store.hash.containsKey( "file:gone.md" ) );
    }

    @Test void incompleteBatchDoesNotTombstone() {
        FakeStore store = new FakeStore(); store.hash.put( "file:x.md", "hx" ); store.page.put( "file:x.md", "X" );
        FakeSink sink = new FakeSink();
        new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of(), List.of(), new SyncCursor( "c" ), false ) ) );   // scan error / partial
        assertTrue( sink.deleted.isEmpty() );                                // absent != deleted on a partial batch
    }

    @Test void drainsMultipleBatchesInOneSyncCall() {
        FakeStore store = new FakeStore(); FakeSink sink = new FakeSink();
        SourceConnector paginating = new SourceConnector() {
            int calls = 0;
            public String connectorId() { return "c1"; }
            public SyncBatch poll( SyncCursor cur ) {
                calls++;
                if ( calls == 1 ) {
                    assertNull( cur );
                    return new SyncBatch( List.of( item( "file:p1.md", "h1" ) ), List.of(), new SyncCursor( "p2" ), false );
                }
                assertEquals( 2, calls );
                assertEquals( new SyncCursor( "p2" ), cur );
                return new SyncBatch( List.of( item( "file:p2.md", "h2" ) ), List.of(), new SyncCursor( "p2-done" ), true );
            }
        };
        SyncReport r = new SyncOrchestrator( store, sink ).sync( paginating );
        assertEquals( List.of( "file:p1.md", "file:p2.md" ), sink.ingested );
        assertEquals( "p2-done", store.cursor.get( "c1" ) );
        assertEquals( 2, r.created() );
    }

    // A connector that does NOT reflect the full corpus (e.g. a feed window) → orchestrator must NOT
    // tombstone a previously-synced URI merely because it's absent from this poll.
    @Test void archiveConnectorDoesNotTombstoneAgedOutUris() {
        FakeStore store = new FakeStore();
        store.hash.put( "file:gone.md", "hg" ); store.page.put( "file:gone.md", "Gone" );   // previously synced
        FakeSink sink = new FakeSink();
        SourceConnector windowed = new SourceConnector() {
            public String connectorId() { return "c1"; }
            public SyncBatch poll( SyncCursor c ) {
                return new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true );
            }
            @Override public boolean reflectsFullCorpus() { return false; }   // archive
        };
        new SyncOrchestrator( store, sink ).sync( windowed );
        assertTrue( sink.deleted.isEmpty(), "windowed connector must not tombstone aged-out URIs" );
        assertTrue( store.hash.containsKey( "file:gone.md" ), "aged-out URI stays synced (archived)" );
    }

    // CRITICAL guard: a full-corpus connector returning ZERO items while the store knows items is far
    // more likely an upstream outage (site down, API failure, missing credential) than a true total
    // wipe — the orchestrator must refuse to mass-tombstone every derived page.
    @Test void emptyCompleteSnapshotDoesNotMassTombstone() {
        FakeStore store = new FakeStore();
        store.hash.put( "u:a", "ha" ); store.page.put( "u:a", "A" );
        store.hash.put( "u:b", "hb" ); store.page.put( "u:b", "B" );
        FakeSink sink = new FakeSink();
        new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of(), List.of(), new SyncCursor( "c" ), true ) ) );
        assertTrue( sink.deleted.isEmpty(), "empty full-corpus snapshot must not mass-delete" );
        assertEquals( 2, store.hash.size(), "sync state must be preserved for the next healthy run" );
    }

    // Explicit (connector-asserted) tombstones are still honored even when the batch has no items —
    // the empty-snapshot guard only suppresses DERIVED (absence-based) tombstones.
    @Test void explicitTombstonesStillHonoredOnEmptyBatch() {
        FakeStore store = new FakeStore();
        store.hash.put( "u:a", "ha" ); store.page.put( "u:a", "A" );
        store.hash.put( "u:b", "hb" ); store.page.put( "u:b", "B" );
        FakeSink sink = new FakeSink();
        new SyncOrchestrator( store, sink ).sync(
            single( new SyncBatch( List.of(), List.of( "u:a" ), new SyncCursor( "c" ), true ) ) );
        assertEquals( List.of( "A" ), sink.deleted );
        assertTrue( store.hash.containsKey( "u:b" ), "derived tombstoning must not fire on an empty snapshot" );
    }

    // seen must accumulate across the whole drain: a paginating full-corpus connector's FINAL batch
    // must not tombstone items that arrived in EARLIER batches of the same sync() call.
    @Test void multiBatchDrainDoesNotTombstoneEarlierBatchItems() {
        FakeStore store = new FakeStore();
        store.hash.put( "file:p1.md", "old1" ); store.page.put( "file:p1.md", "P1" );
        store.hash.put( "file:p2.md", "old2" ); store.page.put( "file:p2.md", "P2" );
        store.hash.put( "file:gone.md", "hg" ); store.page.put( "file:gone.md", "Gone" );
        FakeSink sink = new FakeSink();
        SourceConnector paginating = new SourceConnector() {
            int calls = 0;
            public String connectorId() { return "c1"; }
            public SyncBatch poll( SyncCursor cur ) {
                calls++;
                if ( calls == 1 ) return new SyncBatch( List.of( item( "file:p1.md", "h1" ) ), List.of(), new SyncCursor( "b2" ), false );
                return new SyncBatch( List.of( item( "file:p2.md", "h2" ) ), List.of(), new SyncCursor( "done" ), true );
            }
        };
        new SyncOrchestrator( store, sink ).sync( paginating );
        assertEquals( List.of( "Gone" ), sink.deleted, "only the truly-absent URI is tombstoned" );
        assertTrue( store.hash.containsKey( "file:p1.md" ), "batch-1 item must survive the final batch's tombstone pass" );
    }

    @Test void failedIngestIsNotRecordedSoItRetries() {
        FakeStore store = new FakeStore();
        DerivedPageSink failing = new DerivedPageSink() {
            public IngestOutcome ingest( String connectorId, SourceItem i ) { return new IngestOutcome( i.sourceUri(), IngestOutcome.Status.FAILED ); }
            public void delete( String p ) { }
        };
        new SyncOrchestrator( store, failing ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true ) ) );
        assertTrue( store.syncedHash( "c1", "file:a.md" ).isEmpty() );       // not recorded → next run retries
    }
}
