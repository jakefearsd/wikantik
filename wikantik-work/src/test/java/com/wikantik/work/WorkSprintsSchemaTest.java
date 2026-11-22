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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins V085: sprints, the item sprint and estimate columns, their checks and indexes (work management part 2). */
@RequiresPostgres
class WorkSprintsSchemaTest {

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

    private long sprint( final long inProject, final String name ) throws SQLException {
        return scalar( "INSERT INTO work_sprint (project_id, name, starts_on, ends_on) VALUES (" + inProject
            + ", '" + name + "', '2026-12-01', '2026-12-14') RETURNING id" );
    }

    private static String state( final SQLException e ) {
        return e.getSQLState();
    }

    private static String v085() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", "V085__work_sprints.sql" ) );
    }

    @Test
    void aSprintStartsPlannedWithNoFigures() throws SQLException {
        final long s = sprint( project, "S1" );
        assertEquals( 1, scalar( "SELECT count(*) FROM work_sprint WHERE id = " + s
            + " AND state = 'planned' AND goal = '' AND version = 1 AND started_at IS NULL AND closed_at IS NULL"
            + " AND committed_items IS NULL AND completed_items IS NULL" ) );
    }

    @Test
    void aSprintEndingBeforeItStartsIsRefused() {
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_sprint (project_id, name, starts_on, ends_on) VALUES (" + project
                + ", 'S1', '2026-12-14', '2026-12-01')" ) ) ) );
    }

    @Test
    void anUnknownStateIsRefused() {
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state) VALUES (" + project
                + ", 'S1', '2026-12-01', '2026-12-14', 'paused')" ) ) ) );
    }

    @Test
    void aSprintNameIsUniqueInItsProject() throws SQLException {
        sprint( project, "S1" );
        assertEquals( "23505", state( assertThrows( SQLException.class, () -> sprint( project, "S1" ) ) ) );
        final long other = scalar( "INSERT INTO work_project (key, name) VALUES ('OPS', 'Ops') RETURNING id" );
        sprint( other, "S1" );
    }

    @Test
    void twoActiveSprintsInOneProjectAreRefused() throws SQLException {
        final long other = scalar( "INSERT INTO work_project (key, name) VALUES ('OPS', 'Ops') RETURNING id" );
        exec( "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, started_at, committed_items)"
            + " VALUES (" + project + ", 'S1', '2026-12-01', '2026-12-14', 'active', now(), 0)" );
        assertEquals( "23505", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, started_at, committed_items)"
                + " VALUES (" + project + ", 'S2', '2026-12-01', '2026-12-14', 'active', now(), 0)" ) ) ) );
        exec( "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, started_at, committed_items)"
            + " VALUES (" + other + ", 'S1', '2026-12-01', '2026-12-14', 'active', now(), 0)" );
    }

    @Test
    void anActiveSprintNeedsItsStartAndCommitment() {
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, committed_items)"
                + " VALUES (" + project + ", 'S1', '2026-12-01', '2026-12-14', 'active', 0)" ) ) ) );
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, started_at)"
                + " VALUES (" + project + ", 'S1', '2026-12-01', '2026-12-14', 'active', now())" ) ) ) );
    }

    @Test
    void aClosedSprintNeedsItsCloseAndCompletion() {
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, started_at, committed_items,"
                + " completed_items) VALUES (" + project + ", 'S1', '2026-12-01', '2026-12-14', 'closed', now(), 0, 0)" ) ) ) );
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, started_at, committed_items,"
                + " closed_at) VALUES (" + project + ", 'S1', '2026-12-01', '2026-12-14', 'closed', now(), 0, now())" ) ) ) );
    }

    @Test
    void anEstimateIsAHalfPointFromZeroTo999Point5() throws SQLException {
        for ( final String ok : List.of( "0", "0.5", "999.5" ) ) {
            exec( "UPDATE work_item SET estimate = " + ok + " WHERE id = " + task );
        }
        for ( final String bad : List.of( "-0.5", "1.2" ) ) {
            assertEquals( "23514", state( assertThrows( SQLException.class,
                () -> exec( "UPDATE work_item SET estimate = " + bad + " WHERE id = " + task ) ) ) );
        }
    }

    @Test
    void anEstimateAboveTheColumnRangeIsRefusedByTheColumnType() {
        // NUMERIC(4,1) cannot hold 1000 at all: the overflow (22003) fires before the CHECK ever runs.
        assertEquals( "22003", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_item SET estimate = 1000 WHERE id = " + task ) ) ) );
    }

    @Test
    void anEpicHasNoSprintAndNoEstimate() throws SQLException {
        final long s = sprint( project, "S1" );
        final long epic = scalar( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter)"
            + " VALUES (" + project + ",2,'epic','software','e',2.0,'alice') RETURNING id" );
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_item SET sprint_id = " + s + " WHERE id = " + epic ) ) ) );
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_item SET estimate = 1.0 WHERE id = " + epic ) ) ) );
        exec( "UPDATE work_item SET sprint_id = " + s + ", estimate = 1.0 WHERE id = " + task );
    }

    @Test
    void theAppRoleCannotDeleteASprint() throws Exception {
        final List< String > grants = Arrays.stream( v085().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) ).map( st -> st.replaceAll( "\\s+", " " ).trim() ).toList();
        assertEquals( List.of( "GRANT SELECT, INSERT, UPDATE ON work_sprint TO :app_user" ), grants );
    }

    @Test
    void v085IsANoOpWhenReapplied() throws Exception {
        final String sql = v085().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        sprint( project, "S1" );
    }
}
