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

import com.wikantik.jdbc.Jdbc;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.wikantik.jdbc.NullableColumns.getInstant;

/** The sprint report's queries: the events to replay, and an item's points through its children. */
final class JdbcSprintHistory {

    private static final String MARKERS = "SELECT type, id FROM work_event WHERE project_id = ? AND item_id IS NULL "
        + "AND type IN ('sprint_started','sprint_closed') AND to_value = ?";

    private static final String ITEMS = "SELECT id FROM work_item WHERE sprint_id = ? UNION "
        + "SELECT item_id FROM work_event WHERE project_id = ? AND type = 'field' AND note = 'sprint' "
        + "AND ( from_value = ? OR to_value = ? )";

    /** Sprint and estimate edits, and every status move by any route ({@link StatusMoves}, the one definition). */
    private static final String EVENTS = "SELECT e.id, e.item_id, e.at, e.type, e.from_value, e.to_value, e.note "
        + "FROM work_event e WHERE e.item_id = ANY( ? ) AND ( ( e.type = 'field' AND e.note IN ('sprint','estimate') ) "
        + "OR " + StatusMoves.movedSql( "e" ) + " ) ORDER BY e.id";

    private static final String CHILD_POINTS = "SELECT COALESCE( SUM( COALESCE( s.estimate, ( SELECT SUM( t.estimate ) "
        + "FROM work_item t WHERE t.parent_id = s.id AND t.status <> 'cancelled' ) ) ), 0 ) "
        + "FROM work_item s WHERE s.parent_id = ? AND s.status <> 'cancelled'";

    private final Jdbc jdbc;

    JdbcSprintHistory( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    SprintHistory eventsFor( final long projectId, final long sprintId ) throws SQLException {
        final String sprint = String.valueOf( sprintId );
        final long[] marks = { 0, Long.MAX_VALUE };
        jdbc.query( MARKERS, ps -> {
            ps.setLong( 1, projectId );
            ps.setString( 2, sprint );
        }, rs -> {
            marks[ WorkEvent.SPRINT_STARTED.equals( rs.getString( 1 ) ) ? 0 : 1 ] = rs.getLong( 2 );
            return null;
        } );
        final List< Long > items = jdbc.query( ITEMS, ps -> {
            ps.setLong( 1, sprintId );
            ps.setLong( 2, projectId );
            ps.setString( 3, sprint );
            ps.setString( 4, sprint );
        }, rs -> rs.getLong( 1 ) );
        final List< SprintEvent > events = new ArrayList<>();
        if ( !items.isEmpty() ) {
            jdbc.withConnection( conn -> {
                final Array ids = conn.createArrayOf( "bigint", items.toArray( Long[]::new ) );
                events.addAll( jdbc.query( conn, EVENTS, ps -> ps.setArray( 1, ids ), rs -> new SprintEvent(
                    rs.getLong( "id" ), rs.getLong( "item_id" ), getInstant( rs, "at" ),
                    kind( rs.getString( "type" ), rs.getString( "note" ) ),
                    value( rs.getString( "type" ), rs.getString( "from_value" ), rs.getString( "to_value" ),
                        rs.getString( "note" ) ) ) ) );
                return null;
            } );
        }
        return new SprintHistory( List.copyOf( events ), marks[ 0 ], marks[ 1 ] );
    }

    private static SprintEvent.Kind kind( final String type, final String note ) {
        if ( WorkEvent.FIELD.equals( type ) ) {
            return "sprint".equals( note ) ? SprintEvent.Kind.SPRINT : SprintEvent.Kind.ESTIMATE;
        }
        return SprintEvent.Kind.STATUS;
    }

    /** A field edit's value as stored; a status move's value is the status it went to ({@link StatusMoves}). */
    private static String value( final String type, final String fromValue, final String toValue,
                                 final String note ) {
        if ( WorkEvent.FIELD.equals( type ) ) {
            return toValue;
        }
        return StatusMoves.of( type, fromValue, toValue, note ).map( StatusMoves.Move::to ).orElse( toValue );
    }

    BigDecimal childPoints( final long itemId ) throws SQLException {
        return jdbc.queryOne( CHILD_POINTS, ps -> ps.setLong( 1, itemId ), rs -> rs.getBigDecimal( 1 ) )
            .map( JdbcWorkSprints::points ).orElse( BigDecimal.ZERO.setScale( 1 ) );
    }
}
