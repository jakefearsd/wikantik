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
import com.wikantik.work.WorkFlowStore.ClosedItem;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.wikantik.jdbc.NullableColumns.getInstant;

/**
 * How long each closed item sat done while it was later reopened: the time between a move to done and the move that
 * took it out of done again. The flow figures take this off an item's cycle time, which counts only the time an item
 * was open from its first start to its last close. Moves are read through {@link StatusMoves}, the one definition.
 */
final class ReopenedTime {

    /** A status move and when it happened. */
    record At( Instant at, StatusMoves.Move move ) {
    }

    private static final String DONE = WorkStatus.DONE.wire();

    private ReopenedTime() {
    }

    /**
     * The time spent done between the first start and the last close, from the item's moves in order: each interval
     * from a move to done to the next move out of it. A done interval that began before the first start is ignored.
     */
    static Duration gap( final List< At > moves, final Instant firstStart ) {
        Duration total = Duration.ZERO;
        Instant doneSince = null;
        for ( final At m : moves ) {
            if ( m.at().isBefore( firstStart ) ) {
                continue;
            }
            if ( DONE.equals( m.move().to() ) ) {
                if ( doneSince == null ) {
                    doneSince = m.at();
                }
            } else if ( doneSince != null ) {
                total = total.plus( Duration.between( doneSince, m.at() ) );
                doneSince = null;
            }
        }
        return total;
    }

    /** The items with their reopened time filled in; an item that never started has none. */
    static List< ClosedItem > withGaps( final Jdbc jdbc, final List< ClosedItem > items ) throws SQLException {
        if ( items.isEmpty() ) {
            return items;
        }
        final Map< Long, List< At > > moves = movesOf( jdbc, items.stream().map( ClosedItem::id ).toList() );
        final List< ClosedItem > out = new ArrayList<>( items.size() );
        for ( final ClosedItem c : items ) {
            out.add( c.firstInProgressAt() == null ? c : c.withReopenedGap(
                gap( moves.getOrDefault( c.id(), List.of() ), c.firstInProgressAt() ) ) );
        }
        return out;
    }

    private record Row( long itemId, String type, String from, String to, String note, Instant at ) {
    }

    private static Map< Long, List< At > > movesOf( final Jdbc jdbc, final List< Long > ids ) throws SQLException {
        final List< Row > rows = jdbc.withConnection( conn -> jdbc.query( conn,
            "SELECT e.item_id, e.at, e.type, e.from_value, e.to_value, e.note FROM work_event e "
            + "WHERE e.item_id = ANY( ? ) AND " + StatusMoves.movedSql( "e" ) + " ORDER BY e.item_id, e.at, e.id",
            ps -> ps.setArray( 1, conn.createArrayOf( "bigint", ids.toArray( Long[]::new ) ) ),
            rs -> new Row( rs.getLong( "item_id" ), rs.getString( "type" ), rs.getString( "from_value" ),
                rs.getString( "to_value" ), rs.getString( "note" ), getInstant( rs, "at" ) ) ) );
        final Map< Long, List< At > > out = new HashMap<>();
        for ( final Row r : rows ) {
            StatusMoves.of( r.type(), r.from(), r.to(), r.note() ).ifPresent( m ->
                out.computeIfAbsent( r.itemId(), k -> new ArrayList<>() ).add( new At( r.at(), m ) ) );
        }
        return out;
    }
}
