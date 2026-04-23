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
package com.wikantik.knowledge.extraction;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.api.knowledge.ExtractedMention;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.api.knowledge.ProposedEdge;
import com.wikantik.api.knowledge.ProposedNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the extraction pipeline: listener receives chunk ids,
 * the fake extractor emits mentions + proposals, the listener resolves
 * mentions against pre-seeded {@code kg_nodes}, routes edges through
 * rejection checks, and writes to the right tables.
 */
@Testcontainers( disabledWithoutDocker = true )
class AsyncEntityExtractionListenerIT {

    private static DataSource dataSource;

    private JdbcKnowledgeRepository kgRepo;
    private ContentChunkRepository chunkRepo;
    private ChunkEntityMentionRepository mentionRepo;
    private EntityExtractorConfig config;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "TRUNCATE chunk_entity_mentions, content_chunk_embeddings, kg_content_chunks, "
               + "kg_rejections, kg_proposals, kg_edges, kg_nodes CASCADE" ) ) {
            ps.executeUpdate();
        }
        kgRepo = new JdbcKnowledgeRepository( dataSource );
        chunkRepo = new ContentChunkRepository( dataSource );
        mentionRepo = new ChunkEntityMentionRepository( dataSource );

        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        props.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "0.6" );
        props.setProperty( "wikantik.knowledge.extractor.per_page_min_interval_ms", "0" );
        config = EntityExtractorConfig.fromProperties( props );
    }

    @Test
    void writesMentionsForKnownNodesAndFilesProposalsForUnknowns() throws Exception {
        final UUID napoleonId = kgRepo.upsertNode(
            "Napoleon", "Person", "History", Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID chunkId = seedChunk( "HistoryPage", 0, "chunk text" );

        final CapturingExtractor extractor = new CapturingExtractor( r -> new ExtractionResult(
            List.of( new ProposedNode( "Waterloo", "Place", Map.of(), 0.9, "named" ) ),
            List.of( new ProposedEdge( "Napoleon", "Waterloo", "fought_at", Map.of(), 0.85, "" ) ),
            List.of(
                new ExtractedMention( chunkId, "Napoleon", 0.95 ),
                new ExtractedMention( chunkId, "Waterloo", 0.9 ) ),
            "ollama",
            Duration.ofMillis( 5 ) ) );

        try ( AsyncEntityExtractionListener listener = newListener( extractor ) ) {
            listener.accept( List.of( chunkId ) );
            waitForIdle( listener );

            // Napoleon exists → its mention should land in chunk_entity_mentions.
            assertEquals( 1, countMentionsForChunk( chunkId ) );
            assertTrue( hasMention( chunkId, napoleonId ) );

            // Waterloo doesn't exist → no mention row, but a new-node proposal.
            assertEquals( 1, countProposals( "new-node" ) );
            assertEquals( 1, countProposals( "new-edge" ) );
        }
        assertEquals( 1, extractor.callCount() );
    }

    @Test
    void honoursRejectionListForEdgeProposals() throws Exception {
        kgRepo.upsertNode( "Napoleon", "Person", "History", Provenance.HUMAN_AUTHORED, Map.of() );
        kgRepo.insertRejection( "Napoleon", "Waterloo", "fought_at", "admin", "not-relevant" );

        final UUID chunkId = seedChunk( "HistoryPage", 0, "text" );
        final CapturingExtractor extractor = new CapturingExtractor( r -> new ExtractionResult(
            List.of(),
            List.of( new ProposedEdge( "Napoleon", "Waterloo", "fought_at", Map.of(), 0.9, "" ) ),
            List.of(),
            "ollama",
            Duration.ofMillis( 1 ) ) );

        try ( AsyncEntityExtractionListener listener = newListener( extractor ) ) {
            listener.accept( List.of( chunkId ) );
            waitForIdle( listener );

            assertEquals( 0, countProposals( "new-edge" ),
                          "rejected edges must not be re-proposed on subsequent saves" );
        }
    }

    @Test
    void rateLimitsRepeatedSavesOfTheSamePage() throws Exception {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.per_page_min_interval_ms", "60000" );
        p.setProperty( "wikantik.knowledge.extractor.confidence_threshold", "0.6" );
        final EntityExtractorConfig rateLimited = EntityExtractorConfig.fromProperties( p );

        final UUID chunkId = seedChunk( "Bursty", 0, "text" );
        final CapturingExtractor extractor = new CapturingExtractor( r -> ExtractionResult.empty(
            "ollama", Duration.ofMillis( 1 ) ) );

        try ( AsyncEntityExtractionListener listener = newListenerWithConfig( extractor, rateLimited ) ) {
            listener.accept( List.of( chunkId ) );
            listener.accept( List.of( chunkId ) );
            listener.accept( List.of( chunkId ) );
            waitForIdle( listener );
            assertEquals( 1, extractor.callCount(), "same page within rate-limit window is collapsed to one call" );
        }
    }

    @Test
    void skipsWorkWhenExtractorIsDisabled() throws Exception {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "disabled" );
        final EntityExtractorConfig disabled = EntityExtractorConfig.fromProperties( p );

        final UUID chunkId = seedChunk( "Ignored", 0, "text" );
        final CapturingExtractor extractor = new CapturingExtractor( r -> ExtractionResult.empty(
            "ollama", Duration.ofMillis( 1 ) ) );

        try ( AsyncEntityExtractionListener listener = newListenerWithConfig( extractor, disabled ) ) {
            listener.accept( List.of( chunkId ) );
            Thread.sleep( 100 );
            assertEquals( 0, extractor.callCount(), "disabled extractor is never invoked" );
        }
    }

    // --- helpers ---------------------------------------------------------

    private AsyncEntityExtractionListener newListener( final EntityExtractor extractor ) {
        return newListenerWithConfig( extractor, config );
    }

    private AsyncEntityExtractionListener newListenerWithConfig( final EntityExtractor extractor,
                                                                 final EntityExtractorConfig cfg ) {
        // Listener-owned executor: close() drains it before the test inspects tables.
        return new AsyncEntityExtractionListener(
            extractor, cfg, chunkRepo, mentionRepo, kgRepo, new SimpleMeterRegistry() );
    }

    private UUID seedChunk( final String page, final int index, final String text ) throws Exception {
        final UUID id = UUID.randomUUID();
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_content_chunks ( id, page_name, chunk_index, heading_path, text, "
               + "char_count, token_count_estimate, content_hash, created, modified ) "
               + "VALUES ( ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW() )" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, page );
            ps.setInt( 3, index );
            ps.setArray( 4, c.createArrayOf( "text", new String[] {} ) );
            ps.setString( 5, text );
            ps.setInt( 6, text.length() );
            ps.setInt( 7, Math.max( 1, text.length() / 4 ) );
            ps.setString( 8, Integer.toHexString( text.hashCode() ) );
            ps.executeUpdate();
        }
        return id;
    }

    private int countMentionsForChunk( final UUID chunkId ) throws Exception {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM chunk_entity_mentions WHERE chunk_id = ?" ) ) {
            ps.setObject( 1, chunkId );
            try( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        }
    }

    private boolean hasMention( final UUID chunkId, final UUID nodeId ) throws Exception {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT 1 FROM chunk_entity_mentions WHERE chunk_id = ? AND node_id = ?" ) ) {
            ps.setObject( 1, chunkId );
            ps.setObject( 2, nodeId );
            try( ResultSet rs = ps.executeQuery() ) {
                return rs.next();
            }
        }
    }

    private int countProposals( final String proposalType ) throws Exception {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM kg_proposals WHERE proposal_type = ?" ) ) {
            ps.setString( 1, proposalType );
            try( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        }
    }

    private void waitForIdle( final AsyncEntityExtractionListener listener ) throws InterruptedException {
        // close() drains the executor, which is exactly what we want between assertions.
        listener.close();
    }

    /** Reusable fake extractor that records call count and swaps in a canned result per call. */
    private static final class CapturingExtractor implements EntityExtractor {

        private final AtomicInteger count = new AtomicInteger();
        private final java.util.function.Function< Integer, ExtractionResult > responder;
        private final List< ExtractionChunk > calls = new ArrayList<>();

        CapturingExtractor( final java.util.function.Function< Integer, ExtractionResult > responder ) {
            this.responder = responder;
        }

        @Override
        public String code() {
            return "ollama";
        }

        @Override
        public ExtractionResult extract( final ExtractionChunk chunk, final ExtractionContext context ) {
            final int n = count.incrementAndGet();
            assertNotNull( context, "extractor must always receive a context" );
            assertFalse( chunk.text().isBlank(), "extractor must receive chunk text" );
            calls.add( chunk );
            return responder.apply( n );
        }

        int callCount() {
            return count.get();
        }
    }
}
