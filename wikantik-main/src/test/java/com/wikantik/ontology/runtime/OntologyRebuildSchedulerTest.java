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
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class OntologyRebuildSchedulerTest {

    @Mock OntologyRebuildCoordinator coordinator;

    @Test
    void tickTriggersRebuild() {
        new OntologyRebuildScheduler( coordinator, 24, 168 ).runRebuildOnce();
        verify( coordinator ).triggerRebuild();
    }

    @Test
    void tickSwallowsRebuildConflict() {
        when( coordinator.triggerRebuild() )
                .thenThrow( new OntologyRebuildCoordinator.ConflictException( "running" ) );
        assertDoesNotThrow( () -> new OntologyRebuildScheduler( coordinator, 24, 168 ).runRebuildOnce() );
    }

    @Test
    void tickSwallowsRebuildDisabled() {
        when( coordinator.triggerRebuild() )
                .thenThrow( new OntologyRebuildCoordinator.DisabledException() );
        assertDoesNotThrow( () -> new OntologyRebuildScheduler( coordinator, 24, 168 ).runRebuildOnce() );
    }

    @Test
    void tickTriggersCompaction() {
        new OntologyRebuildScheduler( coordinator, 24, 168 ).runCompactionOnce();
        verify( coordinator ).triggerCompaction();
    }

    @Test
    void tickSwallowsCompactionConflict() {
        when( coordinator.triggerCompaction() )
                .thenThrow( new OntologyRebuildCoordinator.ConflictException( "running" ) );
        assertDoesNotThrow( () -> new OntologyRebuildScheduler( coordinator, 24, 168 ).runCompactionOnce() );
    }

    @Test
    void tickSwallowsCompactionDisabled() {
        when( coordinator.triggerCompaction() )
                .thenThrow( new OntologyRebuildCoordinator.DisabledException() );
        assertDoesNotThrow( () -> new OntologyRebuildScheduler( coordinator, 24, 168 ).runCompactionOnce() );
    }

    @Test
    void startWithBothIntervalsDisabledDoesNotThrow() {
        final OntologyRebuildScheduler s = new OntologyRebuildScheduler( coordinator, 0, 0 );
        assertDoesNotThrow( s::start );
        s.stop();
    }

    @Test
    void startWithOnlyCompactionEnabledDoesNotThrow() {
        final OntologyRebuildScheduler s = new OntologyRebuildScheduler( coordinator, 0, 168 );
        assertDoesNotThrow( s::start );
        s.stop();
    }

    // ------------------------------------------------------------------ first compaction after startup

    private static final Instant NOW = Instant.parse( "2026-10-22T12:00:00Z" );
    private static final Duration WEEK = Duration.ofHours( 168 );
    private static final Duration GRACE = OntologyRebuildScheduler.COMPACTION_GRACE;

    @Mock ScheduledExecutorService executor;

    private OntologyRebuildScheduler scheduler( final long rebuildHours, final long compactionHours ) {
        return new OntologyRebuildScheduler( coordinator, rebuildHours, compactionHours,
                Clock.fixed( NOW, ZoneOffset.UTC ), () -> executor );
    }

    private void verifyCompactionScheduledIn( final Duration delay ) {
        verify( executor ).schedule( any( Runnable.class ), eq( delay.toMillis() ), eq( TimeUnit.MILLISECONDS ) );
    }

    @Test
    void aStoreLastCompactedLongerThanAnIntervalAgoIsCompactedRightAfterTheGraceDelay() {
        assertEquals( GRACE, OntologyRebuildScheduler.firstCompactionDelay(
                Optional.of( NOW.minus( Duration.ofDays( 8 ) ) ), WEEK, NOW ) );
    }

    @Test
    void aStoreCompactedYesterdayWaitsOutTheRestOfItsInterval() {
        assertEquals( Duration.ofDays( 6 ), OntologyRebuildScheduler.firstCompactionDelay(
                Optional.of( NOW.minus( Duration.ofDays( 1 ) ) ), WEEK, NOW ) );
    }

    @Test
    void aStoreWithNoCompactionRecordIsOverdue() {
        assertEquals( GRACE, OntologyRebuildScheduler.firstCompactionDelay( Optional.empty(), WEEK, NOW ) );
    }

    @Test
    void aCompactionDueWithinTheGraceStillWaitsForStartupToSettle() {
        assertEquals( GRACE, OntologyRebuildScheduler.firstCompactionDelay(
                Optional.of( NOW.minus( WEEK ).plus( Duration.ofMinutes( 1 ) ) ), WEEK, NOW ) );
    }

    @Test
    void aRecordInTheFutureNeverPostponesCompactionBeyondOneInterval() {
        assertEquals( WEEK, OntologyRebuildScheduler.firstCompactionDelay(
                Optional.of( NOW.plus( Duration.ofDays( 30 ) ) ), WEEK, NOW ) );
    }

    @Test
    void startSchedulesAnOverdueCompactionAfterTheGraceDelayNotAWeekOut() {
        when( coordinator.lastCompactedAt() ).thenReturn( Optional.of( NOW.minus( Duration.ofDays( 8 ) ) ) );
        scheduler( 0, 168 ).start();
        verifyCompactionScheduledIn( GRACE );
    }

    @Test
    void startSchedulesTheFirstCompactionFromThePersistedRecord() {
        when( coordinator.lastCompactedAt() ).thenReturn( Optional.of( NOW.minus( Duration.ofDays( 1 ) ) ) );
        scheduler( 0, 168 ).start();
        verifyCompactionScheduledIn( Duration.ofDays( 6 ) );
    }

    @Test
    void aTriggeredCompactionTickSchedulesTheNextOneAnIntervalOut() {
        when( coordinator.lastCompactedAt() ).thenReturn( Optional.empty() );
        final OntologyRebuildScheduler s = scheduler( 0, 168 );
        s.start();
        s.runCompactionTick();
        verify( coordinator ).triggerCompaction();
        verifyCompactionScheduledIn( WEEK );
    }

    @Test
    void aCompactionTickSkippedForARunningRebuildRetriesAfterTheGraceDelay() {
        when( coordinator.lastCompactedAt() ).thenReturn( Optional.of( NOW.minus( Duration.ofDays( 1 ) ) ) );
        when( coordinator.triggerCompaction() )
                .thenThrow( new OntologyRebuildCoordinator.ConflictException( "running" ) );
        final OntologyRebuildScheduler s = scheduler( 0, 168 );
        s.start();
        s.runCompactionTick();
        verifyCompactionScheduledIn( GRACE );
    }
}
