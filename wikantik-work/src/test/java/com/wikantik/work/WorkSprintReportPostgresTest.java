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
import com.wikantik.work.WorkSprintsService.SprintView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sprint report over real PostgreSQL, in Europe/Berlin: a sprint Monday to Friday, with each change placed on
 * its day by moving its event's {@code at}. Review Focus 1: scope changes after the start move the burndown on the
 * day they happened and never the commitment.
 */
@RequiresPostgres
class WorkSprintReportPostgresTest {

    private static final ZoneId BERLIN = ZoneId.of( "Europe/Berlin" );
    /** A Monday; {@code -Dwikantik.test.sprintMonday=2027-11-22} shifts the whole class, to prove it ignores the wall clock. */
    private static final LocalDate MON = LocalDate.parse( System.getProperty( "wikantik.test.sprintMonday", "2026-11-23" ) );

    /** A clock whose instant the test moves. */
    private static final class TestClock extends Clock {
        private Instant now = MON.plusDays( 4 ).atTime( 15, 0 ).atZone( BERLIN ).toInstant();

        @Override public ZoneId getZone() {
            return BERLIN;
        }

        @Override public Clock withZone( final ZoneId zone ) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private TestClock clock;
    private GateFixture fx;
    private WorkSprintsService sprints;
    private long sprintId;

    @BeforeEach
    void setUp() throws SQLException {
        clock = new TestClock();
        fx = new GateFixture( null, clock );
        sprints = fx.svc.features().sprints();
        sprintId = sprints.create( fx.lena, "PLAT", new SprintDraft( "S1", "", MON, MON.plusDays( 4 ) ) )
            .sprint().id();
    }

    private interface Change {
        void run() throws SQLException;
    }

    /** Runs a change and dates every event it wrote at noon Berlin on day {@code day} (0 = Monday). */
    private void on( final int day, final Change change ) throws SQLException {
        final long mark = fx.count( "SELECT COALESCE( MAX( id ), 0 ) FROM work_event" );
        change.run();
        final Instant at = MON.plusDays( day ).atTime( 12, 0 ).atZone( BERLIN ).toInstant();
        try ( Connection c = fx.ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_event SET at = ? WHERE id > ?" ) ) {
            ps.setTimestamp( 1, Timestamp.from( at ) );
            ps.setLong( 2, mark );
            ps.executeUpdate();
        }
    }

    /** A task and its estimate, both dated before the sprint week so no event keeps the database's real time. */
    private Item task( final Object estimate ) throws SQLException {
        final Item[] made = new Item[ 1 ];
        on( -4, () -> {
            final Item t = fx.task();
            made[ 0 ] = estimate == null ? t : fx.set( fx.lena, t, "estimate", estimate );
        } );
        return made[ 0 ];
    }

    private void stamp( final String column, final LocalDate day, final int hour ) throws SQLException {
        try ( Connection c = fx.ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_sprint SET " + column + " = ? WHERE id = ?" ) ) {
            ps.setTimestamp( 1, Timestamp.from( day.atTime( hour, 0 ).atZone( BERLIN ).toInstant() ) );
            ps.setLong( 2, sprintId );
            ps.executeUpdate();
        }
    }

    private Item plan( final Item i, final Object sprint ) throws SQLException {
        return fx.set( fx.mia, i, "sprint", sprint );
    }

    /** Starts the sprint; the store stamps NOW(), so the start is placed on the Sunday before the first day. */
    private SprintView start() throws SQLException {
        final SprintView v = sprints.start( fx.lena, sprintId, sprints.get( fx.lena, sprintId ).sprint().version() );
        stamp( "started_at", MON.minusDays( 1 ), 9 );
        return v;
    }

    /** Closes on {@code day} 17:00 Berlin (the store stamps NOW(), so the close is placed by SQL). */
    private void closeOn( final LocalDate day ) throws SQLException {
        sprints.close( fx.lena, sprintId, sprints.get( fx.lena, sprintId ).sprint().version(), null );
        stamp( "closed_at", day, 17 );
    }

    private void clockAt( final LocalDate day, final int hour, final int minute ) {
        clock.now = day.atTime( hour, minute ).atZone( BERLIN ).toInstant();
    }

    private SprintReport report() throws SQLException {
        return sprints.report( fx.lena, sprintId );
    }

