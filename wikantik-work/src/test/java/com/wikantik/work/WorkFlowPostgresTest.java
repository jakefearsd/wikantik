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

import static com.wikantik.work.FlowFixtures.event;
import static com.wikantik.work.FlowFixtures.item;
import static com.wikantik.work.FlowFixtures.move;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The flow report over real PostgreSQL, a fixed clock and SQL-set event times: never the wall clock. */
@RequiresPostgres
class WorkFlowPostgresTest {

    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-21T10:00:00Z" ), ZoneOffset.UTC );
    private static final FlowQuery WINDOW = new FlowQuery( LocalDate.parse( "2026-11-02" ),
        LocalDate.parse( "2026-11-15" ), null, null, null, null );

    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private final Actor olga = Actor.human( "olga", Set.of(), false );
    private GateFixture f;
    private WorkFlowService flow;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture( null, CLOCK );
        flow = f.svc.features().flow();
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        f.svc.putMember( f.admin, "OPS", "olga", "user", WorkRole.LEAD );
    }

    private static Instant t( final String s ) {
        return Instant.parse( "2026-" + s + ":00Z" );
    }

    private FlowReport report() throws SQLException {
        return flow.project( f.lena, "PLAT", WINDOW );
    }

    private static List< Integer > itemsPerWeek( final FlowReport r ) {
        return r.throughput().stream().map( FlowReport.Week::items ).toList();
    }

    @Test
    void aReopenedThenClosedItemCountsOnceAtItsLastClose() throws SQLException {
        final Item i = item( f, "reopened", t( "10-30T09:00" ) );
        move( f, i, "ready", "in_progress", t( "11-02T09:00" ) );
        move( f, i, "in_progress", "done", t( "11-03T09:00" ) );
        move( f, i, "done", "in_progress", t( "11-04T09:00" ) );
        move( f, i, "in_progress", "done", t( "11-10T09:00" ) );
        final FlowReport r = report();
        assertEquals( List.of( 0, 1 ), itemsPerWeek( r ) );
        assertEquals( LocalDate.parse( "2026-11-09" ), r.throughput().get( 1 ).weekStart() );
        assertEquals( Duration.ofDays( 7 ), r.cycleTime().p50(), "first start to last close, less the day it sat done" );
        assertEquals( Duration.ofDays( 11 ), r.leadTime().p50() );
        assertEquals( 1, r.leadTime().n() );
    }

    @Test
    void anItemClosedThenReopenedAndStillOpenCountsNowhere() throws SQLException {
        final Item i = item( f, "reopened and open", t( "11-01T09:00" ) );
        move( f, i, "ready", "in_progress", t( "11-02T09:00" ) );
        move( f, i, "in_progress", "done", t( "11-05T09:00" ) );
        move( f, i, "done", "in_progress", t( "11-06T09:00" ) );
        final FlowReport r = report();
        assertEquals( List.of( 0, 0 ), itemsPerWeek( r ) );
        assertEquals( 0, r.leadTime().n() );
        assertEquals( 0, r.cycleTime().n() );
        final FlowReport.Wip wip = r.wip().get( 0 );
        assertEquals( WorkStatus.IN_PROGRESS, wip.status() );
        assertEquals( 1, wip.count() );
    }

    @Test
    void anItemNeverInProgressCountsInLeadTimeOnly() throws SQLException {
        final Item i = item( f, "straight to done", t( "11-01T09:00" ) );
        move( f, i, "ready", "done", t( "11-04T09:00" ) );
        final FlowReport r = report();
        assertEquals( 0, r.cycleTime().n() );
        assertNull( r.cycleTime().p50() );
        assertEquals( 1, r.leadTime().n() );
        assertEquals( Duration.ofDays( 3 ), r.leadTime().p50() );
    }

    @Test
    void aHandoffToDoneIsThroughput() throws SQLException {
        final Item i = item( f, "handed off as done", t( "11-01T09:00" ) );
        move( f, i, "ready", "in_progress", t( "11-02T09:00" ) );
        event( f, i, WorkEvent.HANDOFF, "in_progress|queue:implementer", "done|lena", "finished", "done",
            t( "11-05T09:00" ) );
        final FlowReport r = report();
        assertEquals( List.of( 1, 0 ), itemsPerWeek( r ), "a hand-off that closes the item is a close" );
        assertEquals( Duration.ofDays( 3 ), r.cycleTime().p50() );
        assertEquals( Duration.ofDays( 4 ), r.leadTime().p50() );
    }

    @Test
    void aClaimStartedItemReopenedAndClosedAgainExcludesTheDoneInterval() throws SQLException {
        final Item i = item( f, "claimed, closed, reopened", t( "11-01T09:00" ) );
        event( f, i, WorkEvent.CLAIMED, "ready", "in_progress", "2026-11-03T09:00:00Z", "in_progress",
            t( "11-02T09:00" ) );
        move( f, i, "in_progress", "done", t( "11-03T09:00" ) );
        move( f, i, "done", "in_progress", t( "11-05T09:00" ) );
        move( f, i, "in_progress", "done", t( "11-10T09:00" ) );
        assertEquals( Duration.ofDays( 6 ), report().cycleTime().p50(), "8 days from the claim, less 2 done" );
    }

    @Test
    void aClaimStartsTheCycle() throws SQLException {
        final Item i = item( f, "claimed then closed", t( "11-01T09:00" ) );
        event( f, i, WorkEvent.CLAIMED, "ready", "in_progress", "2026-11-03T09:00:00Z", "in_progress",
            t( "11-02T09:00" ) );
        move( f, i, "in_progress", "done", t( "11-04T09:00" ) );
        final FlowReport r = report();
        assertEquals( 1, r.cycleTime().n(), "the claim is the first start" );
        assertEquals( Duration.ofDays( 2 ), r.cycleTime().p50() );
        final Item open = item( f, "claimed and open", t( "11-01T09:00" ) );
        event( f, open, WorkEvent.CLAIMED, "ready", "in_progress", "2026-11-20T09:00:00Z", "in_progress",
            t( "11-11T10:00" ) );
        assertEquals( Duration.ofDays( 10 ), report().wip().get( 0 ).items().get( 0 ).age(),
            "work in progress ages from the claim, not from creation" );
    }

    @Test
    void anAgentsWholeRunByClaimAndHandoffIsCounted() throws SQLException {
        final Item i = item( f, "agent work", t( "11-01T09:00" ) );
        event( f, i, WorkEvent.CLAIMED, "ready", "in_progress", "2026-11-03T09:00:00Z", "in_progress",
            t( "11-02T09:00" ) );
        event( f, i, WorkEvent.HANDOFF, "in_progress|queue:implementer", "done|lena", "done", "done",
            t( "11-03T09:00" ) );
        final FlowReport r = report();
        assertEquals( List.of( 1, 0 ), itemsPerWeek( r ) );
        assertEquals( Duration.ofDays( 1 ), r.cycleTime().p50() );
        assertEquals( Duration.ofDays( 2 ), r.leadTime().p50() );
    }

    @Test
    void aLapsedClaimReturningAnItemToReadyIsNotAClose() throws SQLException {
        final Item lapsed = item( f, "lapsed", t( "11-01T09:00" ) );
        event( f, lapsed, WorkEvent.CLAIMED, "ready", "in_progress", "2026-11-03T09:00:00Z", "in_progress",
            t( "11-02T09:00" ) );
        event( f, lapsed, WorkEvent.CLAIM_EXPIRED, "in_progress", "ready", null, "ready", t( "11-03T09:00" ) );
        final Item released = item( f, "released", t( "11-01T09:00" ) );
        event( f, released, WorkEvent.CLAIMED, "ready", "in_progress", "2026-11-03T09:00:00Z", "in_progress",
            t( "11-02T09:00" ) );
        event( f, released, WorkEvent.RELEASED, "agent:1", "ready", "released", "ready", t( "11-03T09:00" ) );
        final FlowReport r = report();
        assertEquals( List.of( 0, 0 ), itemsPerWeek( r ) );
        assertEquals( 0, r.cycleTime().n() );
        assertEquals( 0, r.wip().get( 0 ).count(), "back in ready is not work in progress" );
    }

    @Test
    void aHandoffToDoneOfAnItemNeverInProgressCountsInLeadTimeOnly() throws SQLException {
        final Item i = item( f, "handed straight to done", t( "11-01T09:00" ) );
        event( f, i, WorkEvent.HANDOFF, "ready|queue:implementer", "done|lena", "nothing to do", "done",
            t( "11-04T09:00" ) );
        final FlowReport r = report();
        assertEquals( List.of( 1, 0 ), itemsPerWeek( r ) );
        assertEquals( 0, r.cycleTime().n(), "the same rule as a plain status move: no start, no cycle time" );
        assertEquals( Duration.ofDays( 3 ), r.leadTime().p50() );
    }

    @Test
    void aHandoffThatReopensDropsTheCloseAndTheLastCloseCounts() throws SQLException {
        final Item i = item( f, "reopened by hand-off", t( "10-30T09:00" ) );
        move( f, i, "ready", "in_progress", t( "11-02T09:00" ) );
        event( f, i, WorkEvent.HANDOFF, "in_progress|lena", "done|lena", "done", "done", t( "11-03T09:00" ) );
        event( f, i, WorkEvent.HANDOFF, "done|lena", "in_progress|mia", "not done", "in_progress",
            t( "11-04T09:00" ) );
        assertEquals( List.of( 0, 0 ), itemsPerWeek( report() ), "reopened and still open counts nowhere" );
        event( f, i, WorkEvent.HANDOFF, "in_progress|mia", "done|lena", "done now", "done", t( "11-10T09:00" ) );
        final FlowReport r = report();
        assertEquals( List.of( 0, 1 ), itemsPerWeek( r ), "counted once, in the week of the last close" );
        assertEquals( Duration.ofDays( 7 ), r.cycleTime().p50(), "the day it sat done is not cycle time" );
    }

    @Test
    void aHandoffThatKeepsTheStatusIsNoMove() throws SQLException {
        final Item i = item( f, "passed on in review", t( "11-01T10:00" ) );
        event( f, i, WorkEvent.HANDOFF, "review|lena", "review|mia", "your turn", "review", t( "11-04T09:00" ) );
        event( f, i, WorkEvent.CLAIMED, "review", "review", "2026-11-06T09:00:00Z", null, t( "11-05T09:00" ) );
        final FlowReport r = report();
        assertEquals( Duration.ofDays( 20 ), r.wip().get( 1 ).items().get( 0 ).age(),
            "never in progress: aged from creation" );
    }

    @Test
    void epicsAreLeftOut() throws SQLException {
        final Item epic = item( f, "PLAT", WorkLevel.EPIC, "an epic", t( "11-01T09:00" ) );
        move( f, epic, "draft", "done", t( "11-05T09:00" ) );
        assertEquals( List.of( 0, 0 ), itemsPerWeek( report() ) );
    }

    @Test
    void theWindowEndIsExclusive() throws SQLException {
        final Item in = item( f, "last second", t( "11-01T09:00" ) );
        move( f, in, "ready", "done", Instant.parse( "2026-11-15T23:59:59Z" ) );
        final Item out = item( f, "next midnight", t( "11-01T09:00" ) );
        move( f, out, "ready", "done", Instant.parse( "2026-11-16T00:00:00Z" ) );
        assertEquals( List.of( 0, 1 ), itemsPerWeek( report() ) );
    }

    @Test
    void anEmptyWindowIsZerosAndNones() throws SQLException {
        final FlowReport r = report();
        assertEquals( List.of( 0, 0 ), itemsPerWeek( r ) );
        assertEquals( Durations.NONE, r.cycleTime() );
        assertEquals( Durations.NONE, r.leadTime() );
        assertEquals( 0, r.wip().get( 1 ).count() );
        assertEquals( CLOCK.getZone().getId(), r.zone() );
    }

    @Test
    void filtersNarrow() throws SQLException {
        final Item plain = item( f, "plain", t( "11-01T09:00" ) );
        final Item tagged = item( f, "tagged", t( "11-01T09:00" ) );
        final Item bug = item( f, "bug", t( "11-01T09:00" ) );
        final Item sprinted = item( f, "sprinted", t( "11-01T09:00" ) );
        final Item mine = item( f, "assigned", t( "11-01T09:00" ) );
        final long sprint = f.svc.features().sprints().create( f.lena, "PLAT", new SprintDraft( "S1", "",
            LocalDate.parse( "2026-11-02" ), LocalDate.parse( "2026-11-15" ) ) ).sprint().id();
        FlowFixtures.update( f, "UPDATE work_item SET labels = ARRAY['backend'] WHERE id = " + tagged.id() );
        FlowFixtures.update( f, "UPDATE work_item SET type = 'bug' WHERE id = " + bug.id() );
        FlowFixtures.update( f, "UPDATE work_item SET sprint_id = " + sprint + " WHERE id = " + sprinted.id() );
        FlowFixtures.update( f, "UPDATE work_item SET assignee = 'mia', assignee_queue = NULL WHERE id = " + mine.id() );
        for ( final Item i : List.of( plain, tagged, bug, sprinted, mine ) ) {
            move( f, i, "ready", "done", t( "11-04T09:00" ) );
        }
        assertEquals( 5, itemsPerWeek( report() ).get( 0 ) );
        assertEquals( 1, itemsPerWeek( flow.project( f.lena, "PLAT", new FlowQuery( WINDOW.from(), WINDOW.to(), null,
            "Backend", null, null ) ) ).get( 0 ), "label, normalised" );
        assertEquals( 1, itemsPerWeek( flow.project( f.lena, "PLAT", new FlowQuery( WINDOW.from(), WINDOW.to(), null,
            null, WorkType.BUG, null ) ) ).get( 0 ), "type" );
        assertEquals( 1, itemsPerWeek( flow.project( f.lena, "PLAT", new FlowQuery( WINDOW.from(), WINDOW.to(), sprint,
            null, null, null ) ) ).get( 0 ), "sprint" );
        assertEquals( 1, itemsPerWeek( flow.project( f.lena, "PLAT", new FlowQuery( WINDOW.from(), WINDOW.to(), null,
            null, null, "mia" ) ) ).get( 0 ), "assignee, by display name" );
        final FlowReport nobody = flow.project( f.lena, "PLAT", new FlowQuery( WINDOW.from(), WINDOW.to(), null, null,
            null, "No Such Person" ) );
        assertEquals( List.of( 0, 0 ), itemsPerWeek( nobody ), "an unknown assignee gives an empty report" );
        assertEquals( "labels", assertThrows( WorkValidationException.class, () -> flow.project( f.lena, "PLAT",
            new FlowQuery( null, null, null, "Not A Label!", null, null ) ) ).field() );
    }

    @Test
    void aSprintOfAnotherProjectIsNotFound() throws SQLException {
        final long other = f.svc.features().sprints().create( olga, "OPS", new SprintDraft( "O1", "",
            LocalDate.parse( "2026-11-02" ), LocalDate.parse( "2026-11-15" ) ) ).sprint().id();
        assertThrows( WorkNotFoundException.class, () -> flow.project( f.lena, "PLAT",
            new FlowQuery( null, null, other, null, null, null ) ) );
        assertThrows( WorkNotFoundException.class, () -> flow.project( f.lena, "PLAT",
            new FlowQuery( null, null, 999_999L, null, null, null ) ) );
    }

    @Test
    void aSprintWithNoDatesUsesTheSprintsDates() throws SQLException {
        final long sprint = f.svc.features().sprints().create( f.lena, "PLAT", new SprintDraft( "S1", "",
            LocalDate.parse( "2026-11-09" ), LocalDate.parse( "2026-11-22" ) ) ).sprint().id();
        final FlowReport r = flow.project( f.lena, "PLAT", new FlowQuery( null, null, sprint, null, null, null ) );
        assertEquals( LocalDate.parse( "2026-11-09" ), r.from() );
        assertEquals( LocalDate.parse( "2026-11-22" ), r.to() );
        assertEquals( List.of( LocalDate.parse( "2026-11-09" ), LocalDate.parse( "2026-11-16" ) ),
            r.throughput().stream().map( FlowReport.Week::weekStart ).toList() );
    }

    @Test
    void wipShowsAgesAgainstTheClock() throws SQLException {
        final Item started = item( f, "started", t( "11-01T10:00" ) );
        move( f, started, "ready", "in_progress", t( "11-11T10:00" ) );
        final Item reviewing = item( f, "reviewing", t( "11-16T10:00" ) );
        move( f, reviewing, "ready", "review", t( "11-17T10:00" ) );
        final FlowReport r = report();
        final FlowReport.WipItem a = r.wip().get( 0 ).items().get( 0 );
        assertEquals( started.key(), a.key() );
        assertEquals( "started", a.title() );
        assertEquals( Duration.ofDays( 10 ), a.age() );
        assertEquals( Duration.ofDays( 5 ), r.wip().get( 1 ).items().get( 0 ).age(), "never in progress: from creation" );
    }

    @Test
    void aStrangerGetsNotFoundAndAMemberAnAgentAndAnAdminRead() throws SQLException {
        final Item i = item( f, "done", t( "11-01T09:00" ) );
        move( f, i, "ready", "done", t( "11-04T09:00" ) );
        assertThrows( WorkNotFoundException.class, () -> flow.project( mallory, "PLAT", WINDOW ) );
        assertThrows( WorkNotFoundException.class, () -> flow.project( olga, "PLAT", WINDOW ), "a member of another project" );
        assertThrows( WorkNotFoundException.class, () -> flow.project( mallory, "NOPE", WINDOW ) );
        assertEquals( 1, itemsPerWeek( flow.project( f.mia, "PLAT", WINDOW ) ).get( 0 ) );
        final Actor agent = Actor.agent( f.author.agentId(), f.author.login(), f.lena );
        assertEquals( 1, itemsPerWeek( flow.project( agent, "PLAT", WINDOW ) ).get( 0 ), "an agent member under a member" );
        assertThrows( WorkNotFoundException.class,
            () -> flow.project( Actor.agent( f.author.agentId(), f.author.login(), mallory ), "PLAT", WINDOW ),
            "an agent under a sponsor who is not a member" );
        assertEquals( 1, itemsPerWeek( flow.project( f.admin, "PLAT", WINDOW ) ).get( 0 ) );
    }

    @Test
    void aReportNeverCountsAnotherProjectsItems() throws SQLException {
        final Item mine = item( f, "mine", t( "11-01T09:00" ) );
        move( f, mine, "ready", "done", t( "11-04T09:00" ) );
        final Item theirs = item( f, "OPS", WorkLevel.TASK, "theirs", t( "11-01T09:00" ) );
        move( f, theirs, "ready", "done", t( "11-04T10:00" ) );
        move( f, item( f, "OPS", WorkLevel.TASK, "theirs open", t( "11-01T09:00" ) ), "ready", "in_progress", t( "11-05T09:00" ) );
        assertEquals( 1, itemsPerWeek( report() ).get( 0 ) );
        assertEquals( 0, report().wip().get( 0 ).count() );
        assertEquals( 1, itemsPerWeek( flow.project( olga, "OPS", WINDOW ) ).get( 0 ) );
    }

    @Test
    void anArchivedProjectIsStillReadable() throws SQLException {
        final Item i = item( f, "done", t( "11-01T09:00" ) );
        move( f, i, "ready", "done", t( "11-04T09:00" ) );
        f.svc.archiveProject( f.admin, "PLAT" );
        assertEquals( 1, itemsPerWeek( report() ).get( 0 ) );
    }

    @Test
    void theZoneOfTheClockDecidesTheWeekAndNothingElse() throws SQLException {
        final Clock berlin = Clock.fixed( CLOCK.instant(), ZoneId.of( "Europe/Berlin" ) );
        final GateFixture b = new GateFixture( null, berlin );
        final Item i = item( b, "late sunday", t( "11-01T09:00" ) );
        // 23:30 UTC on Sunday 11-08 is 00:30 Monday 11-09 in Berlin
        move( b, i, "ready", "done", Instant.parse( "2026-11-08T23:30:00Z" ) );
        final FlowReport r = b.svc.features().flow().project( b.lena, "PLAT", WINDOW );
        assertEquals( "Europe/Berlin", r.zone() );
        assertEquals( List.of( 0, 1 ), itemsPerWeek( r ), "the item falls in the week of 11-09" );
        assertEquals( LocalDate.parse( "2026-11-09" ), r.throughput().get( 1 ).weekStart() );
        // the window starts at Berlin midnight: an event just before it (UTC 22:59 on 11-01) is outside
        final Item early = item( b, "before the window", t( "10-20T09:00" ) );
        move( b, early, "ready", "done", Instant.parse( "2026-11-01T22:59:00Z" ) );
        assertEquals( List.of( 0, 1 ), itemsPerWeek( b.svc.features().flow().project( b.lena, "PLAT", WINDOW ) ) );
    }
}
