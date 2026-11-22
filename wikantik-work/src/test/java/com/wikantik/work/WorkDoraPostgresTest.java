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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The DORA report over real PostgreSQL and a fixed clock. Projects PLAT (A: lena leads) and OPS (B: olga leads), the
 * products {@code wikantik} and {@code api}; every time is set by SQL and none is the wall clock.
 */
@RequiresPostgres
class WorkDoraPostgresTest {

    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-21T10:00:00Z" ), ZoneOffset.UTC );
    private static final DoraQuery WINDOW = new DoraQuery( LocalDate.parse( "2026-11-02" ),
        LocalDate.parse( "2026-11-15" ), null );

    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private final Actor olga = Actor.human( "olga", Set.of(), false );
    private GateFixture f;
    private DoraFixtures d;
    private WorkFlowService flow;
    private Item a1;
    private Item b1;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture( null, CLOCK );
        flow = f.svc.features().flow();
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        f.svc.putMember( f.admin, "OPS", "olga", "user", WorkRole.LEAD );
        f.svc.createProduct( f.admin, new Product( 0, "api", "Api", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
        d = new DoraFixtures( f );
        a1 = FlowFixtures.item( f, "a1", t( "10-01T09:00" ) );
        b1 = FlowFixtures.item( f, "OPS", WorkLevel.TASK, "b1", t( "10-01T09:00" ) );
    }

    private static Instant t( final String s ) {
        return Instant.parse( "2026-" + s + ":00Z" );
    }

    private DoraReport a() throws SQLException {
        return flow.dora( f.lena, "PLAT", WINDOW );
    }

    private DoraReport b() throws SQLException {
        return flow.dora( olga, "OPS", WINDOW );
    }

    private static int frequency( final DoraReport r ) {
        return r.deploymentFrequency().stream().mapToInt( DoraReport.WeekCount::deployments ).sum();
    }

    @Test
    void aMemberOfASeesOnlyDeploymentsCarryingAsItems() throws SQLException {
        d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        d.live( "wikantik", List.of( b1 ), t( "11-03T12:00" ) );
        assertEquals( 1, frequency( a() ) );
        assertEquals( 1, frequency( b() ) );
    }

    @Test
    void aDeploymentCarryingAAndBCountsInBoth() throws SQLException {
        d.live( "wikantik", List.of( a1, b1 ), t( "11-03T11:00" ) );
        assertEquals( 1, frequency( a() ) );
        assertEquals( 1, frequency( b() ) );
    }

    @Test
    void stagingIsIgnored() throws SQLException {
        final long id = d.deploy( "wikantik", "staging", List.of( a1 ), null );
        d.ran( id, t( "11-03T10:00" ) );
        d.verified( id, t( "11-03T11:00" ) );
        final DoraReport r = a();
        assertEquals( 0, frequency( r ) );
        assertEquals( new DoraReport.Rate( 0, 0 ), r.changeFailureRate() );
    }

    @Test
    void theProductFilterNarrowsAndAnUnknownProductIsNotFound() throws SQLException {
        d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        d.live( "api", List.of( a1 ), t( "11-04T11:00" ) );
        assertEquals( 2, frequency( a() ) );
        assertEquals( 1, frequency( flow.dora( f.lena, "PLAT", new DoraQuery( WINDOW.from(), WINDOW.to(), "api" ) ) ) );
        assertEquals( "wikantik", flow.dora( f.lena, "PLAT", new DoraQuery( WINDOW.from(), WINDOW.to(), "wikantik" ) )
            .productKey() );
        assertThrows( WorkNotFoundException.class, () -> flow.dora( f.lena, "PLAT",
            new DoraQuery( WINDOW.from(), WINDOW.to(), "nope" ) ) );
    }

    @Test
    void leadTimeRunsToTheFirstVerifiedDeploymentAfterTheMerge() throws SQLException {
        final long change = d.change( f, a1, "work/PLAT-1-x" );
        d.merged( change, "reviewed", t( "11-03T00:00" ), t( "11-04T00:00" ), t( "11-02T00:00" ) );
        d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );          // before the merge: ships nothing
        final long rolled = d.live( "wikantik", List.of( a1 ), t( "11-05T11:00" ) );
        d.rolledBack( rolled, t( "11-05T13:00" ) );                           // verified, then rolled back: ships nothing
        d.live( "wikantik", List.of( a1 ), t( "11-07T00:00" ) );
        final DoraReport r = a();
        assertEquals( 1, r.leadTimeForChanges().n() );
        assertEquals( Duration.ofDays( 5 ), r.leadTimeForChanges().p50(), "first commit 11-02 to the 11-07 deployment" );
        assertEquals( 3, frequency( r ), "the rolled-back one still reached production" );
    }

