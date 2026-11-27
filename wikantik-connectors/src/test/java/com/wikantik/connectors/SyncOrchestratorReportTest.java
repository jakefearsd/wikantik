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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.api.connectors.DerivedPageSink;
import com.wikantik.api.connectors.IngestOutcome;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exact report arithmetic, tombstone bookkeeping and cursor handling of {@link SyncOrchestrator}. */
class SyncOrchestratorReportTest {

    private static SourceItem item( final String uri, final String hash ) {
        return SyncOrchestratorTest.item( uri, hash );
    }

    /** Sink whose outcome per uri is scripted; unlisted uris are CREATED. */
    private static final class ScriptedSink implements DerivedPageSink {
        final Map< String, IngestOutcome.Status > script;
        final List< String > deleted = new ArrayList<>();
        ScriptedSink( final Map< String, IngestOutcome.Status > script ) { this.script = script; }
        @Override public IngestOutcome ingest( final String id, final SourceItem i ) {
            return new IngestOutcome( "Page-" + i.sourceUri(), script.getOrDefault( i.sourceUri(), IngestOutcome.Status.CREATED ) );
        }
        @Override public void delete( final String pageName ) { deleted.add( pageName ); }
    }

    private static SourceConnector scripted( final List< SyncBatch > batches, final List< SyncCursor > polledWith ) {
        final int[] n = { 0 };
        return new SourceConnector() {
            @Override public String connectorId() { return "c1"; }
            @Override public SyncBatch poll( final SyncCursor cur ) {
                polledWith.add( cur );
                return batches.get( n[ 0 ]++ );
            }
        };
    }

    private static void known( final SyncOrchestratorTest.FakeStore s, final String uri, final String page ) {
        s.hash.put( uri, "h-" + uri );
        s.page.put( uri, page );
    }

    @Test
    void reportCountsEveryOutcomeAcrossBatches() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        known( store, "u:same", "Same" );
        store.hash.put( "u:same", "hash-same" );
        known( store, "u:gone1", "Gone1" );
        known( store, "u:gone2", "Gone2" );
        known( store, "u:explicit", "Explicit" );
        final ScriptedSink sink = new ScriptedSink( Map.of(
                "u:upd1", IngestOutcome.Status.UPDATED, "u:upd2", IngestOutcome.Status.UPDATED,
                "u:upd3", IngestOutcome.Status.UPDATED,
                "u:sinkSame", IngestOutcome.Status.UNCHANGED,
                "u:bad1", IngestOutcome.Status.FAILED, "u:bad2", IngestOutcome.Status.FAILED ) );
        final SyncBatch b1 = new SyncBatch( List.of( item( "u:new1", "a" ), item( "u:new2", "b" ), item( "u:new3", "c" ),
                item( "u:upd1", "d" ), item( "u:upd2", "e" ), item( "u:upd3", "f" ) ),
                List.of( "u:explicit" ), new SyncCursor( "p2" ), false );
        final SyncBatch b2 = new SyncBatch( List.of( item( "u:same", "hash-same" ), item( "u:sinkSame", "g" ),
                item( "u:bad1", "h" ), item( "u:bad2", "i" ) ), List.of(), new SyncCursor( "done" ), true );

        final SyncReport r = new SyncOrchestrator( store, sink ).sync( scripted( List.of( b1, b2 ), new ArrayList<>() ) );

