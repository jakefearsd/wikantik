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
}
