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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.wikantik.work.SprintEvent.Kind.ESTIMATE;
import static com.wikantik.work.SprintEvent.Kind.SPRINT;
import static com.wikantik.work.SprintEvent.Kind.STATUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The burndown replay, with hand-built events and no database: every way scope can change mid-sprint. */
class BurndownTest {

    private static final ZoneId BERLIN = ZoneId.of( "Europe/Berlin" );
    private static final LocalDate MON = LocalDate.of( 2026, 11, 23 );
    private static final LocalDate FRI = MON.plusDays( 4 );
    private static final long ID = 7;
    private static final long END = Long.MAX_VALUE;

    private final List< SprintEvent > events = new ArrayList<>();
    private long nextId = 1;

    private SprintEvent at( final long item, final LocalDateTime local, final SprintEvent.Kind kind,
                            final String value ) {
        final SprintEvent e = new SprintEvent( nextId++, item, local.atZone( BERLIN ).toInstant(), kind, value );
        events.add( e );
        return e;
    }

    /** A change on the given day (0 = Monday) at noon local time. */
    private void on( final int day, final long item, final SprintEvent.Kind kind, final String value ) {
        at( item, MON.plusDays( day ).atTime( 12, 0 ), kind, value );
    }

    /** Items 1 (3 points), 2 (2 points) and 3 (no estimate) planned into sprint 7 before the first day. */
    private void planned() {
        at( 1, MON.minusDays( 3 ).atTime( 9, 0 ), SPRINT, "7" );
        at( 1, MON.minusDays( 3 ).atTime( 9, 1 ), ESTIMATE, "3" );
        at( 2, MON.minusDays( 3 ).atTime( 9, 2 ), SPRINT, "7" );
        at( 2, MON.minusDays( 3 ).atTime( 9, 3 ), ESTIMATE, "2" );
        at( 3, MON.minusDays( 3 ).atTime( 9, 4 ), SPRINT, "7" );
    }

    private List< Day > days() {
        return Burndown.of( ID, events, MON, FRI, END, BERLIN );
    }

    private static BigDecimal p( final String v ) {
        return new BigDecimal( v );
    }

    private static void assertDay( final Day d, final String scopePoints, final int scopeItems,
                                   final String remainingPoints, final int remainingItems ) {
        assertEquals( 0, p( scopePoints ).compareTo( d.scopePoints() ), "scope points on " + d.date() );
        assertEquals( scopeItems, d.scopeItems(), "scope items on " + d.date() );
        assertEquals( 0, p( remainingPoints ).compareTo( d.remainingPoints() ), "remaining points on " + d.date() );
        assertEquals( remainingItems, d.remainingItems(), "remaining items on " + d.date() );
    }

    @Test
    void anItemAddedOnDayThreeRaisesScopeAndRemainingFromDayThree() {
        planned();
        on( 2, 4, SPRINT, "7" );
        on( 2, 4, ESTIMATE, "5" );
        final List< Day > d = days();
        assertEquals( 5, d.size() );
        assertDay( d.get( 0 ), "5", 3, "5", 3 );
        assertDay( d.get( 1 ), "5", 3, "5", 3 );
        for ( int i = 2; i < 5; i++ ) {
            assertDay( d.get( i ), "10", 4, "10", 4 );
        }
    }

    @Test
    void anItemRemovedOnDayTwoLowersBothFromDayTwo() {
        planned();
        on( 1, 1, SPRINT, null );
        final List< Day > d = days();
        assertDay( d.get( 0 ), "5", 3, "5", 3 );
        for ( int i = 1; i < 5; i++ ) {
            assertDay( d.get( i ), "2", 2, "2", 2 );
        }
    }

    @Test
    void aReestimateFromThreeToFiveMovesBothByTwoOnItsDay() {
        planned();
        on( 3, 1, ESTIMATE, "5" );
        final List< Day > d = days();
        assertDay( d.get( 2 ), "5", 3, "5", 3 );
        assertDay( d.get( 3 ), "7", 3, "7", 3 );
        assertDay( d.get( 4 ), "7", 3, "7", 3 );
    }

    @Test
    void anItemClosedThenReopenedComesBack() {
        planned();
        on( 1, 1, STATUS, "done" );
        on( 3, 1, STATUS, "in_progress" );
        final List< Day > d = days();
        assertDay( d.get( 0 ), "5", 3, "5", 3 );
        assertDay( d.get( 1 ), "5", 3, "2", 2 );
        assertDay( d.get( 2 ), "5", 3, "2", 2 );
        assertDay( d.get( 3 ), "5", 3, "5", 3 );
        assertDay( d.get( 4 ), "5", 3, "5", 3 );
    }

