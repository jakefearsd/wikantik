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
package com.wikantik.extractcli;

import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.kgpolicy.KgClusterPolicyRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KgPolicyCliTest {

    private static PostgreSQLContainer pg;
    private static DataSource ds;

    @BeforeAll
    static void up() {
        pg = new PostgreSQLContainer( "postgres:15" )
                .withDatabaseName( "wikantik_kgpolicy" )
                .withUsername( "test" ).withPassword( "test" )
                .withInitScript( "kgpolicy-cli-test.sql" );
        pg.start();
        final PGSimpleDataSource d = new PGSimpleDataSource();
        d.setUrl( pg.getJdbcUrl() );
        d.setUser( pg.getUsername() );
        d.setPassword( pg.getPassword() );
        ds = d;
    }

    @AfterAll
    static void down() { if ( pg != null ) pg.stop(); }

    @BeforeEach
    void clean() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( "DELETE FROM chunk_entity_mentions" );
            s.execute( "DELETE FROM kg_edges" );
            s.execute( "DELETE FROM kg_nodes" );
            s.execute( "DELETE FROM kg_cluster_policy" );
            s.execute( "DELETE FROM kg_policy_audit" );
            s.execute( "DELETE FROM kg_excluded_pages" );
        }
    }

    private String stdoutOf( final String... args ) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int rc = new KgPolicyCli( new PrintStream( o ), new PrintStream( e ) )
                .runWithDataSource( ds, args );
        assertEquals( 0, rc, "stderr: " + e.toString( StandardCharsets.UTF_8 ) );
        return o.toString( StandardCharsets.UTF_8 );
    }

    @Test
    void list_prints_each_row() {
        new KgClusterPolicyRepository( ds ).upsert( "java", ClusterAction.INCLUDE, "boot", "admin" );
        new KgClusterPolicyRepository( ds ).upsert( "van-life", ClusterAction.EXCLUDE, "noisy", "admin" );

        final String text = stdoutOf( "list" );
        assertTrue( text.contains( "java" ) );
        assertTrue( text.contains( "include" ) );
        assertTrue( text.contains( "van-life" ) );
        assertTrue( text.contains( "exclude" ) );
    }

    @Test
    void set_then_list_round_trip() {
        stdoutOf( "set", "java", "include", "--reason", "boot" );
        final String text = stdoutOf( "list" );
        assertTrue( text.contains( "java" ) && text.contains( "include" ) );

        // Audit recorded?
        assertEquals( 1, new KgClusterPolicyRepository( ds ).listAudit( java.util.Optional.of( "java" ), 10 ).size() );
    }

    @Test
    void clear_removes_row() {
        new KgClusterPolicyRepository( ds ).upsert( "java", ClusterAction.INCLUDE, "x", "a" );
        stdoutOf( "clear", "java" );
        assertTrue( new KgClusterPolicyRepository( ds ).find( "java" ).isEmpty() );
    }

    @Test
    void purge_without_confirm_is_dry_run() {
        new KgExcludedPagesRepository( ds ).exclude( "Foo", ExclusionReason.CLUSTER_POLICY );
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int rc = new KgPolicyCli( new PrintStream( o ), new PrintStream( e ) )
                .runWithDataSource( ds, new String[] { "purge", "java" } );
        assertEquals( 1, rc, "expected exit 1 for dry-run; stderr=" + e );
        assertTrue( e.toString( StandardCharsets.UTF_8 ).contains( "--confirm" ) );
        // Row still there
        assertTrue( new KgExcludedPagesRepository( ds ).findReason( "Foo" ).isPresent() );
    }

    @Test
    void purge_by_reason_with_confirm_deletes_excluded_rows() {
        new KgExcludedPagesRepository( ds ).exclude( "Foo", ExclusionReason.SYSTEM_PAGE );
        new KgExcludedPagesRepository( ds ).exclude( "Bar", ExclusionReason.SYSTEM_PAGE );
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int rc = new KgPolicyCli( new PrintStream( o ), new PrintStream( e ) )
                .runWithDataSource( ds, new String[] {
                        "purge", "--reason", "system_page", "--confirm" } );
        assertEquals( 0, rc, "stderr=" + e );
        assertTrue( new KgExcludedPagesRepository( ds ).findReason( "Foo" ).isEmpty() );
        assertTrue( new KgExcludedPagesRepository( ds ).findReason( "Bar" ).isEmpty() );
    }

    @Test
    void audit_lists_recent_changes() {
        new KgClusterPolicyRepository( ds ).appendAudit( "java", null, "include", "boot", "a" );
        new KgClusterPolicyRepository( ds ).appendAudit( "java", "include", "exclude", "noisy", "a" );
        final String text = stdoutOf( "audit", "--cluster", "java" );
        assertTrue( text.contains( "include" ) && text.contains( "exclude" ) );
    }

    @Test
    void explain_reports_no_policy_row_for_unknown_cluster() {
        final String text = stdoutOf( "explain", "unknown-cluster" );
        assertTrue( text.contains( "unknown-cluster: no policy row (default exclude)" ), text );
    }

    @Test
    void explain_reports_existing_policy_details() {
        new KgClusterPolicyRepository( ds ).upsert( "java", ClusterAction.EXCLUDE, "too noisy", "admin" );
        final String text = stdoutOf( "explain", "java" );
        assertTrue( text.contains( "java: exclude" ), text );
        assertTrue( text.contains( "set_by=admin" ), text );
        assertTrue( text.contains( "reason=too noisy" ), text );
    }

    @Test
    void review_lists_only_stale_or_never_reviewed_clusters() {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.upsert( "fresh", ClusterAction.INCLUDE, "x", "admin" );
        repo.markReviewed( "fresh" );
        repo.upsert( "never-reviewed", ClusterAction.EXCLUDE, "y", "admin" );

        final String text = stdoutOf( "review" );
        assertTrue( text.contains( "never-reviewed" ), text );
        assertFalse( text.contains( "fresh" ), "recently-reviewed cluster should not be listed: " + text );
    }

    @Test
    void mark_reviewed_updates_reviewed_at_for_each_named_cluster() {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.upsert( "java", ClusterAction.INCLUDE, "x", "admin" );
        repo.upsert( "rust", ClusterAction.INCLUDE, "y", "admin" );

        final String text = stdoutOf( "mark-reviewed", "java", "rust" );
        assertTrue( text.contains( "marked reviewed: java" ), text );
        assertTrue( text.contains( "marked reviewed: rust" ), text );

        final Instant cutoff = Instant.now().minus( 1, ChronoUnit.MINUTES );
        assertTrue( repo.find( "java" ).get().reviewedAt().isAfter( cutoff ) );
        assertTrue( repo.find( "rust" ).get().reviewedAt().isAfter( cutoff ) );
    }

    @Test
    void mark_reviewed_without_cluster_names_returns_exit_two() {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int rc = new KgPolicyCli( new PrintStream( o ), new PrintStream( e ) )
                .runWithDataSource( ds, new String[] { "mark-reviewed" } );
        assertEquals( 2, rc );
        assertTrue( e.toString( StandardCharsets.UTF_8 ).contains( "requires at least one cluster name" ) );
    }

    @Test
    void diff_lists_pages_excluded_under_cluster_policy_reason() {
        new KgExcludedPagesRepository( ds ).exclude( "Alpha", ExclusionReason.CLUSTER_POLICY );
        new KgExcludedPagesRepository( ds ).exclude( "Beta", ExclusionReason.SYSTEM_PAGE );

        final String text = stdoutOf( "diff", "java" );
        assertTrue( text.contains( "Alpha" ), text );
        assertFalse( text.contains( "Beta" ), "system_page-excluded pages must not appear in a cluster_policy diff: " + text );
        assertTrue( text.contains( "(1 pages)" ), text );
    }

    @Test
    void reconcile_reports_counts_by_exclusion_reason() {
        new KgExcludedPagesRepository( ds ).exclude( "Alpha", ExclusionReason.CLUSTER_POLICY );
        new KgExcludedPagesRepository( ds ).exclude( "Beta", ExclusionReason.CLUSTER_POLICY );
        new KgExcludedPagesRepository( ds ).exclude( "Gamma", ExclusionReason.SYSTEM_PAGE );

        final String text = stdoutOf( "reconcile" );
        assertTrue( text.contains( "cluster_policy" ) && text.contains( "2 pages" ), text );
        assertTrue( text.contains( "system_page" ) && text.contains( "1 pages" ), text );
    }

    @Test
    void no_subcommand_prints_usage_and_returns_exit_two() {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int rc = new KgPolicyCli( new PrintStream( o ), new PrintStream( e ) )
                .runWithDataSource( ds, new String[0] );
        assertEquals( 2, rc );
        assertTrue( e.toString( StandardCharsets.UTF_8 ).contains( "usage: kg-policy" ) );
    }

    @Test
    void unknown_subcommand_prints_usage_and_returns_exit_two() {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int rc = new KgPolicyCli( new PrintStream( o ), new PrintStream( e ) )
                .runWithDataSource( ds, new String[] { "bogus-command" } );
        assertEquals( 2, rc );
        assertTrue( e.toString( StandardCharsets.UTF_8 ).contains( "usage: kg-policy" ) );
    }

    @Test
    void run_with_jdbc_flags_parses_them_and_dispatches_to_a_real_connection() {
        // Exercises KgPolicyCli.run(String[]) end-to-end: JdbcArgs.parse() strips
        // the --jdbc-* flags, JdbcArgs.dataSource() builds a real PGSimpleDataSource
        // pointed at the Testcontainers instance, and the remaining "list" args are
        // dispatched exactly like runWithDataSource would.
        new KgClusterPolicyRepository( ds ).upsert( "java", ClusterAction.INCLUDE, "boot", "admin" );

        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        final ByteArrayOutputStream e = new ByteArrayOutputStream();
        final int rc = new KgPolicyCli( new PrintStream( o ), new PrintStream( e ) ).run( new String[] {
                "--jdbc-url", pg.getJdbcUrl(),
                "--jdbc-user", pg.getUsername(),
                "--jdbc-password", pg.getPassword(),
                "list"
        } );
        assertEquals( 0, rc, "stderr=" + e.toString( StandardCharsets.UTF_8 ) );
        assertTrue( o.toString( StandardCharsets.UTF_8 ).contains( "java" ) );
    }

    @Test
    void jdbcArgsParseAppliesOverridesAndLeavesRemainingArgs() {
        final KgPolicyCli.JdbcArgs j = KgPolicyCli.JdbcArgs.parse( new String[] {
                "--jdbc-url", "jdbc:postgresql://example:5432/db",
                "--jdbc-user", "custom-user",
                "--jdbc-password", "custom-pass",
                "list", "--filter", "include"
        } );
        assertEquals( "jdbc:postgresql://example:5432/db", j.jdbcUrl );
        assertEquals( "custom-user", j.jdbcUser );
        assertEquals( "custom-pass", j.jdbcPassword );
        assertArrayEquals( new String[] { "list", "--filter", "include" }, j.remaining );
    }

    @Test
    void jdbcArgsParseAppliesDefaultsWhenNoFlagsGiven() {
        final KgPolicyCli.JdbcArgs j = KgPolicyCli.JdbcArgs.parse( new String[] { "list" } );
        assertEquals( "jdbc:postgresql://localhost:5432/jspwiki", j.jdbcUrl );
        assertEquals( "jspwiki", j.jdbcUser );
        assertArrayEquals( new String[] { "list" }, j.remaining );
    }

    @Test
    void jdbcArgsPasswordEnvReadsFromEnvironment() {
        final KgPolicyCli.JdbcArgs j = KgPolicyCli.JdbcArgs.parse( new String[] {
                "--jdbc-password-env", "PATH", "list" } );
        assertEquals( System.getenv( "PATH" ), j.jdbcPassword );
    }

    @Test
    void jdbcArgsDataSourceReflectsParsedFields() {
        final KgPolicyCli.JdbcArgs j = KgPolicyCli.JdbcArgs.parse( new String[] {
                "--jdbc-url", "jdbc:postgresql://myhost:5432/mydb",
                "--jdbc-user", "myuser",
                "--jdbc-password", "mypass" } );
        final DataSource built = j.dataSource();
        assertInstanceOf( PGSimpleDataSource.class, built );
        final PGSimpleDataSource pgds = ( PGSimpleDataSource ) built;
        // PGSimpleDataSource.getUrl() re-serializes with all driver defaults appended,
        // so assert the host/port/db prefix rather than an exact string match.
        assertTrue( pgds.getUrl().startsWith( "jdbc:postgresql://myhost:5432/mydb" ), pgds.getUrl() );
        assertEquals( "myuser", pgds.getUser() );
        assertEquals( "mypass", pgds.getPassword() );
    }
}
