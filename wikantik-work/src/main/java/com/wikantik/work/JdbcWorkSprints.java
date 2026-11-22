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
import com.wikantik.jdbc.RowMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.jdbc.NullableColumns.getLocalDate;

/**
 * The PostgreSQL {@link WorkSprintStore}: reads, create and update live here; the start/close transitions and the
 * report queries are split into {@link JdbcSprintTransitions} and {@link JdbcSprintHistory}.
 */
final class JdbcWorkSprints implements WorkSprintStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String SELECT = """
        SELECT s.id, s.project_id, p.key AS project_key, s.name, s.goal, s.starts_on, s.ends_on, s.state,
               s.started_at, s.closed_at, s.committed_items, s.committed_points, s.completed_items,
               s.completed_points, s.version
          FROM work_sprint s JOIN work_project p ON p.id = s.project_id
        """;

    private static final String FOR_PROJECT_ORDER = """
         ORDER BY CASE s.state WHEN 'active' THEN 0 WHEN 'planned' THEN 1 ELSE 2 END,
                  CASE WHEN s.state = 'planned' THEN s.starts_on END ASC,
                  CASE WHEN s.state = 'closed' THEN s.starts_on END DESC, s.id
        """;

    private static final String TOTALS = """
        SELECT sprint_id, count(*), COALESCE( SUM( estimate ), 0 ),
               count(*) FILTER ( WHERE status = 'done' ),
               COALESCE( SUM( estimate ) FILTER ( WHERE status = 'done' ), 0 )
          FROM work_item WHERE project_id = ? AND sprint_id IS NOT NULL AND status <> 'cancelled'
         GROUP BY sprint_id
        """;

    static final RowMapper< Sprint > ROW = rs -> new Sprint( rs.getLong( "id" ), rs.getLong( "project_id" ),
        rs.getString( "project_key" ), rs.getString( "name" ), rs.getString( "goal" ),
        getLocalDate( rs, "starts_on" ), getLocalDate( rs, "ends_on" ), SprintState.fromWire( rs.getString( "state" ) ),
        getInstant( rs, "started_at" ), getInstant( rs, "closed_at" ), (Integer) rs.getObject( "committed_items" ),
        rs.getBigDecimal( "committed_points" ), (Integer) rs.getObject( "completed_items" ),
        rs.getBigDecimal( "completed_points" ), rs.getInt( "version" ) );

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;
    private final JdbcSprintTransitions transitions;
    private final JdbcSprintHistory history;

    JdbcWorkSprints( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.transitions = new JdbcSprintTransitions( jdbc, catalog, this );
        this.history = new JdbcSprintHistory( jdbc );
    }

    @Override
    public Optional< Sprint > byId( final long id ) throws SQLException {
        return jdbc.queryOne( SELECT + " WHERE s.id = ?", ps -> ps.setLong( 1, id ), ROW );
    }

    /** The sprint as the caller's transaction sees it. */
    Sprint read( final Connection conn, final long id ) throws SQLException {
        return jdbc.queryOne( conn, SELECT + " WHERE s.id = ?", ps -> ps.setLong( 1, id ), ROW )
            .orElseThrow( JdbcWorkSprints::noSuchSprint );
    }

    static WorkValidationException noSuchSprint() {
        return new WorkValidationException( "sprint", "No such sprint." );
    }

    /** The {@code sprint_*} event of a sprint: project-level, the sprint id in {@code to_value}, its name as note. */
    static WorkEvent event( final long projectId, final String type, final long sprintId, final String name,
                            final Actor by ) {
        return new WorkEvent( 0, projectId, null, null, null, by.login(), by.kind(), by.agentId(), type, null,
            String.valueOf( sprintId ), name );
    }

    @Override
    public List< Sprint > forProject( final long projectId ) throws SQLException {
        return jdbc.query( SELECT + " WHERE s.project_id = ?" + FOR_PROJECT_ORDER,
            ps -> ps.setLong( 1, projectId ), ROW );
    }

    @Override
    public Map< Long, SprintTotals > totals( final long projectId ) throws SQLException {
        final Map< Long, SprintTotals > out = new LinkedHashMap<>();
        jdbc.query( TOTALS, ps -> ps.setLong( 1, projectId ), rs -> out.put( rs.getLong( 1 ),
            new SprintTotals( rs.getInt( 2 ), points( rs.getBigDecimal( 3 ) ), rs.getInt( 4 ),
                points( rs.getBigDecimal( 5 ) ) ) ) );
        return out;
    }

    static BigDecimal points( final BigDecimal p ) {
        return p.setScale( 1 );
    }

    @Override
    public List< Sprint > closed( final long projectId, final int limit ) throws SQLException {
        return jdbc.query( SELECT + " WHERE s.project_id = ? AND s.state = 'closed' "
                + "ORDER BY s.closed_at DESC, s.id DESC LIMIT ?",
            ps -> {
                ps.setLong( 1, projectId );
                ps.setInt( 2, limit );
            }, ROW );
    }

    @Override
    public Sprint create( final Project project, final SprintDraft d, final Actor by ) throws SQLException {
        try {
            return jdbc.inTransaction( conn -> {
                final long id = jdbc.insertReturningKey( conn, "INSERT INTO work_sprint "
                        + "(project_id, name, goal, starts_on, ends_on) VALUES (?,?,?,?,?)",
                    ps -> {
                        ps.setLong( 1, project.id() );
                        ps.setString( 2, d.name() );
                        ps.setString( 3, d.goal() == null ? "" : d.goal() );
                        ps.setObject( 4, d.startsOn() );
                        ps.setObject( 5, d.endsOn() );
                    }, rs -> rs.getLong( 1 ) ).orElseThrow();
                catalog.appendEvent( conn, event( project.id(), WorkEvent.SPRINT_CREATED, id, d.name(), by ) );
                return read( conn, id );
            } );
        } catch ( final SQLException e ) {
            throw nameTaken( e );
        }
    }

    @Override
    public Sprint update( final long id, final int expectedVersion, final SprintDraft d, final Actor by )
            throws SQLException {
        try {
            return jdbc.inTransaction( conn -> {
                final Sprint cur = jdbc.queryOne( conn, SELECT + " WHERE s.id = ? FOR UPDATE OF s",
                    ps -> ps.setLong( 1, id ), ROW ).orElseThrow( JdbcWorkSprints::noSuchSprint );
                if ( cur.state() == SprintState.CLOSED ) {
                    throw new WorkConflictException( "A closed sprint is a record and does not change.",
                        WorkConflictException.SPRINT_CLOSED );
                }
                if ( cur.version() != expectedVersion ) {
                    throw new WorkConflictException( "Sprint " + cur.name() + " changed since version "
                        + expectedVersion + "." );
                }
                final String goal = d.goal() == null ? "" : d.goal();
                if ( cur.name().equals( d.name() ) && cur.goal().equals( goal )
                        && cur.startsOn().equals( d.startsOn() ) && cur.endsOn().equals( d.endsOn() ) ) {
                    return cur;
                }
                jdbc.update( conn, "UPDATE work_sprint SET name = ?, goal = ?, starts_on = ?, ends_on = ?, "
                        + "version = version + 1 WHERE id = ?",
                    ps -> {
                        ps.setString( 1, d.name() );
                        ps.setString( 2, goal );
                        ps.setObject( 3, d.startsOn() );
                        ps.setObject( 4, d.endsOn() );
                        ps.setLong( 5, id );
                    } );
                catalog.appendEvent( conn, event( cur.projectId(), WorkEvent.SPRINT_UPDATED, id, d.name(), by ) );
                return read( conn, id );
            } );
        } catch ( final SQLException e ) {
            throw nameTaken( e );
        }
    }

    private static SQLException nameTaken( final SQLException e ) {
        if ( UNIQUE_VIOLATION.equals( e.getSQLState() ) ) {
            throw new WorkValidationException( "name", "This project already has a sprint with that name." );
        }
        return e;
    }

    @Override
    public Sprint start( final long id, final int expectedVersion, final Actor by ) throws SQLException {
        return transitions.start( id, expectedVersion, by );
    }

    @Override
    public Sprint close( final long id, final int expectedVersion, final Long moveOpenToSprintId, final Actor by )
            throws SQLException {
        return transitions.close( id, expectedVersion, moveOpenToSprintId, by );
    }

    @Override
    public SprintHistory eventsFor( final long projectId, final long sprintId ) throws SQLException {
        return history.eventsFor( projectId, sprintId );
    }

    @Override
    public BigDecimal childPoints( final long itemId ) throws SQLException {
        return history.childPoints( itemId );
    }
}
