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
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Code-level guard that rejects edges whose endpoints straddle the page/entity
 * boundary. The Knowledge Graph stores both wiki-page nodes (node_type != 'concept')
 * and LLM-extracted concept nodes (node_type = 'concept'); edges crossing the two
 * carry no demonstrated downstream utility and were almost entirely generic
 * {@code related_to} noise from frontmatter resolvers picking the wrong node when
 * both kinds shared a name.
 */
@Testcontainers( disabledWithoutDocker = true )
class KgEdgeMixedEndpointGuardTest {

    private static DataSource dataSource;
    private KgEdgeRepository edges;
    private KgNodeRepository nodes;

    @BeforeAll
    static void initDataSource() { dataSource = PostgresTestContainer.createDataSource(); }

    @BeforeEach
    void setUp() throws Exception {
        try ( Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        edges = new KgEdgeRepository( dataSource );
        nodes = new KgNodeRepository( dataSource );
    }

    @Test
    void upsertEdge_acceptsPageToPage() {
        final UUID a = nodes.upsertNode( "PageA", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "PageB", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();

        final KgEdge e = edges.upsertEdge( a, b, "related_to",
                Provenance.HUMAN_CURATED, Map.of() );

        assertNotNull( e );
        assertEquals( "related_to", e.relationshipType() );
    }

    @Test
    void upsertEdge_acceptsConceptToConcept() {
        final UUID a = nodes.upsertNode( "ConceptA", "concept", "OriginPage",
                Provenance.AI_INFERRED, Map.of() ).id();
        final UUID b = nodes.upsertNode( "ConceptB", "concept", "OriginPage",
                Provenance.AI_INFERRED, Map.of() ).id();

        final KgEdge e = edges.upsertEdge( a, b, "related_to",
                Provenance.HUMAN_CURATED, Map.of() );

        assertNotNull( e );
    }

    @Test
    void upsertEdge_rejectsMixedPageToConcept() {
        final UUID page = nodes.upsertNode( "MixedPage", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID concept = nodes.upsertNode( "MixedConcept", "concept", "MixedPage",
                Provenance.AI_INFERRED, Map.of() ).id();

        final KgEdge e = edges.upsertEdge( page, concept, "related_to",
                Provenance.HUMAN_CURATED, Map.of() );

        // Guard fires: returns null, no row inserted.
        assertNull( e );
        assertEquals( 0L, edges.countEdgesWithFilter( null, null ) );
    }

    @Test
    void upsertEdge_rejectsMixedConceptToPage() {
        final UUID page = nodes.upsertNode( "MixedPage2", "hub", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID concept = nodes.upsertNode( "MixedConcept2", "concept", "MixedPage2",
                Provenance.AI_INFERRED, Map.of() ).id();

        final KgEdge e = edges.upsertEdge( concept, page, "part_of",
                Provenance.HUMAN_CURATED, Map.of() );

        assertNull( e );
        assertEquals( 0L, edges.countEdgesWithFilter( null, null ) );
    }

    @Test
    void upsertEdgeWithProvenance_rejectsMixed() {
        final UUID page = nodes.upsertNode( "ProvPage", "article", null,
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID concept = nodes.upsertNode( "ProvConcept", "concept", "ProvPage",
                Provenance.AI_INFERRED, Map.of() ).id();

        // Use the materialization-style write path (machine tier, with proposal id).
        edges.upsertEdgeWithProvenance( page, concept, "related_to",
                Provenance.AI_INFERRED, Map.of(),
                "machine", UUID.randomUUID() );

        assertEquals( 0L, edges.countEdgesWithFilter( null, null ),
                "guarded write path must skip the INSERT on mixed endpoints" );
    }
}
