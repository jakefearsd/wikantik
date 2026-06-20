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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round-trip coverage for {@link EmbeddingIndexService} against a real
 * PostgreSQL testcontainer. Exercises the batch happy path, the per-item
 * fallback that the bge-m3 NaN bug forced into {@code ExperimentIndexer},
 * and the incremental update path that relies on {@code ON CONFLICT
 * DO UPDATE}.
 */
@Testcontainers( disabledWithoutDocker = true )
class EmbeddingIndexServiceTest {

    private static final String MODEL = "qwen3-embedding-0.6b";
    /** Must match the {@code vector(1024)} column dimension enforced by V032. */
    private static final int DIM = 1024;

    private static DataSource dataSource;
    private TextEmbeddingClient client;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        applyV032Migration();
        try( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
        client = mock( TextEmbeddingClient.class );
        when( client.dimension() ).thenReturn( DIM );
        when( client.modelName() ).thenReturn( MODEL );
    }

    /**
     * Ensures the {@code embedding vector(1024)} column added in V032 is
     * present in the test container schema. Idempotent via {@code IF NOT EXISTS}.
     */
    private void applyV032Migration() throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final Statement st = c.createStatement() ) {
            st.execute( "ALTER TABLE content_chunk_embeddings "
                      + "ADD COLUMN IF NOT EXISTS embedding vector(1024)" );
        }
    }

    @Test
    void indexAll_batchHappyPath_insertsAllChunks() throws SQLException {
        final List< UUID > ids = seedChunks( 3 );
        stubBatchEmbed( ids.size(), false );

        final EmbeddingIndexService svc = new EmbeddingIndexService(
            dataSource, client, /*batchSize*/ 32 );
        final int embedded = svc.indexAll( MODEL );

        assertEquals( 3, embedded );
        assertEquals( 3, countRows() );
        for( final UUID id : ids ) {
            assertEquals( DIM, fetchDim( id ), "dim persisted" );
            assertNotNull( fetchVec( id ), "vec persisted" );
        }
    }

    @Test
    void indexAll_batchFails_perItemFallbackSkipsPoisonedChunks() throws SQLException {
        final List< UUID > ids = seedChunks( 3 );

        // Batch of 3 fails (bge-m3 NaN style failure). Per-item retry:
        // chunk 0 succeeds, chunk 1 poisoned (throws), chunk 2 succeeds.
        final AtomicInteger call = new AtomicInteger();
        when( client.embed( ArgumentMatchers.anyList(),
                            ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenAnswer( ( InvocationOnMock inv ) -> {
                final int n = call.incrementAndGet();
                final List< String > texts = inv.getArgument( 0 );
                if ( n == 1 ) { // the batch call
                    throw new RuntimeException( "batch failed (NaN)" );
                }
                // per-item calls have size 1
                if ( texts.get( 0 ).contains( "#1" ) ) {
                    throw new RuntimeException( "poisoned input" );
                }
                return List.of( randomVec() );
            } );

        final EmbeddingIndexService svc = new EmbeddingIndexService(
            dataSource, client, /*batchSize*/ 32 );
        final int embedded = svc.indexAll( MODEL );

        assertEquals( 2, embedded, "poisoned chunk skipped, two inserted" );
        assertEquals( 2, countRows() );
        // middle chunk must be missing; first and last present
        assertTrue( hasEmbedding( ids.get( 0 ) ) );
        assertTrue( !hasEmbedding( ids.get( 1 ) ) );
        assertTrue( hasEmbedding( ids.get( 2 ) ) );
    }

    @Test
    void indexChunks_updatesExistingRowViaOnConflict() throws SQLException {
        final List< UUID > ids = seedChunks( 1 );
        stubBatchEmbed( 1, false );

        final EmbeddingIndexService svc = new EmbeddingIndexService(
            dataSource, client, 32 );
        svc.indexChunks( ids, MODEL );
        final byte[] first = fetchVec( ids.get( 0 ) );

        // second embedding returns a different vector — ON CONFLICT should update.
        // Must be 1024-dim to satisfy the vector(1024) column constraint.
        final float[] different = new float[ DIM ];
        Arrays.fill( different, 9f );
        when( client.embed( ArgumentMatchers.anyList(), ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenReturn( List.of( different ) );

        svc.indexChunks( ids, MODEL );
        final byte[] second = fetchVec( ids.get( 0 ) );

        assertEquals( 1, countRows(), "ON CONFLICT updated, no duplicate rows" );
        assertTrue( !Arrays.equals( first, second ), "vec bytes changed" );
    }

    @Test
    void deleteByModel_removesOnlyThatModelsRows() throws SQLException {
        final List< UUID > ids = seedChunks( 2 );
        stubBatchEmbed( 2, false );

        final EmbeddingIndexService svc = new EmbeddingIndexService(
            dataSource, client, 32 );
        svc.indexAll( MODEL );
        // Seed a different model's row manually so we can prove the delete is scoped
        insertRawEmbedding( ids.get( 0 ), "other-model", new byte[ DIM * 4 ] );

        final int removed = svc.deleteByModel( MODEL );
        assertEquals( 2, removed, "both rows for target model removed" );
        assertEquals( 1, countRows(), "other model's row preserved" );
    }

    @Test
    void upsert_populates_both_vec_and_embedding_columns() throws SQLException {
        // The embedding column is vector(1024) — use 1024-dim vectors so the pgvector
        // dimension constraint is satisfied while the BYTEA path continues working.
        final float[] vec1024 = unitVec1024();
        when( client.embed( ArgumentMatchers.anyList(),
                            ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenAnswer( inv -> {
                final List< String > texts = inv.getArgument( 0 );
                final List< float[] > out = new ArrayList<>( texts.size() );
                for( int i = 0; i < texts.size(); i++ ) out.add( vec1024 );
                return out;
            } );

        final List< UUID > ids = seedChunks( 2 );
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        final int embedded = svc.indexAll( MODEL );

        assertEquals( 2, embedded );
        try( final Connection c = dataSource.getConnection();
             final ResultSet rs = c.createStatement().executeQuery(
                 "SELECT vec IS NOT NULL, embedding IS NOT NULL "
               + "FROM content_chunk_embeddings WHERE model_code = '" + MODEL + "'" ) ) {
            int rowsSeen = 0;
            while( rs.next() ) {
                rowsSeen++;
                assertTrue( rs.getBoolean( 1 ), "vec column should be populated" );
                assertTrue( rs.getBoolean( 2 ), "embedding column should be populated" );
            }
            assertEquals( 2, rowsSeen, "expected 2 rows in content_chunk_embeddings" );
        }
    }

    @Test
    void upsert_round_trips_vector_through_both_codecs() throws SQLException {
        // Use a known deterministic 1024-dim vector so we can compare both decodings.
        // The embedding column is vector(1024) — dimension must match.
        final float[] expected = unitVec1024();
        when( client.embed( ArgumentMatchers.anyList(),
                            ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenReturn( List.of( expected ) );

        final List< UUID > ids = seedChunks( 1 );
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        svc.indexAll( MODEL );

        final UUID id = ids.get( 0 );
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "SELECT vec, embedding::text "
               + "FROM content_chunk_embeddings WHERE chunk_id = ? AND model_code = ?" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, MODEL );
            try( final ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next(), "row must exist" );

                // Decode BYTEA → float[] via little-endian float32 stream.
                final byte[] vecBytes = rs.getBytes( 1 );
                assertNotNull( vecBytes, "vec must be non-null" );
                final ByteBuffer buf = ByteBuffer.wrap( vecBytes ).order( ByteOrder.LITTLE_ENDIAN );
                final float[] fromBytea = new float[ vecBytes.length / Float.BYTES ];
                for( int i = 0; i < fromBytea.length; i++ ) fromBytea[ i ] = buf.getFloat();

                // Decode pgvector literal → float[] (inline parser matching KgNodeEmbeddingRepository).
                final String literal = rs.getString( 2 );
                assertNotNull( literal, "embedding must be non-null" );
                final String trimmed = literal.substring( 1, literal.length() - 1 );
                final String[] parts = trimmed.split( "," );
                final float[] fromPgvector = new float[ parts.length ];
                for( int i = 0; i < parts.length; i++ ) fromPgvector[ i ] = Float.parseFloat( parts[ i ] );

                assertArrayEquals( fromBytea, fromPgvector, 1e-6f,
                    "float[] decoded from BYTEA must match float[] parsed from pgvector literal" );
                assertArrayEquals( expected, fromBytea, 1e-6f,
                    "decoded vector must match the original float[] the client returned" );
            }
        }
    }

    // ---- helpers ----

    /** Inserts N synthetic chunk rows and returns their IDs in insertion order. */
    private List< UUID > seedChunks( final int n ) throws SQLException {
        final List< UUID > ids = new ArrayList<>();
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_content_chunks "
               + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
               + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id" ) ) {
            for( int i = 0; i < n; i++ ) {
                ps.setString( 1, "TestPage" );
                ps.setInt( 2, i );
                // include a marker in the text so per-item mocks can branch on content
                ps.setString( 3, "chunk body #" + i );
                ps.setInt( 4, 12 );
                ps.setInt( 5, 4 );
                ps.setString( 6, "hash" + i );
                try( final ResultSet rs = ps.executeQuery() ) {
                    rs.next();
                    ids.add( rs.getObject( 1, UUID.class ) );
                }
            }
        }
        return ids;
    }

    private void stubBatchEmbed( final int count, final boolean failing ) {
        when( client.embed( ArgumentMatchers.anyList(),
                            ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenAnswer( ( InvocationOnMock inv ) -> {
                if ( failing ) throw new RuntimeException( "batch failed" );
                final List< String > texts = inv.getArgument( 0 );
                final List< float[] > out = new ArrayList<>( texts.size() );
                for( int i = 0; i < texts.size(); i++ ) out.add( randomVec() );
                return out;
            } );
        assertEquals( count, count ); // silence unused-parameter warning
    }

    private float[] randomVec() {
        return unitVec1024();
    }

    /**
     * Returns a 1024-dim unit vector with every component equal to
     * {@code 1 / sqrt(1024) = 0.03125} so the vector is L2-normalised.
     * Required for tests that write to the {@code embedding vector(1024)}
     * column, which enforces a dimension check at insert time.
     */
    private static float[] unitVec1024() {
        final int dim = 1024;
        final float[] v = new float[ dim ];
        final float val = (float) ( 1.0 / Math.sqrt( dim ) );
        Arrays.fill( v, val );
        return v;
    }

    private int countRows() throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final ResultSet rs = c.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM content_chunk_embeddings" ) ) {
            rs.next();
            return rs.getInt( 1 );
        }
    }

    private int fetchDim( final UUID id ) throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "SELECT dim FROM content_chunk_embeddings WHERE chunk_id = ?" ) ) {
            ps.setObject( 1, id );
            try( final ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getInt( 1 );
            }
        }
    }

    private byte[] fetchVec( final UUID id ) throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "SELECT vec FROM content_chunk_embeddings WHERE chunk_id = ? AND model_code = ?" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, MODEL );
            try( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? rs.getBytes( 1 ) : null;
            }
        }
    }

    private boolean hasEmbedding( final UUID id ) throws SQLException {
        return fetchVec( id ) != null;
    }

    private void insertRawEmbedding( final UUID id, final String model, final byte[] vec )
            throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec) "
               + "VALUES (?, ?, ?, ?)" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, model );
            ps.setInt( 3, DIM );
            ps.setBytes( 4, vec );
            ps.executeUpdate();
        }
    }

    /**
     * Back-dates the {@code updated} column for the given chunk's embedding so
     * it is strictly before the chunk's {@code modified} timestamp, making it
     * appear stale to {@link EmbeddingIndexService#indexStale(String)}.
     */
    private void backdateEmbeddingUpdated( final UUID chunkId, final String model )
            throws SQLException {
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "UPDATE content_chunk_embeddings "
               + "SET updated = NOW() - INTERVAL '1 hour' "
               + "WHERE chunk_id = ? AND model_code = ?" ) ) {
            ps.setObject( 1, chunkId );
            ps.setString( 2, model );
            ps.executeUpdate();
        }
        // Bump the chunk's modified timestamp to now so updated < modified
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "UPDATE kg_content_chunks SET modified = NOW() WHERE id = ?" ) ) {
            ps.setObject( 1, chunkId );
            ps.executeUpdate();
        }
    }

    // ---- indexStale tests ----

    @Test
    void indexStale_embedsMissingAndOutdatedChunksOnly() throws SQLException {
        // 3 chunks: chunk 0 = current embedding, chunk 1 = stale (backdated), chunk 2 = no embedding.
        // Reconcile must embed exactly chunks 1 and 2; chunk 0 stays untouched.
        final List< UUID > ids = seedChunks( 3 );
        stubBatchEmbed( 3, false );

        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        // Embed all 3 first to establish a baseline.
        svc.indexAll( MODEL );
        assertEquals( 3, countRows() );

        final byte[] originalVec = fetchVec( ids.get( 0 ) );

        // Make chunk 1 stale: its embedding.updated is before chunk.modified.
        backdateEmbeddingUpdated( ids.get( 1 ), MODEL );
        // Make chunk 2 missing: delete its embedding row.
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM content_chunk_embeddings WHERE chunk_id = ? AND model_code = ?" ) ) {
            ps.setObject( 1, ids.get( 2 ) );
            ps.setString( 2, MODEL );
            ps.executeUpdate();
        }
        assertEquals( 2, countRows(), "setup: 1 row deleted" );

        // Reconcile — must embed chunk 1 (stale) and chunk 2 (missing); skip chunk 0 (current).
        final int reconciled = svc.indexStale( MODEL );

        assertEquals( 2, reconciled, "exactly 2 stale/missing chunks reconciled" );
        assertEquals( 3, countRows(), "all 3 rows present after reconcile" );
        // Chunk 0's vec must be unchanged (not re-embedded).
        final byte[] afterVec = fetchVec( ids.get( 0 ) );
        assertTrue( Arrays.equals( originalVec, afterVec ),
            "chunk 0 vec must be unchanged (was not stale)" );
        assertTrue( hasEmbedding( ids.get( 1 ) ), "chunk 1 re-embedded" );
        assertTrue( hasEmbedding( ids.get( 2 ) ), "chunk 2 newly embedded" );
    }

    @Test
    void indexStale_noopWhenNothingStale() throws SQLException {
        // All 2 chunks have up-to-date embeddings — reconcile returns 0.
        final List< UUID > ids = seedChunks( 2 );
        stubBatchEmbed( 2, false );

        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        svc.indexAll( MODEL );
        assertEquals( 2, countRows() );

        // Reconcile with nothing stale.
        final int reconciled = svc.indexStale( MODEL );

        assertEquals( 0, reconciled, "no stale rows — reconcile is a no-op" );
        assertEquals( 2, countRows(), "row count unchanged" );
    }
}
