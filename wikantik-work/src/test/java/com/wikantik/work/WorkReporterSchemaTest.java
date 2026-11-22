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
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins V091: the reporter role and the rule that an agent's role is viewer or member. */
@RequiresPostgres
class WorkReporterSchemaTest {

    private DataSource ds;
    private long project;
    private long agent;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        project = scalar( "INSERT INTO work_project (key, name) VALUES ('PLAT', 'Platform') RETURNING id" );
        agent = WorkAgentFixtures.agent( ds, "codex" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private void member( final String kind, final String principal, final String role ) throws SQLException {
        exec( "INSERT INTO work_member (project_id, principal_kind, principal, role) VALUES (" + project + ", '" + kind
            + "', '" + principal + "', '" + role + "')" );
    }

    private static String v091() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", "V091__work_reporter_role.sql" ) );
    }

    @Test
    void aUserOrGroupMayHoldTheReporterRole() throws SQLException {
        member( "user", "rita", "reporter" );
        member( "group", "Customers", "reporter" );
        assertEquals( 2, scalar( "SELECT count(*) FROM work_member WHERE role = 'reporter'" ) );
    }

    @Test
    void anAgentRowMayNotBeAReporterOrALead() {
        assertEquals( "23514", assertThrows( SQLException.class, () -> member( "agent", String.valueOf( agent ), "reporter" ) ).getSQLState() );
        assertEquals( "23514", assertThrows( SQLException.class, () -> member( "agent", String.valueOf( agent ), "lead" ) ).getSQLState() );
    }

    @Test
    void anAgentRowMayBeAViewerOrAMember() throws SQLException {
        member( "agent", String.valueOf( agent ), "member" );
        exec( "UPDATE work_member SET role = 'viewer' WHERE principal_kind = 'agent'" );
        assertEquals( 1, scalar( "SELECT count(*) FROM work_member WHERE principal_kind = 'agent' AND role = 'viewer'" ) );
    }

    @Test
    void anUnknownRoleIsRefused() {
        assertEquals( "23514", assertThrows( SQLException.class, () -> member( "user", "owen", "owner" ) ).getSQLState() );
    }

    @Test
    void theOldUnnamedCheckIsGone() throws SQLException {
        assertEquals( 0, scalar( "SELECT count(*) FROM pg_constraint WHERE conname = 'work_member_role_check'" ) );
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_constraint WHERE conname = 'work_member_role_chk'" ) );
    }

    @Test
    void theReporterIndexExists() throws SQLException {
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_work_member_reporters'" ) );
    }

    @Test
    void v091IsANoOpWhenReapplied() throws Exception {
        final String sql = v091().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        member( "user", "rita", "reporter" );
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_constraint WHERE conname = 'work_member_role_chk'" ) );
        assertEquals( 1, scalar( "SELECT count(*) FROM pg_constraint WHERE conname = 'work_member_agent_role_chk'" ) );
    }
}
