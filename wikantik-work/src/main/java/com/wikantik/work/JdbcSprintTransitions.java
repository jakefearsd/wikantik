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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Starting and closing a sprint. Lock order (P2-2): the sprint rows first, in id order, then the item rows; an item
 * write takes {@code FOR SHARE} on its sprint rows before its own row, so the commitment and the completion are read
 * with nothing under them moving, and the order has no cycle.
 */
final class JdbcSprintTransitions {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String BAD_TARGET = "Unfinished work can move only to a planned sprint of this project, "
        + "or to the backlog.";

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;
    private final JdbcWorkSprints sprints;

    JdbcSprintTransitions( final Jdbc jdbc, final JdbcWorkCatalog catalog, final JdbcWorkSprints sprints ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.sprints = sprints;
    }

    /** A locked sprint row. */
    private record Locked( long id, long projectId, String name, SprintState state, int version ) {
    }

    private record Totals( int items, BigDecimal points ) {
    }

    private static WorkConflictException activeAlready() {
        return new WorkConflictException( "This project already has an active sprint.",
            WorkConflictException.SPRINT_ACTIVE );
    }

    private static WorkConflictException staleSprint( final Locked row, final int expected ) {
        return new WorkConflictException( "Sprint " + row.name() + " changed since version " + expected + "." );
    }

    private static WorkConflictException badTransition( final Locked row, final SprintState to ) {
        return new WorkConflictException( "A sprint that is " + row.state().wire() + " cannot become "
            + to.wire() + ".", WorkConflictException.BAD_TRANSITION );
    }

    Sprint start( final long id, final int expectedVersion, final Actor by ) throws SQLException {
        try {
            return jdbc.inTransaction( conn -> {
                final Locked row = jdbc.queryOne( conn, "SELECT project_id, state, version, name FROM work_sprint "
                        + "WHERE id = ? FOR UPDATE", ps -> ps.setLong( 1, id ),
                    rs -> new Locked( id, rs.getLong( 1 ), rs.getString( 4 ), SprintState.fromWire( rs.getString( 2 ) ),
                        rs.getInt( 3 ) ) ).orElseThrow( JdbcWorkSprints::noSuchSprint );
                if ( !row.state().mayBecome( SprintState.ACTIVE ) ) {
                    throw badTransition( row, SprintState.ACTIVE );
                }
                if ( row.version() != expectedVersion ) {
                    throw staleSprint( row, expectedVersion );
                }
                if ( jdbc.queryOne( conn, "SELECT 1 FROM work_sprint WHERE project_id = ? AND state = 'active'",
                        ps -> ps.setLong( 1, row.projectId() ), rs -> 1 ).isPresent() ) {
                    throw activeAlready();
                }
                final Totals held = jdbc.queryOne( conn, "SELECT count(*), COALESCE( SUM( estimate ), 0 ) "
                        + "FROM work_item WHERE sprint_id = ? AND status <> 'cancelled'", ps -> ps.setLong( 1, id ),
                    rs -> new Totals( rs.getInt( 1 ), rs.getBigDecimal( 2 ) ) ).orElseThrow();
                jdbc.update( conn, "UPDATE work_sprint SET state = 'active', started_at = NOW(), "
                        + "committed_items = ?, committed_points = ?, version = version + 1 WHERE id = ?",
                    ps -> {
                        ps.setInt( 1, held.items() );
                        ps.setBigDecimal( 2, held.points() );
                        ps.setLong( 3, id );
                    } );
                catalog.appendEvent( conn,
                    JdbcWorkSprints.event( row.projectId(), WorkEvent.SPRINT_STARTED, id, row.name(), by ) );
                return sprints.read( conn, id );
            } );
        } catch ( final SQLException e ) {
            if ( UNIQUE_VIOLATION.equals( e.getSQLState() ) ) {
                // A racing start took the project's one active slot between our check and our write.
                throw activeAlready();
            }
            throw e;
        }
    }

