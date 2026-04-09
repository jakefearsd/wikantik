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
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubDiscoveryServiceTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private ContentEmbeddingRepository contentRepo;
    private HubDiscoveryRepository discoveryRepo;
    private TfidfModel model;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM hub_discovery_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_proposals" );
            conn.createStatement().execute( "DELETE FROM hub_centroids" );
            conn.createStatement().execute( "DELETE FROM kg_content_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        contentRepo = new ContentEmbeddingRepository( dataSource );
        discoveryRepo = new HubDiscoveryRepository( dataSource );
    }

    @Test
    void generateClusterProposals_findsClusterOfNonMembers() {
        // Existing TechHub with 2 members (Java, Python).
        final var techHub = kgRepo.upsertNode( "TechHub", "hub", "TechHub",
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        final var java = kgRepo.upsertNode( "Java", "article", "Java",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final var python = kgRepo.upsertNode( "Python", "article", "Python",
            Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), java.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), python.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        // Non-members: 3 cooking pages forming a tight cluster, 3 sports pages forming another,
        // and one standalone outlier.
        final String[] pages = {
            "Baking", "Roasting", "Grilling",
            "Soccer", "Basketball", "Tennis",
            "Miscellaneous"
        };
        for ( final String name : pages ) {
            kgRepo.upsertNode( name, "article", name, Provenance.HUMAN_AUTHORED, Map.of() );
        }
        model = new TfidfModel();
        model.build(
            List.of( "Java", "Python", "Baking", "Roasting", "Grilling",
                     "Soccer", "Basketball", "Tennis", "Miscellaneous", "TechHub" ),
            List.of(
                // Hub members — programming (kept so they are in the model but excluded from candidates)
                "Java programming language JVM bytecode compile run execute runtime",
                "Python programming language script execute runtime bytecode dynamic",
                // Cooking cluster — lots of shared cooking vocabulary, no sports words
                "baking bread cake flour sugar butter oven recipe dough knead bake baking",
                "roasting roast oven meat chicken beef pork temperature seasoning oven baking",
                "grilling grill barbecue charcoal meat chicken beef outdoor fire baking roasting",
                // Sports cluster — lots of shared sports vocabulary, no cooking words
                "soccer football pitch goal player team league kick score match field stadium",
                "basketball hoop court player team league score dribble slam match field stadium",
                "tennis court racquet player serve volley match score grand slam stadium league",
                // Outlier — no shared vocabulary with any cluster
                "quantum xylophone abstract topology divergent perpendicular",
                // Hub node
                "Technology hub programming languages software development"
            )
        );
        contentRepo.saveEmbeddings( 1, model, Map.of() );

        final HubDiscoveryService service = HubDiscoveryService.builder()
            .kgRepo( kgRepo )
            .discoveryRepo( discoveryRepo )
            .contentRepo( contentRepo )
            .minClusterSize( 3 )
            .minPts( 3 )
            .minCandidatePool( 5 )
            .contentModel( model )
            .build();

        final int created = service.generateClusterProposals();
        assertTrue( created >= 2, "Expected at least 2 discovered clusters, got " + created );

        final var all = discoveryRepo.list( 50, 0 );
        assertEquals( created, all.size() );

        // No hub-member page (Java, Python) should appear in any proposal.
        for ( final var prop : all ) {
            assertFalse( prop.memberPages().contains( "Java" ),
                "Existing hub member 'Java' should not be proposed" );
            assertFalse( prop.memberPages().contains( "Python" ),
                "Existing hub member 'Python' should not be proposed" );
        }

        // At least one proposal should contain the cooking triad as a coherent cluster.
        final boolean cookingFound = all.stream().anyMatch( p ->
            p.memberPages().containsAll( List.of( "Baking", "Roasting", "Grilling" ) ) );
        assertTrue( cookingFound, "Expected a cluster containing Baking, Roasting, Grilling" );
    }
}
