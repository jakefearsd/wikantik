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
package com.wikantik.importer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ImportJobRegistryTest {

    private final List< Runnable > queued = new ArrayList<>();
    private final AtomicReference< Instant > now = new AtomicReference<>( Instant.now() );
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone( final java.time.ZoneId z ) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final ImportJobRegistry registry = new ImportJobRegistry( 1, clock, queued::add );

    @Test
    void secondJobForSameUserIsUserRunning() throws Exception {
        registry.start( "alice", id -> ImportTestJobs.job( id, "alice" ) );
        final ImportJobConflictException e = assertThrows( ImportJobConflictException.class,
            () -> registry.start( "alice", id -> ImportTestJobs.job( id, "alice" ) ) );
        assertEquals( ImportJobConflictException.Reason.USER_RUNNING, e.reason() );
    }

    @Test
    void capacityReason() throws Exception {
        registry.start( "alice", id -> ImportTestJobs.job( id, "alice" ) );
        final ImportJobConflictException e = assertThrows( ImportJobConflictException.class,
            () -> registry.start( "bob", id -> ImportTestJobs.job( id, "bob" ) ) );
        assertEquals( ImportJobConflictException.Reason.CAPACITY, e.reason() );
        assertThrows( ImportJobConflictException.class, () -> registry.ensureCanStart( "bob" ) );
    }

    @Test
    void finishedJobFreesSlotAndIsCurrentForAnHour() throws Exception {
        final VaultImportJob alice = registry.start( "alice", id -> ImportTestJobs.job( id, "alice" ) );
        queued.get( 0 ).run();
        assertFalse( alice.isRunning() );
        registry.start( "bob", id -> ImportTestJobs.job( id, "bob" ) );
        queued.get( 1 ).run();
        assertTrue( registry.current( "alice" ).isPresent() );
        assertTrue( registry.find( alice.id() ).isPresent() );
        now.set( now.get().plus( Duration.ofMinutes( 61 ) ) );
        assertTrue( registry.current( "alice" ).isEmpty() );
        assertTrue( registry.find( alice.id() ).isEmpty() || registry.current( "alice" ).isEmpty() );
        registry.start( "carol", id -> ImportTestJobs.job( id, "carol" ) );   // triggers eviction
        assertTrue( registry.find( alice.id() ).isEmpty() );
    }

    @Test
    void rejectedExecutionDiscardsUploadAndThrows() throws Exception {
        final ImportJobRegistry rejecting = new ImportJobRegistry( 1, clock, r -> {
            throw new java.util.concurrent.RejectedExecutionException( "full" );
        } );
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText( Map.of( "A.md", "a" ) ), "v.zip" );
        assertThrows( IllegalStateException.class,
            () -> rejecting.start( "alice", id -> ImportTestJobs.job( id, "alice", up ) ) );
        assertFalse( Files.exists( up.file() ) );
        assertTrue( rejecting.current( "alice" ).isEmpty() );
    }

    @Test
    void closeDiscardsTempFiles() throws Exception {
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText( Map.of( "A.md", "a" ) ), "v.zip" );
        registry.start( "alice", id -> ImportTestJobs.job( id, "alice", up ) );
        assertTrue( Files.exists( up.file() ) );
        registry.close();
        assertFalse( Files.exists( up.file() ) );
    }
}
