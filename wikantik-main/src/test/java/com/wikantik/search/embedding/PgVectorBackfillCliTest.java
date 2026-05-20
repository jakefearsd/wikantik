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
package com.wikantik.search.embedding;

import com.wikantik.PostgresTestContainer;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for {@link PgVectorBackfillCli} against a live pgvector
 * container. Verifies idempotency, BYTEA→pgvector round-trip correctness,
 * --force behaviour, and graceful skipping of corrupt rows.
 *
 * <p>Run with: {@code mvn -pl wikantik-main test -Dtest=PgVectorBackfillCliTest}</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class PgVectorBackfillCliTest {

    private static final String MODEL_CODE = "backfill-test-model";
    private static final int DIM = 1024; // must match vector(1024) column dimension from V032

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

    // ---- test cases ----

    @Test
    void backfill_writes_embedding_for_rows_with_null_embedding() throws Exception {
        seedRowsWithByteaOnly( 3, MODEL_CODE );
        final PgVectorBackfillCli cli = new PgVectorBackfillCli( dataSource );
        final int written = cli.run( MODEL_CODE, /* force= */ false );
        assertEquals( 3, written );

        // Verify embedding column is now populated AND the float values round-trip.
        try ( Connection c = dataSource.getConnection();
              Statement st = c.createStatement();
              ResultSet rs = st.executeQuery(
                  "SELECT chunk_id, dim, vec, embedding::text FROM content_chunk_embeddings "
                + "WHERE model_code = '" + MODEL_CODE + "'" ) ) {
            int rowCount = 0;
            while ( rs.next() ) {
                rowCount++;
                final float[] fromBytea = decodeByteaInline( rs.getBytes( 3 ), rs.getInt( 2 ) );
                final float[] fromVector = parsePgVectorLiteralInline( rs.getString( 4 ) );
                assertArrayEquals( fromBytea, fromVector, 1e-6f );
            }
            assertEquals( 3, rowCount, "expected 3 rows in the table" );
        }
    }

    @Test
    void backfill_skips_rows_with_existing_embedding_unless_force() throws Exception {
        seedRowsWithBothColumns( 2, MODEL_CODE );
        final PgVectorBackfillCli cli = new PgVectorBackfillCli( dataSource );
        assertEquals( 0, cli.run( MODEL_CODE, false ), "should skip non-null rows" );
        assertEquals( 2, cli.run( MODEL_CODE, true ), "should overwrite under --force" );
    }

    @Test
    void backfill_skips_rows_with_corrupt_bytea_payload() throws Exception {
        seedRowWithWrongSizeBytea( MODEL_CODE ); // vec is 100 bytes for dim=1024
        final PgVectorBackfillCli cli = new PgVectorBackfillCli( dataSource );
        final int written = cli.run( MODEL_CODE, false );
        assertEquals( 0, written, "corrupt row should be skipped, not crash the run" );
    }

    // ---- seed helpers ----

    /**
     * Insert {@code count} chunk rows with BYTEA populated and {@code embedding} NULL.
     * These are the rows the backfill CLI must fill in.
     */
    private void seedRowsWithByteaOnly( final int count, final String modelCode ) throws Exception {
        try ( Connection c = dataSource.getConnection() ) {
            for ( int i = 0; i < count; i++ ) {
                final UUID chunkId = insertChunk( c, "IT_BF_Page" + i, i );
                final float[] vec = makeDistinctVector( i );
                final byte[] bytea = encodeAsLittleEndianFloat32( vec );
                insertEmbeddingRowByteaOnly( c, chunkId, modelCode, DIM, bytea );
            }
        }
    }

    /**
     * Insert {@code count} chunk rows with BOTH {@code vec} (BYTEA) and
     * {@code embedding} (vector) populated — representing rows already backfilled
     * or written by the dual-write path. The backfill CLI must skip these (unless force).
     */
    private void seedRowsWithBothColumns( final int count, final String modelCode ) throws Exception {
        try ( Connection c = dataSource.getConnection() ) {
            for ( int i = 0; i < count; i++ ) {
                final UUID chunkId = insertChunk( c, "IT_BF_Both" + i, i );
                final float[] vec = makeDistinctVector( i );
                final byte[] bytea = encodeAsLittleEndianFloat32( vec );
                insertEmbeddingRowBothColumns( c, chunkId, modelCode, DIM, bytea, vec );
            }
        }
    }

    /**
     * Insert one chunk row whose {@code vec} BYTEA is intentionally the wrong size
     * (100 bytes instead of dim*4=4096 bytes). The CLI must skip it gracefully.
     */
    private void seedRowWithWrongSizeBytea( final String modelCode ) throws Exception {
        try ( Connection c = dataSource.getConnection() ) {
            final UUID chunkId = insertChunk( c, "IT_BF_Corrupt", 0 );
            final byte[] badBytea = new byte[ 100 ]; // wrong size for dim=1024
            insertEmbeddingRowByteaOnly( c, chunkId, modelCode, DIM, badBytea );
        }
    }

    // ---- low-level DB helpers ----

    private UUID insertChunk( final Connection c,
                               final String pageName,
                               final int chunkIndex ) throws Exception {
        final String sql =
            "INSERT INTO kg_content_chunks "
          + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
          + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
        try ( PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            ps.setInt( 2, chunkIndex );
            ps.setString( 3, "Backfill IT content for " + pageName );
            ps.setInt( 4, 40 );
            ps.setInt( 5, 10 );
            ps.setString( 6, "bf-hash-" + pageName + "-" + chunkIndex );
            try ( ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getObject( 1, UUID.class );
            }
        }
    }

    private void insertEmbeddingRowByteaOnly( final Connection c,
                                               final UUID chunkId,
                                               final String modelCode,
                                               final int dim,
                                               final byte[] bytea ) throws Exception {
        final String sql =
            "INSERT INTO content_chunk_embeddings "
          + "(chunk_id, model_code, dim, vec, embedding) "
          + "VALUES (?, ?, ?, ?, NULL)";
        try ( PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, chunkId );
            ps.setString( 2, modelCode );
            ps.setInt( 3, dim );
            ps.setBytes( 4, bytea );
            ps.executeUpdate();
        }
    }

    private void insertEmbeddingRowBothColumns( final Connection c,
                                                 final UUID chunkId,
                                                 final String modelCode,
                                                 final int dim,
                                                 final byte[] bytea,
                                                 final float[] vec ) throws Exception {
        final String sql =
            "INSERT INTO content_chunk_embeddings "
          + "(chunk_id, model_code, dim, vec, embedding) "
          + "VALUES (?, ?, ?, ?, ?::vector)";
        try ( PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, chunkId );
            ps.setString( 2, modelCode );
            ps.setInt( 3, dim );
            ps.setBytes( 4, bytea );
            ps.setString( 5, PgVectorChunkVectorIndex.formatVector( vec ) );
            ps.executeUpdate();
        }
    }

    private void cleanTestRows() throws Exception {
        try ( Connection c = dataSource.getConnection();
              Statement st = c.createStatement() ) {
            st.execute( "DELETE FROM kg_content_chunks WHERE page_name LIKE 'IT_BF_%'" );
        }
    }

    private void applyV032Migration() throws Exception {
        try ( Connection c = dataSource.getConnection();
              Statement st = c.createStatement() ) {
            st.execute( "ALTER TABLE content_chunk_embeddings "
                      + "ADD COLUMN IF NOT EXISTS embedding vector(1024)" );
            st.execute(
                "CREATE INDEX IF NOT EXISTS content_chunk_embeddings_hnsw_idx "
              + "ON content_chunk_embeddings "
              + "USING hnsw (embedding vector_cosine_ops) "
              + "WITH (m = 16, ef_construction = 64)" );
        }
    }

    // ---- inline decoder helpers (test-only, NOT exposed on the production class) ----

    /**
     * Decode a little-endian float32 BYTEA into a float[]. Test-only mirror of what the
     * production CLI does internally — used to verify the round-trip assertion.
     */
    private static float[] decodeByteaInline( final byte[] raw, final int dim ) {
        final float[] out = new float[ dim ];
        final ByteBuffer bb = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN );
        for ( int i = 0; i < dim; i++ ) {
            out[ i ] = bb.getFloat();
        }
        return out;
    }

    /**
     * Parse a pgvector text literal of the form {@code [v1,v2,...]} into a float[].
     * Used to re-read the vector column as text and compare against the original BYTEA.
     */
    private static float[] parsePgVectorLiteralInline( final String literal ) {
        // pgvector casts to text as "[v1,v2,...]"
        final String trimmed = literal.trim();
        final String inner = trimmed.substring( 1, trimmed.length() - 1 ); // strip [ and ]
        final String[] parts = inner.split( "," );
        final float[] out = new float[ parts.length ];
        for ( int i = 0; i < parts.length; i++ ) {
            out[ i ] = Float.parseFloat( parts[ i ].trim() );
        }
        return out;
    }

    /**
     * Build a distinct unit-length vector for seed row {@code index} so we can
     * tell the rows apart in the round-trip assertion.
     */
    private static float[] makeDistinctVector( final int index ) {
        final float[] v = new float[ DIM ];
        final float base = (float) ( 1.0 / Math.sqrt( DIM ) );
        Arrays.fill( v, base );
        // perturb one element to make each vector unique, then renormalize
        v[ index % DIM ] += 0.1f;
        double sumSq = 0.0;
        for ( final float f : v ) sumSq += (double) f * (double) f;
        final double inv = 1.0 / Math.sqrt( sumSq );
        for ( int i = 0; i < v.length; i++ ) v[ i ] = (float) ( v[ i ] * inv );
        return v;
    }

    /**
     * Encode a float[] as little-endian IEEE 754 float32 bytes — the same encoding
     * that the production embedding pipeline writes to {@code content_chunk_embeddings.vec}.
     */
    private static byte[] encodeAsLittleEndianFloat32( final float[] v ) {
        final ByteBuffer bb = ByteBuffer.allocate( v.length * Float.BYTES )
                                        .order( ByteOrder.LITTLE_ENDIAN );
        for ( final float f : v ) bb.putFloat( f );
        return bb.array();
    }
}