    @Test
    void aChangeWithoutCommitTimeFallsBackAndIsCounted() throws SQLException {
        d.merged( d.change( f, a1, "work/PLAT-1-x" ), "reviewed", t( "11-03T00:00" ), t( "11-04T00:00" ), null );
        d.live( "wikantik", List.of( a1 ), t( "11-05T00:00" ) );
        final DoraReport r = a();
        assertEquals( Duration.ofDays( 2 ), r.leadTimeForChanges().p50(), "from opened_at" );
        assertEquals( 1, r.changesWithoutCommitTime() );
        assertEquals( 1, r.changesWithoutUsableCommitTime() );
        assertEquals( 0, r.changesWithClampedCommitTime() );
    }

    @Test
    void aRebasedChangeIsClampedAndCounted() throws SQLException {
        d.merged( d.change( f, a1, "work/PLAT-1-x" ), "reviewed", t( "11-03T00:00" ), t( "11-04T00:00" ),
            Instant.parse( "2025-01-01T00:00:00Z" ) );
        d.live( "wikantik", List.of( a1 ), t( "11-05T00:00" ) );
        final DoraReport r = a();
        assertEquals( Duration.ofDays( 32 ), r.leadTimeForChanges().p50(), "30 days before opening to 11-05" );
        assertEquals( 1, r.changesWithClampedCommitTime() );
    }

    @Test
    void aMergeFarOlderThanTheLookbackIsNotASample() throws SQLException {
        // merged 2025-09-30: more than 366 days before the window starts (2026-11-02), so it is never read
        d.merged( d.change( f, a1, "work/PLAT-1-x" ), "reviewed", Instant.parse( "2025-09-29T00:00:00Z" ),
            Instant.parse( "2025-09-30T00:00:00Z" ), null );
        d.live( "wikantik", List.of( a1 ), t( "11-05T00:00" ) );
        assertEquals( 0, a().leadTimeForChanges().n() );
    }

    @Test
    void aMergeWithinTheLookbackStillCounts() throws SQLException {
        d.merged( d.change( f, a1, "work/PLAT-1-x" ), "reviewed", Instant.parse( "2026-01-01T00:00:00Z" ),
            Instant.parse( "2026-01-02T00:00:00Z" ), null );
        d.live( "wikantik", List.of( a1 ), t( "11-05T00:00" ) );
        assertEquals( 1, a().leadTimeForChanges().n(), "a change that waited ten months for a deployment" );
    }

    @Test
    void aFixForwardThroughTheServiceCountsAsAFailure() throws SQLException {
        final long d1 = d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        final long d2 = f.svc.deployments().request( f.lena, new DeploymentDraft( "wikantik", null, "9.9.9",
            List.of( a1.key() ), d1 ) ).deployment().id();
        d.ran( d2, t( "11-04T10:00" ) );
        d.verified( d2, t( "11-04T14:00" ) );
        final DoraReport r = a();
        assertEquals( new DoraReport.Rate( 1, 2 ), r.changeFailureRate() );
        assertEquals( new DoraReport.Rate( 1, 2 ), r.reworkRate() );
        assertEquals( 1, r.recoveryTime().n() );
        assertEquals( Duration.ofHours( 27 ), r.recoveryTime().p50(), "D2 verified minus D1 verified" );
        assertEquals( 0, r.unrecovered() );
    }

    @Test
    void aRejectedRemediationMarksNothing() throws SQLException {
        final long d1 = d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        final long d2 = d.deploy( "wikantik", List.of( a1 ), d1 );
        d.rejected( d2 );
        final DoraReport r = a();
        assertEquals( new DoraReport.Rate( 0, 1 ), r.changeFailureRate() );
        assertEquals( new DoraReport.Rate( 0, 1 ), r.reworkRate() );
        assertEquals( 0, r.recoveryTime().n() );
    }

    @Test
    void aRemediatorCarryingOnlyBsItemsStillMarksAsDeploymentFailed() throws SQLException {
        final long d1 = d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        final long d2 = d.deploy( "wikantik", List.of( b1 ), d1 );
        d.ran( d2, t( "11-04T10:00" ) );
        d.verified( d2, t( "11-04T12:00" ) );
        final DoraReport r = a();
        assertEquals( new DoraReport.Rate( 1, 1 ), r.changeFailureRate(), "A's deployment failed: B fixed it forward" );
        assertEquals( Duration.ofHours( 25 ), r.recoveryTime().p50(), "only the duration shows" );
        assertEquals( 0, r.unrecovered() );
        assertEquals( new DoraReport.Rate( 0, 1 ), r.reworkRate(), "D2 is not one of A's deployments" );
    }

