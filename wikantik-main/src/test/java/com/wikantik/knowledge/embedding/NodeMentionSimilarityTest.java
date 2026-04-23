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
package com.wikantik.knowledge.embedding;

import com.wikantik.PostgresTestContainer;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JDBC-backed tests for {@link NodeMentionSimilarity}. Uses the shared
 * {@link PostgresTestContainer} so we exercise the real pgvector / BYTEA
 * storage path and catch query / encoding mistakes.
 */
@Testcontainers( disabledWithoutDocker = true )
class NodeMentionSimilarityTest {

    private static final String MODEL_CODE = "test-model";
    private static final int DIM = 4;

    private static DataSource dataSource;
    private NodeMentionSimilarity similarity;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            // Wipe in dependency-safe order. chunk_entity_mentions is CASCADED
            // by kg_content_chunks and kg_nodes, so deleting it first is fine.
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        similarity = new NodeMentionSimilarity( dataSource, MODEL_CODE );
    }

    @Test
    void zeroMentionsReturnsEmptyVectorAndEmptySimilarList() {
        insertNode( "Alice" );
        assertTrue( similarity.vectorFor( "Alice" ).isEmpty() );
        assertEquals( List.of(), similarity.similarTo( "Alice", 10 ) );
    }

    @Test
    void singleMentionReturnsNormalizedChunkVector() {
        final UUID alice = insertNode( "Alice" );
        final UUID chunk = insertChunk( "AlicePage", 0, "alice content" );
        final float[] vec = new float[]{ 2f, 0f, 0f, 0f };   // magnitude 2, not unit
        insertEmbedding( chunk, vec );
        insertMention( chunk, alice, 1.0f );

        final float[] centroid = similarity.vectorFor( "Alice" ).orElseThrow();
        // Centroid of a single unit-normalized vector equals that unit vector.
        assertArrayEquals( new float[]{ 1f, 0f, 0f, 0f }, centroid, 1e-5f );
    }

    @Test
    void multipleMentionsAverageInCentroid() {
        final UUID alice = insertNode( "Alice" );
        final UUID c1 = insertChunk( "P1", 0, "t1" );
        final UUID c2 = insertChunk( "P2", 0, "t2" );
        insertEmbedding( c1, new float[]{ 1f, 0f, 0f, 0f } );
        insertEmbedding( c2, new float[]{ 0f, 1f, 0f, 0f } );
        insertMention( c1, alice, 1.0f );
        insertMention( c2, alice, 1.0f );

        final float[] centroid = similarity.vectorFor( "Alice" ).orElseThrow();
        final float expected = (float) ( 1.0 / Math.sqrt( 2.0 ) );
        assertArrayEquals( new float[]{ expected, expected, 0f, 0f }, centroid, 1e-5f );
    }

    @Test
    void mentionWithMissingEmbeddingIsSkippedNotErrored() {
        final UUID alice = insertNode( "Alice" );
        final UUID c1 = insertChunk( "P1", 0, "t1" );
        final UUID c2 = insertChunk( "P2", 0, "t2" );
        insertEmbedding( c1, new float[]{ 1f, 0f, 0f, 0f } );
        // c2 has no embedding row — the mention below should be silently skipped.
        insertMention( c1, alice, 1.0f );
        insertMention( c2, alice, 1.0f );

        final float[] centroid = similarity.vectorFor( "Alice" ).orElseThrow();
        assertArrayEquals( new float[]{ 1f, 0f, 0f, 0f }, centroid, 1e-5f );
    }

    @Test
    void similarToRanksByCosineDescending() {
        final UUID alice = insertNode( "Alice" );
        final UUID bob = insertNode( "Bob" );
        final UUID carol = insertNode( "Carol" );
        final UUID dave = insertNode( "Dave" );

        // Alice vector is [1,0,0,0]
        bindMention( alice, new float[]{ 1f, 0f, 0f, 0f } );
        // Bob is [0.9, 0.44, 0, 0] — close to Alice
        bindMention( bob, new float[]{ 0.9f, 0.44f, 0f, 0f } );
        // Carol is [0,1,0,0] — orthogonal
        bindMention( carol, new float[]{ 0f, 1f, 0f, 0f } );
        // Dave is [-1,0,0,0] — opposite
        bindMention( dave, new float[]{ -1f, 0f, 0f, 0f } );

        final List< ScoredName > ranked = similarity.similarTo( "Alice", 3 );
        assertEquals( 3, ranked.size() );
        assertEquals( "Bob",   ranked.get( 0 ).name() );
        assertEquals( "Carol", ranked.get( 1 ).name() );
        assertEquals( "Dave",  ranked.get( 2 ).name() );
        // First score > second > third.
        assertTrue( ranked.get( 0 ).score() > ranked.get( 1 ).score() );
        assertTrue( ranked.get( 1 ).score() > ranked.get( 2 ).score() );
    }

    @Test
    void similarToExcludesQueryNode() {
        final UUID alice = insertNode( "Alice" );
        final UUID bob = insertNode( "Bob" );
        bindMention( alice, new float[]{ 1f, 0f, 0f, 0f } );
        bindMention( bob,   new float[]{ 1f, 0f, 0f, 0f } );

        final List< ScoredName > ranked = similarity.similarTo( "Alice", 5 );
        assertEquals( List.of( "Bob" ), ranked.stream().map( ScoredName::name ).toList() );
    }

    @Test
    void similarToReturnsEmptyWhenQueryNodeHasNoMentions() {
        insertNode( "Ghost" );
        final UUID bob = insertNode( "Bob" );
        bindMention( bob, new float[]{ 1f, 0f, 0f, 0f } );
        assertEquals( List.of(), similarity.similarTo( "Ghost", 5 ) );
    }

    @Test
    void similarToVectorWithExcludes() {
        final UUID alice = insertNode( "Alice" );
        final UUID bob = insertNode( "Bob" );
        final UUID carol = insertNode( "Carol" );
        bindMention( alice, new float[]{ 1f, 0f, 0f, 0f } );
        bindMention( bob,   new float[]{ 1f, 0f, 0f, 0f } );
        bindMention( carol, new float[]{ 0.5f, 0.5f, 0f, 0f } );

        final List< ScoredName > ranked = similarity.similarTo(
            new float[]{ 1f, 0f, 0f, 0f }, 5, Set.of( "Alice" ) );
        // Alice is excluded, so top result is Bob (identical direction), then Carol.
        assertEquals( List.of( "Bob", "Carol" ), ranked.stream().map( ScoredName::name ).toList() );
    }

    @Test
    void mentionedNodeNamesListsOnlyNodesWithMentions() {
        final UUID alice = insertNode( "Alice" );
        insertNode( "Bob" );   // no mentions → must not appear
        bindMention( alice, new float[]{ 1f, 0f, 0f, 0f } );

        assertEquals( List.of( "Alice" ), similarity.mentionedNodeNames() );
    }

    @Test
    void dimensionReflectsStoredVectors() {
        final UUID alice = insertNode( "Alice" );
        bindMention( alice, new float[]{ 1f, 0f, 0f, 0f } );
        assertEquals( DIM, similarity.dimension() );
        assertTrue( similarity.isReady() );
    }

    @Test
    void isReadyFalseWhenNoEmbeddings() {
        insertNode( "Alice" );
        assertFalse( similarity.isReady() );
        assertEquals( 0, similarity.dimension() );
    }

    // ---- helpers ----

    private void bindMention( final UUID nodeId, final float[] vec ) {
        final UUID chunk = insertChunk( "p_" + nodeId, 0, "t_" + nodeId );
        insertEmbedding( chunk, vec );
        insertMention( chunk, nodeId, 1.0f );
    }

    private UUID insertNode( final String name ) {
        final UUID id = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_nodes (id, name, node_type) VALUES (?, ?, 'article')" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, name );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
        return id;
    }

    private UUID insertChunk( final String pageName, final int chunkIndex, final String text ) {
        final UUID id = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_content_chunks "
                + "(id, page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, pageName );
            ps.setInt( 3, chunkIndex );
            ps.setString( 4, text );
            ps.setInt( 5, text.length() );
            ps.setInt( 6, text.length() );
            ps.setString( 7, Integer.toHexString( text.hashCode() ) );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
        return id;
    }

    private void insertEmbedding( final UUID chunkId, final float[] vec ) {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec) "
                + "VALUES (?, ?, ?, ?)" ) ) {
            ps.setObject( 1, chunkId );
            ps.setString( 2, MODEL_CODE );
            ps.setInt( 3, vec.length );
            ps.setBytes( 4, encode( vec ) );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
    }

    private void insertMention( final UUID chunkId, final UUID nodeId, final float confidence ) {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO chunk_entity_mentions "
                + "(chunk_id, node_id, confidence, extractor) "
                + "VALUES (?, ?, ?, 'test')" ) ) {
            ps.setObject( 1, chunkId );
            ps.setObject( 2, nodeId );
            ps.setFloat( 3, confidence );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
    }

    private static byte[] encode( final float[] vec ) {
        final ByteBuffer buf = ByteBuffer.allocate( vec.length * Float.BYTES )
            .order( ByteOrder.LITTLE_ENDIAN );
        for ( final float f : vec ) buf.putFloat( f );
        return buf.array();
    }
}
