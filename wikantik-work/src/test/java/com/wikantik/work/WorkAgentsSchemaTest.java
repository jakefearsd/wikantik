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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the V076 phase 2 schema: queues, the agent registry, claim columns and agent attribution. */
@RequiresPostgres
class WorkAgentsSchemaTest {

    private DataSource ds;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "work_agent_key_history", "work_agent_queue", "work_agent", "work_queue",
            "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product", "work_project" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private long apiKey( final String scope ) throws SQLException {
        return WorkAgentFixtures.apiKey( ds, scope );
    }

    private long agent( final String name, final long keyId ) throws SQLException {
        return WorkAgentFixtures.agent( ds, name, keyId );
    }

    private long item() throws SQLException {
        final long p = scalar( "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" );
        return scalar( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter) VALUES ("
            + p + ",1,'task','software','t',1.0,'alice') RETURNING id" );
    }

    private static String state( final SQLException e ) {
        return e.getSQLState();
    }

    @Test
    void phaseTwoTablesExist() throws SQLException {
        final TreeSet< String > found = new TreeSet<>();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( "SELECT table_name FROM information_schema.tables "
                  + "WHERE table_schema = 'public' AND table_name LIKE 'work\\_%'" ) ) {
            while ( rs.next() ) {
                found.add( rs.getString( 1 ) );
            }
        }
        assertTrue( found.containsAll( java.util.List.of( "work_queue", "work_agent", "work_agent_queue",
            "work_agent_key_history" ) ),
            "found: " + found );
    }

    @Test
    void itemCannotHaveBothAssigneeAndQueue() throws SQLException {
        final long id = item();
        exec( "INSERT INTO work_queue (key, name) VALUES ('implementer', 'Implementer')" );
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "UPDATE work_item SET assignee = 'lena', assignee_queue = 'implementer' WHERE id = " + id ) ) ) );
        exec( "UPDATE work_item SET assignee_queue = 'implementer' WHERE id = " + id );
    }

    @Test
    void claimColumnsAreAllOrNothing() throws SQLException {
        final long id = item();
        final long a = agent( "codex", apiKey( "work" ) );
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "UPDATE work_item SET claimed_by_agent = " + a + " WHERE id = " + id ) ) ) );
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "UPDATE work_item SET claimed_at = NOW() WHERE id = " + id ) ) ) );
        exec( "UPDATE work_item SET claimed_by_agent = " + a
            + ", claimed_at = NOW(), claim_expires_at = NOW() + INTERVAL '1 hour' WHERE id = " + id );
    }

    @Test
    void oneKeyBindsOneAgent() throws SQLException {
        final long key = apiKey( "work" );
        agent( "codex", key );
        assertEquals( "23505", state( assertThrows( SQLException.class, () -> agent( "other", key ) ) ) );
    }

    @Test
    void agentQueueNeedsAKnownQueue() throws SQLException {
        final long a = agent( "codex", apiKey( "work" ) );
        assertEquals( "23503", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_agent_queue (agent_id, queue_key) VALUES (" + a + ", 'nope')" ) ) ) );
        exec( "INSERT INTO work_queue (key, name) VALUES ('reviewer', 'Reviewer')" );
        exec( "INSERT INTO work_agent_queue (agent_id, queue_key) VALUES (" + a + ", 'reviewer')" );
    }

    @Test
    void queueKeyPatternEnforced() {
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_queue (key, name) VALUES ('Implementer', 'x')" ) ) ) );
    }

    @Test
    void agentNamePatternEnforced() throws SQLException {
        final long key = apiKey( "work" );
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> agent( "Codex Bot", key ) ) ) );
        agent( "codex@docker2", key );
    }

    @Test
    void apiKeyScopeAdmitsWork() throws SQLException {
        apiKey( "work" );
        for ( final String scope : new String[] { "mcp", "mcp_admin", "mcp_read", "tools", "all" } ) {
            apiKey( scope );
        }
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> apiKey( "bogus" ) ) ) );
    }

    @Test
    void eventAgentIdMustNameAnAgent() throws SQLException {
        final long id = item();
        final long p = scalar( "SELECT project_id FROM work_item WHERE id = " + id );
        assertEquals( "23503", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_event (project_id, item_id, actor, type, agent_id) VALUES ("
                + p + "," + id + ",'x','created',999999)" ) ) ) );
    }

    @Test
    void attributionColumnsReferenceAgents() throws SQLException {
        final long id = item();
        assertEquals( "23503", state( assertThrows( SQLException.class, () -> exec(
            "UPDATE work_item SET reporter_agent_id = 999999 WHERE id = " + id ) ) ) );
        assertEquals( "23503", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_item_revision (item_id, version, title, body, edited_by, edited_by_agent_id) "
                + "VALUES (" + id + ",1,'t','b','x',999999)" ) ) ) );
        assertEquals( "23503", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_comment (item_id, author, body, author_agent_id) VALUES ("
                + id + ",'x','b',999999)" ) ) ) );
    }

    @Test
    void v076IsANoOpWhenReapplied() throws Exception {
        final long id = item();
        final long a = agent( "codex", apiKey( "work" ) );
        exec( "UPDATE work_item SET claimed_by_agent = " + a
            + ", claimed_at = NOW(), claim_expires_at = NOW() + INTERVAL '1 hour' WHERE id = " + id );
        final String before = fingerprint();
        final java.nio.file.Path file = java.nio.file.Path.of( "..", "bin", "db", "migrations",
            "V076__work_management_agents.sql" );
        final String sql = java.nio.file.Files.readString( file ).replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        assertEquals( before, fingerprint() );
    }

    @Test
    void keyHistoryTableExistsAndIsKeyedByTheApiKey() throws SQLException {
        final long key = apiKey( "work" );
        final long a = agent( "codex", key );
        exec( "INSERT INTO work_agent_key_history (api_key_id, agent_id) VALUES (" + key + "," + a + ")" );
        assertEquals( "23505", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_agent_key_history (api_key_id, agent_id) VALUES (" + key + "," + a + ")" ) ) ) );
        assertEquals( "23503", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_agent_key_history (api_key_id, agent_id) VALUES (" + apiKey( "work" ) + ",999999)" ) ) ) );
        assertEquals( "23503", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_agent_key_history (api_key_id, agent_id) VALUES (999999," + a + ")" ) ) ) );
    }

    @Test
    void v077IsANoOpWhenReapplied() throws Exception {
        final long key = apiKey( "work" );
        exec( "INSERT INTO work_agent_key_history (api_key_id, agent_id) VALUES (" + key + ","
            + agent( "codex", apiKey( "work" ) ) + ")" );
        final String before = fingerprint() + scalar( "SELECT count(*) FROM work_agent_key_history" );
        final java.nio.file.Path file = java.nio.file.Path.of( "..", "bin", "db", "migrations",
            "V077__work_agent_key_history.sql" );
        final String sql = java.nio.file.Files.readString( file ).replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        assertEquals( before, fingerprint() + scalar( "SELECT count(*) FROM work_agent_key_history" ) );
    }

    /** Columns, constraints, indexes and row counts, so a re-run that added anything shows up. */
    private String fingerprint() throws SQLException {
        final StringBuilder sb = new StringBuilder();
        for ( final String q : new String[] {
            "SELECT string_agg(table_name || '.' || column_name || ':' || data_type, ',' ORDER BY table_name, column_name) "
                + "FROM information_schema.columns WHERE table_schema='public' AND table_name LIKE 'work\\_%'",
            "SELECT string_agg(conname || '=' || pg_get_constraintdef(oid), ',' ORDER BY conname) "
                + "FROM pg_constraint WHERE conrelid::regclass::text LIKE 'work\\_%' OR conname = 'api_keys_scope_chk'",
            "SELECT string_agg(indexname, ',' ORDER BY indexname) FROM pg_indexes WHERE tablename LIKE 'work\\_%'",
            "SELECT (SELECT count(*) FROM work_item) || '/' || (SELECT count(*) FROM work_agent) || '/' "
                + "|| (SELECT count(*) FROM work_queue) || '/' || (SELECT count(*) FROM api_keys)" } ) {
            try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
                  ResultSet rs = s.executeQuery( q ) ) {
                rs.next();
                sb.append( rs.getString( 1 ) ).append( '\n' );
            }
        }
        return sb.toString();
    }
}