    private static void assertDay( final Day d, final String scopePoints, final int scopeItems,
                                   final String remainingPoints, final int remainingItems ) {
        assertEquals( 0, new BigDecimal( scopePoints ).compareTo( d.scopePoints() ), "scope points " + d.date() );
        assertEquals( scopeItems, d.scopeItems(), "scope items " + d.date() );
        assertEquals( 0, new BigDecimal( remainingPoints ).compareTo( d.remainingPoints() ), "remaining " + d.date() );
        assertEquals( remainingItems, d.remainingItems(), "remaining items " + d.date() );
    }

    private static Set< String > keys( final List< WorkService.ItemSummary > l ) {
        return l.stream().map( s -> s.item().key() ).collect( java.util.stream.Collectors.toSet() );
    }

    @Test
    void theReplayAtTheStartEqualsTheCommitment() throws SQLException {
        final Item a = task( 3 );
        final Item b = task( 2 );
        on( -3, () -> plan( a, sprintId ) );
        on( -3, () -> plan( b, sprintId ) );
        start();
        final Sprint s = sprints.get( fx.lena, sprintId ).sprint();
        final Day first = report().days().get( 0 );
        assertEquals( s.committedItems(), first.scopeItems() );
        assertEquals( 0, s.committedPoints().compareTo( first.scopePoints() ) );
        assertEquals( 5, report().days().size() );
    }

    @Test
    void theCommitmentDoesNotMoveWhenScopeChanges() throws SQLException {
        final Item a = task( 3 );
        final Item b = task( 2 );
        on( -3, () -> plan( a, sprintId ) );
        on( -3, () -> plan( b, sprintId ) );
        start();
        final Sprint before = sprints.get( fx.lena, sprintId ).sprint();
        final Item c = task( 5 );
        on( 1, () -> plan( c, sprintId ) );
        on( 2, () -> plan( a, null ) );
        on( 2, () -> fx.set( fx.lena, b, "estimate", 4 ) );
        on( 3, () -> fx.status( fx.lena, b, "done" ) );
        on( 4, () -> fx.status( fx.lena, b, "ready" ) );
        final Sprint after = sprints.get( fx.lena, sprintId ).sprint();
        assertEquals( before.committedItems(), after.committedItems() );
        assertEquals( 0, before.committedPoints().compareTo( after.committedPoints() ) );
        assertEquals( before.completedItems(), after.completedItems() );
        assertEquals( before.completedPoints(), after.completedPoints() );
        assertEquals( 2, after.committedItems() );
        assertEquals( 0, new BigDecimal( "5" ).compareTo( after.committedPoints() ) );
    }

    @Test
    void aReestimateAndAReopenMoveTheBurndownOnTheirDay() throws SQLException {
        final Item a = task( 3 );
        final Item b = task( 2 );
        on( -3, () -> plan( a, sprintId ) );
        on( -3, () -> plan( b, sprintId ) );
        start();
        on( 1, () -> fx.set( fx.lena, a, "estimate", 5 ) );
        on( 2, () -> fx.status( fx.lena, b, "done" ) );
        on( 3, () -> fx.status( fx.lena, b, "ready" ) );
        final List< Day > d = report().days();
        assertDay( d.get( 0 ), "5", 2, "5", 2 );
        assertDay( d.get( 1 ), "7", 2, "7", 2 );
        assertDay( d.get( 2 ), "7", 2, "5", 1 );
        assertDay( d.get( 3 ), "7", 2, "7", 2 );
        assertDay( d.get( 4 ), "7", 2, "7", 2 );
    }

    @Test
    void aHandoffIntoDoneCountsAsDone() throws SQLException {
        final Item a = task( 3 );
        final Item b = task( 2 );
        on( -3, () -> plan( a, sprintId ) );
        on( -3, () -> plan( b, sprintId ) );
        start();
        fx.level( a, Autonomy.AUTONOMOUS );
        on( 0, () -> fx.claimedBy( fx.author, a ) );
        on( 1, () -> fx.handoff( fx.author, a, WorkStatus.DONE, "lena", null ) );
        final List< Day > d = report().days();
        assertDay( d.get( 0 ), "5", 2, "5", 2 );
        assertDay( d.get( 1 ), "5", 2, "2", 1 );
    }

