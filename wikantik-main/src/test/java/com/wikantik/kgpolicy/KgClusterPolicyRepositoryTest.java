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
package com.wikantik.kgpolicy;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ClusterPolicy;
import com.wikantik.api.kgpolicy.PolicyAuditEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KgClusterPolicyRepositoryTest {

    private static DataSource ds;

    @BeforeAll
    static void up() {
        ds = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void clean() throws Exception {
        try( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( "DELETE FROM kg_policy_audit" );
            s.execute( "DELETE FROM kg_cluster_policy" );
        }
    }

    @Test
    void upsert_inserts_then_updates() {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.upsert( "java", ClusterAction.INCLUDE, "bootstrap", "admin" );
        Optional< ClusterPolicy > p = repo.find( "java" );
        assertTrue( p.isPresent() );
        assertEquals( ClusterAction.INCLUDE, p.get().action() );
        assertEquals( "bootstrap", p.get().reason() );

        repo.upsert( "java", ClusterAction.EXCLUDE, "noisy", "admin" );
        p = repo.find( "java" );
        assertEquals( ClusterAction.EXCLUDE, p.get().action() );
        assertEquals( "noisy", p.get().reason() );
    }

    @Test
    void delete_removes_row() {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.upsert( "python", ClusterAction.INCLUDE, "useful", "admin" );
        assertTrue( repo.find( "python" ).isPresent() );

        repo.delete( "python" );
        assertFalse( repo.find( "python" ).isPresent() );
    }

    @Test
    void list_orders_by_cluster_name() {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.upsert( "zebra", ClusterAction.EXCLUDE, "noisy", "admin" );
        repo.upsert( "alpha", ClusterAction.INCLUDE, "core", "admin" );
        repo.upsert( "mango", ClusterAction.INCLUDE, "fruit", "admin" );

        final List< ClusterPolicy > list = repo.list();
        assertEquals( 3, list.size() );
        assertEquals( "alpha", list.get( 0 ).cluster() );
        assertEquals( "mango", list.get( 1 ).cluster() );
        assertEquals( "zebra", list.get( 2 ).cluster() );
    }

    @Test
    void mark_reviewed_bumps_only_review_timestamp() throws Exception {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.upsert( "rust", ClusterAction.INCLUDE, "systems", "admin" );
        final ClusterPolicy before = repo.find( "rust" ).orElseThrow();
        assertNull( before.reviewedAt() );

        Thread.sleep( 30 );
        repo.markReviewed( "rust" );
        final ClusterPolicy after = repo.find( "rust" ).orElseThrow();

        assertNotNull( after.reviewedAt() );
        assertTrue( after.reviewedAt().isAfter( before.setAt() ),
            "reviewedAt should be after setAt" );
        // action and reason unchanged
        assertEquals( before.action(), after.action() );
        assertEquals( before.reason(), after.reason() );
    }

    @Test
    void audit_appends_each_change() {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.appendAudit( "go", null, "include", "first set", "admin" );
        repo.appendAudit( "go", "include", "exclude", "too noisy", "admin" );

        final List< PolicyAuditEntry > entries = repo.listAudit( Optional.empty(), 10 );
        assertEquals( 2, entries.size() );
        // reverse-chronological: most recent first
        assertEquals( "exclude", entries.get( 0 ).newAction() );
        assertEquals( "include", entries.get( 1 ).newAction() );
        assertNull( entries.get( 1 ).oldAction() );
    }

    @Test
    void audit_filter_by_cluster() {
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        repo.appendAudit( "clojure", null, "include", "lisp", "admin" );
        repo.appendAudit( "scala", null, "exclude", "verbose", "admin" );
        repo.appendAudit( "clojure", "include", "exclude", "too niche", "admin" );

        final List< PolicyAuditEntry > clojureEntries =
            repo.listAudit( Optional.of( "clojure" ), 10 );
        assertEquals( 2, clojureEntries.size() );
        assertTrue( clojureEntries.stream().allMatch( e -> "clojure".equals( e.cluster() ) ) );

        final List< PolicyAuditEntry > scalaEntries =
            repo.listAudit( Optional.of( "scala" ), 10 );
        assertEquals( 1, scalaEntries.size() );
        assertEquals( "scala", scalaEntries.get( 0 ).cluster() );
    }
}
