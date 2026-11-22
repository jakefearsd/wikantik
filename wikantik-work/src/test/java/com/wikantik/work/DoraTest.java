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
package com.wikantik.work;

import com.wikantik.work.WorkFlowStore.ChangeFact;
import com.wikantik.work.WorkFlowStore.DeploymentFact;
import com.wikantik.work.WorkFlowStore.RecoveryEnd;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One scenario written as a fact table; every figure checked against it by hand. */
class DoraTest {

    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-21T10:00:00Z" ), ZoneOffset.UTC );
    private static final MetricWindow W =
        MetricWindow.of( LocalDate.parse( "2026-11-02" ), LocalDate.parse( "2026-11-15" ), CLOCK );
    private static final long WEB = 1;
    private static final long API = 2;

    private static Instant t( final String s ) {
        return s == null ? null : Instant.parse( "2026-" + s + ":00Z" );
    }

    private static DeploymentFact d( final long id, final long product, final DeploymentState state, final String started,
                                     final String verified, final String failed, final String rolledBack,
                                     final Long remediates, final boolean remediated ) {
        return new DeploymentFact( id, product, state, t( started ), t( verified ), t( failed ), t( rolledBack ),
            remediates, remediated );
    }

    private static List< DeploymentFact > table() {
        final List< DeploymentFact > rows = new ArrayList<>();
        rows.add( d( 1, WEB, DeploymentState.VERIFIED, "11-02T10:00", "11-02T11:00", null, null, null, false ) );
        rows.add( d( 2, WEB, DeploymentState.ROLLED_BACK, "11-03T10:00", "11-03T11:00", null, "11-03T13:00", null, false ) );
        rows.add( d( 3, WEB, DeploymentState.FAILED, "11-04T10:00", null, "11-04T10:30", null, null, false ) );
        rows.add( d( 4, WEB, DeploymentState.VERIFIED, "11-05T11:00", "11-05T12:00", null, null, null, false ) );
        rows.add( d( 5, WEB, DeploymentState.VERIFIED, "11-09T10:00", "11-09T11:00", null, null, null, true ) );
        rows.add( d( 6, WEB, DeploymentState.VERIFIED, "11-09T14:00", "11-09T15:00", null, null, 5L, false ) );
        rows.add( d( 7, WEB, DeploymentState.FAILED, "11-10T10:00", null, "11-10T10:05", null, null, false ) );
        rows.add( d( 9, API, DeploymentState.VERIFIED, "11-16T10:00", "11-16T11:00", null, null, null, false ) );
        rows.add( d( 10, WEB, DeploymentState.VERIFIED, "11-01T23:00", "11-02T00:30", null, null, null, false ) );
        rows.add( d( 11, WEB, DeploymentState.RUNNING, "11-12T10:00", null, null, null, null, false ) );
        return rows;
    }

    private static List< RecoveryEnd > ends( final List< DeploymentFact > rows ) {
        return rows.stream().filter( f -> f.verifiedAt() != null )
            .map( f -> new RecoveryEnd( f.id(), f.productId(), f.verifiedAt(), f.remediatesId() ) ).toList();
    }

    private static Duration h( final long hours, final long minutes ) {
        return Duration.ofHours( hours ).plusMinutes( minutes );
    }

    @Test
    void frequencyCountsVerificationsPerWeekIncludingOneLaterRolledBack() {
        final List< DoraReport.WeekCount > weeks = Dora.frequency( table(), W );
        assertEquals( List.of( LocalDate.parse( "2026-11-02" ), LocalDate.parse( "2026-11-09" ) ),
            weeks.stream().map( DoraReport.WeekCount::weekStart ).toList() );
        assertEquals( List.of( 4, 2 ), weeks.stream().map( DoraReport.WeekCount::deployments ).toList() );
    }

    @Test
    void ranCountsOnlyFinishedDeploymentsStartedInTheWindow() {
        assertEquals( 7, table().stream().filter( f -> Dora.ran( f, W ) ).count() );
        assertEquals( List.of( 1L, 2L, 3L, 4L, 5L, 6L, 7L ),
            table().stream().filter( f -> Dora.ran( f, W ) ).map( DeploymentFact::id ).toList() );
    }

    @Test
    void aRollbackAFailureAndAFixForwardAreFailures() {
        assertEquals( new DoraReport.Rate( 4, 7 ), Dora.changeFailureRate( table(), W ) );
    }

    @Test
    void aRejectedRemediationIsNotAFailure() {
        final List< DeploymentFact > rows = table();
        rows.set( 6, d( 7, WEB, DeploymentState.VERIFIED, "11-10T10:00", "11-10T11:00", null, null, null, false ) );
        assertEquals( new DoraReport.Rate( 3, 7 ), Dora.changeFailureRate( rows, W ) );
    }

    @Test
    void recoveryFromRollbackNextVerifiedAndRemediator() {
        final List< DeploymentFact > rows = table();
        final Dora.Recovery r = Dora.recovery( rows, ends( rows ), W );
        assertEquals( 3, r.durations().n() );
        assertEquals( h( 4, 0 ), r.durations().p50() );
        assertEquals( h( 25, 30 ), r.durations().p85() );
        assertEquals( 1, r.unrecovered() );
    }

    @Test
    void aVerifiedFailureIsNotRecoveredByAnUnrelatedLaterDeployment() {
        final List< DeploymentFact > rows = table();
        rows.removeIf( f -> f.id() == 6 );
        rows.add( d( 12, WEB, DeploymentState.VERIFIED, "11-12T09:00", "11-12T10:00", null, null, null, false ) );
        final Dora.Recovery r = Dora.recovery( rows, ends( rows ), W );
        assertEquals( 1, r.unrecovered(), "row 5 went live and nothing fixes it, row 12 notwithstanding" );
        assertEquals( 3, r.durations().n(), "rows 2, 3 and 7 recovered" );
        assertEquals( h( 47, 55 ), r.durations().p85(), "row 7 never went live, so the next verified deployment ends it" );
    }