    @Test
    void addedAndRemovedAreRelativeToTheStart() throws SQLException {
        final Item kept = task( 1 );
        final Item dropped = task( 1 );
        on( -3, () -> plan( kept, sprintId ) );
        on( -3, () -> plan( dropped, sprintId ) );
        start();
        final Item joined = task( 1 );
        final Item flicker = task( 1 );
        on( 1, () -> plan( joined, sprintId ) );
        on( 1, () -> plan( dropped, null ) );
        on( 2, () -> plan( flicker, sprintId ) );
        on( 3, () -> plan( flicker, null ) );
        final SprintReport r = report();
        assertEquals( Set.of( joined.key() ), keys( r.added() ) );
        assertEquals( Set.of( dropped.key() ), keys( r.removed() ) );
    }

    @Test
    void theCloseOwnMovesDoNotShowAsRemoved() throws SQLException {
        final Item a = task( 3 );
        final Item b = task( 2 );
        on( -3, () -> plan( a, sprintId ) );
        on( -3, () -> plan( b, sprintId ) );
        start();
        closeOn( MON.plusDays( 4 ) );
        final SprintReport r = report();
        assertTrue( r.removed().isEmpty() );
        assertTrue( r.added().isEmpty() );
        final Day last = r.days().get( r.days().size() - 1 );
        assertEquals( MON.plusDays( 4 ), last.date() );
        assertDay( last, "5", 2, "5", 2 );
    }

    private void twoItems() throws SQLException {
        final Item a = task( 3 );
        final Item b = task( 2 );
        on( -3, () -> plan( a, sprintId ) );
        on( -3, () -> plan( b, sprintId ) );
        start();
    }

    @Test
    void anActiveSprintMidWeekStopsAtTodayInTheClocksZone() throws SQLException {
        twoItems();
        // 00:30 Wednesday in Berlin is still Tuesday in UTC: the day is the clock's zone's day
        clockAt( MON.plusDays( 2 ), 0, 30 );
        final List< Day > d = report().days();
        assertEquals( 3, d.size() );
        assertEquals( MON.plusDays( 2 ), d.get( 2 ).date() );
    }

    @Test
    void aSprintClosedEarlyStopsAtTheCloseDay() throws SQLException {
        twoItems();
        closeOn( MON.plusDays( 2 ) );
        final List< Day > d = report().days();
        assertEquals( 3, d.size() );
        assertEquals( MON.plusDays( 2 ), d.get( 2 ).date() );
    }

    @Test
    void aSprintClosedLateRunsToTheCloseDayAndAgreesWithItsCompletion() throws SQLException {
        final Item a = task( 3 );
        final Item b = task( 2 );
        on( -3, () -> plan( a, sprintId ) );
        on( -3, () -> plan( b, sprintId ) );
        start();
        on( 5, () -> fx.status( fx.lena, a, "done" ) );
        clockAt( MON.plusDays( 8 ), 10, 0 );
        closeOn( MON.plusDays( 6 ) );
        final SprintReport r = report();
        final List< Day > d = r.days();
        assertEquals( 7, d.size() );
        final Day last = d.get( 6 );
        assertEquals( MON.plusDays( 6 ), last.date() );
        final Sprint s = r.sprint().sprint();
        assertEquals( s.completedItems(), last.scopeItems() - last.remainingItems() );
        assertEquals( 0, s.completedPoints().compareTo( last.scopePoints().subtract( last.remainingPoints() ) ) );
        assertDay( last, "5", 2, "2", 1 );
    }

    @Test
    void anActiveSprintThreeDaysOverdueRunsToToday() throws SQLException {
        twoItems();
        clockAt( MON.plusDays( 7 ), 10, 0 );
        final List< Day > d = report().days();
        assertEquals( 8, d.size() );
        assertEquals( MON.plusDays( 7 ), d.get( 7 ).date() );
        assertDay( d.get( 7 ), "5", 2, "5", 2 );
    }

    @Test
    void aPlannedSprintHasNoDays() throws SQLException {
        final SprintReport r = report();
        assertTrue( r.days().isEmpty() );
        assertTrue( r.added().isEmpty() );
        assertTrue( r.removed().isEmpty() );
        assertEquals( SprintState.PLANNED, r.sprint().sprint().state() );
    }

    @Test
    void aStrangerGetsNotFound() {
        final Actor stranger = Actor.human( "nobody", Set.of(), false );
        assertThrows( WorkNotFoundException.class, () -> sprints.report( stranger, sprintId ) );
        assertThrows( WorkNotFoundException.class, () -> sprints.report( fx.lena, 999_999L ) );
    }
}
