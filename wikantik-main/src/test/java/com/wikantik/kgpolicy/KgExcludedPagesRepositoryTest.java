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
import com.wikantik.api.kgpolicy.ExclusionReason;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class KgExcludedPagesRepositoryTest {

    private static DataSource ds;

    @BeforeAll
    static void up() {
        ds = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void clean() throws Exception {
        try( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( "DELETE FROM kg_excluded_pages" );
        }
    }

    @Test
    void exclude_inserts_with_reason() {
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        repo.exclude( "About", ExclusionReason.SYSTEM_PAGE );
        assertEquals( Optional.of( ExclusionReason.SYSTEM_PAGE ), repo.findReason( "About" ) );
    }

    @Test
    void exclude_upgrades_reason_to_strongest() {
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        repo.exclude( "Foo", ExclusionReason.CLUSTER_POLICY );
        repo.exclude( "Foo", ExclusionReason.SYSTEM_PAGE );  // stronger; wins
        assertEquals( Optional.of( ExclusionReason.SYSTEM_PAGE ), repo.findReason( "Foo" ) );
    }

    @Test
    void exclude_does_not_downgrade_reason() {
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        repo.exclude( "Foo", ExclusionReason.SYSTEM_PAGE );
        repo.exclude( "Foo", ExclusionReason.CLUSTER_POLICY );  // weaker; ignored
        assertEquals( Optional.of( ExclusionReason.SYSTEM_PAGE ), repo.findReason( "Foo" ) );
    }

    @Test
    void release_removes_only_when_reason_matches() {
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        repo.exclude( "Foo", ExclusionReason.CLUSTER_POLICY );
        repo.release( "Foo", ExclusionReason.SYSTEM_PAGE );  // wrong reason — no-op
        assertTrue( repo.findReason( "Foo" ).isPresent() );

        repo.release( "Foo", ExclusionReason.CLUSTER_POLICY );
        assertTrue( repo.findReason( "Foo" ).isEmpty() );
    }

    @Test
    void list_excluded_pages_for_cluster() {
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        repo.exclude( "Foo", ExclusionReason.CLUSTER_POLICY );
        repo.exclude( "Bar", ExclusionReason.SYSTEM_PAGE );
        repo.exclude( "Baz", ExclusionReason.CLUSTER_POLICY );

        final Set< String > clusterPolicyExcluded = repo.listByReason( ExclusionReason.CLUSTER_POLICY );
        assertEquals( Set.of( "Foo", "Baz" ), clusterPolicyExcluded );
    }

    @Test
    void purge_clears_listed_pages_atomically() {
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        repo.exclude( "A", ExclusionReason.SYSTEM_PAGE );
        repo.exclude( "B", ExclusionReason.SYSTEM_PAGE );
        repo.exclude( "C", ExclusionReason.CLUSTER_POLICY );
        final int removed = repo.removeAll( List.of( "A", "B" ) );
        assertEquals( 2, removed );
        assertTrue( repo.findReason( "A" ).isEmpty() );
        assertTrue( repo.findReason( "B" ).isEmpty() );
        assertTrue( repo.findReason( "C" ).isPresent() );
    }
}
