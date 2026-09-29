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

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.Tier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural coverage for {@link KgEdgeRepository} beyond the narrow filter/count slice
 * already covered by {@link KgEdgeRepositoryFilterCountTest}: the mixed page/entity edge
 * guard, the CHECK/FK constraint translation on both upsert entry points, tier filtering,
 * direction-scoped edge lookups, stale-edge diffing, elevate-to-human-curated, and the
 * delete+record-rejection transaction.
 */
@RequiresPostgres
class KgEdgeRepositoryTest {

    private static DataSource dataSource;
    private KgEdgeRepository edges;
    private KgNodeRepository nodes;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        edges = new KgEdgeRepository( dataSource );
        nodes = new KgNodeRepository( dataSource );
    }

    private UUID node( final String name, final String type ) {
        return nodes.upsertNode( name, type, "SourcePage", Provenance.HUMAN_AUTHORED, Map.of() ).id();
    }

    // ------------------------------------------------------------------ mixed edge guard

    @Test
    void upsertEdgeRejectsMixedPageAndEntityEndpoints() {
        final UUID page = node( "APage", "article" );      // page-like: not in ENTITY_CLASS_SET
        final UUID entity = node( "AConcept", "concept" );  // entity-like

        final KgEdge result = edges.upsertEdge( page, entity, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        assertNull( result, "a mixed page/entity edge must be rejected" );
        assertEquals( 0L, edges.countEdges() );
    }

    @Test
    void upsertEdgeWithProvenanceRejectsMixedEndpointsToo() {
        final UUID page = node( "BPage", "hub" );
        final UUID entity = node( "BConcept", "technology" );

        edges.upsertEdgeWithProvenance( page, entity, "related_to", Provenance.AI_INFERRED, Map.of(),
                "machine", null );

        assertEquals( 0L, edges.countEdges() );
    }

    @Test
    void upsertEdgeAllowsHomogeneousEntityEndpoints() {
        final UUID a = node( "ConceptA", "concept" );
        final UUID b = node( "ConceptB", "technology" );

        final KgEdge result = edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        assertNotNull( result );
        assertEquals( 1L, edges.countEdges() );
    }

    @Test
    void upsertEdgeAllowsHomogeneousPageEndpoints() {
        final UUID a = node( "PageA", "article" );
        final UUID b = node( "PageB", "hub" );

        final KgEdge result = edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        assertNotNull( result );
        assertEquals( 1L, edges.countEdges() );
    }

    // ------------------------------------------------------------------ CHECK / FK translation

    @Test
    void upsertEdgeTranslatesRelationshipTypeCheckViolation() {
        final UUID a = node( "X1", "concept" );
        final UUID b = node( "X2", "concept" );

        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.upsertEdge( a, b, "not_a_real_relationship", Provenance.HUMAN_CURATED, Map.of() ) );

        assertTrue( ex.getMessage().contains( "not in the closed vocabulary" ), ex.getMessage() );
        assertTrue( ex.getMessage().contains( "Allowed:" ), ex.getMessage() );
    }

    @Test
    void upsertEdgeTranslatesCheckViolationWithoutSuggestionsForBlankType() {
        final UUID a = node( "Blank1", "concept" );
        final UUID b = node( "Blank2", "concept" );

        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.upsertEdge( a, b, "", Provenance.HUMAN_CURATED, Map.of() ) );

        assertTrue( ex.getMessage().contains( "not in the closed vocabulary" ), ex.getMessage() );
        assertFalse( ex.getMessage().contains( "Did you mean" ), ex.getMessage() );
    }

    @Test
    void upsertEdgeWithProvenanceTranslatesRelationshipTypeCheckViolation() {
        final UUID a = node( "X3", "concept" );
        final UUID b = node( "X4", "concept" );

        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.upsertEdgeWithProvenance( a, b, "bogus_relationship", Provenance.AI_INFERRED,
                        Map.of(), "machine", null ) );

        assertTrue( ex.getMessage().contains( "not in the closed vocabulary" ), ex.getMessage() );
    }

    @Test
    void upsertEdgeTranslatesMissingSourceForeignKey() {
        final UUID missingSource = UUID.randomUUID();
        final UUID target = node( "RealTarget", "concept" );

        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.upsertEdge( missingSource, target, "related_to", Provenance.HUMAN_CURATED, Map.of() ) );

        assertTrue( ex.getMessage().contains( "Source node not found" ), ex.getMessage() );
        assertTrue( ex.getMessage().contains( missingSource.toString() ), ex.getMessage() );
    }

    @Test
    void upsertEdgeTranslatesMissingTargetForeignKey() {
        final UUID source = node( "RealSource", "concept" );
        final UUID missingTarget = UUID.randomUUID();

        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> edges.upsertEdge( source, missingTarget, "related_to", Provenance.HUMAN_CURATED, Map.of() ) );

        assertTrue( ex.getMessage().contains( "Target node not found" ), ex.getMessage() );
    }

    // ------------------------------------------------------------------ tier / distinct / count

    @Test
    void getAllEdgesByTierFiltersOutMachineTierWhenHumanRequested() {
        final UUID a = node( "T1", "concept" );
        final UUID b = node( "T2", "concept" );
        final UUID c = node( "T3", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() ); // tier=human
        edges.upsertEdgeWithProvenance( b, c, "requires", Provenance.AI_INFERRED, Map.of(), "machine", null );

        final List< KgEdge > humanOnly = edges.getAllEdges( Tier.HUMAN );
        assertEquals( 1, humanOnly.size() );
        assertEquals( "related_to", humanOnly.get( 0 ).relationshipType() );

        final List< KgEdge > machineAndHuman = edges.getAllEdges( Tier.MACHINE );
        assertEquals( 2, machineAndHuman.size() );
    }

    @Test
    void getDistinctRelationshipTypesReturnsSortedUniqueValues() {
        final UUID a = node( "D1", "concept" );
        final UUID b = node( "D2", "concept" );
        final UUID c = node( "D3", "concept" );
        edges.upsertEdge( a, b, "requires", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( b, c, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( a, c, "requires", Provenance.HUMAN_CURATED, Map.of() );

        assertEquals( List.of( "related_to", "requires" ), edges.getDistinctRelationshipTypes() );
    }

    @Test
    void getAllEdgesReturnsEverythingOrderedById() {
        final UUID a = node( "G1", "concept" );
        final UUID b = node( "G2", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        assertEquals( 1, edges.getAllEdges().size() );
    }

    // ------------------------------------------------------------------ direction-scoped lookups

    @Test
    void getEdgesForNodeHonoursDirection() {
        final UUID a = node( "N1", "concept" );
        final UUID b = node( "N2", "concept" );
        final UUID c = node( "N3", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() ); // a -> b
        edges.upsertEdge( c, a, "requires", Provenance.HUMAN_CURATED, Map.of() );   // c -> a

        assertEquals( 1, edges.getEdgesForNode( a, "outbound" ).size() );
        assertEquals( 1, edges.getEdgesForNode( a, "inbound" ).size() );
        assertEquals( 2, edges.getEdgesForNode( a, "both" ).size() );
        assertTrue( edges.getEdgesForNode( b, "outbound" ).isEmpty() );
    }

    @Test
    void getEdgesForNodeRejectsInvalidDirection() {
        final UUID a = node( "N4", "concept" );
        assertThrows( IllegalArgumentException.class, () -> edges.getEdgesForNode( a, "sideways" ) );
    }

    // ------------------------------------------------------------------ diffAndRemoveStaleEdges

    @Test
    void diffAndRemoveStaleEdgesDeletesOnlyStaleHumanAuthoredEdges() {
        final UUID source = node( "Diff1", "concept" );
        final UUID keep = node( "KeepMe", "concept" );
        final UUID stale = node( "StaleTarget", "concept" );
        final UUID machineTarget = node( "MachineTarget", "concept" );

        edges.upsertEdge( source, keep, "related_to", Provenance.HUMAN_AUTHORED, Map.of() );
        edges.upsertEdge( source, stale, "related_to", Provenance.HUMAN_AUTHORED, Map.of() );
        edges.upsertEdgeWithProvenance( source, machineTarget, "related_to", Provenance.AI_INFERRED,
                Map.of(), "machine", null );

        // currentEdges only names "KeepMe" as still current — StaleTarget must be removed,
        // but the AI_INFERRED edge to MachineTarget must survive regardless (not human-authored).
        edges.diffAndRemoveStaleEdges( source, Set.of( Map.entry( "KeepMe", "related_to" ) ) );

        final List< KgEdge > remaining = edges.getEdgesForNode( source, "outbound" );
        assertEquals( 2, remaining.size() );
        assertTrue( remaining.stream().anyMatch( e -> e.targetId().equals( keep ) ) );
        assertTrue( remaining.stream().anyMatch( e -> e.targetId().equals( machineTarget ) ) );
        assertFalse( remaining.stream().anyMatch( e -> e.targetId().equals( stale ) ) );
    }

    // ------------------------------------------------------------------ queryEdgesWithNames / endpointKind

    @Test
    void queryEdgesWithNamesPlainOverloadDelegatesWithNullEndpointKind() {
        final UUID a = node( "Q1", "concept" );
        final UUID b = node( "Q2", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        final List< Map< String, Object > > rows = edges.queryEdgesWithNames( null, null, 50, 0 );
        assertEquals( 1, rows.size() );
        assertEquals( "Q1", rows.get( 0 ).get( "source_name" ) );
        assertEquals( "Q2", rows.get( 0 ).get( "target_name" ) );
        assertNotNull( rows.get( 0 ).get( "created" ) );
    }

    @Test
    void queryEdgesWithNamesFiltersByPageEndpointKind() {
        final UUID p1 = node( "PageOne", "article" );
        final UUID p2 = node( "PageTwo", "hub" );
        final UUID c1 = node( "ConceptOne", "concept" );
        final UUID c2 = node( "ConceptTwo", "concept" );
        edges.upsertEdge( p1, p2, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( c1, c2, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        final List< Map< String, Object > > pageOnly = edges.queryEdgesWithNames( null, null, "page", 50, 0 );
        assertEquals( 1, pageOnly.size() );
        assertEquals( "PageOne", pageOnly.get( 0 ).get( "source_name" ) );

        final List< Map< String, Object > > entityOnly = edges.queryEdgesWithNames( null, null, "entity", 50, 0 );
        assertEquals( 1, entityOnly.size() );
        assertEquals( "ConceptOne", entityOnly.get( 0 ).get( "source_name" ) );

        // An unrecognised endpoint kind falls through to the default (no filter) branch.
        final List< Map< String, Object > > unknownKind = edges.queryEdgesWithNames( null, null, "bogus", 50, 0 );
        assertEquals( 2, unknownKind.size() );
    }

    @Test
    void countEdgesWithFilterSingleArgDelegatesToNullEndpointKind() {
        final UUID a = node( "CF1", "concept" );
        final UUID b = node( "CF2", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        assertEquals( 1L, edges.countEdgesWithFilter( "related_to", null ) );
    }

    // ------------------------------------------------------------------ elevateToHumanCurated / findById

    @Test
    void elevateToHumanCuratedReturnsNullForNullId() {
        assertNull( edges.elevateToHumanCurated( null ) );
    }

    @Test
    void elevateToHumanCuratedReturnsNullWhenIdNotFound() {
        assertNull( edges.elevateToHumanCurated( UUID.randomUUID() ) );
    }

    @Test
    void elevateToHumanCuratedSetsTierAndProvenance() {
        final UUID a = node( "E1", "concept" );
        final UUID b = node( "E2", "concept" );
        edges.upsertEdgeWithProvenance( a, b, "related_to", Provenance.AI_INFERRED, Map.of(), "machine", null );
        final KgEdge before = edges.getAllEdges().get( 0 );
        assertEquals( "machine", before.tier() );

        final KgEdge after = edges.elevateToHumanCurated( before.id() );

        assertNotNull( after );
        assertEquals( "human", after.tier() );
        assertEquals( Provenance.HUMAN_CURATED, after.provenance() );
    }

    @Test
    void findByIdReturnsNullWhenMissing() {
        assertNull( edges.findById( UUID.randomUUID() ) );
    }

    // ------------------------------------------------------------------ bulkDeleteByFilter / findDistinctSourceIdsByFilter

    @Test
    void bulkDeleteByFilterRemovesMatchingEdgesOnly() {
        final UUID a = node( "BD1", "concept" );
        final UUID b = node( "BD2", "concept" );
        final UUID c = node( "BD3", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( a, c, "requires", Provenance.HUMAN_CURATED, Map.of() );

        final int deleted = edges.bulkDeleteByFilter( "related_to", null );

        assertEquals( 1, deleted );
        assertEquals( 1L, edges.countEdges() );
        assertEquals( List.of( "requires" ), edges.getDistinctRelationshipTypes() );
    }

    @Test
    void bulkDeleteByFilterSingleArgOverloadDelegates() {
        final UUID a = node( "BD4", "concept" );
        final UUID b = node( "BD5", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        assertEquals( 1, edges.bulkDeleteByFilter( "related_to", null ) );
    }

    @Test
    void findDistinctSourceIdsByFilterReturnsUniqueSources() {
        final UUID a = node( "FS1", "concept" );
        final UUID b = node( "FS2", "concept" );
        final UUID c = node( "FS3", "concept" );
        edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.upsertEdge( a, c, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        final List< UUID > sourceIds = edges.findDistinctSourceIdsByFilter( "related_to", null, null );

        assertEquals( List.of( a ), sourceIds );
    }

    // ------------------------------------------------------------------ deleteEdgeAndRecordRejection

    @Test
    void deleteEdgeAndRecordRejectionDeletesEdgeAndInsertsRejection() throws Exception {
        final UUID a = node( "R1", "concept" );
        final UUID b = node( "R2", "concept" );
        final KgEdge edge = edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );

        edges.deleteEdgeAndRecordRejection( edge.id(), "tester", "not relevant" );

        assertNull( edges.findById( edge.id() ) );
        try ( final Connection conn = dataSource.getConnection() ) {
            final var rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM kg_rejections WHERE proposed_source = 'R1' AND proposed_target = 'R2'" );
            rs.next();
            assertEquals( 1, rs.getInt( 1 ) );
        }
    }

    @Test
    void deleteEdgeAndRecordRejectionThrowsWhenEdgeMissing() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
                () -> edges.deleteEdgeAndRecordRejection( UUID.randomUUID(), "tester", "why" ) );
        assertTrue( ex.getMessage().contains( "Edge not found" ) );
    }

    @Test
    void deleteEdgeAndRecordRejectionIsIdempotentOnDuplicateTriple() throws Exception {
        final UUID a = node( "R3", "concept" );
        final UUID b = node( "R4", "concept" );
        final KgEdge first = edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        edges.deleteEdgeAndRecordRejection( first.id(), "tester", "first" );

        // Re-create the same edge and reject it again — the ON CONFLICT DO NOTHING must not throw.
        final KgEdge second = edges.upsertEdge( a, b, "related_to", Provenance.HUMAN_CURATED, Map.of() );
        assertDoesNotThrow( () -> edges.deleteEdgeAndRecordRejection( second.id(), "tester", "second" ) );

        try ( final Connection conn = dataSource.getConnection() ) {
            final var rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM kg_rejections WHERE proposed_source = 'R3' AND proposed_target = 'R4'" );
            rs.next();
            assertEquals( 1, rs.getInt( 1 ), "duplicate rejection triple must not create a second row" );
        }
    }

    // ------------------------------------------------------------------ findEdgesByProvenance / deleteEdgesByProvenance

    @Test
    void findAndDeleteEdgesByProvenanceRoundTrip() {
        final UUID a = node( "P1", "concept" );
        final UUID b = node( "P2", "concept" );
        final UUID proposalId = UUID.randomUUID();
        edges.upsertEdgeWithProvenance( a, b, "related_to", Provenance.AI_INFERRED, Map.of(),
                "machine", proposalId );

        assertEquals( 1, edges.findEdgesByProvenance( proposalId ).size() );

        final int deleted = edges.deleteEdgesByProvenance( proposalId );

        assertEquals( 1, deleted );
        assertTrue( edges.findEdgesByProvenance( proposalId ).isEmpty() );
    }
}
