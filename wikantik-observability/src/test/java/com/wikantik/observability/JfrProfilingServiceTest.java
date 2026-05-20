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
package com.wikantik.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JfrProfilingServiceTest {

    private static final long FIVE_GB = 5L * 1024 * 1024 * 1024;

    private JfrProfilingService svc;

    @AfterEach
    void stopRunningRecording() {
        // Defensive cleanup — any recording left running by a test must be
        // stopped so the @TempDir cleanup can delete the .jfr file and so
        // JFR's JVM-side state doesn't leak between tests.
        if ( svc == null ) return;
        for ( final var info : svc.list() ) {
            if ( "RUNNING".equals( info.status() ) ) {
                try { svc.stop( info.recordingId() ); }
                catch ( final RuntimeException ignore ) { /* best-effort */ }
            }
        }
    }

    @Test
    void startCreatesRunningRecording( @TempDir final Path tmp ) {
        svc = new JfrProfilingService( tmp, FIVE_GB );
        final JfrProfilingService.RecordingInfo info = svc.start( 2, "test-label" );
        assertNotNull( info.recordingId() );
        assertEquals( "RUNNING", info.status() );
        assertEquals( 2, info.durationSeconds() );
        assertEquals( "test-label", info.label() );
        assertTrue( info.filePath().toString().endsWith( ".jfr" ),
            "filePath should be a .jfr file, was: " + info.filePath() );
    }

    @Test
    void concurrentStartIsRejected( @TempDir final Path tmp ) {
        svc = new JfrProfilingService( tmp, FIVE_GB );
        svc.start( 5, "first" );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> svc.start( 5, "second" ) );
        assertTrue( ex.getMessage().contains( "already running" ),
            "expected 'already running' in message, got: " + ex.getMessage() );
    }

    @Test
    void durationOutOfRangeRejected( @TempDir final Path tmp ) {
        svc = new JfrProfilingService( tmp, FIVE_GB );
        assertThrows( IllegalArgumentException.class, () -> svc.start( 0, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> svc.start( 601, "x" ) );
    }

    @Test
    void stopProducesFinishedFile( @TempDir final Path tmp ) throws Exception {
        svc = new JfrProfilingService( tmp, FIVE_GB );
        final var info = svc.start( 2, "stop-test" );
        // Give JFR enough time to flush at least one chunk to disk so the
        // resulting file is non-zero. 200ms is conservative.
        TimeUnit.MILLISECONDS.sleep( 200 );
        final var stopped = svc.stop( info.recordingId() );
        assertEquals( "FINISHED", stopped.status() );
        assertTrue( stopped.sizeBytes() > 0,
            "recording should have non-zero bytes after stop, got " + stopped.sizeBytes() );
        assertTrue( stopped.filePath().toFile().exists() );
    }

    @Test
    void listReturnsKnownRecordings( @TempDir final Path tmp ) throws Exception {
        svc = new JfrProfilingService( tmp, FIVE_GB );
        final var a = svc.start( 2, "a" );
        TimeUnit.MILLISECONDS.sleep( 50 );
        svc.stop( a.recordingId() );
        final var b = svc.start( 2, "b" );
        TimeUnit.MILLISECONDS.sleep( 50 );
        svc.stop( b.recordingId() );
        final List< JfrProfilingService.RecordingInfo > all = svc.list();
        assertEquals( 2, all.size() );
    }

    @Test
    void unknownRecordingIdRejectedOnStop( @TempDir final Path tmp ) {
        svc = new JfrProfilingService( tmp, FIVE_GB );
        assertThrows( IllegalArgumentException.class, () -> svc.stop( "no-such-id" ) );
    }

    @Test
    void sizeCapRejectsNewStart( @TempDir final Path tmp ) throws Exception {
        // Pre-populate a fake .jfr file larger than the (tiny) cap so the next start refuses.
        java.nio.file.Files.write( tmp.resolve( "preexisting.jfr" ), new byte[ 1024 ] );
        svc = new JfrProfilingService( tmp, 512 );  // 512 bytes cap; the file above blows it.
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> svc.start( 2, "would-overflow" ) );
        assertTrue( ex.getMessage().contains( "size cap" ),
            "expected 'size cap' in message, got: " + ex.getMessage() );
    }
}