    @Test
    void aRemediatorThatWasRolledBackEndsNothing() throws SQLException {
        final long d1 = d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        final long d2 = d.deploy( "wikantik", List.of( a1 ), d1 );
        d.ran( d2, t( "11-04T10:00" ) );
        d.verified( d2, t( "11-04T12:00" ) );
        d.rolledBack( d2, t( "11-04T14:00" ) );
        final DoraReport r = a();
        assertEquals( 1, r.unrecovered(), "D1 is still broken: its fix was itself rolled back" );
        assertEquals( 1, r.recoveryTime().n(), "only D2 lasted: live 12:00, rolled back 14:00" );
        assertEquals( Duration.ofHours( 2 ), r.recoveryTime().p50() );
    }

    @Test
    void aRemediatorThatFailedEndsNothingButTheNextVerifiedDeploymentDoes() throws SQLException {
        final long d1 = d.deploy( "wikantik", List.of( a1 ), null );
        d.ran( d1, t( "11-03T10:00" ) );
        d.failed( d1, t( "11-03T11:00" ) );
        final long d2 = d.deploy( "wikantik", List.of( a1 ), d1 );
        d.ran( d2, t( "11-04T09:00" ) );
        d.failed( d2, t( "11-04T10:00" ) );
        d.live( "wikantik", List.of( a1 ), t( "11-05T12:00" ) );
        final DoraReport r = a();
        assertEquals( 0, r.unrecovered() );
        assertEquals( 2, r.recoveryTime().n() );
        assertEquals( Duration.ofHours( 26 ), r.recoveryTime().p50(), "D2: 26h; D1: 11-03 11:00 to 11-05 12:00 is 49h" );
        assertEquals( Duration.ofHours( 49 ), r.recoveryTime().p85() );
    }

    @Test
    void theRecoveryRowsCarryNoTextAndTheReportOnlyItsOwnKeys() {
        for ( final Class< ? > c : List.of( WorkFlowStore.RecoveryEnd.class, WorkFlowStore.DeploymentFact.class ) ) {
            for ( final java.lang.reflect.RecordComponent rc : c.getRecordComponents() ) {
                assertFalse( rc.getType() == String.class, c.getSimpleName() + "." + rc.getName() + " is text" );
            }
        }
        for ( final java.lang.reflect.RecordComponent rc : DoraReport.class.getRecordComponents() ) {
            assertFalse( rc.getType() == String.class && !List.of( "zone", "productKey" ).contains( rc.getName() ),
                "DoraReport." + rc.getName() + " is text" );
        }
    }

    @Test
    void aViewerOfAOnlySeesNoIdOfADeploymentOnlyBCanSee() throws SQLException {
        final long fixed = d.live( "wikantik", List.of( b1 ), t( "11-03T11:00" ) );
        final long forward = d.deploy( "wikantik", List.of( a1 ), fixed );
        final WorkDeploymentsService deployments = f.svc.deployments();
        assertNull( deployments.deployment( f.lena, forward ).deployment().remediatesId(),
            "the target carries only B's items: its id is not A's to see" );
        assertEquals( fixed, deployments.deployment( f.admin, forward ).deployment().remediatesId() );
        f.svc.putMember( f.admin, "OPS", "lena", "user", WorkRole.MEMBER );
        assertEquals( fixed, deployments.deployment( f.lena, forward ).deployment().remediatesId(),
            "once she can see the target, the id shows" );
    }

    @Test
    void mergeMixCountsKindsAndFlags() throws SQLException {
        final Item a2 = FlowFixtures.item( f, "a2", t( "10-01T09:00" ) );
        final Item a3 = FlowFixtures.item( f, "a3", t( "10-01T09:00" ) );
        d.merged( d.change( f, a1, "work/x" ), "reviewed", t( "11-03T00:00" ), t( "11-04T00:00" ), null );
        final long unreviewed = d.change( f, a2, "work/y" );
        d.merged( unreviewed, "unreviewed", t( "11-03T00:00" ), t( "11-05T00:00" ), null );
        final long direct = d.change( f, a3, "work/z" );
        d.merged( direct, "direct", t( "11-03T00:00" ), t( "11-06T00:00" ), null );
        d.exec( "UPDATE work_change SET policy_flag = 'unreviewed' WHERE id = " + unreviewed );
        d.exec( "UPDATE work_change SET policy_flag = 'direct', flag_acknowledged_at = '2026-11-07T00:00:00Z' "
            + "WHERE id = " + direct );
        d.merged( d.change( f, b1, "work/b" ), "direct", t( "11-03T00:00" ), t( "11-06T00:00" ), null );
        assertEquals( new DoraReport.MergeMix( 1, 1, 1, 2, 1 ), a().merges() );
        assertEquals( new DoraReport.MergeMix( 0, 0, 1, 0, 0 ), b().merges() );
    }

