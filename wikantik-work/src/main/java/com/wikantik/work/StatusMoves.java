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

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one definition of "this event moved the item from status A to status B", in Java ({@link #of}) and in SQL
 * ({@link #movedSql}, {@link #toSql}, {@link #intoSql}). The flow figures, the sprint burndown and what a reporter is
 * told all read status moves through it, so an item closed by a hand-off or started by a claim counts the same
 * everywhere ({@code StatusMovesPostgresTest} compares the two forms over every event type).
 *
 * <p>A move is written by five event types:
 * <ul>
 *   <li>{@code status}: {@code from_value} to {@code to_value};</li>
 *   <li>{@code handoff}: the status part of {@code status|target} on each side;</li>
 *   <li>{@code claimed}: a take moves {@code ready} to {@code in_progress} (a take of a review item moves nothing);</li>
 *   <li>{@code claim_expired}: a lapse moves {@code in_progress} back to {@code ready} (review stays review);</li>
 *   <li>{@code released}: {@code to_value} {@code ready} means in-progress work went back to ready, unless the note
 *       is {@code moved} or {@code reassigned}: those releases come with an item update or hand-off whose own event
 *       records the move.</li>
 * </ul>
 * Either side must be a status word and the two must differ; anything else is not a move.
 */
final class StatusMoves {

    /** A move: {@code from} is null when the event does not name a known status before it. */
    record Move( String from, String to ) {
    }

    /** The event types that may carry a status move. */
    static final Set< String > TYPES = Set.of( WorkEvent.STATUS, WorkEvent.HANDOFF, WorkEvent.CLAIMED,
        WorkEvent.CLAIM_EXPIRED, WorkEvent.RELEASED );

    /** The release notes that come with an item update or hand-off, which records the status move itself. */
    private static final Set< String > UPDATE_RELEASES = Set.of( "moved", "reassigned" );
    private static final Set< String > STATUSES = EnumSet.allOf( WorkStatus.class ).stream().map( WorkStatus::wire )
        .collect( Collectors.toUnmodifiableSet() );
    private static final String READY = WorkStatus.READY.wire();
    private static final String IN_PROGRESS = WorkStatus.IN_PROGRESS.wire();

    private StatusMoves() {
    }

    /** The move this event made, or empty when it moved no status. */
    static Optional< Move > of( final WorkEvent e ) {
        return of( e.type(), e.fromValue(), e.toValue(), e.note() );
    }

    /** The move an event row with these columns made, or empty when it moved no status. */
    static Optional< Move > of( final String type, final String fromValue, final String toValue, final String note ) {
        final String from;
        final String to;
        switch ( Objects.toString( type, "" ) ) {
            case WorkEvent.STATUS, WorkEvent.CLAIMED, WorkEvent.CLAIM_EXPIRED -> {
                from = fromValue;
                to = toValue;
            }
            case WorkEvent.HANDOFF -> {
                from = statusPart( fromValue );
                to = statusPart( toValue );
            }
            case WorkEvent.RELEASED -> {
                // a claimed item is in progress or in review; only in-progress work goes back to ready
                if ( !READY.equals( toValue ) || UPDATE_RELEASES.contains( Objects.toString( note, "" ) ) ) {
                    return Optional.empty();
                }
                from = IN_PROGRESS;
                to = READY;
            }
            default -> {
                return Optional.empty();
            }
        }
        if ( to == null || !STATUSES.contains( to ) || to.equals( from ) ) {
            return Optional.empty();
        }
        return Optional.of( new Move( from != null && STATUSES.contains( from ) ? from : null, to ) );
    }

    /** The SQL condition, over the {@code work_event} row aliased {@code alias}, that holds exactly when {@link #of} is present. */
    static String movedSql( final String alias ) {
        final String a = alias + ".";
        final String statuses = STATUSES.stream().sorted().map( s -> "'" + s + "'" ).collect( Collectors.joining( "," ) );
        final String types = TYPES.stream().sorted().map( s -> "'" + s + "'" ).collect( Collectors.joining( "," ) );
        return "( " + a + "type IN (" + types + ") AND ( "
            + "( " + a + "type IN ('" + WorkEvent.STATUS + "','" + WorkEvent.CLAIMED + "','" + WorkEvent.CLAIM_EXPIRED
            + "') AND " + a + "to_value IN (" + statuses + ") AND " + a + "from_value IS DISTINCT FROM " + a + "to_value )"
            + " OR ( " + a + "type = '" + WorkEvent.HANDOFF + "' AND split_part( " + a + "to_value, '|', 1 ) IN ("
            + statuses + ") AND split_part( " + a + "from_value, '|', 1 ) IS DISTINCT FROM split_part( " + a
            + "to_value, '|', 1 ) )"
            + " OR ( " + a + "type = '" + WorkEvent.RELEASED + "' AND " + a + "to_value = '" + READY + "' AND COALESCE( "
            + a + "note, '' ) NOT IN (" + UPDATE_RELEASES.stream().sorted().map( s -> "'" + s + "'" )
            .collect( Collectors.joining( "," ) ) + ") ) ) )";
    }

    /** The SQL expression for the status a move went to; meaningful only where {@link #movedSql} holds. */
    static String toSql( final String alias ) {
        final String a = alias + ".";
        return "CASE WHEN " + a + "type = '" + WorkEvent.HANDOFF + "' THEN split_part( " + a + "to_value, '|', 1 ) ELSE "
            + a + "to_value END";
    }

    /** The SQL condition that the row aliased {@code alias} is a move into {@code status}. */
    static String intoSql( final String alias, final WorkStatus status ) {
        return "( " + movedSql( alias ) + " AND " + toSql( alias ) + " = '" + status.wire() + "' )";
    }

    private static String statusPart( final String value ) {
        if ( value == null ) {
            return null;
        }
        final int bar = value.indexOf( '|' );
        return bar < 0 ? value : value.substring( 0, bar );
    }
}
