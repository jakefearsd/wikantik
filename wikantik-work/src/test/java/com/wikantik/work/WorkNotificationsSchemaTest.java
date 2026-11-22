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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins V086: event transaction ids, watches, notifications, cursors, preferences and webhooks (work part 3). */
@RequiresPostgres
class WorkNotificationsSchemaTest {

    private DataSource ds;
    private long project;
    private long task;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        project = scalar( "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" );
        task = scalar( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter) VALUES ("
            + project + ",1,'task','software','t',1.0,'alice') RETURNING id" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private static String state( final SQLException e ) {
        return e.getSQLState();
    }

    private static String v086() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", "V086__work_notifications.sql" ) );
    }

    private String event() {
        return "INSERT INTO work_event (project_id, item_id, actor, type) VALUES (" + project + "," + task
            + ",'alice','edited')";
    }

    private long eventId() throws SQLException {
        return scalar( event() + " RETURNING id" );
    }

    @Test
    void everyEventGetsTheTransactionThatWroteIt() throws SQLException {
        final long first;
        final long second;
        try ( Connection c = ds.getConnection() ) {
            c.setAutoCommit( false );
            try ( Statement s = c.createStatement() ) {
                s.execute( event() );
                s.execute( event() );
            }
            c.commit();
        }
        final long later = eventId();
        first = scalar( "SELECT min(id) FROM work_event" );
        second = first + 1;
        assertEquals( 1, scalar( "SELECT count(DISTINCT tx) FROM work_event WHERE id IN (" + first + "," + second + ")" ) );
        assertEquals( 1, scalar( "SELECT count(*) FROM work_event WHERE id = " + later
            + " AND tx > (SELECT tx FROM work_event WHERE id = " + first + ")" ) );
    }

    @Test
    void aWatchStateOutsideTheListIsRefused() {
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_watch (item_id, login, state) VALUES (" + task + ", 'alice', 'loud')" ) ) ) );
    }

    @Test
    void aNotificationReasonOutsideTheListIsRefused() throws SQLException {
        final long e = eventId();
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_notification (recipient, event_id, project_id, item_id, reason) VALUES ('bob',"
                + e + "," + project + "," + task + ",'gossip')" ) ) ) );
    }

    @Test
    void onePersonGetsOneRowPerEvent() throws SQLException {
        final long e = eventId();
        final String insert = "INSERT INTO work_notification (recipient, event_id, project_id, item_id, reason) VALUES ('bob',"
            + e + "," + project + "," + task + ",'watching')";
        exec( insert );
        assertEquals( "23505", state( assertThrows( SQLException.class, () -> exec( insert ) ) ) );
    }

    @Test
    void aProjectLevelNotificationNeedsNoItem() throws SQLException {
        final long e = scalar( "INSERT INTO work_event (project_id, actor, type) VALUES (" + project
            + ",'alice','settings') RETURNING id" );
        exec( "INSERT INTO work_notification (recipient, event_id, project_id, reason) VALUES ('bob',"
            + e + "," + project + ",'webhook_disabled')" );
    }

    @Test
    void aGenericWebhookNeedsASecret() throws SQLException {
        final String cols = "INSERT INTO work_webhook (project_id, url, secret, format, owner, cursor_tx, cursor_event_id)"
            + " VALUES (" + project + ", 'https://example.com/h', ";
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            cols + "NULL, 'generic', 'lena', '0'::xid8, 0)" ) ) ) );
        exec( cols + "NULL, 'slack', 'lena', '0'::xid8, 0)" );
        exec( cols + "'s3cret', 'generic', 'lena', '0'::xid8, 0)" );
    }

    @Test
    void theAppRoleCannotDeleteNotifications() throws Exception {
        final List< String > grants = Arrays.stream( v086().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) ).map( st -> st.replaceAll( "\\s+", " " ).trim() ).toList();
        assertEquals( List.of(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON work_watch, work_webhook, work_notify_pref TO :app_user",
            "GRANT SELECT, INSERT, UPDATE ON work_notification, work_notify_cursor TO :app_user" ), grants );
    }

    private static final String SNAPSHOT_TABLES =
        "('work_event','work_watch','work_notification','work_notify_cursor','work_notify_pref','work_webhook')";

    /** Columns, indexes, constraints and row counts of the V086 tables and work_event, as one comparable string. */
    private String schemaSnapshot() throws SQLException {
        final StringBuilder out = new StringBuilder();
        final String[] queries = {
            "SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable || ' ' "
                + "|| coalesce(column_default,'') FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name IN " + SNAPSHOT_TABLES + " ORDER BY 1",
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename IN "
                + SNAPSHOT_TABLES + " ORDER BY 1",
            "SELECT conrelid::regclass || ' ' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid IN "
                + "(SELECT oid FROM pg_class WHERE relname IN " + SNAPSHOT_TABLES + " AND relnamespace = current_schema()::regnamespace) ORDER BY 1",
            "SELECT 'rows ' || (SELECT count(*) FROM work_event) || ' ' || (SELECT count(*) FROM work_watch) || ' '"
                + " || (SELECT count(*) FROM work_notification) || ' ' || (SELECT count(*) FROM work_notify_cursor)"
                + " || ' ' || (SELECT count(*) FROM work_notify_pref) || ' ' || (SELECT count(*) FROM work_webhook)"
                + " || ' ' || (SELECT coalesce(string_agg(id || ':' || tx::text, ','  ORDER BY id), '') FROM work_event)" };
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            for ( final String q : queries ) {
                try ( ResultSet rs = s.executeQuery( q ) ) {
                    while ( rs.next() ) {
                        out.append( rs.getString( 1 ) ).append( '\n' );
                    }
                }
            }
        }
        return out.toString();
    }

    @Test
    void v086IsANoOpWhenReapplied() throws Exception {
        final String sql = v086().replace( ":app_user", "CURRENT_USER" );
        eventId();
        eventId();
        exec( sql );
        final String before = schemaSnapshot();
        assertTrue( before.contains( "work_event.tx" ), "snapshot should list the tx column" );
        assertTrue( before.contains( "idx_work_notification_unread" ), "snapshot should list the indexes" );
        exec( sql );
        assertEquals( before, schemaSnapshot() );
    }

    @Test
    void theUnreadAndMentionLookupsHavePartialIndexes() throws SQLException {
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_notification_unread' "
            + "AND indexdef LIKE '%(recipient, project_id)%' AND indexdef LIKE '%read_at IS NULL%'" ) );
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_notification_item' "
            + "AND indexdef LIKE '%(item_id)%'" ) );
    }
}
