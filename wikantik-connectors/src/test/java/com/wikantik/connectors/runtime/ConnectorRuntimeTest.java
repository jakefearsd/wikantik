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
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.*;
import com.wikantik.connectors.SyncOrchestrator;
import com.wikantik.connectors.SyncReport;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectorRuntimeTest {

    private static SourceConnector connector( String id ) {
        return new SourceConnector() {
            public String connectorId() { return id; }
            public SyncBatch poll( SyncCursor c ) {
                return new SyncBatch( List.of(), List.of(), new SyncCursor( "done" ), true );
            }
        };
    }

    /** Connector whose {@code poll()} records its id into {@code synced} — used to observe which
     *  connectors {@link ConnectorRuntime#syncDue} actually ran. */
    private static SourceConnector trackingConnector( String id, Set< String > synced ) {
        return new SourceConnector() {
            public String connectorId() { return id; }
            public SyncBatch poll( SyncCursor c ) {
                synced.add( id );
                return new SyncBatch( List.of(), List.of(), new SyncCursor( "done" ), true );
            }
        };
    }

    private static ConnectorRuntime runtime( DataSource ds, SourceConnector... cs ) {
        Map<String,SourceConnector> byId = new LinkedHashMap<>();
        Map<String,String> typeById = new LinkedHashMap<>();
        for ( SourceConnector c : cs ) { byId.put( c.connectorId(), c ); typeById.put( c.connectorId(), "filesystem" ); }
        ConnectorRegistry reg = new ConnectorRegistry( byId, typeById );
        // A real orchestrator over fake state + fake sink so syncNow returns a real SyncReport.
        SyncStateStore store = mock( SyncStateStore.class );
        when( store.loadCursor( anyString() ) ).thenReturn( Optional.empty() );
        when( store.syncedHash( anyString(), anyString() ) ).thenReturn( Optional.empty() );
        when( store.knownUris( anyString() ) ).thenReturn( List.of() );
        DerivedPageSink sink = mock( DerivedPageSink.class );
        SyncOrchestrator orch = new SyncOrchestrator( store, sink );
        return new ConnectorRuntime( reg, orch, new ConnectorStatusReader( ds ) );
    }

    /** Runtime with connector "c1" wired to an orchestrator stub returning {@code SyncReport(1,0,0,0,0)}. */
    private static ConnectorRuntime runtimeWith( final RunRecorder rec, final boolean syncingEnabled ) {
        final SyncOrchestrator orch = mock( SyncOrchestrator.class );
        when( orch.sync( any() ) ).thenReturn( new SyncReport( 1, 0, 0, 0, 0 ) );
        return runtimeWith( rec, syncingEnabled, orch );
    }

    private static ConnectorRuntime runtimeWith( final RunRecorder rec, final boolean syncingEnabled, final SyncOrchestrator orch ) {
        final ConnectorRegistry reg = new ConnectorRegistry( Map.of( "c1", connector( "c1" ) ), Map.of( "c1", "filesystem" ) );
        return new ConnectorRuntime( reg, orch, new ConnectorStatusReader( mock( DataSource.class ) ), rec, syncingEnabled );
    }

    private static RunRecorder noopRecorder() {
        return new RunRecorder() {
            @Override public long start( final String id, final String trigger ) { return -1L; }
            @Override public void finish( final long runId, final SyncReport r ) { }
            @Override public void fail( final long runId, final String error ) { }
        };
    }

    @Test void syncNowRunsRegisteredConnector() {
        // ds unused by syncNow; pass a mock
        SyncReport r = runtime( mock( DataSource.class ), connector( "fs1" ) ).syncNow( "fs1" );
        assertNotNull( r );          // empty fixture → 0 of everything, but a real report
        assertEquals( 0, r.created() + r.updated() + r.unchanged() + r.deleted() + r.failed() );
    }

    @Test void syncNowUnknownIdThrows() {
        assertThrows( IllegalArgumentException.class,
            () -> runtime( mock( DataSource.class ), connector( "fs1" ) ).syncNow( "nope" ) );
    }

    @Test void dueTickSchedulerStarts() {
        ConnectorRuntime rt = runtime( mock( DataSource.class ), connector( "fs1" ) );
        rt.startDueTickScheduler( id -> 24L );
        assertTrue( rt.isSchedulerRunning() );
        rt.stop();
        assertFalse( rt.isSchedulerRunning() );
    }

    // A manual /admin sync must not run concurrently with a scheduled sync of the SAME connector —
    // interleaved tombstone/re-sync passes can strand sync-state rows pointing at deleted pages.
    @Test void concurrentSyncOfSameConnectorIsRejected() throws Exception {
        var started = new java.util.concurrent.CountDownLatch( 1 );
        var release = new java.util.concurrent.CountDownLatch( 1 );
        SourceConnector blocking = new SourceConnector() {
            public String connectorId() { return "slow"; }
            public SyncBatch poll( SyncCursor c ) {
                started.countDown();
                try { release.await(); } catch ( InterruptedException e ) { Thread.currentThread().interrupt(); }
                return new SyncBatch( List.of(), List.of(), new SyncCursor( "done" ), true );
            }
        };
        ConnectorRuntime rt = runtime( mock( DataSource.class ), blocking );
        Thread first = new Thread( () -> rt.syncNow( "slow" ) );
        first.start();
        assertTrue( started.await( 5, java.util.concurrent.TimeUnit.SECONDS ) );
        // preemptive timeout: an implementation that does NOT reject would enter poll() and block on
        // the latch — the test must fail fast (red), not deadlock the suite
        assertTimeoutPreemptively( java.time.Duration.ofSeconds( 5 ),
            () -> assertThrows( SyncInProgressException.class, () -> rt.syncNow( "slow" ),
                "second sync of the same connector must be rejected while the first runs" ) );
        release.countDown();
        first.join( 5000 );
        assertNotNull( rt.syncNow( "slow" ), "sync must work again once the first run finishes" );
    }

    @Test void concurrentSyncOfDifferentConnectorsIsAllowed() throws Exception {
        var started = new java.util.concurrent.CountDownLatch( 1 );
        var release = new java.util.concurrent.CountDownLatch( 1 );
        SourceConnector blocking = new SourceConnector() {
            public String connectorId() { return "slow"; }
            public SyncBatch poll( SyncCursor c ) {
                started.countDown();
                try { release.await(); } catch ( InterruptedException e ) { Thread.currentThread().interrupt(); }
                return new SyncBatch( List.of(), List.of(), new SyncCursor( "done" ), true );
            }
        };
        ConnectorRuntime rt = runtime( mock( DataSource.class ), blocking, connector( "fast" ) );
        Thread first = new Thread( () -> rt.syncNow( "slow" ) );
        first.start();
        assertTrue( started.await( 5, java.util.concurrent.TimeUnit.SECONDS ) );
        assertTimeoutPreemptively( java.time.Duration.ofSeconds( 5 ),
            () -> assertNotNull( rt.syncNow( "fast" ), "a DIFFERENT connector must sync while 'slow' runs" ) );
        release.countDown();
        first.join( 5000 );
    }

    @Test void doubleStartRestartsWithoutLeakingAndStopsCleanly() {
        ConnectorRuntime rt = runtime( mock( DataSource.class ), connector( "fs1" ) );
        rt.startDueTickScheduler( id -> 24L );
        rt.startDueTickScheduler( id -> 24L );  // idempotent restart — must not orphan the first executor
        assertTrue( rt.isSchedulerRunning() );
        rt.stop();
        assertFalse( rt.isSchedulerRunning() );
    }

    @Test void syncNowRecordsRunHistory() {
        final List< String > events = new ArrayList<>();
        final RunRecorder rec = new RunRecorder() {
            @Override public long start( final String id, final String trigger ) { events.add( "start:" + id + ":" + trigger ); return 7L; }
            @Override public void finish( final long runId, final SyncReport r ) { events.add( "finish:" + runId + ":" + r.created() ); }
            @Override public void fail( final long runId, final String error ) { events.add( "fail:" + runId ); }
        };
        final ConnectorRuntime rt = runtimeWith( rec, true );
        rt.syncNow( "c1", "scheduled" );
        assertEquals( List.of( "start:c1:scheduled", "finish:7:1" ), events );
    }

    @Test void syncNowRecordsFailureWhenOrchestratorThrows() {
        final List< String > events = new ArrayList<>();
        final RunRecorder rec = new RunRecorder() {
            @Override public long start( final String id, final String trigger ) { events.add( "start:" + id + ":" + trigger ); return 7L; }
            @Override public void finish( final long runId, final SyncReport r ) { events.add( "finish:" + runId + ":" + r.created() ); }
            @Override public void fail( final long runId, final String error ) { events.add( "fail:" + runId ); }
        };
        final SyncOrchestrator orch = mock( SyncOrchestrator.class );
        when( orch.sync( any() ) ).thenThrow( new RuntimeException( "db down" ) );
        final ConnectorRuntime rt = runtimeWith( rec, true, orch );
        assertThrows( RuntimeException.class, () -> rt.syncNow( "c1", "manual" ) );
        assertEquals( List.of( "start:c1:manual", "fail:7" ), events );
    }

    @Test void killSwitchRejectsSync() {
        final ConnectorRuntime rt = runtimeWith( noopRecorder(), false );   // syncingEnabled=false
        assertThrows( ConnectorsDisabledException.class, () -> rt.syncNow( "c1" ) );
    }

    // Regression: runRecorder.start() runs after the lock is acquired — if it throws (e.g.
    // JdbcSyncRunStore wrapping a SQLException), the lock MUST still be released or the
    // connector is permanently stuck returning SyncInProgressException until restart.
    // The first sync runs on a SEPARATE thread: ReentrantLock is reentrant, so a leaked
    // lock is invisible to a same-thread retry — only a different thread (scheduler vs.
    // admin request) observes the stuck connector.
    @Test void lockReleasedWhenRunRecorderStartThrows() throws Exception {
        final var firstCall = new java.util.concurrent.atomic.AtomicBoolean( true );
        final RunRecorder rec = new RunRecorder() {
            @Override public long start( final String id, final String trigger ) {
                if ( firstCall.getAndSet( false ) ) throw new RuntimeException( "run store down" );
                return 7L;
            }
            @Override public void finish( final long runId, final SyncReport r ) { }
            @Override public void fail( final long runId, final String error ) { }
        };
        final ConnectorRuntime rt = runtimeWith( rec, true );
        final var thrown = new java.util.concurrent.atomic.AtomicReference< RuntimeException >();
        final Thread first = new Thread( () -> {
            try {
                rt.syncNow( "c1" );
            } catch ( final RuntimeException e ) {
                thrown.set( e );
            }
        } );
        first.start();
        first.join( 5000 );
        assertNotNull( thrown.get(), "start() failure must propagate" );
        assertNotNull( rt.syncNow( "c1" ), "lock must be released after start() throws — not SyncInProgressException" );
    }

    @Test void swapRegistryChangesVisibleConnectors() {
        final ConnectorRuntime rt = runtimeWith( noopRecorder(), true );    // registry contains c1
        rt.swapRegistry( new ConnectorRegistry( Map.of( "c2", connector( "c2" ) ), Map.of( "c2", "feed" ), Map.of( "c2", "db" ) ) );
        assertThrows( IllegalArgumentException.class, () -> rt.syncNow( "c1" ) );
        assertEquals( "db", rt.registry().originOf( "c2" ) );
        assertEquals( "properties", rt.registry().originOf( "unknown" ) );
    }

    @Test void syncDueRunsOnlyDueConnectors() {
        final Instant now = Instant.parse( "2026-07-15T12:00:00Z" );
        final Set< String > syncedIds = new HashSet<>();
        final Map< String, SourceConnector > byId = new LinkedHashMap<>();
        final Map< String, String > typeById = new LinkedHashMap<>();
        for ( final String id : List.of( "fresh", "stale", "never", "manualOnly" ) ) {
            byId.put( id, trackingConnector( id, syncedIds ) );
            typeById.put( id, "filesystem" );
        }
        final ConnectorRegistry reg = new ConnectorRegistry( byId, typeById );

        final SyncStateStore store = mock( SyncStateStore.class );
        when( store.loadCursor( anyString() ) ).thenReturn( Optional.empty() );
        when( store.syncedHash( anyString(), anyString() ) ).thenReturn( Optional.empty() );
        when( store.knownUris( anyString() ) ).thenReturn( List.of() );
        final SyncOrchestrator orch = new SyncOrchestrator( store, mock( DerivedPageSink.class ) );

        final ConnectorStatusReader statusReader = mock( ConnectorStatusReader.class );
        when( statusReader.read( eq( "fresh" ), anyString() ) ).thenReturn(
            new ConnectorStatus( "fresh", "filesystem", now.minus( 1, ChronoUnit.HOURS ).toString(), "ok", 0 ) );
        when( statusReader.read( eq( "stale" ), anyString() ) ).thenReturn(
            new ConnectorStatus( "stale", "filesystem", now.minus( 25, ChronoUnit.HOURS ).toString(), "ok", 0 ) );
        when( statusReader.read( eq( "never" ), anyString() ) ).thenReturn(
            new ConnectorStatus( "never", "filesystem", null, null, 0 ) );
        when( statusReader.read( eq( "manualOnly" ), anyString() ) ).thenReturn(
            new ConnectorStatus( "manualOnly", "filesystem", null, null, 0 ) );

        final ConnectorRuntime rt = new ConnectorRuntime( reg, orch, statusReader, noopRecorder(), true );
        final Map< String, Long > hours = Map.of( "fresh", 24L, "stale", 24L, "never", 24L, "manualOnly", 0L );
        final int ran = rt.syncDue( hours::get, now );

        assertEquals( 2, ran );                          // stale + never
        assertEquals( Set.of( "stale", "never" ), syncedIds );
    }

    @Test void syncDueSkipsZeroInterval() {
        final ConnectorRuntime rt = runtime( mock( DataSource.class ), connector( "fs1" ) );
        assertEquals( 0, rt.syncDue( id -> 0L, Instant.now() ) );
    }

    @Test void syncDueSurvivesFailures() {
        final ConnectorRegistry reg = new ConnectorRegistry(
            Map.of( "bad", connector( "bad" ), "good", connector( "good" ) ),
            Map.of( "bad", "filesystem", "good", "filesystem" ) );

        final SyncOrchestrator orch = mock( SyncOrchestrator.class );
        when( orch.sync( any() ) ).thenAnswer( inv -> {
            final SourceConnector c = inv.getArgument( 0 );
            if ( "bad".equals( c.connectorId() ) ) throw new RuntimeException( "boom" );
            return new SyncReport( 1, 0, 0, 0, 0 );
        } );

        final ConnectorStatusReader statusReader = mock( ConnectorStatusReader.class );
        when( statusReader.read( anyString(), anyString() ) )
            .thenReturn( new ConnectorStatus( "x", "filesystem", null, null, 0 ) );   // never ran -> due

        final ConnectorRuntime rt = new ConnectorRuntime( reg, orch, statusReader, noopRecorder(), true );
        assertEquals( 1, rt.syncDue( id -> 1L, Instant.now() ) );   // only "good" counted, no exception escapes
    }
}
