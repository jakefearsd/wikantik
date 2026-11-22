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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-1: the one definition of a status move, in its Java and SQL forms, over a fixture holding every event type with
 * every shape of value a writer produces (and some no writer produces). The flow figures, the burndown and the
 * reporter rule read moves only through {@link StatusMoves}, so agreement here is agreement between all three.
 */
@RequiresPostgres
class StatusMovesPostgresTest {

    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-21T10:00:00Z" ), ZoneOffset.UTC );
    private static final Instant AT = Instant.parse( "2026-11-10T10:00:00Z" );

    /** from, to, note: plain statuses, hand-off pairs, holders, released notes, nonsense and nulls. */
    private static final List< String[] > VALUES = List.of( new String[] { "ready", "done", null },
        new String[] { "ready", "in_progress", "2026-11-11T10:00:00Z" }, new String[] { "review", "review", null },
        new String[] { "in_progress", "ready", null }, new String[] { null, "ready", null },
        new String[] { "ready", null, null }, new String[] { "ready", "otto", null },
        new String[] { "ready", "nonsense", null }, new String[] { "in_progress|otto", "done|queue:x", "n" },
        new String[] { "ready|otto", "ready|queue:x", "n" }, new String[] { null, "done|otto", null },
        new String[] { "done|lena", "in_progress|mia", "reopen" }, new String[] { "agent:1", "ready", "released" },
        new String[] { "agent:1", "ready", "moved" }, new String[] { "agent:1", "ready", "reassigned" },
        new String[] { "agent:1", "ready", "access removed" }, new String[] { "agent:1", "review", null },
        new String[] { "agent:1", "ready", null }, new String[] { "change:3", "done", null } );

    private GateFixture f;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture( null, CLOCK );
        item = FlowFixtures.item( f, "every move", AT.minusSeconds( 86_400 ) );
        for ( final Field field : WorkEvent.class.getFields() ) {
            if ( Modifier.isStatic( field.getModifiers() ) && field.getType() == String.class ) {
                for ( final String[] v : VALUES ) {
                    FlowFixtures.event( f, item, (String) value( field ), v[ 0 ], v[ 1 ], v[ 2 ], null, AT );
                }
            }
        }
    }

    private static Object value( final Field field ) {
        try {
            return field.get( null );
        } catch ( final IllegalAccessException e ) {
            throw new IllegalStateException( "cannot read " + field.getName(), e );
        }
    }

    /** event id to the status moved to, by the SQL form. */
    private Map< Long, String > sqlMoves() throws SQLException {
        final Map< Long, String > out = new TreeMap<>();
        try ( Connection c = f.ds.getConnection(); Statement st = c.createStatement();
              ResultSet rs = st.executeQuery( "SELECT e.id, " + StatusMoves.toSql( "e" ) + " FROM work_event e "
                  + "WHERE e.item_id = " + item.id() + " AND " + StatusMoves.movedSql( "e" ) ) ) {
            while ( rs.next() ) {
                out.put( rs.getLong( 1 ), rs.getString( 2 ) );
            }
        }
        return out;
    }

    @Test
    void theSqlAndTheJavaFormAgreeOnEveryEventTypeAndShape() throws SQLException {
        final Map< Long, String > java = new TreeMap<>();
        for ( final WorkEvent e : new JdbcWorkStore( f.ds ).itemEvents( item.id() ) ) {
            StatusMoves.of( e ).ifPresent( m -> java.put( e.id(), m.to() ) );
        }
        assertTrue( java.size() >= 15, "the comparison covers real moves: " + java.size() );
        assertEquals( java, sqlMoves() );
    }

    @Test
    void intoSqlPicksExactlyTheMovesIntoOneStatus() throws SQLException {
        for ( final WorkStatus s : WorkStatus.values() ) {
            final long sql = WorkAgentFixtures.scalar( f.ds, "SELECT count(*) FROM work_event e WHERE e.item_id = "
                + item.id() + " AND " + StatusMoves.intoSql( "e", s ) );
            final long java = sqlMoves().values().stream().filter( s.wire()::equals ).count();
            assertEquals( java, sql, s.wire() );
        }
    }

    @Test
    void theReporterRuleIsTheStatusMoveRulePlusComments() {
        for ( final String type : List.of( WorkEvent.STATUS, WorkEvent.HANDOFF, WorkEvent.CLAIMED, WorkEvent.CLAIM_EXPIRED,
                WorkEvent.RELEASED ) ) {
            for ( final String[] v : VALUES ) {
                final WorkEvent e = new WorkEvent( 1, 1, 1L, "P-1", AT, "lena", Actor.Kind.HUMAN, null, type, v[ 0 ],
                    v[ 1 ], v[ 2 ] );
                final Optional< StatusMoves.Move > move = StatusMoves.of( e );
                assertEquals( move.isPresent(), ReporterEvents.notifies( e ), type + " " + String.join( ",",
                    String.valueOf( v[ 0 ] ), String.valueOf( v[ 1 ] ), String.valueOf( v[ 2 ] ) ) );
                move.ifPresent( m -> assertEquals( m.to(), ReporterEvents.reshape( e ).orElseThrow().toValue() ) );
            }
        }
    }

    @Test
    void theBurndownReadsTheSameMoves() throws SQLException {
        final long sprint = f.svc.features().sprints().create( f.lena, "PLAT", new SprintDraft( "S1", "",
            java.time.LocalDate.parse( "2026-11-09" ), java.time.LocalDate.parse( "2026-11-13" ) ) ).sprint().id();
        FlowFixtures.update( f, "UPDATE work_item SET sprint_id = " + sprint + " WHERE id = " + item.id() );
        final Map< Long, String > burndown = new TreeMap<>();
        for ( final SprintEvent e : new JdbcSprintHistory( new com.wikantik.jdbc.Jdbc( f.ds ) )
                .eventsFor( item.projectId(), sprint ).events() ) {
            if ( e.kind() == SprintEvent.Kind.STATUS && e.itemId() == item.id() ) {
                burndown.put( e.id(), e.value() );
            }
        }
        assertEquals( sqlMoves(), burndown );
    }
}
