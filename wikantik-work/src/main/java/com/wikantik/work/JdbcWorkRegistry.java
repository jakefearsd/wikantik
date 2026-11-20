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

import com.wikantik.jdbc.NullableColumns;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.RowMapper;
import com.wikantik.jdbc.SqlBinder;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.work.SqlValues.timestamp;

/** Queues and the agent registry; the base of {@link JdbcWorkCatalog}, so {@link JdbcWorkStore} inherits them. */
abstract class JdbcWorkRegistry implements WorkRegistryStore {

    private static final String QUEUE_COLUMNS = "key, name, description, retired_at";

    private static final RowMapper< Queue > QUEUE_ROW = rs -> new Queue( rs.getString( "key" ),
        rs.getString( "name" ), rs.getString( "description" ), getInstant( rs, "retired_at" ) );

    private static final RowMapper< Agent > AGENT_ROW = rs -> {
        final Array keys = rs.getArray( "queues" );
        final String[] queues = keys == null ? new String[ 0 ] : ( String[] ) keys.getArray();
        return new Agent( rs.getLong( "id" ), rs.getString( "name" ), rs.getString( "vendor" ),
            rs.getString( "runtime" ), rs.getString( "sponsor" ), List.of( queues ),
            getInstant( rs, "created_at" ), getInstant( rs, "retired_at" ) );
    };

    private static final String AGENT_SELECT = """
        SELECT a.id, a.name, a.vendor, a.runtime, a.sponsor, a.created_at, a.retired_at,
               ARRAY( SELECT q.queue_key FROM work_agent_queue q WHERE q.agent_id = a.id
                      ORDER BY q.queue_key ) AS queues
          FROM work_agent a
        """;

    protected final Jdbc jdbc;