    @Test
    void aSupersededDirectChangeIsCountedNowhere() throws SQLException {
        final long pr = d.change( f, a1, "work/pr" );
        d.merged( pr, "reviewed", t( "11-03T00:00" ), t( "11-04T00:00" ), null );
        final long direct = d.change( f, a1, "main" );
        d.merged( direct, "direct", t( "11-04T00:00" ), t( "11-04T00:00" ), t( "11-03T00:00" ) );
        d.exec( "UPDATE work_change SET policy_flag = 'direct', superseded_by = " + pr + " WHERE id = " + direct );
        d.live( "wikantik", List.of( a1 ), t( "11-05T00:00" ) );
        final DoraReport r = a();
        assertEquals( new DoraReport.MergeMix( 1, 0, 0, 0, 0 ), r.merges(), "not in the mix, not flagged" );
        assertEquals( 1, r.leadTimeForChanges().n(), "not a lead-time sample" );
        assertEquals( Duration.ofDays( 2 ), r.leadTimeForChanges().p50() );
    }

    @Test
    void aStrangerGetsNotFound() {
        assertThrows( WorkNotFoundException.class, () -> flow.dora( mallory, "PLAT", WINDOW ) );
        assertThrows( WorkNotFoundException.class, () -> flow.dora( olga, "PLAT", WINDOW ) );
        assertThrows( WorkNotFoundException.class, () -> flow.dora( mallory, "NOPE", WINDOW ) );
    }

    @Test
    void anAgentMemberReadsUnderAMemberButNotUnderAStranger() throws SQLException {
        d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        assertEquals( 1, frequency( flow.dora( Actor.agent( f.author.agentId(), f.author.login(), f.lena ), "PLAT",
            WINDOW ) ) );
        assertThrows( WorkNotFoundException.class, () -> flow.dora( Actor.agent( f.author.agentId(),
            f.author.login(), mallory ), "PLAT", WINDOW ) );
    }

    @Test
    void theProductViewIsForWikiAdminsOnly() throws SQLException {
        d.live( "wikantik", List.of( a1 ), t( "11-03T11:00" ) );
        d.live( "wikantik", List.of( b1 ), t( "11-03T12:00" ) );
        for ( final Actor who : List.of( f.lena, olga, mallory, f.author ) ) {
            assertThrows( WorkDeniedException.class, () -> flow.doraForProduct( who, "wikantik", WINDOW.from(),
                WINDOW.to() ), who.login() );
        }
        final DoraReport r = flow.doraForProduct( f.admin, "wikantik", WINDOW.from(), WINDOW.to() );
        assertEquals( 2, frequency( r ), "the admin sees X and Y" );
        assertEquals( "wikantik", r.productKey() );
        assertThrows( WorkNotFoundException.class, () -> flow.doraForProduct( f.admin, "nope", null, null ) );
    }

    @Test
    void anEmptyWindowIsZerosAndNullsNeverNaN() throws SQLException {
        final DoraReport r = a();
        assertEquals( new DoraReport.Rate( 0, 0 ), r.changeFailureRate() );
        assertEquals( new DoraReport.Rate( 0, 0 ), r.reworkRate() );
        assertEquals( Durations.NONE, r.recoveryTime() );
        assertEquals( Durations.NONE, r.leadTimeForChanges() );
        assertEquals( new DoraReport.MergeMix( 0, 0, 0, 0, 0 ), r.merges() );
        assertNull( r.productKey() );
        assertEquals( List.of( 0, 0 ), r.deploymentFrequency().stream().map( DoraReport.WeekCount::deployments ).toList() );
    }

    @Test
    void theZoneOfTheClockDecidesTheWeek() throws SQLException {
        final Clock berlin = Clock.fixed( CLOCK.instant(), ZoneId.of( "Europe/Berlin" ) );
        final GateFixture b = new GateFixture( null, berlin );
        final DoraFixtures bd = new DoraFixtures( b );
        final Item item = FlowFixtures.item( b, "late", t( "10-01T09:00" ) );
        // 23:30 UTC on Sunday 11-08 is 00:30 Monday 11-09 in Berlin
        bd.live( "wikantik", List.of( item ), Instant.parse( "2026-11-08T23:30:00Z" ) );
        final DoraReport r = b.svc.features().flow().dora( b.lena, "PLAT", WINDOW );
        assertEquals( "Europe/Berlin", r.zone() );
        assertEquals( List.of( 0, 1 ), r.deploymentFrequency().stream().map( DoraReport.WeekCount::deployments ).toList() );
        assertEquals( LocalDate.parse( "2026-11-09" ), r.deploymentFrequency().get( 1 ).weekStart() );
    }
}
