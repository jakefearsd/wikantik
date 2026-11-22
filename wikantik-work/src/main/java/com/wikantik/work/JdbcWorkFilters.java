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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.RowMapper;

import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.jdbc.NullableColumns.getNullableLong;

/** The PostgreSQL {@link WorkFilterStore}: every write is one statement. */
final class JdbcWorkFilters implements WorkFilterStore {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final Gson GSON = new Gson();
    private static final Type MAP = new TypeToken< TreeMap< String, String > >() { }.getType();
    private static final String SELECT = "SELECT f.id, f.owner, f.name, f.project_id, p.key AS project_key, f.shared, "
        + "f.filter::text AS filter, f.created_at FROM work_saved_filter f LEFT JOIN work_project p ON p.id = f.project_id";

    private static final RowMapper< SavedFilter > ROW = rs -> new SavedFilter( rs.getLong( "id" ),
        rs.getString( "owner" ), rs.getString( "name" ), getNullableLong( rs, "project_id" ),
        rs.getString( "project_key" ), rs.getBoolean( "shared" ), parse( rs.getString( "filter" ) ),
        getInstant( rs, "created_at" ) );

    private final Jdbc jdbc;

    JdbcWorkFilters( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    private static SortedMap< String, String > parse( final String json ) {
        final TreeMap< String, String > map = GSON.fromJson( json, MAP );
        return map == null ? new TreeMap<>() : map;
    }

    private static WorkConflictException taken( final String name, final Throwable cause ) {
        final WorkConflictException e = new WorkConflictException( "You already have a saved filter named " + name + ".",
            WorkConflictException.EXISTS );
        if ( cause != null ) {
            e.initCause( cause );
        }
        return e;
    }

    @Override
    public SavedFilter insert( final String owner, final String name, final Long projectId, final boolean shared,
                               final SortedMap< String, String > filter ) throws SQLException {
        final long id = jdbc.queryOne( "INSERT INTO work_saved_filter (owner, name, project_id, shared, filter) "
            + "VALUES (?,?,?,?,?::jsonb) ON CONFLICT (owner, name) DO NOTHING RETURNING id", ps -> {
                ps.setString( 1, owner );
                ps.setString( 2, name );
                ps.setObject( 3, projectId );
                ps.setBoolean( 4, shared );
                ps.setString( 5, GSON.toJson( new TreeMap<>( filter ) ) );
            }, rs -> rs.getLong( 1 ) ).orElseThrow( () -> taken( name, null ) );
        return byId( id ).orElseThrow( () -> new IllegalStateException( "saved filter " + id + " vanished" ) );
    }

    @Override
    public Optional< SavedFilter > byId( final long id ) throws SQLException {
        return jdbc.queryOne( SELECT + " WHERE f.id = ?", ps -> ps.setLong( 1, id ), ROW );
    }

    @Override
    public int countOwned( final String owner ) throws SQLException {
        return jdbc.queryOne( "SELECT count(*) FROM work_saved_filter WHERE owner = ?", ps -> ps.setString( 1, owner ),
            rs -> rs.getInt( 1 ) ).orElse( 0 );
    }

    @Override
    public List< SavedFilter > visibleTo( final String owner, final Collection< Long > projectIds, final int limit )
            throws SQLException {
        return jdbc.withConnection( conn -> jdbc.query( conn, SELECT + " WHERE ( f.owner = ? AND f.project_id IS NULL ) "
            + "OR ( f.project_id = ANY( ? ) AND ( f.owner = ? OR f.shared ) ) ORDER BY lower( f.name ), f.id LIMIT ?",
            ps -> {
                ps.setString( 1, owner );
                ps.setArray( 2, conn.createArrayOf( "bigint", projectIds.toArray( new Long[ 0 ] ) ) );
                ps.setString( 3, owner );
                ps.setInt( 4, limit );
            }, ROW ) );
    }

    @Override
    public SavedFilter rename( final long id, final String name ) throws SQLException {
        final int changed;
        try {
            changed = jdbc.update( "UPDATE work_saved_filter SET name = ? WHERE id = ?", ps -> {
                ps.setString( 1, name );
                ps.setLong( 2, id );
            } );
        } catch ( final SQLException e ) {
            if ( UNIQUE_VIOLATION.equals( e.getSQLState() ) ) {
                throw taken( name, e );
            }
            throw e;
        }
        if ( changed == 0 ) {
            throw new WorkNotFoundException( WorkScopes.NOT_FOUND );
        }
        return byId( id ).orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
    }

    @Override
    public boolean delete( final long id ) throws SQLException {
        return jdbc.update( "DELETE FROM work_saved_filter WHERE id = ?", ps -> ps.setLong( 1, id ) ) > 0;
    }
}