    @Test
    void aCancelledItemLeavesScope() {
        planned();
        on( 2, 2, STATUS, "cancelled" );
        final List< Day > d = days();
        assertDay( d.get( 1 ), "5", 3, "5", 3 );
        assertDay( d.get( 2 ), "3", 2, "3", 2 );
    }

    @Test
    void aNullEstimateCountsOneItemAndNoPoints() {
        at( 3, MON.minusDays( 1 ).atTime( 9, 0 ), SPRINT, "7" );
        for ( final Day d : days() ) {
            assertDay( d, "0", 1, "0", 1 );
        }
    }

    @Test
    void anItemInAnotherSprintIsNotCounted() {
        planned();
        at( 9, MON.minusDays( 1 ).atTime( 9, 0 ), SPRINT, "8" );
        at( 9, MON.minusDays( 1 ).atTime( 9, 1 ), ESTIMATE, "13" );
        on( 1, 2, SPRINT, "8" );
        final List< Day > d = days();
        assertDay( d.get( 0 ), "5", 3, "5", 3 );
        assertDay( d.get( 1 ), "3", 2, "3", 2 );
    }

    @Test
    void eventsAtOrAfterTheCutoffAreIgnored() {
        planned();
        on( 3, 1, SPRINT, null );
        final SprintEvent cut = events.get( events.size() - 1 );
        final List< Day > d = Burndown.of( ID, events, MON, FRI, cut.id(), BERLIN );
        assertDay( d.get( 4 ), "5", 3, "5", 3 );
        assertDay( days().get( 4 ), "2", 2, "2", 2 );
    }

    @Test
    void eventsAreBucketedAtTheZonesMidnight() {
        planned();
        at( 1, MON.atTime( 23, 30 ), STATUS, "done" );
        at( 2, MON.plusDays( 1 ).atTime( 0, 30 ), STATUS, "done" );
        final List< Day > d = days();
        assertDay( d.get( 0 ), "5", 3, "2", 2 );
        assertDay( d.get( 1 ), "5", 3, "0", 1 );
    }

    @Test
    void noDaysWhenTheEndIsBeforeTheStart() {
        planned();
        assertTrue( Burndown.of( ID, events, FRI, MON, END, BERLIN ).isEmpty() );
        assertEquals( 1, Burndown.of( ID, events, MON, MON, END, BERLIN ).size() );
    }

    @Test
    void anUnparsableEstimateCountsAsNone() {
        planned();
        on( 0, 1, ESTIMATE, "three" );
        final List< Day > d = days();
        assertDay( d.get( 0 ), "2", 3, "2", 3 );
    }

    @Test
    void membersAtIgnoresStatusAndLaterEvents() {
        planned();
        on( 1, 2, STATUS, "cancelled" );
        on( 1, 1, SPRINT, null );
        final long cutoff = events.get( events.size() - 1 ).id();
        assertEquals( Set.of( 1L, 2L, 3L ), Burndown.membersAt( ID, events, cutoff ) );
        assertEquals( Set.of( 2L, 3L ), Burndown.membersAt( ID, events, END ) );
        assertEquals( Set.of(), Burndown.membersAt( ID, events, 1 ) );
    }

    @Test
    void anEventExactlyAtLocalMidnightBelongsToTheNewDay() {
        planned();
        at( 1, MON.plusDays( 1 ).atStartOfDay(), STATUS, "done" );
        final List< Day > d = days();
        assertDay( d.get( 0 ), "5", 3, "5", 3 );
        assertDay( d.get( 1 ), "5", 3, "2", 2 );
    }

    @Test
    void aDayAcrossTheAutumnClockChangeIsBucketedByLocalDate() {
        // 2026-10-25 is 25 hours long in Berlin: 23:30 that evening is still the 25th, 00:00 is the 26th
        final LocalDate sat = LocalDate.of( 2026, 10, 24 );
        at( 1, sat.minusDays( 1 ).atTime( 9, 0 ), SPRINT, "7" );
        at( 1, sat.minusDays( 1 ).atTime( 9, 1 ), ESTIMATE, "3" );
        at( 2, sat.minusDays( 1 ).atTime( 9, 2 ), SPRINT, "7" );
        at( 2, sat.minusDays( 1 ).atTime( 9, 3 ), ESTIMATE, "2" );
        at( 1, sat.plusDays( 1 ).atTime( 23, 30 ), STATUS, "done" );
        at( 2, sat.plusDays( 2 ).atStartOfDay(), STATUS, "done" );
        final List< Day > d = Burndown.of( ID, events, sat, sat.plusDays( 3 ), END, BERLIN );
        assertDay( d.get( 0 ), "5", 2, "5", 2 );
        assertDay( d.get( 1 ), "5", 2, "2", 1 );
        assertDay( d.get( 2 ), "5", 2, "0", 0 );
        assertEquals( 4, d.size() );
    }
}
