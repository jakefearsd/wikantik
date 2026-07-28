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

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the degree-0 ("orphaned") node queries on {@link KgNodeRepository} —
 * the storage behind the {@code list_orphaned_kg_nodes} admin MCP tool and the
 * curator's orphan burn-down view.
 *
 * <p>The subtle part is the {@code source_page_excluded} filter: it LEFT JOINs
 * {@code kg_excluded_pages}, and stub nodes (no {@code source_page}) must remain
 * visible from <em>both</em> filter directions because they cannot be classified.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class KgNodeRepositoryOrphanedNodesTest {

    private static DataSource dataSource;
    private KgNodeRepository nodes;
    private KgEdgeRepository edges;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestContainer.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
            conn.createStatement().execute( "DELETE FROM kg_excluded_pages" );
        }
        nodes = new KgNodeRepository( dataSource );
        edges = new KgEdgeRepository( dataSource );
    }

    // ------------------------------------------------------------------ helpers

    private UUID node( final String name, final String type, final String sourcePage ) {
        return nodes.upsertNode( name, type, sourcePage, Provenance.HUMAN_AUTHORED, Map.of() ).id();
    }

    private void excludePage( final String pageName ) throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute(
                    "INSERT INTO kg_excluded_pages ( page_name, reason ) VALUES ( '"
                            + pageName + "', 'cluster_policy' )" );
        }
    }

    private static List< String > names( final List< KgNode > result ) {
        return result.stream().map( KgNode::name ).toList();
    }

    // ------------------------------------------------------------------ basic orphan detection

    @Test
    void aNodeWithNoEdgesIsOrphaned() {
        node( "Lonely", "concept", "PageA" );

        assertEquals( List.of( "Lonely" ), names( nodes.listOrphanedNodes( null, 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( null ) );
    }

    @Test
    void aNodeWithAnOutboundEdgeIsNotOrphaned() {
        final UUID a = node( "Source", "concept", "PageA" );
        final UUID b = node( "Target", "concept", "PageB" );
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );

        assertEquals( 0L, nodes.countOrphanedNodes( null ) );
        assertTrue( nodes.listOrphanedNodes( null, 100, 0 ).isEmpty() );
    }

    @Test
    void anInboundEdgeAloneAlsoDisqualifiesANode() {
        final UUID a = node( "Source", "concept", "PageA" );
        final UUID b = node( "Target", "concept", "PageB" );
        final UUID c = node( "Untouched", "concept", "PageC" );
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );

        // Only the node on neither end of an edge is orphaned.
        assertEquals( List.of( "Untouched" ), names( nodes.listOrphanedNodes( null, 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( null ) );
        assertFalse( names( nodes.listOrphanedNodes( null, 100, 0 ) ).contains( "Target" ) );
        assertEquals( 3, countAll(), "sanity: all three nodes exist" );
        assertTrue( c != null );
    }

    private int countAll() {
        return nodes.getAllNodes().size();
    }

    // ------------------------------------------------------------------ ordering + paging

    @Test
    void orphansAreOrderedByNameAndPaged() {
        node( "Charlie", "concept", "P" );
        node( "Alpha",   "concept", "P" );
        node( "Bravo",   "concept", "P" );

        assertEquals( List.of( "Alpha", "Bravo", "Charlie" ),
                names( nodes.listOrphanedNodes( null, 100, 0 ) ) );
        assertEquals( List.of( "Alpha", "Bravo" ), names( nodes.listOrphanedNodes( null, 2, 0 ) ) );
        assertEquals( List.of( "Charlie" ),        names( nodes.listOrphanedNodes( null, 2, 2 ) ) );
        assertTrue( nodes.listOrphanedNodes( null, 2, 99 ).isEmpty() );
    }

    @Test
    void countIgnoresLimitAndOffset() {
        node( "Alpha", "concept", "P" );
        node( "Bravo", "concept", "P" );
        node( "Charlie", "concept", "P" );

        assertEquals( 1, nodes.listOrphanedNodes( null, 1, 0 ).size() );
        assertEquals( 3L, nodes.countOrphanedNodes( null ) );
    }

    @Test
    void anEmptyGraphCountsZeroOrphans() {
        assertEquals( 0L, nodes.countOrphanedNodes( null ) );
        assertTrue( nodes.listOrphanedNodes( Map.of(), 100, 0 ).isEmpty() );
    }

    // ------------------------------------------------------------------ filters

    @Test
    void nodeTypeFilterNarrowsTheResult() {
        node( "APerson", "person",  "P" );
        node( "AConcept", "concept", "P" );

        assertEquals( List.of( "APerson" ),
                names( nodes.listOrphanedNodes( Map.of( "node_type", "person" ), 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( Map.of( "node_type", "person" ) ) );
    }

    @Test
    void sourcePageFilterNarrowsTheResult() {
        node( "FromA", "concept", "PageA" );
        node( "FromB", "concept", "PageB" );

        assertEquals( List.of( "FromA" ),
                names( nodes.listOrphanedNodes( Map.of( "source_page", "PageA" ), 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( Map.of( "source_page", "PageA" ) ) );
    }

    @Test
    void filtersCombineConjunctively() {
        node( "Match",       "person",  "PageA" );
        node( "WrongType",   "concept", "PageA" );
        node( "WrongPage",   "person",  "PageB" );

        final Map< String, Object > filters = Map.of( "node_type", "person", "source_page", "PageA" );
        assertEquals( List.of( "Match" ), names( nodes.listOrphanedNodes( filters, 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( filters ) );
    }

    @Test
    void unknownFilterKeysAreIgnored() {
        node( "Lonely", "concept", "PageA" );

        assertEquals( 1L, nodes.countOrphanedNodes( Map.of( "not_a_column", "whatever" ) ) );
    }

    // ------------------------------------------------------------------ source_page_excluded

    @Test
    void excludedTrueReturnsNodesFromExcludedPages() throws Exception {
        node( "FromBanned", "concept", "BannedPage" );
        node( "FromOk",     "concept", "OkPage" );
        excludePage( "BannedPage" );   // page excluded after extraction — how orphans arise

        assertEquals( List.of( "FromBanned" ),
                names( nodes.listOrphanedNodes( Map.of( "source_page_excluded", true ), 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( Map.of( "source_page_excluded", true ) ) );
    }

    @Test
    void excludedFalseReturnsNodesFromIncludedPages() throws Exception {
        node( "FromBanned", "concept", "BannedPage" );
        node( "FromOk",     "concept", "OkPage" );
        excludePage( "BannedPage" );

        assertEquals( List.of( "FromOk" ),
                names( nodes.listOrphanedNodes( Map.of( "source_page_excluded", false ), 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( Map.of( "source_page_excluded", false ) ) );
    }

    @Test
    void stubNodesWithoutASourcePageSurviveBothFilterDirections() throws Exception {
        node( "Stub", "concept", null );
        excludePage( "BannedPage" );

        assertTrue( names( nodes.listOrphanedNodes( Map.of( "source_page_excluded", true ), 100, 0 ) )
                        .contains( "Stub" ),
                "a stub cannot be classified as included, so excluded=true must keep it" );
        assertTrue( names( nodes.listOrphanedNodes( Map.of( "source_page_excluded", false ), 100, 0 ) )
                        .contains( "Stub" ),
                "a stub cannot be classified as excluded either, so excluded=false must keep it too" );
    }

    @Test
    void nonBooleanExcludedFilterIsIgnoredRatherThanMisapplied() throws Exception {
        node( "FromBanned", "concept", "BannedPage" );
        node( "FromOk",     "concept", "OkPage" );
        excludePage( "BannedPage" );

        // The filter is only honoured when it is an actual Boolean.
        assertEquals( 2L, nodes.countOrphanedNodes( Map.of( "source_page_excluded", "true" ) ) );
    }

    @Test
    void excludedFilterCombinesWithNodeType() throws Exception {
        node( "BannedPerson",  "person",  "BannedPage" );
        node( "BannedConcept", "concept", "BannedPage" );
        excludePage( "BannedPage" );

        final Map< String, Object > filters =
                Map.of( "source_page_excluded", true, "node_type", "person" );
        assertEquals( List.of( "BannedPerson" ), names( nodes.listOrphanedNodes( filters, 100, 0 ) ) );
        assertEquals( 1L, nodes.countOrphanedNodes( filters ) );
    }

    @Test
    void deletingTheOnlyEdgeTurnsBothEndpointsIntoOrphans() {
        final UUID a = node( "Alpha", "concept", "P" );
        final UUID b = node( "Bravo", "concept", "P" );
        edges.upsertEdge( a, b, "related", Provenance.HUMAN_CURATED, Map.of() );
        assertEquals( 0L, nodes.countOrphanedNodes( null ) );

        edges.getAllEdges().forEach( e -> edges.deleteEdge( e.id() ) );

        assertEquals( 2L, nodes.countOrphanedNodes( null ) );
    }
}
