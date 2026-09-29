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

import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the generic {@code catch (SQLException e) { LOG.warn(...); throw ... }} paths of
 * {@link KgEdgeRepository} that real Postgres constraint violations can't reach (there is no
 * way to make a well-formed statement against a live schema fail with an arbitrary, unclassified
 * {@link SQLException}). A {@link DataSource} whose {@code getConnection()} always throws lets
 * every read/write method fail before any statement is prepared, driving each method into its
 * own catch block without a live database.
 *
 * <p>{@link KgEdgeRepository#upsertEdge} and {@link KgEdgeRepository#upsertEdgeWithProvenance}
 * first call the private {@code isMixedEdgeEndpoints} guard, which has its own
 * fail-open catch (returns {@code false} rather than throwing) — this incidentally covers that
 * catch block too, since the same broken {@link DataSource} is used for the guard's lookup.</p>
 */
class KgEdgeRepositoryErrorPathTest {

    private KgEdgeRepository edges;

    @BeforeEach
    void setUp() throws SQLException {
        final DataSource broken = mock( DataSource.class );
        when( broken.getConnection() ).thenThrow( new SQLException( "simulated connection failure" ) );
        edges = new KgEdgeRepository( broken );
    }

    @Test
    void upsertEdgeFailsOpenOnMixedEdgeLookupThenWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.upsertEdge( UUID.randomUUID(), UUID.randomUUID(), "related_to",
                        Provenance.HUMAN_CURATED, Map.of() ) );
        assertTrue( ex.getMessage().contains( "Failed to upsert edge" ), ex.getMessage() );
    }

    @Test
    void upsertEdgeWithProvenanceWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.upsertEdgeWithProvenance( UUID.randomUUID(), UUID.randomUUID(), "related_to",
                        Provenance.AI_INFERRED, Map.of(), "machine", null ) );
        assertTrue( ex.getMessage().contains( "Failed to upsertEdgeWithProvenance" ), ex.getMessage() );
    }

    @Test
    void deleteEdgeWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.deleteEdge( UUID.randomUUID() ) );
        assertTrue( ex.getMessage().contains( "Failed to delete edge" ), ex.getMessage() );
    }

    @Test
    void findEdgesByProvenanceWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.findEdgesByProvenance( UUID.randomUUID() ) );
    }

    @Test
    void getAllEdgesWrapsGenericFailure() {
        assertThrows( RuntimeException.class, edges::getAllEdges );
    }

    @Test
    void getAllEdgesByTierWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.getAllEdges( Tier.HUMAN ) );
    }

    @Test
    void getEdgesForNodeWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.getEdgesForNode( UUID.randomUUID(), "both" ) );
    }

    @Test
    void diffAndRemoveStaleEdgesWrapsGenericFailure() {
        assertThrows( RuntimeException.class,
                () -> edges.diffAndRemoveStaleEdges( UUID.randomUUID(), Set.of() ) );
    }

    @Test
    void queryEdgesWithNamesWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.queryEdgesWithNames( null, null, 50, 0 ) );
    }

    @Test
    void countEdgesWithFilterWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.countEdgesWithFilter( null, null, "page" ) );
    }

    @Test
    void bulkDeleteByFilterWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.bulkDeleteByFilter( null, null, "entity" ) );
    }

    @Test
    void findDistinctSourceIdsByFilterWrapsGenericFailure() {
        assertThrows( RuntimeException.class,
                () -> edges.findDistinctSourceIdsByFilter( null, null, null ) );
    }

    @Test
    void elevateToHumanCuratedWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.elevateToHumanCurated( UUID.randomUUID() ) );
    }

    @Test
    void findByIdWrapsGenericFailure() {
        assertThrows( RuntimeException.class, () -> edges.findById( UUID.randomUUID() ) );
    }

    @Test
    void deleteEdgeAndRecordRejectionWrapsGenericFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.deleteEdgeAndRecordRejection( UUID.randomUUID(), "tester", "reason" ) );
        assertTrue( ex.getMessage().contains( "deleteEdgeAndRecordRejection failed" ), ex.getMessage() );
    }

    // ------------------------------------------------------------------ shared KgJdbcSupport helpers

    @Test
    void getDistinctRelationshipTypesWrapsFailure() {
        assertThrows( RuntimeException.class, edges::getDistinctRelationshipTypes );
    }

    @Test
    void countEdgesWrapsFailure() {
        assertThrows( RuntimeException.class, edges::countEdges );
    }

    @Test
    void deleteEdgesByProvenanceWrapsFailure() {
        assertThrows( RuntimeException.class, () -> edges.deleteEdgesByProvenance( UUID.randomUUID() ) );
    }
}
