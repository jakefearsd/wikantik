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

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link KgNodeRepository} surfaces not touched by {@link KgNodeRepositoryBypassTest}
 * (pure SQL-shape assertions) or {@link KgNodeRepositoryOrphanedNodesTest} (orphan-specific
 * queries): the {@code provenanceFilter} branch of {@link KgNodeRepository#queryNodes} /
 * {@link KgNodeRepository#searchNodes}, plain (non-{@code Tier}) {@code searchNodes} overloads,
 * and {@link KgNodeRepository#countNodesWithFilter}, which no existing test calls at all.
 */
@RequiresPostgres
class KgNodeRepositoryTest {

    private static DataSource dataSource;
    private KgNodeRepository nodes;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestDb.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        nodes = new KgNodeRepository( dataSource );
    }

    private KgNode node( final String name, final Provenance provenance ) {
        return nodes.upsertNode( name, "concept", "P", provenance, Map.of() );
    }

    // ------------------------------------------------------------------ queryNodes provenanceFilter

    @Test
    void queryNodesFiltersByProvenanceSet() {
        node( "Authored", Provenance.HUMAN_AUTHORED );
        node( "Curated", Provenance.HUMAN_CURATED );
        node( "Inferred", Provenance.AI_INFERRED );

        final List< KgNode > result = nodes.queryNodes( null,
                Set.of( Provenance.HUMAN_AUTHORED, Provenance.HUMAN_CURATED ), 100, 0 );

        assertEquals( 2, result.size() );
        assertTrue( result.stream().noneMatch( n -> "Inferred".equals( n.name() ) ) );
    }

    @Test
    void countNodesWithFilterMatchesQueryNodesForTheSameFilters() {
        node( "Authored", Provenance.HUMAN_AUTHORED );
        node( "Curated", Provenance.HUMAN_CURATED );

        final Map< String, Object > filters = Map.of( "node_type", "concept" );
        assertEquals( 2L, nodes.countNodesWithFilter( filters, null ) );
        assertEquals( 1L, nodes.countNodesWithFilter( filters,
                Set.of( Provenance.HUMAN_CURATED ) ) );
    }

    @Test
    void countNodesWithFilterOnEmptyTableIsZero() {
        assertEquals( 0L, nodes.countNodesWithFilter( null, null ) );
    }

    @Test
    void countNodesWithFilterHonoursSourcePageAndNameFilters() {
        nodes.upsertNode( "MatchName", "concept", "PageA", Provenance.HUMAN_AUTHORED, Map.of() );
        nodes.upsertNode( "Other", "concept", "PageB", Provenance.HUMAN_AUTHORED, Map.of() );

        assertEquals( 1L, nodes.countNodesWithFilter( Map.of( "source_page", "PageA" ), null ) );
        assertEquals( 1L, nodes.countNodesWithFilter( Map.of( "name", "matchn" ), null ) );
    }

    // ------------------------------------------------------------------ searchNodes overloads

    @Test
    void searchNodesPlainThreeArgOverloadDelegatesWithoutAdminBypass() {
        node( "SearchableThing", Provenance.HUMAN_AUTHORED );

        final List< KgNode > result = nodes.searchNodes( "searchable", null, 10 );

        assertEquals( 1, result.size() );
        assertEquals( "SearchableThing", result.get( 0 ).name() );
    }

    @Test
    void searchNodesFourArgOverloadFiltersByProvenance() {
        node( "FindMeAuthored", Provenance.HUMAN_AUTHORED );
        node( "FindMeInferred", Provenance.AI_INFERRED );

        final List< KgNode > filtered = nodes.searchNodes( "findme",
                Set.of( Provenance.HUMAN_AUTHORED ), 10, false );

        assertEquals( 1, filtered.size() );
        assertEquals( "FindMeAuthored", filtered.get( 0 ).name() );
    }

    @Test
    void searchNodesSearchesPropertiesTextToo() {
        nodes.upsertNode( "PropSearch", "concept", "P", Provenance.HUMAN_AUTHORED,
                Map.of( "note", "uniquePropertyMarker" ) );

        final List< KgNode > result = nodes.searchNodes( "uniquepropertymarker", null, 10 );

        assertEquals( 1, result.size() );
    }
}
