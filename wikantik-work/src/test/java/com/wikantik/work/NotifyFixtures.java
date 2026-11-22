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

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Raw-SQL rows for the notification tests: one project and item, events with a controlled time. */
final class NotifyFixtures {

    private static final Instant BASE = Instant.parse( "2026-03-01T09:00:00Z" );

    final DataSource ds;
    final long project;
    final long item;
    private int tick;

    NotifyFixtures() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        ds = PostgresTestDb.createDataSource();
        project = newProject( "WIKI" );
        item = newItem( project, 1 );
    }

    long newProject( final String key ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, "INSERT INTO work_project (key, name) VALUES ('" + key + "', '" + key
            + "') RETURNING id" );
    }

    long newItem( final long projectId, final int seq ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter) "
            + "VALUES (" + projectId + "," + seq + ",'task','software','t" + seq + "',1.0,'alice') RETURNING id" );
    }

    /** The statement inserting one event; every event gets its own minute, so no row reads the wall clock. */
    String eventSql( final long projectId, final Long itemId, final String type, final String actor, final String to,
                     final String note ) {
        tick++;
        return "INSERT INTO work_event (project_id, item_id, at, actor, type, to_value, note) VALUES (" + projectId
            + "," + itemId + ",'" + BASE.plus( tick, ChronoUnit.MINUTES ) + "','" + actor + "','" + type + "',"
            + ( to == null ? "NULL" : "'" + to + "'" ) + "," + ( note == null ? "NULL" : "'" + note + "'" )
            + ") RETURNING id";
    }

    long event( final String type, final String actor, final String to ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, eventSql( project, item, type, actor, to, null ) );
    }

    long comment( final String author, final boolean removed, final String... mentioned ) throws SQLException {
        final long id = WorkAgentFixtures.scalar( ds, "INSERT INTO work_comment (item_id, author, body, created_at, removed_at) "
            + "VALUES (" + item + ",'" + author + "','b','" + BASE + "'," + ( removed ? "'" + BASE + "'" : "NULL" )
            + ") RETURNING id" );
        for ( final String m : mentioned ) {
            mention( id, m );
        }
        return id;
    }

    void mention( final long commentId, final String login ) throws SQLException {
        WorkAgentFixtures.exec( ds, "INSERT INTO work_mention (item_id, comment_id, mentioned) VALUES (" + item + ","
            + commentId + ",'" + login + "')" );
    }

    long count( final String table ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM " + table );
    }
}
