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
package com.wikantik.search.hybrid;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link PgVectorChunkVectorIndex#topKChunks} against a
 * live pgvector container. Seeds four chunks — three close to the query vector
 * and one far away — and verifies that the HNSW lookup returns the top-3 in
 * score order with self-similarity near 1.0 and the far chunk excluded.
 *
 * <p>Run with: {@code mvn -pl wikantik-main test -Dtest=PgVectorChunkVectorIndexPgTest}</p>
 */
// Requires Docker; uses the shared PostgresTestContainer singleton.
class PgVectorChunkVectorIndexPgTest {

    private static final String MODEL_CODE = "bge-m3";
    private static final int DIM = PgVectorChunkVectorIndex.EMBEDDING_DIM; // 1024

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = PostgresTestContainer.createDataSource();
        applyV032Migration();
        cleanTestRows();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanTestRows();
    }

    @Test
    void topKChunks_returnsHighestScoredChunksFirst() throws Exception {
        // Build a unit-length base vector: all values = 1/sqrt(DIM).
        final float[] base = unitVector();

        // Three close vectors: base + tiny perturbation on one component.
        final float[] close0 = base.clone();
        final float[] close1 = perturbAndNormalize( base, 0, 0.01f );
        final float[] close2 = perturbAndNormalize( base, 1, 0.01f );

        // One far vector: negate all components (orthogonal in cosine terms —
        // cosine distance = 2.0, similarity = -1.0).
        final float[] far = base.clone();
        for ( int i = 0; i < far.length; i++ ) far[ i ] = -far[ i ];

        final UUID chunk0Id = insertChunkAndEmbedding( "IT_PgVec_Page0", 0, close0 );
        final UUID chunk1Id = insertChunkAndEmbedding( "IT_PgVec_Page1", 1, close1 );
        final UUID chunk2Id = insertChunkAndEmbedding( "IT_PgVec_Page2", 2, close2 );
        final UUID farId    = insertChunkAndEmbedding( "IT_PgVec_PageFar", 3, far );

        // ef_search = 200 so HNSW recall is reliable on this tiny 4-node graph.
        final PgVectorChunkVectorIndex idx =
            new PgVectorChunkVectorIndex( dataSource, MODEL_CODE, 200 );

        final List< ScoredChunk > top = idx.topKChunks( close0, 3 );

        assertEquals( 3, top.size(), "expected 3 results, got " + top.size() );
        assertEquals( chunk0Id, top.get( 0 ).chunkId(),
            "self-similarity should rank first; got " + top.get( 0 ).chunkId() );
        assertTrue( top.get( 0 ).score() > 0.99,
            "self-similarity should be ≈ 1.0, was " + top.get( 0 ).score() );
        assertTrue( top.stream().noneMatch( s -> s.chunkId().equals( farId ) ),
            "far chunk should not appear in top-3" );

        // Scores must be in descending order.
        for ( int i = 0; i < top.size() - 1; i++ ) {
            assertTrue( top.get( i ).score() >= top.get( i + 1 ).score(),
                "results must be sorted descending by score, but position " + i
                + " score=" + top.get( i ).score()
                + " < position " + ( i + 1 ) + " score=" + top.get( i + 1 ).score() );
        }
    }

    @Test
    void isReady_trueWhenAtLeastOneEmbeddingExists() throws Exception {
        cleanTestRows();
        insertChunkAndEmbedding( "IT_PgVec_Page1", 0, unitVector() );
        final PgVectorChunkVectorIndex idx =
            new PgVectorChunkVectorIndex( dataSource, MODEL_CODE, 100 );
        assertTrue( idx.isReady() );
    }

    @Test
    void isReady_falseWhenColumnAllNull() throws Exception {
        cleanTestRows();
        insertChunkRowWithNullEmbedding( "IT_PgVec_Page1", 0 );
        final PgVectorChunkVectorIndex idx =
            new PgVectorChunkVectorIndex( dataSource, MODEL_CODE, 100 );
        assertFalse( idx.isReady() );
    }

    @Test
    void size_returnsRowCountForModel_andCachesForFiveMinutes() throws Exception {
        cleanTestRows();
        insertChunkAndEmbedding( "IT_PgVec_Page1", 0, unitVector() );
        insertChunkAndEmbedding( "IT_PgVec_Page2", 0, perturbAndNormalize( unitVector(), 0, 0.01f ) );
        insertChunkAndEmbedding( "IT_PgVec_Page3", 0, perturbAndNormalize( unitVector(), 1, 0.01f ) );
        final PgVectorChunkVectorIndex idx =
            new PgVectorChunkVectorIndex( dataSource, MODEL_CODE, 100 );

        assertEquals( 3, idx.size() );

        // Insert a 4th row directly; size() should still return 3 because the cache
        // is fresh. (The cache TTL is 5 minutes — we don't sleep through it in this test.)
        insertChunkAndEmbedding( "IT_PgVec_Page4", 0, perturbAndNormalize( unitVector(), 2, 0.01f ) );
        assertEquals( 3, idx.size(),
            "size() should be cache-stable within the 5-minute TTL" );
    }

    // ---- helpers ----

    /**
     * Apply the V032 migration DDL to the shared test container so the
     * {@code embedding vector(1024)} column and HNSW index are present.
     * This is idempotent — uses {@code IF NOT EXISTS} / {@code ADD COLUMN IF NOT EXISTS}.
     */
    private void applyV032Migration() throws Exception {
        try ( Connection conn = dataSource.getConnection();
              Statement st = conn.createStatement() ) {
            st.execute(
                "ALTER TABLE content_chunk_embeddings "
              + "ADD COLUMN IF NOT EXISTS embedding vector(1024)" );
            st.execute(
                "CREATE INDEX IF NOT EXISTS content_chunk_embeddings_hnsw_idx "
              + "ON content_chunk_embeddings "
              + "USING hnsw (embedding vector_cosine_ops) "
              + "WITH (m = 16, ef_construction = 64)" );
        }
    }

    private void cleanTestRows() throws Exception {
        try ( Connection conn = dataSource.getConnection();
              Statement st = conn.createStatement() ) {
            st.execute( "DELETE FROM kg_content_chunks WHERE page_name LIKE 'IT_PgVec_%'" );
        }
    }

    /**
     * Insert one {@code kg_content_chunks} row and one
     * {@code content_chunk_embeddings} row whose {@code embedding} column
     * holds the supplied L2-normalized vector. Returns the generated chunk id.
     */
    private UUID insertChunkAndEmbedding( final String pageName,
                                           final int chunkIndex,
                                           final float[] vec ) throws Exception {
        final UUID chunkId;
        try ( Connection conn = dataSource.getConnection() ) {
            // Insert the chunk row.
            final String insertChunk =
                "INSERT INTO kg_content_chunks "
              + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
              + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
            try ( PreparedStatement ps = conn.prepareStatement( insertChunk ) ) {
                ps.setString( 1, pageName );
                ps.setInt( 2, chunkIndex );
                ps.setString( 3, "IT content for " + pageName );
                ps.setInt( 4, 30 );
                ps.setInt( 5, 8 );
                ps.setString( 6, "it-hash-" + pageName + "-" + chunkIndex );
                try ( var rs = ps.executeQuery() ) {
                    rs.next();
                    chunkId = rs.getObject( 1, UUID.class );
                }
            }

            // Insert the embedding row. The legacy 'vec' and 'dim' columns are NOT NULL
            // in the test schema, so supply a trivial 4-byte placeholder so the constraint
            // is satisfied; the HNSW path reads only the 'embedding' column.
            final String insertEmb =
                "INSERT INTO content_chunk_embeddings "
              + "(chunk_id, model_code, dim, vec, embedding) "
              + "VALUES (?, ?, ?, ?, ?::vector)";
            try ( PreparedStatement ps = conn.prepareStatement( insertEmb ) ) {
                ps.setObject( 1, chunkId );
                ps.setString( 2, MODEL_CODE );
                ps.setInt( 3, DIM );
                ps.setBytes( 4, new byte[]{ 0, 0, 0, 0 } ); // placeholder for legacy BYTEA column
                ps.setString( 5, PgVectorChunkVectorIndex.formatVector( vec ) );
                ps.executeUpdate();
            }
        }
        return chunkId;
    }

    /** Returns an L2-normalized unit vector where every component equals 1/sqrt(DIM). */
    private static float[] unitVector() {
        final float[] v = new float[ DIM ];
        final float val = (float) ( 1.0 / Math.sqrt( DIM ) );
        Arrays.fill( v, val );
        return v;
    }

    /**
     * Insert one {@code kg_content_chunks} row and one
     * {@code content_chunk_embeddings} row with the {@code embedding} column left
     * NULL. Used to verify that {@code isReady()} returns {@code false} when no
     * non-NULL embeddings exist for the model.
     */
    private void insertChunkRowWithNullEmbedding( final String pageName,
                                                   final int chunkIndex ) throws Exception {
        try ( Connection conn = dataSource.getConnection() ) {
            final String insertChunk =
                "INSERT INTO kg_content_chunks "
              + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
              + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
            final UUID chunkId;
            try ( PreparedStatement ps = conn.prepareStatement( insertChunk ) ) {
                ps.setString( 1, pageName );
                ps.setInt( 2, chunkIndex );
                ps.setString( 3, "IT null-emb content for " + pageName );
                ps.setInt( 4, 30 );
                ps.setInt( 5, 8 );
                ps.setString( 6, "it-hash-null-" + pageName + "-" + chunkIndex );
                try ( var rs = ps.executeQuery() ) {
                    rs.next();
                    chunkId = rs.getObject( 1, UUID.class );
                }
            }
            final String insertEmb =
                "INSERT INTO content_chunk_embeddings "
              + "(chunk_id, model_code, dim, vec, embedding) "
              + "VALUES (?, ?, 1024, ?, NULL)";
            try ( PreparedStatement ps = conn.prepareStatement( insertEmb ) ) {
                ps.setObject( 1, chunkId );
                ps.setString( 2, MODEL_CODE );
                ps.setBytes( 3, new byte[]{ 0, 0, 0, 0 } );
                ps.executeUpdate();
            }
        }
    }

    /**
     * Clone {@code base}, add {@code delta} to component {@code idx}, then
     * L2-normalize and return the result.
     */
    private static float[] perturbAndNormalize( final float[] base,
                                                 final int idx,
                                                 final float delta ) {
        final float[] v = base.clone();
        v[ idx ] += delta;
        double sumSq = 0.0;
        for ( final float f : v ) sumSq += (double) f * (double) f;
        final double inv = 1.0 / Math.sqrt( sumSq );
        for ( int i = 0; i < v.length; i++ ) v[ i ] = (float) ( v[ i ] * inv );
        return v;
    }
}
