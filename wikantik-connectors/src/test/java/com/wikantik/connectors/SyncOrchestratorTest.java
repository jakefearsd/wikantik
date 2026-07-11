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
    }
    /** Records ingest/delete calls; page name = uri. */
    static final class FakeSink implements DerivedPageSink {
        final List< String > ingested = new ArrayList<>();
        final List< String > deleted = new ArrayList<>();
        public IngestOutcome ingest( SourceItem i ) { ingested.add( i.sourceUri() ); return new IngestOutcome( i.sourceUri(), IngestOutcome.Status.CREATED ); }
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

    @Test void failedIngestIsNotRecordedSoItRetries() {
        FakeStore store = new FakeStore();
        DerivedPageSink failing = new DerivedPageSink() {
            public IngestOutcome ingest( SourceItem i ) { return new IngestOutcome( i.sourceUri(), IngestOutcome.Status.FAILED ); }
            public void delete( String p ) { }
        };
        new SyncOrchestrator( store, failing ).sync(
            single( new SyncBatch( List.of( item( "file:a.md", "h1" ) ), List.of(), new SyncCursor( "c" ), true ) ) );
        assertTrue( store.syncedHash( "c1", "file:a.md" ).isEmpty() );       // not recorded → next run retries
    }
}
