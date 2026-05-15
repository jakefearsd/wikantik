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
package com.wikantik.llm.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LlmActivityLogTest {

    private static LlmActivityLog log( final int maxRecords ) {
        return new LlmActivityLog( true, 60, maxRecords, 20 );
    }

    @Test
    void beginThenSucceedTransitionsToOk() {
        final LlmActivityLog log = log( 100 );
        final LlmCall call = log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "3 texts" );
        assertEquals( CallStatus.IN_FLIGHT, call.status() );

        log.succeed( call, "3 vectors" );
        assertEquals( CallStatus.OK, call.status() );
        assertTrue( call.durationMs() >= 0 );

        final LlmActivityLog.Snapshot snap = log.snapshot( 50, null, null );
        assertEquals( 1, snap.calls().size() );
        assertEquals( "OK", snap.calls().get( 0 ).status() );
        assertEquals( 0, snap.inFlight() );
    }

    @Test
    void failTransitionsToErrorAndKeepsMessage() {
        final LlmActivityLog log = log( 100 );
        final LlmCall call = log.begin( Subsystem.PROPOSAL_JUDGE, "ollama", "gemma", "chat", "p" );
        log.fail( call, new IllegalStateException( "boom" ) );
        assertEquals( CallStatus.ERROR, call.status() );
        assertTrue( call.errorMessage().contains( "boom" ) );
    }

    @Test
    void inFlightCallIsCountedAndVisible() {
        final LlmActivityLog log = log( 100 );
        log.begin( Subsystem.EMBEDDING, "ollama", "nomic", "embed", "x" );
        final LlmActivityLog.Snapshot snap = log.snapshot( 50, null, null );
        assertEquals( 1, snap.inFlight() );
        assertEquals( "IN_FLIGHT", snap.calls().get( 0 ).status() );
    }

    @Test
    void promptPreviewIsTruncatedToConfiguredLength() {
        final LlmActivityLog log = new LlmActivityLog( true, 60, 100, 5 );
        final LlmCall call = log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed",
                                        "abcdefghijklmnop" );
        assertEquals( "abcde…", call.promptPreview() );
    }

    @Test
    void countCapEvictsOldestFinalizedRecords() {
        final LlmActivityLog log = log( 3 );
        for ( int i = 0; i < 10; i++ ) {
            final LlmCall c = log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i );
            log.succeed( c, "ok" );
        }
        final LlmActivityLog.Snapshot snap = log.snapshot( 50, null, null );
        assertEquals( 3, snap.calls().size() );
        assertEquals( "n9", snap.calls().get( 0 ).promptPreview() );
    }

    @Test
    void inFlightRecordSurvivesCountCapEviction() {
        final LlmActivityLog log = log( 3 );
        // An in-flight call recorded first, never finalized.
        log.begin( Subsystem.PROPOSAL_JUDGE, "ollama", "m", "chat", "in-flight-call" );
        // Many finalized calls that blow past the count cap of 3.
        for ( int i = 0; i < 20; i++ ) {
            log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i ), "ok" );
        }
        final LlmActivityLog.Snapshot snap = log.snapshot( 99, null, null );
        assertEquals( 1, snap.inFlight() );
        final boolean inFlightStillPresent = snap.calls().stream()
            .anyMatch( c -> "IN_FLIGHT".equals( c.status() )
                         && "in-flight-call".equals( c.promptPreview() ) );
        assertTrue( inFlightStillPresent, "in-flight record must survive count-cap eviction" );
    }

    @Test
    void snapshotIsNewestFirstAndRespectsLimit() {
        final LlmActivityLog log = log( 100 );
        for ( int i = 0; i < 5; i++ ) {
            log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "n" + i ), "ok" );
        }
        final LlmActivityLog.Snapshot snap = log.snapshot( 2, null, null );
        assertEquals( 2, snap.calls().size() );
        assertEquals( "n4", snap.calls().get( 0 ).promptPreview() );
        assertEquals( "n3", snap.calls().get( 1 ).promptPreview() );
    }

    @Test
    void snapshotFiltersBySubsystemAndStatus() {
        final LlmActivityLog log = log( 100 );
        log.succeed( log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", "e" ), "ok" );
        log.fail( log.begin( Subsystem.PROPOSAL_JUDGE, "ollama", "m", "chat", "j" ),
                  new RuntimeException( "x" ) );

        assertEquals( 1, log.snapshot( 50, Subsystem.EMBEDDING, null ).calls().size() );
        assertEquals( 1, log.snapshot( 50, null, CallStatus.ERROR ).calls().size() );
        assertEquals( 0, log.snapshot( 50, Subsystem.EMBEDDING, CallStatus.ERROR ).calls().size() );
    }

    @Test
    void concurrentWritesAreAllRecorded() throws Exception {
        final LlmActivityLog log = log( 10_000 );
        final int threads = 8;
        final int perThread = 200;
        final ExecutorService pool = Executors.newFixedThreadPool( threads );
        final CountDownLatch done = new CountDownLatch( threads );
        for ( int t = 0; t < threads; t++ ) {
            pool.submit( () -> {
                try {
                    for ( int i = 0; i < perThread; i++ ) {
                        log.succeed( log.begin( Subsystem.PROPOSAL_JUDGE, "ollama", "m", "chat", "p" ),
                                     "ok" );
                    }
                } finally {
                    done.countDown();
                }
            } );
        }
        assertTrue( done.await( 30, TimeUnit.SECONDS ) );
        pool.shutdown();
        assertEquals( threads * perThread, log.snapshot( 99_999, null, null ).calls().size() );
    }

    @Test
    void recorderMethodsNeverThrowOnNullArguments() {
        final LlmActivityLog log = log( 100 );
        final LlmCall call = log.begin( Subsystem.EMBEDDING, "ollama", "m", "embed", null );
        assertNotNull( call );
        log.succeed( call, null );
        log.fail( call, null );
    }
}
