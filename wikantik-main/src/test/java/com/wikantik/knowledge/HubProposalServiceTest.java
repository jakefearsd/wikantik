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
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubProposalServiceTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private ContentEmbeddingRepository contentRepo;
    private EmbeddingRepository embeddingRepo;
    private HubProposalRepository proposalRepo;
    private HubProposalService service;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
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
        embeddingRepo = new EmbeddingRepository( dataSource );
        proposalRepo = new HubProposalRepository( dataSource );
    }

    @Test
    void generateProposals_createsProposalsAboveThreshold() {
        // Create a Hub node with 3 members. Production stores membership as kg_edges
        // of type 'related', not as a node property — see GraphProjector +
        // FrontmatterRelationshipDetector. Mirror that data path here.
        final var techHub = kgRepo.upsertNode( "TechHub", "hub", "TechHub", Provenance.HUMAN_AUTHORED,
            Map.of( "type", "hub" ) );
        final var java = kgRepo.upsertNode( "Java", "article", "Java", Provenance.HUMAN_AUTHORED, Map.of() );
        final var python = kgRepo.upsertNode( "Python", "article", "Python", Provenance.HUMAN_AUTHORED, Map.of() );
        final var kotlin = kgRepo.upsertNode( "Kotlin", "article", "Kotlin", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Rust", "article", "Rust", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Cooking", "article", "Cooking", Provenance.HUMAN_AUTHORED, Map.of() );

        kgRepo.upsertEdge( techHub.id(), java.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), python.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), kotlin.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        // Train a content model with similar docs for programming languages
        final var embService = new EmbeddingService( kgRepo, embeddingRepo, contentRepo, null, null );
        final TfidfModel model = new TfidfModel();
        model.build(
            List.of( "Java", "Python", "Kotlin", "Rust", "Cooking", "TechHub" ),
            List.of(
                "Java programming language object oriented JVM bytecode",
                "Python programming language dynamic typing scripting",
                "Kotlin programming language JVM coroutines android",
                "Rust programming language memory safety systems",
                "Cooking recipes food baking kitchen ingredients",
                "Technology hub programming languages software development"
            )
        );
        contentRepo.saveEmbeddings( 1, model, Map.of() );

        // With only 2 candidates (Rust and Cooking), Rust scores at the 50th percentile;
        // use a threshold of 49 so it passes
        service = new HubProposalService( kgRepo, proposalRepo, contentRepo, 49, model );
        service.generateProposals();

        // Should have at least one proposal (Rust is similar to programming Hubs)
        final List< HubProposalRepository.HubProposal > proposals =
            proposalRepo.listProposals( "pending", null, 50, 0 );
        assertFalse( proposals.isEmpty(), "Should generate at least one proposal" );
        // Existing members should not be proposed
        for ( final var p : proposals ) {
            assertFalse( List.of( "Java", "Python", "Kotlin" ).contains( p.pageName() ),
                "Should not propose existing members" );
        }
    }

    @Test
    void generateProposals_skipsRejectedPairs() {
        final var techHub = kgRepo.upsertNode( "TechHub", "hub", "TechHub", Provenance.HUMAN_AUTHORED,
            Map.of( "type", "hub" ) );
        final var java = kgRepo.upsertNode( "Java", "article", "Java", Provenance.HUMAN_AUTHORED, Map.of() );
        final var python = kgRepo.upsertNode( "Python", "article", "Python", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Kotlin", "article", "Kotlin", Provenance.HUMAN_AUTHORED, Map.of() );

        kgRepo.upsertEdge( techHub.id(), java.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), python.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        final TfidfModel model = new TfidfModel();
        model.build(
            List.of( "Java", "Python", "Kotlin", "TechHub" ),
            List.of( "Java programming", "Python programming", "Kotlin programming", "Tech hub" )
        );
        contentRepo.saveEmbeddings( 1, model, Map.of() );

        // Reject Kotlin for TechHub
        proposalRepo.insertProposal( "TechHub", "Kotlin", 0.9, 95.0 );
        final var pending = proposalRepo.listProposals( "pending", null, 50, 0 );
        proposalRepo.updateStatus( pending.get( 0 ).id(), "rejected", "admin", "Not relevant" );

        service = new HubProposalService( kgRepo, proposalRepo, contentRepo, 0, model );
        service.generateProposals();

        // Should not have a new pending proposal for Kotlin
        final var newPending = proposalRepo.listProposals( "pending", null, 50, 0 );
        for ( final var p : newPending ) {
            assertFalse( "Kotlin".equals( p.pageName() ) && "TechHub".equals( p.hubName() ),
                "Should not re-propose rejected pair" );
        }
    }
}
