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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins V081: change records and the acknowledgement columns (work management phase 3b). */
@RequiresPostgres
class WorkChangesSchemaTest {

    private DataSource ds;
    private long item;
    private long product;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final long p = scalar( "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" );
        product = scalar( "INSERT INTO work_product (key, name) VALUES ('wikantik', 'Wikantik') RETURNING id" );
        item = scalar( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter, product_id) VALUES ("
            + p + ",1,'task','software','t',1.0,'alice'," + product + ") RETURNING id" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private String change( final String branch, final String extraCols, final String extraVals ) {
        return "INSERT INTO work_change (item_id, product_id, branch, base_branch, opened_by" + extraCols
            + ") VALUES (" + item + "," + product + ",'" + branch + "','main','alice'" + extraVals + ")";
    }

    private static String state( final SQLException e ) {
        return e.getSQLState();
    }

    private static String v081() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations",
            "V081__work_changes_and_acknowledgement.sql" ) );
    }

    @Test
    void aSecondLiveChangeOnOneBranchIsRefused() throws SQLException {
        exec( change( "work/WIKI-1-x", "", "" ) );
        assertEquals( "23505", state( assertThrows( SQLException.class,
            () -> exec( change( "work/WIKI-1-x", ", state", ", 'in_review'" ) ) ) ) );
        exec( change( "work/WIKI-1-y", "", "" ) );
    }

    @Test
    void aMergedChangeDoesNotBlockTheBranch() throws SQLException {
        exec( change( "main", ", state, merge_kind, merged_sha", ", 'merged', 'direct', 'abc1234'" ) );
        exec( change( "main", ", state, merge_kind, merged_sha", ", 'merged', 'direct', 'abc1235'" ) );
        exec( change( "main", ", state", ", 'abandoned'" ) );
        exec( change( "main", "", "" ) );
    }

    @Test
    void aMergedChangeNeedsAMergeKind() {
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( change( "b", ", state", ", 'merged'" ) ) ) ) );
    }

    @Test
    void anOpenChangeMayNotCarryOne() {
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( change( "b", ", merge_kind", ", 'reviewed'" ) ) ) ) );
    }

    @Test
    void anUnknownStateOrMergeKindIsRefused() {
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( change( "b", ", state", ", 'closed'" ) ) ) ) );
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( change( "b", ", state, merge_kind", ", 'merged', 'squash'" ) ) ) ) );
    }

    @Test
    void ackColumnsDefaultToNotRequired() throws SQLException {
        assertEquals( 1, scalar( "SELECT count(*) FROM work_item WHERE id = " + item
            + " AND ack_required = FALSE AND acknowledged_by IS NULL AND acknowledged_at IS NULL" ) );
    }

    /** The test container's role is a superuser, so the no-delete grant is pinned on the migration text. */
    @Test
    void theAppRoleCannotDeleteAChange() throws Exception {
        final String grant = Arrays.stream( v081().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) && st.contains( "work_change" ) )
            .findFirst().orElseThrow().replaceAll( "\\s+", " " ).trim();
        assertEquals( "GRANT SELECT, INSERT, UPDATE ON work_change TO :app_user", grant );
    }

    @Test
    void v081IsANoOpWhenReapplied() throws Exception {
        final String sql = v081().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        exec( change( "after", "", "" ) );
    }
}
