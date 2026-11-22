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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricWindowTest {

    /** A Saturday. */
    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-21T10:00:00Z" ), ZoneOffset.UTC );

    private static LocalDate d( final String s ) {
        return LocalDate.parse( s );
    }

    @Test
    void defaultsToTheLast90Days() {
        final MetricWindow w = MetricWindow.of( null, null, CLOCK );
        assertEquals( d( "2026-08-24" ), w.from() );
        assertEquals( d( "2026-11-21" ), w.to() );
        assertEquals( ZoneOffset.UTC, w.zone() );
    }

    @Test
    void fromOnlyEndsToday() {
        assertEquals( d( "2026-11-21" ), MetricWindow.of( d( "2026-11-01" ), null, CLOCK ).to() );
    }

    @Test
    void toOnlyStarts89DaysBefore() {
        assertEquals( d( "2026-04-12" ), MetricWindow.of( null, d( "2026-07-10" ), CLOCK ).from() );
    }

    @Test
    void the366DaysAreAllowedThe367AreNot() {
        MetricWindow.of( d( "2025-12-31" ), d( "2026-12-31" ), CLOCK );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> MetricWindow.of( d( "2025-12-30" ), d( "2026-12-31" ), CLOCK ) );
        assertEquals( "to", e.field() );
        assertEquals( "A report covers at most 366 days.", e.getMessage() );
    }

    @Test
    void fromAfterToIsRefusedNamingFrom() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> MetricWindow.of( d( "2026-11-10" ), d( "2026-11-09" ), CLOCK ) );
        assertEquals( "from", e.field() );
        assertEquals( "The start date is after the end date.", e.getMessage() );
    }

    @Test
    void aDefaultedStartAfterAnEarlyToIsStillAValidWindow() {
        // to alone, in the distant past: from = to - 89 days, never after to
        final MetricWindow w = MetricWindow.of( null, d( "1970-01-05" ), CLOCK );
        assertEquals( d( "1969-10-08" ), w.from() );
    }

    @Test
    void theEndIsExclusive() {
        final MetricWindow w = MetricWindow.of( d( "2026-11-01" ), d( "2026-11-21" ), CLOCK );
        assertTrue( w.contains( Instant.parse( "2026-11-21T23:59:59Z" ) ) );
        assertFalse( w.contains( Instant.parse( "2026-11-22T00:00:00Z" ) ) );
        assertTrue( w.contains( Instant.parse( "2026-11-01T00:00:00Z" ) ) );
        assertFalse( w.contains( Instant.parse( "2026-10-31T23:59:59Z" ) ) );
        assertEquals( Instant.parse( "2026-11-22T00:00:00Z" ), w.end() );
    }

    @Test
    void sundayNightAndMondayAreDifferentWeeks() {
        final MetricWindow w = MetricWindow.of( d( "2026-11-01" ), d( "2026-11-21" ), CLOCK );
        assertEquals( d( "2026-11-02" ), w.weekOf( Instant.parse( "2026-11-08T23:59:59Z" ) ) );
        assertEquals( d( "2026-11-09" ), w.weekOf( Instant.parse( "2026-11-09T00:00:00Z" ) ) );
    }

    @Test
    void theZoneDecidesTheWeek() {
        final Clock berlin = Clock.fixed( CLOCK.instant(), ZoneId.of( "Europe/Berlin" ) );
        final MetricWindow w = MetricWindow.of( d( "2026-11-01" ), d( "2026-11-21" ), berlin );
        assertEquals( d( "2026-11-09" ), w.weekOf( Instant.parse( "2026-11-08T23:30:00Z" ) ) );
        assertEquals( Instant.parse( "2026-10-31T23:00:00Z" ), w.start() );
    }

    @Test
    void weekStartsCoversPartialWeeks() {
        final MetricWindow w = MetricWindow.of( d( "2026-11-04" ), d( "2026-11-18" ), CLOCK );
        assertEquals( List.of( d( "2026-11-02" ), d( "2026-11-09" ), d( "2026-11-16" ) ), w.weekStarts() );
    }

    @Test
    void aSpringForwardWeekIsStillOneWeek() {
        final Clock berlin = Clock.fixed( CLOCK.instant(), ZoneId.of( "Europe/Berlin" ) );
        // the clocks went forward on Sunday 2026-03-29: that week has 167 hours, and is still one week
        final MetricWindow w = MetricWindow.of( d( "2026-03-23" ), d( "2026-04-12" ), berlin );
        assertEquals( List.of( d( "2026-03-23" ), d( "2026-03-30" ), d( "2026-04-06" ) ), w.weekStarts() );
        assertEquals( Instant.parse( "2026-03-22T23:00:00Z" ), w.start(), "local midnight, still winter time" );
        assertEquals( d( "2026-03-23" ), w.weekOf( Instant.parse( "2026-03-29T00:30:00Z" ) ) );
        assertEquals( d( "2026-03-30" ), w.weekOf( Instant.parse( "2026-03-29T22:00:00Z" ) ), "Monday 00:00 CEST" );
        assertEquals( d( "2026-03-23" ), w.weekOf( Instant.parse( "2026-03-29T21:59:59Z" ) ) );
    }

    @Test
    void anInProgressWeekIsListed() {
        final MetricWindow w = MetricWindow.of( d( "2026-11-16" ), d( "2026-11-21" ), CLOCK );
        assertEquals( List.of( d( "2026-11-16" ) ), w.weekStarts() );
    }
}