    Sprint close( final long id, final int expectedVersion, final Long target, final Actor by ) throws SQLException {
        return jdbc.inTransaction( conn -> {
            final Locked row = lockForClose( conn, id, expectedVersion, target );
            final List< long[] > open = new ArrayList<>();
            final int[] done = { 0 };
            BigDecimal donePoints = BigDecimal.ZERO;
            final List< BigDecimal > doneEstimates = new ArrayList<>();
            jdbc.query( conn, "SELECT id, status, estimate FROM work_item WHERE sprint_id = ? ORDER BY id FOR UPDATE",
                ps -> ps.setLong( 1, id ), rs -> {
                    final String status = rs.getString( 2 );
                    if ( WorkStatus.DONE.wire().equals( status ) ) {
                        done[ 0 ]++;
                        final BigDecimal estimate = rs.getBigDecimal( 3 );
                        doneEstimates.add( estimate == null ? BigDecimal.ZERO : estimate );
                    } else if ( !WorkStatus.CANCELLED.wire().equals( status ) ) {
                        open.add( new long[] { rs.getLong( 1 ) } );
                    }
                    return null;
                } );
            for ( final BigDecimal e : doneEstimates ) {
                donePoints = donePoints.add( e );
            }
            final BigDecimal completedPoints = donePoints;
            jdbc.update( conn, "UPDATE work_sprint SET state = 'closed', closed_at = NOW(), completed_items = ?, "
                    + "completed_points = ?, version = version + 1 WHERE id = ?", ps -> {
                ps.setInt( 1, done[ 0 ] );
                ps.setBigDecimal( 2, completedPoints );
                ps.setLong( 3, id );
            } );
            catalog.appendEvent( conn,
                JdbcWorkSprints.event( row.projectId(), WorkEvent.SPRINT_CLOSED, id, row.name(), by ) );
            for ( final long[] item : open ) {
                moveItem( conn, row, item[ 0 ], target, by );
            }
            return sprints.read( conn, id );
        } );
    }

    /** Statement 1 of a close: the sprint and its target locked in id order, then every refusal that needs only them. */
    private Locked lockForClose( final Connection conn, final long id, final int expectedVersion, final Long target )
            throws SQLException {
        final Long[] ids = Stream.of( id, target ).filter( java.util.Objects::nonNull ).distinct().sorted()
            .toArray( Long[]::new );
        final Map< Long, Locked > rows = new HashMap<>();
        jdbc.query( conn, "SELECT id, project_id, state, version, name FROM work_sprint WHERE id = ANY( ? ) "
                + "ORDER BY id FOR UPDATE", ps -> ps.setArray( 1, conn.createArrayOf( "bigint", ids ) ),
            rs -> rows.put( rs.getLong( 1 ), new Locked( rs.getLong( 1 ), rs.getLong( 2 ), rs.getString( 5 ),
                SprintState.fromWire( rs.getString( 3 ) ), rs.getInt( 4 ) ) ) );
        final Locked row = rows.get( id );
        if ( row == null ) {
            throw JdbcWorkSprints.noSuchSprint();
        }
        if ( !row.state().mayBecome( SprintState.CLOSED ) ) {
            throw badTransition( row, SprintState.CLOSED );
        }
        if ( row.version() != expectedVersion ) {
            throw staleSprint( row, expectedVersion );
        }
        if ( target != null ) {
            final Locked t = rows.get( target );
            if ( target == id || t == null || t.state() != SprintState.PLANNED || t.projectId() != row.projectId() ) {
                throw new WorkConflictException( BAD_TARGET, WorkConflictException.BAD_TARGET );
            }
        }
        return row;
    }

    private void moveItem( final Connection conn, final Locked row, final long itemId, final Long target,
                           final Actor by ) throws SQLException {
        jdbc.update( conn, "UPDATE work_item SET sprint_id = ?, version = version + 1, updated_at = NOW() "
                + "WHERE id = ?", ps -> {
            ps.setObject( 1, target );
            ps.setLong( 2, itemId );
        } );
        catalog.appendEvent( conn, new WorkEvent( 0, row.projectId(), itemId, null, null, by.login(), by.kind(),
            by.agentId(), WorkEvent.FIELD, String.valueOf( row.id() ), target == null ? null : String.valueOf( target ),
            "sprint" ) );
    }
}
