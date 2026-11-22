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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The burndown as a pure replay of an item's sprint, estimate and status events (P2-3, P2-4). Scope and remaining at
 * the end of each day are derived from the events up to then, so a change made mid-sprint moves the line on the day
 * it happened and the committed figures (which are never replayed) stay as they were at the start.
 */
final class Burndown {

    private static final Logger LOG = LogManager.getLogger( Burndown.class );

    /** What the replay knows of one item. */
    private static final class State {
        boolean inSprint;
        BigDecimal estimate;
        boolean done;
        boolean cancelled;
    }

    private Burndown() {
    }

    /**
     * One {@link Day} for each date from {@code from} to {@code to} inclusive, empty when {@code to} is before
     * {@code from}. A day ends at the zone's next midnight; only events with an id below {@code beforeEventId} and an
     * {@code at} before that midnight are replayed, in the order given (event id).
     */
    static List< Day > of( final long sprintId, final List< SprintEvent > events, final LocalDate from,
                           final LocalDate to, final long beforeEventId, final ZoneId zone ) {
        final List< Day > days = new ArrayList<>();
        for ( LocalDate d = from; !d.isAfter( to ); d = d.plusDays( 1 ) ) {
            final Instant end = d.plusDays( 1 ).atStartOfDay( zone ).toInstant();
            days.add( day( d, replay( sprintId, events, beforeEventId, end ) ) );
        }
        return days;
    }

    /**
     * The items whose last sprint event before {@code beforeEventId} names the sprint. Status is ignored: a cancelled
     * item is still a member of its sprint.
     */
    static Set< Long > membersAt( final long sprintId, final List< SprintEvent > events, final long beforeEventId ) {
        final Map< Long, Boolean > in = new HashMap<>();
        for ( final SprintEvent e : events ) {
            if ( e.id() < beforeEventId && e.kind() == SprintEvent.Kind.SPRINT ) {
                in.put( e.itemId(), names( sprintId, e.value() ) );
            }
        }
        final Set< Long > out = new LinkedHashSet<>();
        in.forEach( ( item, member ) -> {
            if ( member ) {
                out.add( item );
            }
        } );
        return out;
    }

    private static Map< Long, State > replay( final long sprintId, final List< SprintEvent > events,
                                              final long beforeEventId, final Instant end ) {
        final Map< Long, State > states = new HashMap<>();
        for ( final SprintEvent e : events ) {
            if ( e.id() < beforeEventId && e.at().isBefore( end ) ) {
                apply( states.computeIfAbsent( e.itemId(), k -> new State() ), sprintId, e );
            }
        }
        return states;
    }

    private static void apply( final State s, final long sprintId, final SprintEvent e ) {
        switch ( e.kind() ) {
            case SPRINT -> s.inSprint = names( sprintId, e.value() );
            case ESTIMATE -> s.estimate = estimate( e.value() );
            case STATUS -> {
                s.done = "done".equals( e.value() );
                s.cancelled = "cancelled".equals( e.value() );
            }
        }
    }

    private static Day day( final LocalDate date, final Map< Long, State > states ) {
        BigDecimal scopePoints = BigDecimal.ZERO.setScale( 1 );
        BigDecimal remainingPoints = BigDecimal.ZERO.setScale( 1 );
        int scopeItems = 0;
        int remainingItems = 0;
        for ( final State s : states.values() ) {
            if ( !s.inSprint || s.cancelled ) {
                continue;
            }
            final BigDecimal points = s.estimate == null ? BigDecimal.ZERO : s.estimate;
            scopeItems++;
            scopePoints = scopePoints.add( points );
            if ( !s.done ) {
                remainingItems++;
                remainingPoints = remainingPoints.add( points );
            }
        }
        return new Day( date, remainingPoints, remainingItems, scopePoints, scopeItems );
    }

    private static boolean names( final long sprintId, final String value ) {
        return String.valueOf( sprintId ).equals( value );
    }

    /** An estimate that is null or does not parse counts as none; it never throws. */
    private static BigDecimal estimate( final String value ) {
        if ( value == null ) {
            return null;
        }
        try {
            return new BigDecimal( value );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Ignoring an estimate event whose value is not a number: '{}': {}", value, e.getMessage() );
            return null;
        }
    }
}
