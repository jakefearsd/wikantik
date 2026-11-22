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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/** The PostgreSQL {@link WorkWatchStore}; the {@link Connection} twins serve the dispatch transaction. */
final class JdbcWorkWatches implements WorkWatchStore {

    private static final String WATCH = "INSERT INTO work_watch (item_id, login, state) VALUES (?,?,'watching') "
        + "ON CONFLICT DO NOTHING";
    private static final String SUBSCRIBE = "INSERT INTO work_watch (item_id, login, state) VALUES (?,?,'watching') "
        + "ON CONFLICT (item_id, login) DO UPDATE SET state = 'watching'";
    private static final String MUTE = "INSERT INTO work_watch (item_id, login, state) VALUES (?,?,'muted') "
        + "ON CONFLICT (item_id, login) DO UPDATE SET state = 'muted'";
    private static final String WATCHERS = "SELECT login FROM work_watch WHERE item_id = ? AND state = 'watching'";

    private final Jdbc jdbc;

    JdbcWorkWatches( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public void watch( final long itemId, final String login ) throws SQLException {
        jdbc.update( WATCH, ps -> bind( ps, itemId, login ) );
    }

    void watch( final Connection conn, final long itemId, final String login ) throws SQLException {
        jdbc.update( conn, WATCH, ps -> bind( ps, itemId, login ) );
    }

    @Override
    public void subscribe( final long itemId, final String login ) throws SQLException {
        jdbc.update( SUBSCRIBE, ps -> bind( ps, itemId, login ) );
    }

    void subscribe( final Connection conn, final long itemId, final String login ) throws SQLException {
        jdbc.update( conn, SUBSCRIBE, ps -> bind( ps, itemId, login ) );
    }

    @Override
    public void mute( final long itemId, final String login ) throws SQLException {
        jdbc.update( MUTE, ps -> bind( ps, itemId, login ) );
    }

    @Override
    public void unwatch( final long itemId, final String login ) throws SQLException {
        jdbc.update( "DELETE FROM work_watch WHERE item_id = ? AND login = ?", ps -> bind( ps, itemId, login ) );
    }

    @Override
    public WatchState stateOf( final long itemId, final String login ) throws SQLException {
        return jdbc.queryOne( "SELECT state FROM work_watch WHERE item_id = ? AND login = ?",
            ps -> bind( ps, itemId, login ), rs -> WatchState.fromWire( rs.getString( 1 ) ) )
            .orElse( WatchState.NONE );
    }

    @Override
    public Set< String > watchers( final long itemId ) throws SQLException {
        return new LinkedHashSet<>( jdbc.query( WATCHERS, ps -> ps.setLong( 1, itemId ), rs -> rs.getString( 1 ) ) );
    }

    Set< String > watchers( final Connection conn, final long itemId ) throws SQLException {
        return new LinkedHashSet<>( jdbc.query( conn, WATCHERS, ps -> ps.setLong( 1, itemId ), rs -> rs.getString( 1 ) ) );
    }

    private static void bind( final java.sql.PreparedStatement ps, final long itemId, final String login )
            throws SQLException {
        ps.setLong( 1, itemId );
        ps.setString( 2, login );
    }
}
