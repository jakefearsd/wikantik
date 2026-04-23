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
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class HubProposalServiceTest {

    private static final String MODEL = "test-model";

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private HubProposalRepository proposalRepo;
    private NodeMentionSimilarity similarity;
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
            conn.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            conn.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        proposalRepo = new HubProposalRepository( dataSource );
        similarity = new NodeMentionSimilarity( dataSource, MODEL );
    }

    // Hand-crafted vectors so programming languages cluster tightly and Cooking is an outlier.
    private static final float[] JAVA    = { 1.0f,  0.0f, 0.02f, 0.0f, 0.0f };
    private static final float[] PYTHON  = { 0.98f, 0.0f, 0.05f, 0.0f, 0.0f };
    private static final float[] KOTLIN  = { 0.97f, 0.0f, 0.08f, 0.0f, 0.0f };
    private static final float[] RUST    = { 0.96f, 0.0f, 0.10f, 0.0f, 0.0f };
    private static final float[] COOKING = { 0.0f,  1.0f, 0.0f,  0.0f, 0.0f };
    private static final float[] HUB_VEC = { 0.97f, 0.0f, 0.05f, 0.0f, 0.0f };

    @Test
    void generateProposals_createsProposalsAboveThreshold() {
        // Hub + 3 member articles, wired by related edges (mirrors GraphProjector).
        final var techHub = kgRepo.upsertNode( "TechHub", "hub", "TechHub",
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        final var java    = kgRepo.upsertNode( "Java",    "article", "Java",    Provenance.HUMAN_AUTHORED, Map.of() );
        final var python  = kgRepo.upsertNode( "Python",  "article", "Python",  Provenance.HUMAN_AUTHORED, Map.of() );
        final var kotlin  = kgRepo.upsertNode( "Kotlin",  "article", "Kotlin",  Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Rust",    "article", "Rust",    Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Cooking", "article", "Cooking", Provenance.HUMAN_AUTHORED, Map.of() );

        kgRepo.upsertEdge( techHub.id(), java.id(),   "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), python.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), kotlin.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Java",    JAVA );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Python",  PYTHON );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Kotlin",  KOTLIN );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Rust",    RUST );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Cooking", COOKING );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "TechHub", HUB_VEC );

        // With only 2 candidates (Rust and Cooking), Rust scores at the 50th percentile;
        // threshold of 49 so Rust passes but Cooking (low similarity) is still eligible
        // to be below the threshold — we verify "at least one proposal generated".
        service = HubProposalService.builder()
            .kgRepo( kgRepo )
            .proposalRepo( proposalRepo )
            .similarity( similarity )
            .reviewPercentile( 49 )
            .build();
        service.generateProposals();

        final List< HubProposalRepository.HubProposal > proposals =
            proposalRepo.listProposals( "pending", null, 50, 0 );
        assertFalse( proposals.isEmpty(), "Should generate at least one proposal" );
        for ( final var p : proposals ) {
            assertFalse( List.of( "Java", "Python", "Kotlin" ).contains( p.pageName() ),
                "Should not propose existing members" );
        }
    }

    @Test
    void generateProposals_skipsRejectedPairs() {
        final var techHub = kgRepo.upsertNode( "TechHub", "hub", "TechHub",
            Provenance.HUMAN_AUTHORED, Map.of( "type", "hub" ) );
        final var java   = kgRepo.upsertNode( "Java",   "article", "Java",   Provenance.HUMAN_AUTHORED, Map.of() );
        final var python = kgRepo.upsertNode( "Python", "article", "Python", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertNode( "Kotlin", "article", "Kotlin", Provenance.HUMAN_AUTHORED, Map.of() );

        kgRepo.upsertEdge( techHub.id(), java.id(),   "related", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( techHub.id(), python.id(), "related", Provenance.HUMAN_AUTHORED, Map.of() );

        MentionFixtures.seedMentionByName( dataSource, MODEL, "Java",    JAVA );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Python",  PYTHON );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "Kotlin",  KOTLIN );
        MentionFixtures.seedMentionByName( dataSource, MODEL, "TechHub", HUB_VEC );

        // Reject Kotlin for TechHub
        proposalRepo.insertProposal( "TechHub", "Kotlin", 0.9, 95.0 );
        final var pending = proposalRepo.listProposals( "pending", null, 50, 0 );
        proposalRepo.updateStatus( pending.get( 0 ).id(), "rejected", "admin", "Not relevant" );

        service = HubProposalService.builder()
            .kgRepo( kgRepo )
            .proposalRepo( proposalRepo )
            .similarity( similarity )
            .reviewPercentile( 0 )
            .build();
        service.generateProposals();

        // Should not have a new pending proposal for Kotlin
        final var newPending = proposalRepo.listProposals( "pending", null, 50, 0 );
        for ( final var p : newPending ) {
            assertFalse( "Kotlin".equals( p.pageName() ) && "TechHub".equals( p.hubName() ),
                "Should not re-propose rejected pair" );
        }
    }
}
