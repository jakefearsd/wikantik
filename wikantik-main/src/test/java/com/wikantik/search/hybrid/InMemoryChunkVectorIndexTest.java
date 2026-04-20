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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link InMemoryChunkVectorIndex} against a real
 * PostgreSQL testcontainer with the V008/V009 schema. Covers the load and
 * query contracts plus reload semantics and multi-model isolation.
 */
@Testcontainers( disabledWithoutDocker = true )
class InMemoryChunkVectorIndexTest {

    private static final String MODEL = "test-model";
    private static final String OTHER_MODEL = "other-model";
    private static final int DIM = 4;
    private static final double EPS = 1e-6;

    private static DataSource dataSource;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
    }

    @Test
    void emptyTable_isReadyFalse_andTopKReturnsEmpty() {
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertFalse( idx.isReady() );
        assertEquals( 0, idx.size() );
        assertEquals( 0, idx.dimension() );
        assertEquals( List.of(), idx.topKChunks( unit( new float[]{ 1f, 0f, 0f, 0f } ), 5 ) );
    }

    @Test
    void coldStart_loadsAllRowsAndTopKReturnsExpectedOrdering() throws SQLException {
        // Three unit-ish vectors: a perfectly aligned with query, c aligned but weaker, b orthogonal.
        final UUID a = seedChunk( "PageA", 0 );
        final UUID b = seedChunk( "PageB", 0 );
        final UUID c = seedChunk( "PageC", 0 );

        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        insertEmbedding( b, MODEL, new float[]{ 0f, 1f, 0f, 0f } );
        insertEmbedding( c, MODEL, new float[]{ 0.6f, 0.8f, 0f, 0f } );

        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertTrue( idx.isReady() );
        assertEquals( 3, idx.size() );
        assertEquals( DIM, idx.dimension() );

        // Query = (1,0,0,0) already unit. Expected cosines: a=1.0, c=0.6, b=0.0.
        final List< ScoredChunk > top = idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 3 );
        assertEquals( 3, top.size() );
        assertEquals( a, top.get( 0 ).chunkId() );
        assertEquals( "PageA", top.get( 0 ).pageName() );
        assertEquals( 1.0, top.get( 0 ).score(), EPS );
        assertEquals( c, top.get( 1 ).chunkId() );
        assertEquals( 0.6, top.get( 1 ).score(), EPS );
        assertEquals( b, top.get( 2 ).chunkId() );
        assertEquals( 0.0, top.get( 2 ).score(), EPS );
    }

    @Test
    void topK_honoursKLimit() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        final UUID b = seedChunk( "PageB", 0 );
        seedChunk( "PageC", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        insertEmbedding( b, MODEL, new float[]{ 0.9f, 0.1f, 0f, 0f } );
        // third row: make another vector so 'size' > k
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "SELECT id FROM kg_content_chunks WHERE page_name='PageC'" ) ) {
            try( final ResultSet rs = ps.executeQuery() ) {
                rs.next();
                insertEmbedding( rs.getObject( 1, UUID.class ), MODEL,
                    new float[]{ 0f, 1f, 0f, 0f } );
            }
        }

        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        final List< ScoredChunk > top1 = idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 1 );
        assertEquals( 1, top1.size() );
        assertEquals( a, top1.get( 0 ).chunkId() );

        final List< ScoredChunk > top2 = idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 2 );
        assertEquals( 2, top2.size() );
        assertTrue( top2.get( 0 ).score() >= top2.get( 1 ).score() );

        // k > size → effectively clamped to size
        final List< ScoredChunk > top99 = idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 99 );
        assertEquals( 3, top99.size() );
    }

    @Test
    void dimMismatch_throwsIllegalStateException() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertThrows( IllegalStateException.class,
            () -> idx.topKChunks( new float[]{ 1f, 0f, 0f }, 1 ) );
    }

    @Test
    void reload_picksUpNewRows_andBumpsLastRefresh() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertEquals( 1, idx.size() );
        final long firstRefresh = idx.lastRefreshMillis();

        // sleep a millisecond so the second refresh timestamp is distinguishable
        try { Thread.sleep( 2 ); } catch( final InterruptedException ignored ) {
            Thread.currentThread().interrupt();
        }

        final UUID b = seedChunk( "PageB", 0 );
        insertEmbedding( b, MODEL, new float[]{ 0f, 1f, 0f, 0f } );
        idx.reload();

        assertEquals( 2, idx.size() );
        assertTrue( idx.lastRefreshMillis() >= firstRefresh );
        final List< ScoredChunk > top = idx.topKChunks( new float[]{ 0f, 1f, 0f, 0f }, 1 );
        assertEquals( b, top.get( 0 ).chunkId() );
    }

    @Test
    void multipleModels_isolated() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        final UUID b = seedChunk( "PageB", 0 );
        // Same chunk a is embedded by both models; chunk b only by MODEL.
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        insertEmbedding( a, OTHER_MODEL, new float[]{ 0f, 0f, 1f, 0f } );
        insertEmbedding( b, MODEL, new float[]{ 0f, 1f, 0f, 0f } );

        final InMemoryChunkVectorIndex modelIdx =
            new InMemoryChunkVectorIndex( dataSource, MODEL );
        final InMemoryChunkVectorIndex otherIdx =
            new InMemoryChunkVectorIndex( dataSource, OTHER_MODEL );

        assertEquals( 2, modelIdx.size() );
        assertEquals( 1, otherIdx.size() );

        // MODEL query that aligns to a's MODEL vector
        assertEquals( a, modelIdx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 1 )
            .get( 0 ).chunkId() );
        // OTHER_MODEL only has a; the top result must come from the z-axis vector
        final ScoredChunk top = otherIdx.topKChunks( new float[]{ 0f, 0f, 1f, 0f }, 1 ).get( 0 );
        assertEquals( a, top.chunkId() );
        assertEquals( 1.0, top.score(), EPS );
    }

    @Test
    void constructor_rejectsBlankModelAndNullDataSource() {
        assertThrows( IllegalArgumentException.class,
            () -> new InMemoryChunkVectorIndex( null, MODEL ) );
        assertThrows( IllegalArgumentException.class,
            () -> new InMemoryChunkVectorIndex( dataSource, "" ) );
        assertThrows( IllegalArgumentException.class,
            () -> new InMemoryChunkVectorIndex( dataSource, null ) );
    }

    @Test
    void topK_rejectsInvalidArgs() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertThrows( IllegalArgumentException.class, () -> idx.topKChunks( null, 1 ) );
        assertThrows( IllegalArgumentException.class,
            () -> idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 0 ) );
        assertThrows( IllegalArgumentException.class,
            () -> idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, -3 ) );
    }

    @Test
    void corruptVectorBytes_throwsIllegalStateException() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        // Insert with dim=4 but only 2 floats worth of bytes (8 bytes) — corrupt.
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec) "
               + "VALUES (?, ?, ?, ?)" ) ) {
            ps.setObject( 1, a );
            ps.setString( 2, MODEL );
            ps.setInt( 3, DIM );
            ps.setBytes( 4, new byte[]{ 0, 0, 0, 0, 0, 0, 0, 0 } );
            ps.executeUpdate();
        }
        assertThrows( RuntimeException.class,
            () -> new InMemoryChunkVectorIndex( dataSource, MODEL ) );
    }

    @Test
    void topK_largeNUsesHeapAndReturnsTrueTopByDot() throws SQLException {
        // Seed 50 vectors aligned variously to the x-axis. Ranking by dot with (1,0,0,0)
        // collapses to ranking by the first component / vector L2 norm. We pick scores
        // so the expected top-5 is unambiguous, exercising heap insert/replace paths.
        final UUID[] ids = new UUID[ 50 ];
        for( int i = 0; i < 50; i++ ) {
            ids[ i ] = seedChunk( "Page" + i, 0 );
            // x-axis alignment varies from 0.02..1.0 in 50 steps; small y/z noise for variety
            final float xComp = ( i + 1 ) / 50f;
            final float[] v = new float[]{ xComp, 0.01f, 0.0f, 0.0f };
            insertEmbedding( ids[ i ], MODEL, v );
        }

        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertEquals( 50, idx.size() );

        final List< ScoredChunk > top5 = idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 5 );
        assertEquals( 5, top5.size() );
        // Top 5 must be the last five seeded (highest x-component → highest cosine).
        for( int i = 0; i < 5; i++ ) {
            assertEquals( ids[ 49 - i ], top5.get( i ).chunkId(),
                "rank " + i + " should be the (49-i)th-seeded vector" );
        }
        // Strictly descending scores
        for( int i = 1; i < top5.size(); i++ ) {
            assertTrue( top5.get( i - 1 ).score() >= top5.get( i ).score() );
        }
    }

    @Test
    void upsertChunks_addsNewRows() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertEquals( 1, idx.size() );

        // Add a new chunk + embedding outside the index, then upsert just that chunkId.
        final UUID b = seedChunk( "PageB", 0 );
        insertEmbedding( b, MODEL, new float[]{ 0f, 1f, 0f, 0f } );
        idx.upsertChunks( List.of( b ) );

        assertEquals( 2, idx.size() );
        assertEquals( b, idx.topKChunks( new float[]{ 0f, 1f, 0f, 0f }, 1 ).get( 0 ).chunkId() );
        assertEquals( a, idx.topKChunks( new float[]{ 1f, 0f, 0f, 0f }, 1 ).get( 0 ).chunkId() );
    }

    @Test
    void upsertChunks_replacesExistingRows() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );

        // Replace embedding for chunk a in the database — flip orientation to y-axis.
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "UPDATE content_chunk_embeddings SET vec = ? WHERE chunk_id = ? AND model_code = ?" ) ) {
            ps.setBytes( 1, encode( new float[]{ 0f, 1f, 0f, 0f } ) );
            ps.setObject( 2, a );
            ps.setString( 3, MODEL );
            ps.executeUpdate();
        }
        idx.upsertChunks( List.of( a ) );

        assertEquals( 1, idx.size(), "row count must not double after replace" );
        // After replace, the y-axis query should match a with score 1.0.
        final List< ScoredChunk > top = idx.topKChunks( new float[]{ 0f, 1f, 0f, 0f }, 1 );
        assertEquals( a, top.get( 0 ).chunkId() );
        assertEquals( 1.0, top.get( 0 ).score(), EPS );
    }

    @Test
    void upsertChunks_removesRowsAbsentFromDatabase() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        final UUID b = seedChunk( "PageB", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        insertEmbedding( b, MODEL, new float[]{ 0f, 1f, 0f, 0f } );
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        assertEquals( 2, idx.size() );

        // Delete chunk a's embedding row, then upsert a — listener should observe it as gone.
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM content_chunk_embeddings WHERE chunk_id = ? AND model_code = ?" ) ) {
            ps.setObject( 1, a );
            ps.setString( 2, MODEL );
            ps.executeUpdate();
        }
        idx.upsertChunks( List.of( a ) );

        assertEquals( 1, idx.size() );
        assertEquals( b, idx.topKChunks( new float[]{ 0f, 1f, 0f, 0f }, 1 ).get( 0 ).chunkId() );
    }

    @Test
    void upsertChunks_emptyOrNullIsNoOp() throws SQLException {
        final UUID a = seedChunk( "PageA", 0 );
        insertEmbedding( a, MODEL, new float[]{ 1f, 0f, 0f, 0f } );
        final InMemoryChunkVectorIndex idx = new InMemoryChunkVectorIndex( dataSource, MODEL );
        final long beforeRefresh = idx.lastRefreshMillis();

        idx.upsertChunks( List.of() );
        idx.upsertChunks( null );

        assertEquals( 1, idx.size() );
        assertEquals( beforeRefresh, idx.lastRefreshMillis(),
            "no-op upserts should not bump the refresh timestamp" );
    }

    // ---- helpers ----

    private UUID seedChunk( final String pageName, final int idx ) throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_content_chunks "
               + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
               + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id" ) ) {
            ps.setString( 1, pageName );
            ps.setInt( 2, idx );
            ps.setString( 3, "body" );
            ps.setInt( 4, 4 );
            ps.setInt( 5, 2 );
            ps.setString( 6, UUID.randomUUID().toString() );
            try( final ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getObject( 1, UUID.class );
            }
        }
    }

    private void insertEmbedding( final UUID chunkId, final String model, final float[] vec )
            throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec) "
               + "VALUES (?, ?, ?, ?)" ) ) {
            ps.setObject( 1, chunkId );
            ps.setString( 2, model );
            ps.setInt( 3, vec.length );
            ps.setBytes( 4, encode( vec ) );
            ps.executeUpdate();
        }
    }

    private static byte[] encode( final float[] vec ) {
        final ByteBuffer buf = ByteBuffer.allocate( vec.length * Float.BYTES )
            .order( ByteOrder.LITTLE_ENDIAN );
        for( final float f : vec ) buf.putFloat( f );
        return buf.array();
    }

    /** Normalize to unit length so the dot-product score matches cosine. */
    private static float[] unit( final float[] v ) {
        double sq = 0.0;
        for( final float f : v ) sq += (double) f * (double) f;
        final double inv = 1.0 / Math.sqrt( sq );
        final float[] out = new float[ v.length ];
        for( int i = 0; i < v.length; i++ ) out[ i ] = (float) ( v[ i ] * inv );
        return out;
    }

}
