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
package com.wikantik.knowledge;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.api.knowledge.KgRejection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link KgRejectionRepository} — the "negative knowledge" store that
 * stops the extractor re-proposing an edge a curator already turned down.
 */
@Testcontainers( disabledWithoutDocker = true )
class KgRejectionRepositoryTest {

    private static DataSource dataSource;
    private KgRejectionRepository repo;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestDb.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
        }
        repo = new KgRejectionRepository( dataSource );
    }

    // ------------------------------------------------------------------ insert / isRejected

    @Test
    void insertedRejectionIsReportedAsRejected() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "wrong direction" );

        assertTrue( repo.isRejected( "Alpha", "Beta", "implements" ) );
    }

    @Test
    void isRejectedIsScopedToTheExactTriple() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "wrong direction" );

        assertFalse( repo.isRejected( "Alpha", "Beta", "located_in" ), "relationship must match" );
        assertFalse( repo.isRejected( "Alpha", "Gamma", "implements" ), "target must match" );
        assertFalse( repo.isRejected( "Gamma", "Beta", "implements" ), "source must match" );
    }

    @Test
    void unknownTripleIsNotRejected() {
        assertFalse( repo.isRejected( "Nothing", "Here", "related_to" ) );
    }

    @Test
    void reinsertingTheSameTripleUpdatesReviewerAndReasonInPlace() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "first pass" );
        repo.insertRejection( "Alpha", "Beta", "implements", "bob", "second look, still no" );

        final List< KgRejection > all = repo.listRejections( "Alpha", "Beta", "implements" );
        assertEquals( 1, all.size(), "the unique constraint must upsert, not duplicate" );
        assertEquals( "bob", all.get( 0 ).rejectedBy() );
        assertEquals( "second look, still no", all.get( 0 ).reason() );
    }

    @Test
    void nullReviewerAndReasonAreAccepted() {
        repo.insertRejection( "Alpha", "Beta", "implements", null, null );

        final List< KgRejection > all = repo.listRejections( null, null, null );
        assertEquals( 1, all.size() );
        assertEquals( null, all.get( 0 ).rejectedBy() );
        assertEquals( null, all.get( 0 ).reason() );
    }

    @Test
    void insertWithNullSourceViolatesNotNullAndIsWrappedInRuntimeException() {
        assertThrows( RuntimeException.class,
                () -> repo.insertRejection( null, "Beta", "implements", "alice", "x" ) );
    }

    // ------------------------------------------------------------------ listRejections

    @Test
    void listRejectionsWithoutFiltersReturnsEverythingAndMapsAllColumns() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "no" );
        repo.insertRejection( "Gamma", "Delta", "located_in", "bob", "nope" );

        final List< KgRejection > all = repo.listRejections( null, null, null );

        assertEquals( 2, all.size() );
        final KgRejection any = all.stream()
                .filter( r -> "Alpha".equals( r.proposedSource() ) )
                .findFirst().orElseThrow();
        assertNotNull( any.id(), "id is a generated UUID" );
        assertEquals( "Beta", any.proposedTarget() );
        assertEquals( "implements", any.proposedRelationship() );
        assertEquals( "alice", any.rejectedBy() );
        assertEquals( "no", any.reason() );
        assertNotNull( any.created(), "created carries the DB default timestamp" );
    }

    @Test
    void listRejectionsFiltersBySourceAlone() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "no" );
        repo.insertRejection( "Alpha", "Delta", "located_in", "alice", "no" );
        repo.insertRejection( "Gamma", "Beta", "implements", "bob", "no" );

        final List< KgRejection > forAlpha = repo.listRejections( "Alpha", null, null );

        assertEquals( 2, forAlpha.size() );
        assertTrue( forAlpha.stream().allMatch( r -> "Alpha".equals( r.proposedSource() ) ) );
    }

    @Test
    void listRejectionsFiltersByTargetAlone() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "no" );
        repo.insertRejection( "Gamma", "Beta", "located_in", "bob", "no" );
        repo.insertRejection( "Alpha", "Delta", "implements", "alice", "no" );

        final List< KgRejection > toBeta = repo.listRejections( null, "Beta", null );

        assertEquals( 2, toBeta.size() );
        assertTrue( toBeta.stream().allMatch( r -> "Beta".equals( r.proposedTarget() ) ) );
    }

    @Test
    void listRejectionsFiltersByRelationshipAlone() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "no" );
        repo.insertRejection( "Gamma", "Delta", "implements", "bob", "no" );
        repo.insertRejection( "Alpha", "Delta", "located_in", "alice", "no" );

        final List< KgRejection > implementsOnly = repo.listRejections( null, null, "implements" );

        assertEquals( 2, implementsOnly.size() );
        assertTrue( implementsOnly.stream().allMatch( r -> "implements".equals( r.proposedRelationship() ) ) );
    }

    @Test
    void listRejectionsWithNoMatchReturnsEmptyList() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "no" );

        assertTrue( repo.listRejections( "Nope", null, null ).isEmpty() );
    }

    // ------------------------------------------------------------------ deleteRejection

    @Test
    void deleteRejectionRemovesTheTripleAndReportsOneRow() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "no" );

        assertEquals( 1, repo.deleteRejection( "Alpha", "Beta", "implements" ) );
        assertFalse( repo.isRejected( "Alpha", "Beta", "implements" ),
                "deleting the rejection must re-open the edge for proposal" );
    }

    @Test
    void deleteRejectionOnUnknownTripleReportsZeroRows() {
        assertEquals( 0, repo.deleteRejection( "Nothing", "Here", "related_to" ) );
    }

    @Test
    void deleteRejectionLeavesSiblingTriplesIntact() {
        repo.insertRejection( "Alpha", "Beta", "implements", "alice", "no" );
        repo.insertRejection( "Alpha", "Beta", "located_in", "alice", "no" );

        repo.deleteRejection( "Alpha", "Beta", "implements" );

        assertFalse( repo.isRejected( "Alpha", "Beta", "implements" ) );
        assertTrue( repo.isRejected( "Alpha", "Beta", "located_in" ) );
    }
}