    @Test
    void anotherProductsDeploymentEndsNothing() {
        final List< DeploymentFact > rows = table();
        final Dora.Recovery r = Dora.recovery( rows, ends( rows ), W );
        assertEquals( 1, r.unrecovered(), "row 7 (web) is not ended by row 9 (api)" );
    }

    @Test
    void reworkIsRemediatingOverRan() {
        assertEquals( new DoraReport.Rate( 1, 7 ), Dora.rework( table(), W ) );
    }

    @Test
    void anEmptyWindowHasZeroOverZero() {
        assertEquals( new DoraReport.Rate( 0, 0 ), Dora.changeFailureRate( List.of(), W ) );
        assertEquals( new DoraReport.Rate( 0, 0 ), Dora.rework( List.of(), W ) );
        final Dora.Recovery r = Dora.recovery( List.of(), List.of(), W );
        assertEquals( Durations.NONE, r.durations() );
        assertEquals( 0, r.unrecovered() );
        assertEquals( List.of( 0, 0 ), Dora.frequency( List.of(), W ).stream().map( DoraReport.WeekCount::deployments ).toList() );
        assertEquals( Durations.NONE, Dora.leadTime( List.of() ).durations() );
    }

    @Test
    void aFailedThenRolledBackDeploymentEndsAtTheRollback() {
        final List< DeploymentFact > rows = List.of( d( 1, WEB, DeploymentState.ROLLED_BACK, "11-03T10:00", null,
            "11-03T10:30", "11-03T12:30", null, false ) );
        final Dora.Recovery r = Dora.recovery( rows, ends( rows ), W );
        assertEquals( h( 2, 0 ), r.durations().p50() );
    }

    // ---- lead time

    private static ChangeFact change( final long id, final String first, final String opened, final String shipped ) {
        return new ChangeFact( id, t( first ), t( opened ), t( opened ), t( shipped ) );
    }

    @Test
    void leadTimeFallsBackToOpenedAtAndCountsIt() {
        final Dora.LeadTime lt = Dora.leadTime( List.of( change( 1, "11-01T09:00", "11-01T10:00", "11-02T11:00" ),
            change( 2, null, "11-03T08:00", "11-05T12:00" ), change( 3, "11-02T09:00", "11-02T10:00", null ) ) );
        assertEquals( 2, lt.durations().n() );
        assertEquals( h( 26, 0 ), lt.durations().p50() );
        assertEquals( h( 52, 0 ), lt.durations().p85() );
        assertEquals( 1, lt.withoutCommitTime() );
        assertEquals( 1, lt.withoutUsableCommitTime(), "a missing commit time is not a usable one" );
        assertEquals( 0, lt.clamped() );
    }

    @Test
    void aNegativeLeadTimeIsDropped() {
        final Dora.LeadTime lt = Dora.leadTime( List.of( change( 1, "11-05T09:00", "11-05T10:00", "11-04T11:00" ) ) );
        assertEquals( Durations.NONE, lt.durations() );
    }

    @Test
    void aRebasedChangeIsClamped() {
        // first commit 2025: believed no earlier than 30 days before the change was opened
        final Dora.LeadTime lt = Dora.leadTime( List.of( new ChangeFact( 1, Instant.parse( "2025-01-01T00:00:00Z" ),
            t( "11-10T00:00" ), t( "11-10T00:00" ), t( "11-12T00:00" ) ) ) );
        assertEquals( Duration.ofDays( 32 ), lt.durations().p50() );
        assertEquals( 1, lt.clamped() );
        assertEquals( 0, lt.withoutCommitTime() );
    }

    @Test
    void aCommitExactlyThirtyDaysBeforeOpeningIsBelievedAsGiven() {
        final Dora.LeadTime lt = Dora.leadTime( List.of( new ChangeFact( 1, t( "10-11T00:00" ), t( "11-10T00:00" ),
            t( "11-10T00:00" ), t( "11-12T00:00" ) ) ) );
        assertEquals( Duration.ofDays( 32 ), lt.durations().p50() );
        assertEquals( 0, lt.clamped() );
    }

    @Test
    void aCommitAfterOpeningFallsBackToOpening() {
        final Dora.LeadTime lt = Dora.leadTime( List.of( new ChangeFact( 1, t( "11-11T00:00" ), t( "11-10T00:00" ),
            t( "11-11T12:00" ), t( "11-12T00:00" ) ) ) );
        assertEquals( Duration.ofDays( 2 ), lt.durations().p50() );
        assertEquals( 0, lt.clamped() );
        assertEquals( 0, lt.withoutCommitTime() );
    }

    @Test
    void aCommitAfterOpeningIsCountedAsNotUsable() {
        final Dora.LeadTime lt = Dora.leadTime( List.of( new ChangeFact( 1, t( "11-11T00:00" ), t( "11-10T00:00" ),
            t( "11-11T12:00" ), t( "11-12T00:00" ) ), change( 2, "11-01T09:00", "11-01T10:00", "11-02T11:00" ) ) );
        assertEquals( 2, lt.durations().n(), "both are measured: the late commit from opening" );
        assertEquals( 1, lt.withoutUsableCommitTime() );
        assertEquals( 0, lt.withoutCommitTime(), "the commit time was there, only unusable" );
    }
}