        assertEquals( 3, r.created() );
        assertEquals( 3, r.updated() );
        assertEquals( 2, r.unchanged(), "hash-equal item plus a sink-reported UNCHANGED" );
        assertEquals( 3, r.deleted(), "one explicit tombstone plus two derived (absent) ones" );
        assertEquals( 2, r.failed() );
        assertEquals( List.of( "Explicit", "Gone1", "Gone2" ),
                sink.deleted.stream().sorted().toList() );
    }

    @Test
    void sinkReportedUnchangedStillRecordsTheHash() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        final ScriptedSink sink = new ScriptedSink( Map.of( "u:a", IngestOutcome.Status.UNCHANGED ) );
        new SyncOrchestrator( store, sink ).sync( scripted( List.of(
                new SyncBatch( List.of( item( "u:a", "ha" ) ), List.of(), new SyncCursor( "c" ), true ) ), new ArrayList<>() ) );
        assertEquals( "ha", store.hash.get( "u:a" ) );
        assertEquals( "Page-u:a", store.page.get( "u:a" ) );
    }

    @Test
    void explicitTombstoneOfAnUnknownUriDeletesAndCountsNothing() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        known( store, "u:keep", "Keep" );
        final ScriptedSink sink = new ScriptedSink( Map.of() );
        final SyncReport r = new SyncOrchestrator( store, sink ).sync( scripted( List.of(
                new SyncBatch( List.of( item( "u:keep", "h-u:keep" ) ), List.of( "u:never-synced" ),
                        new SyncCursor( "c" ), true ) ), new ArrayList<>() ) );
        assertEquals( 0, r.deleted() );
        assertTrue( sink.deleted.isEmpty() );
    }

    @Test
    void tombstoneRemovesTheSyncStateSoItIsNotDeletedTwice() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        known( store, "u:keep", "Keep" );
        known( store, "u:dead", "Dead" );
        final ScriptedSink sink = new ScriptedSink( Map.of() );
        // u:dead is both explicitly tombstoned and absent from the snapshot
        final SyncReport r = new SyncOrchestrator( store, sink ).sync( scripted( List.of(
                new SyncBatch( List.of( item( "u:keep", "h-u:keep" ) ), List.of( "u:dead" ), new SyncCursor( "c" ), true ) ),
                new ArrayList<>() ) );
        assertEquals( 1, r.deleted() );
        assertEquals( List.of( "Dead" ), sink.deleted );
        assertFalse( store.hash.containsKey( "u:dead" ) );
        assertFalse( store.page.containsKey( "u:dead" ) );
        assertTrue( store.hash.containsKey( "u:keep" ) );
    }

    @Test
    void derivedTombstoneCountAddsToTheExplicitOne() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        known( store, "u:keep", "Keep" );
        known( store, "u:x", "X" );
        known( store, "u:gone", "Gone" );
        final SyncReport r = new SyncOrchestrator( store, new ScriptedSink( Map.of() ) ).sync( scripted( List.of(
                new SyncBatch( List.of( item( "u:keep", "h-u:keep" ) ), List.of( "u:x" ), new SyncCursor( "c" ), true ) ),
                new ArrayList<>() ) );
        assertEquals( 2, r.deleted() );
    }

    @Test
    void firstPollReceivesTheStoredCursorAndEachNextOneThePreviousBatchsCursor() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        store.cursor.put( "c1", "resume-here" );
        final List< SyncCursor > polled = new ArrayList<>();
        new SyncOrchestrator( store, new ScriptedSink( Map.of() ) ).sync( scripted( List.of(
                new SyncBatch( List.of( item( "u:a", "a" ) ), List.of(), new SyncCursor( "mid" ), false ),
                new SyncBatch( List.of( item( "u:b", "b" ) ), List.of(), new SyncCursor( "end" ), true ) ), polled ) );
        assertEquals( List.of( "resume-here", "mid" ), polled.stream().map( SyncCursor::value ).toList() );
        assertEquals( "end", store.cursor.get( "c1" ) );
    }

    @Test
    void freshConnectorIsPolledWithANullCursor() {
        final List< SyncCursor > polled = new ArrayList<>();
        new SyncOrchestrator( new SyncOrchestratorTest.FakeStore(), new ScriptedSink( Map.of() ) ).sync( scripted( List.of(
                new SyncBatch( List.of(), List.of(), new SyncCursor( "x" ), true ) ), polled ) );
        assertEquals( 1, polled.size() );
        assertNull( polled.get( 0 ) );
    }

    @Test
    void cursorIsPersistedAfterEachBatchSoACrashResumesFromTheLastProcessedOne() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        final int[] n = { 0 };
        final SourceConnector crashing = new SourceConnector() {
            @Override public String connectorId() { return "c1"; }
            @Override public SyncBatch poll( final SyncCursor cur ) {
                if ( n[ 0 ]++ == 0 ) {
                    return new SyncBatch( List.of( item( "u:a", "a" ) ), List.of(), new SyncCursor( "after-1" ), false );
                }
                throw new IllegalStateException( "upstream died" );
            }
        };
        try {
            new SyncOrchestrator( store, new ScriptedSink( Map.of() ) ).sync( crashing );
            throw new AssertionError( "the crash must propagate to the caller (the runtime isolates it)" );
        } catch ( final IllegalStateException expected ) {
            assertEquals( "upstream died", expected.getMessage() );
        }
        assertEquals( "after-1", store.cursor.get( "c1" ) );
        assertEquals( "a", store.hash.get( "u:a" ) );
    }

    @Test
    void incompleteBatchWithAnUnadvancedCursorStopsAfterOnePollAndPersistsIt() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        store.cursor.put( "c1", "stuck" );
        known( store, "u:old", "Old" );
        final List< SyncCursor > polled = new ArrayList<>();
        final SyncReport r = new SyncOrchestrator( store, new ScriptedSink( Map.of() ) ).sync( scripted( List.of(
                new SyncBatch( List.of( item( "u:a", "a" ) ), List.of(), new SyncCursor( "stuck" ), false ) ), polled ) );
        assertEquals( 1, polled.size() );
        assertEquals( 1, r.created() );
        assertEquals( 0, r.deleted(), "an undrained sync must not derive tombstones" );
        assertEquals( "stuck", store.cursor.get( "c1" ) );
    }

    @Test
    void anAdvancedCursorOnAnIncompleteBatchKeepsDraining() {
        final List< SyncCursor > polled = new ArrayList<>();
        new SyncOrchestrator( new SyncOrchestratorTest.FakeStore(), new ScriptedSink( Map.of() ) ).sync( scripted( List.of(
                new SyncBatch( List.of(), List.of(), new SyncCursor( "1" ), false ),
                new SyncBatch( List.of(), List.of(), new SyncCursor( "2" ), false ),
                new SyncBatch( List.of(), List.of(), new SyncCursor( "3" ), true ) ), polled ) );
        assertEquals( 3, polled.size() );
    }

    @Test
    void aCompleteBatchWithANullCursorClearsTheStoredCursor() {
        final SyncOrchestratorTest.FakeStore store = new SyncOrchestratorTest.FakeStore();
        store.cursor.put( "c1", "old" );
        new SyncOrchestrator( store, new ScriptedSink( Map.of() ) ).sync( scripted( List.of(
                new SyncBatch( List.of(), List.of(), null, true ) ), new ArrayList<>() ) );
        assertTrue( store.cursor.containsKey( "c1" ) );
        assertNull( store.cursor.get( "c1" ) );
    }
}
