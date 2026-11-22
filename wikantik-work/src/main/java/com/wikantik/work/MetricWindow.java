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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * The days a flow or DORA report covers, in the work clock's zone: {@code from} 00:00 up to and excluding
 * {@code to + 1} 00:00. Weeks start on Monday. SQL only ever receives the instants {@link #start()} and
 * {@link #end()}.
 */
record MetricWindow( LocalDate from, LocalDate to, ZoneId zone ) {

    /** The days a report covers when it is not told: the last 90, today included. */
    static final int DEFAULT_DAYS = 90;
    /** The most days one report covers. */
    static final int MAX_DAYS = 366;

    /**
     * The window for the requested days: {@code to} defaults to today, {@code from} to 89 days before {@code to}.
     */
    static MetricWindow of( final LocalDate from, final LocalDate to, final Clock clock ) {
        final LocalDate end = to == null ? LocalDate.now( clock ) : to;
        final LocalDate begin = from == null ? end.minusDays( DEFAULT_DAYS - 1 ) : from;
        if ( begin.isAfter( end ) ) {
            throw new WorkValidationException( "from", "The start date is after the end date." );
        }
        if ( ChronoUnit.DAYS.between( begin, end ) + 1 > MAX_DAYS ) {
            throw new WorkValidationException( "to", "A report covers at most 366 days." );
        }
        return new MetricWindow( begin, end, clock.getZone() );
    }

    Instant start() {
        return from.atStartOfDay( zone ).toInstant();
    }

    /** Exclusive. */
    Instant end() {
        return to.plusDays( 1 ).atStartOfDay( zone ).toInstant();
    }

    boolean contains( final Instant t ) {
        return !t.isBefore( start() ) && t.isBefore( end() );
    }

    /** The Monday on or before the instant's date in the zone. */
    LocalDate weekOf( final Instant t ) {
        return t.atZone( zone ).toLocalDate().with( TemporalAdjusters.previousOrSame( DayOfWeek.MONDAY ) );
    }

    /** Every Monday from the week of the first day to the week of the last, partial weeks included. */
    List< LocalDate > weekStarts() {
        final List< LocalDate > out = new ArrayList<>();
        final LocalDate last = weekOf( end().minusNanos( 1 ) );
        for ( LocalDate w = weekOf( start() ); !w.isAfter( last ); w = w.plusWeeks( 1 ) ) {
            out.add( w );
        }
        return out;
    }
}
