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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the code-host migration: change columns, the product hook and learned identities (work part 5). */
@RequiresPostgres
class WorkCodeHostSchemaTest {

    private static final String MIGRATION = "V089__work_codehost.sql";

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

    private String change( final String branch, final String cols, final String vals ) {
        return "INSERT INTO work_change (item_id, product_id, branch, base_branch" + cols
            + ") VALUES (" + item + "," + product + ",'" + branch + "','main'" + vals + ")";
    }

    private static String migration() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", MIGRATION ) );
    }

    private static String state( final SQLException e ) {
        return e.getSQLState();
    }

    @Test
    void aChangeNeedsAnInsideOrOutsideOpener() throws SQLException {
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec( change( "a", "", "" ) ) ) ) );
        exec( change( "b", ", opened_by", ", 'alice'" ) );
        exec( change( "c", ", external_opened_by", ", 'octocat'" ) );
    }

    @Test
    void anUnknownSourceOrFlagIsRefused() {
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( change( "a", ", opened_by, source", ", 'alice', 'carrier-pigeon'" ) ) ) ) );
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( change( "b", ", opened_by, policy_flag", ", 'alice', 'maybe'" ) ) ) ) );
    }

    @Test
    void oneDirectChangePerItemAndCommit() throws SQLException {
        final String direct = ", opened_by, state, merge_kind, merged_sha, source";
        exec( change( "main", direct, ", 'alice', 'merged', 'direct', 'abc1234', 'codehost'" ) );
        assertEquals( "23505", state( assertThrows( SQLException.class,
            () -> exec( change( "main", direct, ", 'alice', 'merged', 'direct', 'abc1234', 'codehost'" ) ) ) ) );
        exec( change( "main", direct, ", 'alice', 'merged', 'reviewed', 'abc1234', 'codehost'" ) );
    }

    @Test
    void legacyReportedDirectChangesAreOutsideTheUniqueIndex() throws SQLException {
        final String direct = ", opened_by, state, merge_kind, merged_sha";
        exec( change( "main", direct, ", 'alice', 'merged', 'direct', 'abc1234'" ) );
        exec( change( "main", direct, ", 'alice', 'merged', 'direct', 'abc1234'" ) );
    }

    @Test
    void theFlagIndexLeavesOutAcknowledgedAndSuperseded() throws SQLException {
        final String def = text( "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_work_change_flag'" );
        assertTrue( def.contains( "policy_flag IS NOT NULL" ), def );
        assertTrue( def.contains( "flag_acknowledged_at IS NULL" ), def );
        assertTrue( def.contains( "superseded_by IS NULL" ), def );
    }

    @Test
    void aHookBelongsToOneProduct() throws SQLException {
        exec( "INSERT INTO work_product_hook (product_id, secret, created_by) VALUES (" + product + ",'s','root')" );
        assertEquals( "23505", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_product_hook (product_id, secret, created_by) VALUES (" + product + ",'t','root')" ) ) ) );
    }

    @Test
    void theAppRoleCannotDeleteHooksOrIdentities() throws Exception {
        final String grant = Arrays.stream( migration().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) ).findFirst().orElseThrow().replaceAll( "\\s+", " " ).trim();
        assertEquals( "GRANT SELECT, INSERT, UPDATE ON work_product_hook, work_codehost_identity TO :app_user", grant );
        // The test container's role is a superuser, so the grant text is the pin; a plain role proves the privilege shape.
        exec( "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'codehost_probe') THEN "
            + "CREATE ROLE codehost_probe; END IF; END $$" );
        exec( grant.replace( ":app_user", "codehost_probe" ) );
        for ( final String table : new String[] { "work_product_hook", "work_codehost_identity" } ) {
            assertFalse( bool( "SELECT has_table_privilege('codehost_probe', '" + table + "', 'DELETE')" ), table );
            assertTrue( bool( "SELECT has_table_privilege('codehost_probe', '" + table + "', 'INSERT')" ), table );
        }
    }

    @Test
    void reapplyingIsANoOp() throws Exception {
        final String sql = migration().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        exec( change( "after", ", opened_by", ", 'alice'" ) );
    }

    private String text( final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery( sql ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }

    private boolean bool( final String sql ) throws SQLException {
        return "t".equals( text( sql ) ) || "true".equals( text( sql ) );
    }

    @Test
    void pullRequestsFromOneBranchEachKeepOneLiveChange() throws SQLException {
        final String linked = ", opened_by, review_number";
        exec( change( "work/WIKI-1-x", linked, ", 'alice', '5'" ) );
        exec( change( "work/WIKI-1-x", linked, ", 'alice', '6'" ) );
        assertEquals( "23505", state( assertThrows( SQLException.class,
            () -> exec( change( "work/WIKI-1-y", linked, ", 'alice', '5'" ) ) ) ), "one live change per pull request" );
        exec( change( "work/WIKI-1-x", ", opened_by", ", 'alice'" ) );
        assertEquals( "23505", state( assertThrows( SQLException.class,
            () -> exec( change( "work/WIKI-1-x", ", opened_by", ", 'alice'" ) ) ) ), "one unlinked live change per branch" );
        final String def = text( "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_work_change_live_branch'" );
        assertTrue( def.contains( "review_number IS NULL" ), def );
    }

    @Test
    void theAppRoleCannotDeleteHeadReports() throws Exception {
        final String grant = Arrays.stream( migration().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) && st.contains( "work_codehost_head" ) ).findFirst().orElseThrow()
            .replaceAll( "\\s+", " " ).trim();
        assertEquals( "GRANT SELECT, INSERT, UPDATE, DELETE ON work_codehost_head TO :app_user", grant,
            "head reports are bounded per change, so the oldest are deleted" );
        final String lifecycle = Arrays.stream( migration().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) && st.contains( "work_codehost_lifecycle" ) ).findFirst().orElseThrow()
            .replaceAll( "\\s+", " " ).trim();
        assertEquals( "GRANT SELECT, INSERT ON work_codehost_lifecycle TO :app_user", lifecycle );
    }

    @Test
    void onePullRequestIsOneChangeWhateverItsState() throws SQLException {
        exec( change( "work/WIKI-1-x", ", opened_by, review_number, state", ", 'alice', '5', 'abandoned'" ) );
        assertEquals( "23505", state( assertThrows( SQLException.class,
            () -> exec( change( "work/WIKI-1-x", ", opened_by, review_number", ", 'alice', '5'" ) ) ) ),
            "a closed pull request's change is reopened, never replaced" );
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( change( "work/WIKI-1-y", ", opened_by, lifecycle_kind", ", 'alice', 'forgotten'" ) ) ) ) );
    }

    /** Every column and index of the code-host tables, as the catalogue describes them. */
    private String schema() throws SQLException {
        return text( "SELECT string_agg( d, ' | ' ORDER BY d ) FROM ( "
            + "SELECT table_name || '.' || column_name || ':' || data_type || ':' || is_nullable || ':' "
            + "|| COALESCE( column_default, '-' ) AS d FROM information_schema.columns WHERE table_name IN "
            + "('work_change','work_product_hook','work_codehost_identity','work_codehost_head','work_codehost_lifecycle') "
            + "UNION ALL SELECT indexname || ':' || indexdef FROM pg_indexes WHERE tablename IN "
            + "('work_change','work_product_hook','work_codehost_identity','work_codehost_head','work_codehost_lifecycle') "
            + "UNION ALL SELECT conname || ':' || pg_get_constraintdef( oid ) FROM pg_constraint WHERE conrelid IN "
            + "( 'work_change'::regclass, 'work_codehost_head'::regclass, 'work_codehost_lifecycle'::regclass ) ) s" );
    }

    @Test
    void reapplyingLeavesTheSchemaAsItWas() throws Exception {
        final String before = schema();
        final String sql = migration().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        assertEquals( before, schema() );
    }

    @Test
    void reapplyingKeepsTheNarrowedBranchIndex() throws Exception {
        final String sql = migration().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( Files.readString( Path.of( "..", "bin", "db", "migrations", "V081__work_changes_and_acknowledgement.sql" ) )
            .replace( ":app_user", "CURRENT_USER" ) );
        exec( sql );
        assertTrue( text( "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_work_change_live_branch'" )
            .contains( "review_number IS NULL" ), "V081 reapplied after V089 leaves the narrowed index" );
    }
}
