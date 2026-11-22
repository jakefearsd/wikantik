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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresPostgres
class JdbcWorkWatchesPostgresTest {

    private WorkWatchStore watches;
    private long item;

    @BeforeEach
    void setUp() throws SQLException {
        final DataSource ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final long project = WorkAgentFixtures.scalar( ds, "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" );
        item = WorkAgentFixtures.scalar( ds, "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter) VALUES ("
            + project + ",1,'task','software','t',1.0,'alice') RETURNING id" );
        watches = new JdbcWorkStore( ds ).notifyStores().watches();
    }

    @Test
    void watchDoesNotUndoAMute() throws SQLException {
        watches.mute( item, "bob" );
        watches.watch( item, "bob" );
        assertEquals( WatchState.MUTED, watches.stateOf( item, "bob" ) );
        watches.watch( item, "carol" );
        assertEquals( WatchState.WATCHING, watches.stateOf( item, "carol" ) );
    }

    @Test
    void subscribeOverridesAMute() throws SQLException {
        watches.mute( item, "bob" );
        watches.subscribe( item, "bob" );
        assertEquals( WatchState.WATCHING, watches.stateOf( item, "bob" ) );
    }

    @Test
    void watchersOmitTheMuted() throws SQLException {
        watches.watch( item, "bob" );
        watches.watch( item, "carol" );
        watches.mute( item, "dave" );
        assertEquals( Set.of( "bob", "carol" ), watches.watchers( item ) );
    }

    @Test
    void unwatchRemovesTheRow() throws SQLException {
        watches.watch( item, "bob" );
        watches.unwatch( item, "bob" );
        assertEquals( WatchState.NONE, watches.stateOf( item, "bob" ) );
        assertEquals( Set.of(), watches.watchers( item ) );
    }

    @Test
    void stateOfIsNoneWithoutARow() throws SQLException {
        assertEquals( WatchState.NONE, watches.stateOf( item, "nobody" ) );
    }

    @Test
    void anEventWrittenByExistingCodeCarriesATransactionId() throws SQLException {
        final DataSource ds = PostgresTestDb.createDataSource();
        final JdbcWorkStore store = new JdbcWorkStore( ds );
        final long project = WorkAgentFixtures.scalar( ds, "SELECT id FROM work_project WHERE key = 'WIKI'" );
        store.archiveProject( project, java.time.Instant.parse( "2026-01-01T00:00:00Z" ), Actor.human( "lena", Set.of(), true ) );
        assertEquals( 1, WorkAgentFixtures.scalar( ds,
            "SELECT count(*) FROM work_event WHERE type = 'settings' AND tx IS NOT NULL" ) );
    }
}