    JdbcWorkRegistry( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public Queue createQueue( final String key, final String name, final String description ) throws SQLException {
        return jdbc.queryOne( "INSERT INTO work_queue (key, name, description) VALUES (?,?,?) RETURNING "
                + QUEUE_COLUMNS,
            ps -> { ps.setString( 1, key ); ps.setString( 2, name ); ps.setString( 3, description ); },
            QUEUE_ROW ).orElseThrow( () -> new SQLException( "INSERT work_queue returned no row" ) );
    }

    @Override
    public Optional< Queue > queue( final String key ) throws SQLException {
        return jdbc.queryOne( "SELECT " + QUEUE_COLUMNS + " FROM work_queue WHERE key = ?",
            ps -> ps.setString( 1, key ), QUEUE_ROW );
    }

    @Override
    public List< Queue > queues() throws SQLException {
        return jdbc.query( "SELECT " + QUEUE_COLUMNS + " FROM work_queue ORDER BY key", ps -> { }, QUEUE_ROW );
    }

    @Override
    public void updateQueue( final String key, final String name, final String description ) throws SQLException {
        jdbc.update( "UPDATE work_queue SET name = ?, description = ? WHERE key = ?",
            ps -> { ps.setString( 1, name ); ps.setString( 2, description ); ps.setString( 3, key ); } );
    }

    @Override
    public void retireQueue( final String key, final Instant at ) throws SQLException {
        jdbc.update( "UPDATE work_queue SET retired_at = ? WHERE key = ?",
            ps -> { ps.setTimestamp( 1, timestamp( at ) ); ps.setString( 2, key ); } );
    }

    @Override
    public Agent registerAgent( final String name, final String vendor, final String runtime,
                                final List< String > queues, final String sponsor ) throws SQLException {
        final long id = jdbc.inTransaction( conn -> {
            final long created = jdbc.queryOne( conn, "INSERT INTO work_agent (name, vendor, runtime, sponsor) "
                    + "VALUES (?,?,?,?) RETURNING id",
                ps -> {
                    ps.setString( 1, name.toLowerCase( Locale.ROOT ) );
                    ps.setString( 2, vendor );
                    ps.setString( 3, runtime );
                    ps.setString( 4, sponsor );
                },
                rs -> rs.getLong( 1 ) ).orElseThrow( () -> new SQLException( "INSERT work_agent returned no row" ) );
            insertQueues( conn, created, queues );
            insertSponsorChange( conn, created, sponsor, sponsor );
            return created;
        } );
        return agent( id ).orElseThrow( () -> new SQLException( "work_agent " + id + " vanished" ) );
    }

    @Override
    public void setSponsor( final long agentId, final String sponsorOrNull, final String changedBy )
            throws SQLException {
        jdbc.inTransaction( conn -> {
            setSponsor( conn, agentId, sponsorOrNull, changedBy );
            return null;
        } );
    }

    /**
     * The sponsor change on the caller's connection, for a transaction that also ends claims. The UPDATE takes the
     * agent row's lock, so a claim in flight (which share-locks that row with the sponsor it was resolved under) is
     * waited for, and one that waited finds its sponsor gone from the row.
     */
    void setSponsor( final java.sql.Connection conn, final long agentId, final String sponsorOrNull,
                     final String changedBy ) throws SQLException {
        jdbc.update( conn, "UPDATE work_agent SET sponsor = ? WHERE id = ?",
            ps -> { ps.setString( 1, sponsorOrNull ); ps.setLong( 2, agentId ); } );
        insertSponsorChange( conn, agentId, sponsorOrNull, changedBy );
    }

    private void insertSponsorChange( final java.sql.Connection conn, final long agentId, final String sponsor,
                                      final String changedBy ) throws SQLException {
        jdbc.update( conn, "INSERT INTO work_agent_sponsor_history (agent_id, sponsor, changed_by) VALUES (?,?,?)",
            ps -> { ps.setLong( 1, agentId ); ps.setString( 2, sponsor ); ps.setString( 3, changedBy ); } );
    }

    @Override
    public List< SponsorChange > sponsorHistory( final long agentId ) throws SQLException {
        return jdbc.query( "SELECT sponsor, since, changed_by FROM work_agent_sponsor_history WHERE agent_id = ? "
                + "ORDER BY since DESC, id DESC",
            ps -> ps.setLong( 1, agentId ),
            rs -> new SponsorChange( rs.getString( "sponsor" ), getInstant( rs, "since" ), rs.getString( "changed_by" ) ) );
    }

    @Override
    public java.util.Set< Long > agentsWithMemberRowIn( final java.util.Collection< Long > projectIds )
            throws SQLException {
        if ( projectIds.isEmpty() ) {
            return java.util.Set.of();
        }
        final Long[] ids = projectIds.toArray( new Long[ 0 ] );
        return new java.util.HashSet<>( jdbc.withConnection( conn -> jdbc.query( conn,
            "SELECT DISTINCT principal FROM work_member WHERE principal_kind = 'agent' "
                + "AND project_id = ANY( ? ) AND principal ~ '^[0-9]{1,18}$'",
            ps -> ps.setArray( 1, conn.createArrayOf( "bigint", ids ) ),
            rs -> Long.parseLong( rs.getString( 1 ) ) ) ) );
    }

    @Override
    public List< Long > projectsWithAgentMember( final long agentId ) throws SQLException {
        return jdbc.query( "SELECT project_id FROM work_member WHERE principal_kind = 'agent' AND principal = ? "
                + "ORDER BY project_id",
            ps -> ps.setString( 1, String.valueOf( agentId ) ), rs -> rs.getLong( 1 ) );
    }

    private void insertQueues( final java.sql.Connection conn, final long agentId, final List< String > queues )
            throws SQLException {
        final List< SqlBinder > binders = new ArrayList<>();
        for ( final String q : queues ) {
            binders.add( ps -> { ps.setLong( 1, agentId ); ps.setString( 2, q ); } );
        }
        if ( !binders.isEmpty() ) {
            jdbc.batch( conn, "INSERT INTO work_agent_queue (agent_id, queue_key) VALUES (?,?)", binders );
        }
    }

    @Override
    public Optional< Agent > agent( final long id ) throws SQLException {
        return jdbc.queryOne( AGENT_SELECT + " WHERE a.id = ?", ps -> ps.setLong( 1, id ), AGENT_ROW );
    }

    @Override
    public Optional< Agent > agentByName( final String name ) throws SQLException {
        return jdbc.queryOne( AGENT_SELECT + " WHERE lower( a.name ) = lower( ? )",
            ps -> ps.setString( 1, name ), AGENT_ROW );
    }

    @Override
    public List< Agent > agents() throws SQLException {
        return jdbc.query( AGENT_SELECT + " ORDER BY a.name", ps -> { }, AGENT_ROW );
    }

    @Override
    public void updateAgent( final long id, final String name, final String vendor, final String runtime,
                             final List< String > queues ) throws SQLException {
        jdbc.inTransaction( conn -> {
            jdbc.update( conn, "UPDATE work_agent SET name = ?, vendor = ?, runtime = ? WHERE id = ?",
                ps -> {
                    ps.setString( 1, name.toLowerCase( Locale.ROOT ) );
                    ps.setString( 2, vendor );
                    ps.setString( 3, runtime );
                    ps.setLong( 4, id );
                } );
            jdbc.update( conn, "DELETE FROM work_agent_queue WHERE agent_id = ?", ps -> ps.setLong( 1, id ) );
            insertQueues( conn, id, queues );
            return null;
        } );
    }

}
