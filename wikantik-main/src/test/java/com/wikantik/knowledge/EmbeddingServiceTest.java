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
import com.wikantik.knowledge.ComplExModel.Prediction;
import com.wikantik.knowledge.EmbeddingService.*;
import com.wikantik.knowledge.TfidfModel.SimilarPagePair;
import com.wikantik.test.StubPageManager;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class EmbeddingServiceTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository kgRepo;
    private EmbeddingRepository embeddingRepo;
    private ContentEmbeddingRepository contentEmbeddingRepo;
    private EmbeddingService service;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        if ( service != null ) {
            service.shutdown();
        }
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_content_embeddings" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        embeddingRepo = new EmbeddingRepository( dataSource );
        contentEmbeddingRepo = new ContentEmbeddingRepository( dataSource );
        service = new EmbeddingService( kgRepo, embeddingRepo, contentEmbeddingRepo, null, null );
    }

    @Test
    void retrainProducesModel() {
        seedGraph();
        assertFalse( service.isReady() );

        service.retrain();

        assertTrue( service.isReady() );
        final var status = service.getStatus();
        assertEquals( 1, status.modelVersion() );
        assertTrue( status.entityCount() >= 6 );
        assertTrue( status.relationCount() >= 1 );
        assertNotNull( status.lastTrained() );
    }

    @Test
    void similarNodesReturnResults() {
        seedGraph();
        service.retrain();

        final List< Prediction > similar = service.getSimilarNodes( "ServiceA", 3 );
        assertFalse( similar.isEmpty() );
        assertTrue( similar.size() <= 3 );
        // Should not include self
        assertTrue( similar.stream().noneMatch( p -> p.entityName().equals( "ServiceA" ) ) );
    }

    @Test
    void predictMissingEdgesExcludesExisting() {
        seedGraph();
        service.retrain();

        final List< EdgePrediction > predictions = service.predictMissingEdges( 10 );
        // All predictions should be for non-existing edges
        for( final EdgePrediction ep : predictions ) {
            assertFalse(
                ep.sourceName().equals( "ServiceA" ) && ep.relationshipType().equals( "depends-on" )
                    && ep.targetName().equals( "DatabaseX" ),
                "Should not predict existing edge ServiceA->depends-on->DatabaseX"
            );
        }
    }

    @Test
    void predictEdgesForNode() {
        seedGraph();
        service.retrain();

        final List< EdgePrediction > predictions = service.predictEdgesForNode( "ServiceC", 5 );
        // ServiceC only depends-on DatabaseX and QueueZ; predictions should suggest other targets
        for( final EdgePrediction ep : predictions ) {
            assertEquals( "ServiceC", ep.sourceName() );
        }
    }

    @Test
    void anomalousEdgesReturnResults() {
        seedGraph();
        service.retrain();

        final List< EdgePrediction > anomalous = service.getAnomalousEdges( 5 );
        // Should return results (at worst, all edges sorted by plausibility)
        assertFalse( anomalous.isEmpty() );
        // Should be in ascending score order (least plausible first)
        for( int i = 1; i < anomalous.size(); i++ ) {
            assertTrue( anomalous.get( i ).score() >= anomalous.get( i - 1 ).score() );
        }
    }

    @Test
    void mergeCandidatesReturnResults() {
        seedGraph();
        service.retrain();

        final List< MergeCandidate > candidates = service.getMergeCandidates( 5, 0.0 );
        assertFalse( candidates.isEmpty() );
        // Should be in descending similarity order
        for( int i = 1; i < candidates.size(); i++ ) {
            assertTrue( candidates.get( i ).similarity() <= candidates.get( i - 1 ).similarity() );
        }
    }

    @Test
    void unknownNodeReturnsEmpty() {
        seedGraph();
        service.retrain();

        assertTrue( service.getSimilarNodes( "NonExistent", 5 ).isEmpty() );
        assertTrue( service.predictEdgesForNode( "NonExistent", 5 ).isEmpty() );
    }

    @Test
    void retrainSkipsWhenTooFewNodes() {
        // Only one node, no edges
        kgRepo.upsertNode( "Alone", "solo", null, Provenance.HUMAN_AUTHORED, Map.of() );
        service.retrain();
        assertFalse( service.isReady() );
    }

    @Test
    void statusReportsWhenNotTrained() {
        final var status = service.getStatus();
        assertEquals( 0, status.modelVersion() );
        assertEquals( 0, status.entityCount() );
        assertNull( status.lastTrained() );
        assertFalse( status.training() );
    }

    @Test
    void retrainContentModelCoversAllPages() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "MachineLearning", "# Machine Learning\nAlgorithms for classification and prediction" );
        pm.savePage( "DeepLearning", "# Deep Learning\nNeural network architectures for ML" );
        pm.savePage( "BakingRecipes", "# Baking\nCake recipes with flour and sugar" );

        final EmbeddingService svcWithPages = new EmbeddingService( kgRepo, embeddingRepo, contentEmbeddingRepo, pm, null );
        svcWithPages.retrainContentModel();

        assertTrue( svcWithPages.isContentReady() );
        final var status = svcWithPages.getStatus();
        assertTrue( status.contentReady() );
        assertEquals( 3, status.contentEntityCount() );
        assertEquals( TfidfModel.DIMENSION, status.contentDimension() );
        assertNotNull( status.contentLastTrained() );
    }

    @Test
    void topSimilarPagePairsReturnsOrderedResults() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "MachineLearning", "Machine learning algorithms for classification and prediction" );
        pm.savePage( "DeepLearning", "Deep learning neural network architectures for machine learning" );
        pm.savePage( "BakingRecipes", "Cake recipes with flour sugar butter and baking powder" );
        pm.savePage( "CookingBasics", "Basic cooking recipes kitchen techniques food preparation" );

        final EmbeddingService svcWithPages = new EmbeddingService( kgRepo, embeddingRepo, contentEmbeddingRepo, pm, null );
        svcWithPages.retrainContentModel();

        final List< SimilarPagePair > pairs = svcWithPages.getTopSimilarPagePairs( 3 );
        assertFalse( pairs.isEmpty() );
        assertTrue( pairs.size() <= 3 );
        // Descending by score
        for( int i = 1; i < pairs.size(); i++ ) {
            assertTrue( pairs.get( i ).score() <= pairs.get( i - 1 ).score() );
        }
    }

    @Test
    void topSimilarPagePairsReturnsEmptyWhenNoContentModel() {
        assertTrue( service.getTopSimilarPagePairs( 10 ).isEmpty() );
    }

    @Test
    void retrainContentModelWithNullPageManagerIsNoop() {
        // service was created with pageManager=null
        service.retrainContentModel();
        assertFalse( service.isContentReady() );
    }

    @Test
    void contentSimilarNodesReturnResults() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "ServiceA", "Service A handles user authentication and authorization" );
        pm.savePage( "ServiceB", "Service B processes user requests and authentication" );
        pm.savePage( "DatabaseX", "Database for storing transactional records" );

        final EmbeddingService svcWithPages = new EmbeddingService( kgRepo, embeddingRepo, contentEmbeddingRepo, pm, null );
        svcWithPages.retrainContentModel();

        final List< ContentSimilarity > similar = svcWithPages.getContentSimilarNodes( "ServiceA", 2 );
        assertFalse( similar.isEmpty() );
        assertTrue( similar.size() <= 2 );
    }

    @Test
    void contentSimilarUnknownNodeReturnsEmpty() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "TestPage", "Some content here" );

        final EmbeddingService svcWithPages = new EmbeddingService( kgRepo, embeddingRepo, contentEmbeddingRepo, pm, null );
        svcWithPages.retrainContentModel();
        assertTrue( svcWithPages.getContentSimilarNodes( "NonExistent", 5 ).isEmpty() );
    }

    @Test
    void enhancedMergeCandidatesReturnBothScores() {
        // Need both structural and content models — use pages that match graph nodes
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "ServiceA", "Service A handles authentication" );
        pm.savePage( "ServiceB", "Service B handles authentication" );
        pm.savePage( "ServiceC", "Service C processes messages" );
        pm.savePage( "ServiceD", "Service D processes messages" );
        pm.savePage( "DatabaseX", "PostgreSQL database for storage" );
        pm.savePage( "CacheY", "Redis cache layer" );
        pm.savePage( "QueueZ", "Message queue system" );

        final EmbeddingService svcWithPages = new EmbeddingService( kgRepo, embeddingRepo, contentEmbeddingRepo, pm, null );
        seedGraph();
        svcWithPages.retrain();
        svcWithPages.retrainContentModel();

        final List< EnhancedMergeCandidate > candidates =
            svcWithPages.getMergeCandidatesEnhanced( 5, 0.0 );
        assertFalse( candidates.isEmpty() );
        for( final EnhancedMergeCandidate mc : candidates ) {
            assertEquals( 0.5 * mc.structural() + 0.5 * mc.content(), mc.combined(), 1e-6 );
        }
        // Descending by combined
        for( int i = 1; i < candidates.size(); i++ ) {
            assertTrue( candidates.get( i ).combined() <= candidates.get( i - 1 ).combined() );
        }
    }

    // ---- Helper to populate the graph ----

    private void seedGraph() {
        final var sA = kgRepo.upsertNode( "ServiceA", "service", "ServiceA.md", Provenance.HUMAN_AUTHORED, Map.of() );
        final var sB = kgRepo.upsertNode( "ServiceB", "service", "ServiceB.md", Provenance.HUMAN_AUTHORED, Map.of() );
        final var sC = kgRepo.upsertNode( "ServiceC", "service", "ServiceC.md", Provenance.HUMAN_AUTHORED, Map.of() );
        final var sD = kgRepo.upsertNode( "ServiceD", "service", "ServiceD.md", Provenance.HUMAN_AUTHORED, Map.of() );
        final var dbX = kgRepo.upsertNode( "DatabaseX", "database", "DatabaseX.md", Provenance.HUMAN_AUTHORED, Map.of() );
        final var cY = kgRepo.upsertNode( "CacheY", "cache", "CacheY.md", Provenance.HUMAN_AUTHORED, Map.of() );
        final var qZ = kgRepo.upsertNode( "QueueZ", "queue", "QueueZ.md", Provenance.HUMAN_AUTHORED, Map.of() );

        kgRepo.upsertEdge( sA.id(), dbX.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sB.id(), dbX.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sC.id(), dbX.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sD.id(), dbX.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sA.id(), cY.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sB.id(), cY.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sD.id(), cY.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sA.id(), qZ.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.upsertEdge( sC.id(), qZ.id(), "depends-on", Provenance.HUMAN_AUTHORED, Map.of() );
    }
}
