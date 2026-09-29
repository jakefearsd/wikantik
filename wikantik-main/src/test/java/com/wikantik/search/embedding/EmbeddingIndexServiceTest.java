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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round-trip coverage for {@link EmbeddingIndexService} against a real
 * PostgreSQL testcontainer. Exercises the batch happy path, the per-item
 * fallback that the bge-m3 NaN bug forced into {@code ExperimentIndexer},
 * and the incremental update path that relies on {@code ON CONFLICT
 * DO UPDATE}.
 */
@RequiresPostgres
class EmbeddingIndexServiceTest {

    private static final String MODEL = "qwen3-embedding-0.6b";
    /** Must match the {@code vector(1024)} column dimension enforced by V032. */
    private static final int DIM = 1024;

    private static DataSource dataSource;
    private TextEmbeddingClient client;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestDb.createDataSource();
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

    /**
     * A full backfill takes hours of CPU inference. If it runs inside one
     * transaction, any interruption (crash, restart, redeploy) discards every
     * embedded row — which is exactly what left production with zero rows after
     * 2208 chunks had been embedded. Completed batches must be durable before
     * the run finishes.
     *
     * <p>{@code countRows()} opens its own connection, so it observes only
     * COMMITTED rows — that is what makes this a real durability assertion
     * rather than a read of the indexer's own uncommitted transaction.</p>
     */
    @Test
    void indexAll_commitsCompletedBatches_beforeTheRunFinishes() throws SQLException {
        seedChunks( 8 );
        stubBatchEmbed( 8, false );

        final List< Integer > visibleToOtherConnections = new ArrayList<>();

        final EmbeddingIndexService svc = new EmbeddingIndexService(
            dataSource, client, /*batchSize*/ 2, /*contextResolver*/ null, /*commitBatchSize*/ 4 );

        svc.indexAll( MODEL, upserted -> {
            try {
                visibleToOtherConnections.add( countRows() );
            } catch( final SQLException e ) {
                throw new IllegalStateException( "probe query failed", e );
            }
        } );

        assertEquals( 8, countRows(), "every row committed once the run completes" );
        assertTrue(
            visibleToOtherConnections.stream().anyMatch( n -> n > 0 && n < 8 ),
            "another connection must see committed partial progress while the run is still "
          + "in flight — otherwise an interrupted backfill loses every embedded row. Observed: "
          + visibleToOtherConnections );
    }

    /**
     * {@code indexChunks} already catches both {@code SQLException} and {@code RuntimeException}
     * around its transaction (rolling back explicitly in both) — but not {@link Error}. An
     * {@code AssertionError}/OOM anywhere in that transaction bypasses both catches and, in the
     * pre-{@code Jdbc} code, reaches the end of the try-with-resources block without an explicit
     * {@code rollback()} ever being called. A raw test connection aborts the open transaction on
     * close either way, so "no partial rows" can't tell the two code paths apart — counting the
     * {@code rollback()} calls directly can.
     */
    @Test
    void indexChunks_rollsBackExplicitlyEvenOnAnErrorNeitherExistingCatchCanSee() throws SQLException {
        final List< UUID > ids = seedChunks( 1 );
        stubBatchEmbed( 1, false );

        final com.wikantik.jdbc.testing.FaultInjectingDataSource tracking =
            new com.wikantik.jdbc.testing.FaultInjectingDataSource( dataSource );
        // 2 statement-creation calls per indexChunks(): #1 = SELECT_BY_IDS_SQL prepare,
        // #2 = UPSERT_SQL prepare (inside the write batch). Fail the UPSERT prepare with an Error.
        tracking.failOn( 2, new AssertionError( "simulated invariant violation" ) );

        final EmbeddingIndexService svc = new EmbeddingIndexService( tracking, client, 32 );

        assertThrows( AssertionError.class, () -> svc.indexChunks( ids, MODEL ) );
        assertEquals( 0, countRows(), "the only write batch never committed — no partial rows" );
        assertEquals( 1, tracking.rollbacks(),
            "the open transaction must be explicitly rolled back, even for a failure neither "
          + "the SQLException nor the RuntimeException catch can see" );
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

    // ---- transient-retry tests (A, B, C) ----

    /**
     * Test A — persistent transient 503: reconcile retries the batch, then aborts.
     *
     * <p>Before the fix: per-item fallback was attempted for every chunk,
     * resulting in ~N WARNs and upserted=0 with all chunks marked poisoned.
     * After the fix: the batch is retried {@code maxTransientRetries} times,
     * then a transient {@link EmbeddingException} is thrown — no chunks are
     * poisoned, no rows are committed.</p>
     */
    @Test
    void embedTransientAlwaysFails_abortsReconcileWithoutPoisoningChunks() throws SQLException {
        final EmbeddingIndexService.Sleeper noOpSleeper = millis -> { /* no-op for tests */ };

        seedChunks( 3 );

        // Every embed() call throws a transient 503-style exception.
        when( client.embed( ArgumentMatchers.anyList(),
                            ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenThrow( new EmbeddingException( "Ollama embed HTTP 503: server busy", true ) );

        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, /*batchSize*/ 32 );
        svc.configureTransientRetryForTest( 2, noOpSleeper );

        // Must throw a transient EmbeddingException — NOT silently swallow it.
        final EmbeddingException thrown = assertThrows( EmbeddingException.class,
            () -> svc.indexAll( MODEL ),
            "persistent transient failure must abort the reconcile" );
        assertTrue( thrown.isTransient(), "aborted exception must be flagged transient" );

        // embed() must have been called exactly 3 times: initial + 2 retries (maxTransientRetries=2).
        verify( client, times( 3 ) )
            .embed( ArgumentMatchers.anyList(), ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) );

        // No rows committed — all chunks remain stale for the next reconcile cycle.
        assertEquals( 0, countRows(), "no rows must be upserted when the reconcile aborts" );
    }

    /**
     * Test B — non-transient poison pill: bad chunk is isolated, good chunks succeed.
     *
     * <p>A non-transient {@link EmbeddingException} on the batch call triggers the
     * per-item fallback. The poison chunk (chunk #1) is skipped; the other two are
     * upserted normally.</p>
     */
    @Test
    void embedNonTransientBatchFail_perItemFallbackSkipsPoisonChunkOnly() throws SQLException {
        final List< UUID > ids = seedChunks( 3 );

        final AtomicInteger call = new AtomicInteger();
        when( client.embed( ArgumentMatchers.anyList(),
                            ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenAnswer( ( InvocationOnMock inv ) -> {
                final int n = call.incrementAndGet();
                final List< String > texts = inv.getArgument( 0 );
                if ( n == 1 ) {
                    // Batch call fails with a non-transient EmbeddingException (e.g., bad response shape).
                    throw new EmbeddingException( "non-transient: unexpected response shape", false );
                }
                // Per-item calls (size 1) — chunk #1 is poisoned.
                if ( texts.get( 0 ).contains( "#1" ) ) {
                    throw new EmbeddingException( "poison chunk: model rejected this input", false );
                }
                return List.of( randomVec() );
            } );

        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, /*batchSize*/ 32 );
        final int embedded = svc.indexAll( MODEL );

        assertEquals( 2, embedded, "poison chunk skipped; two good chunks must be upserted" );
        assertEquals( 2, countRows() );
        assertTrue(  hasEmbedding( ids.get( 0 ) ), "chunk #0 must be embedded" );
        assertTrue( !hasEmbedding( ids.get( 1 ) ), "chunk #1 (poison) must be skipped" );
        assertTrue(  hasEmbedding( ids.get( 2 ) ), "chunk #2 must be embedded" );
    }

    /**
     * Test C — transient failure recovers on retry: reconcile succeeds.
     *
     * <p>The first batch call throws a transient exception; the second succeeds.
     * With a no-op sleeper the retry is instant. All chunks must be upserted and
     * none should be marked poisoned.</p>
     */
    @Test
    void embedTransientFailsOnce_recoversOnRetry_upsertAll() throws SQLException {
        final EmbeddingIndexService.Sleeper noOpSleeper = millis -> { /* no-op for tests */ };

        seedChunks( 3 );

        final AtomicInteger call = new AtomicInteger();
        when( client.embed( ArgumentMatchers.anyList(),
                            ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenAnswer( ( InvocationOnMock inv ) -> {
                final int n = call.incrementAndGet();
                if ( n == 1 ) {
                    // First attempt throws transient (Ollama 503).
                    throw new EmbeddingException( "Ollama embed HTTP 503: max pending requests", true );
                }
                // Retry succeeds — return valid vectors for all texts in the batch.
                final List< String > texts = inv.getArgument( 0 );
                final List< float[] > out = new ArrayList<>( texts.size() );
                for ( int i = 0; i < texts.size(); i++ ) {
                    out.add( randomVec() );
                }
                return out;
            } );

        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, /*batchSize*/ 32 );
        svc.configureTransientRetryForTest( 2, noOpSleeper );

        final int embedded = svc.indexAll( MODEL );

        assertEquals( 3, embedded, "all chunks must be upserted after recovery" );
        assertEquals( 3, countRows(), "all 3 rows present" );
        // embed() called exactly twice: 1 failing attempt + 1 successful retry.
        verify( client, times( 2 ) )
            .embed( ArgumentMatchers.anyList(), ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) );
    }

    // ---- constructor validation ----

    @Test
    void constructor_rejectsInvalidArguments() {
        assertThrows( IllegalArgumentException.class,
            () -> new EmbeddingIndexService( null, client, 32 ),
            "null dataSource must be rejected" );
        assertThrows( IllegalArgumentException.class,
            () -> new EmbeddingIndexService( dataSource, null, 32 ),
            "null client must be rejected" );
        assertThrows( IllegalArgumentException.class,
            () -> new EmbeddingIndexService( dataSource, client, 0 ),
            "non-positive batchSize must be rejected" );
        assertThrows( IllegalArgumentException.class,
            () -> new EmbeddingIndexService( dataSource, client, 32, null, 0 ),
            "non-positive commitBatchSize must be rejected" );
    }

    @Test
    void twoArgConstructor_usesDefaultBatchSize() {
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client );
        assertEquals( EmbeddingIndexService.DEFAULT_BATCH_SIZE, svc.batchSize() );
    }

    @Test
    void batchSize_returnsConfiguredValue() {
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 7 );
        assertEquals( 7, svc.batchSize() );
    }

    @Test
    void publicMethods_rejectBlankModelCode() {
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        assertThrows( IllegalArgumentException.class, () -> svc.indexAll( "" ) );
        assertThrows( IllegalArgumentException.class, () -> svc.indexStale( " " ) );
        assertThrows( IllegalArgumentException.class, () -> svc.indexChunks( List.of( UUID.randomUUID() ), null ) );
        assertThrows( IllegalArgumentException.class, () -> svc.deleteByModel( "" ) );
        assertThrows( IllegalArgumentException.class, () -> svc.status( null ) );
    }

    @Test
    void indexChunks_nullOrEmptyCollectionIsANoOp() {
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        assertEquals( 0, svc.indexChunks( null, MODEL ) );
        assertEquals( 0, svc.indexChunks( List.of(), MODEL ) );
    }

    // ---- status() ----

    @Test
    void status_returnsZeroSnapshotWhenNoRowsForModel() {
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        final EmbeddingIndexService.Status st = svc.status( "no-such-model" );
        assertEquals( "no-such-model", st.modelCode() );
        assertEquals( 0, st.dim() );
        assertEquals( 0, st.rowCount() );
        assertNull( st.lastUpdated() );
    }

    @Test
    void status_reflectsRowCountDimAndLastUpdatedAfterIndexing() throws SQLException {
        seedChunks( 2 );
        stubBatchEmbed( 2, false );
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        svc.indexAll( MODEL );

        final EmbeddingIndexService.Status st = svc.status( MODEL );
        assertEquals( MODEL, st.modelCode() );
        assertEquals( DIM, st.dim() );
        assertEquals( 2, st.rowCount() );
        assertNotNull( st.lastUpdated() );
    }

    @Test
    void status_wrapsSqlExceptionAsRuntimeException() throws SQLException {
        final DataSource failing = mock( DataSource.class );
        when( failing.getConnection() ).thenThrow( new SQLException( "connection refused" ) );
        final EmbeddingIndexService svc = new EmbeddingIndexService( failing, client, 32 );
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> svc.status( MODEL ) );
        assertTrue( ex.getMessage().contains( "status failed for" ) );
    }

    @Test
    void deleteByModel_wrapsSqlExceptionAsRuntimeException() throws SQLException {
        final DataSource failing = mock( DataSource.class );
        when( failing.getConnection() ).thenThrow( new SQLException( "connection refused" ) );
        final EmbeddingIndexService svc = new EmbeddingIndexService( failing, client, 32 );
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> svc.deleteByModel( MODEL ) );
        assertTrue( ex.getMessage().contains( "deleteByModel failed for" ) );
    }

    @Test
    void indexAll_wrapsSqlExceptionFromConnectionFailure() throws SQLException {
        final DataSource failing = mock( DataSource.class );
        when( failing.getConnection() ).thenThrow( new SQLException( "connection refused" ) );
        final EmbeddingIndexService svc = new EmbeddingIndexService( failing, client, 32 );
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> svc.indexAll( MODEL ) );
        assertTrue( ex.getMessage().contains( "indexAll failed for" ) );
        assertInstanceOf( SQLException.class, ex.getCause() );
    }

    @Test
    void indexChunks_wrapsSqlExceptionFromConnectionFailure() throws SQLException {
        final DataSource failing = mock( DataSource.class );
        when( failing.getConnection() ).thenThrow( new SQLException( "connection refused" ) );
        final EmbeddingIndexService svc = new EmbeddingIndexService( failing, client, 32 );
        final RuntimeException ex = assertThrows( RuntimeException.class,
            () -> svc.indexChunks( List.of( UUID.randomUUID() ), MODEL ) );
        assertTrue( ex.getMessage().contains( "indexChunks failed for" ) );
        assertInstanceOf( SQLException.class, ex.getCause() );
    }

    /**
     * A non-SQLException {@link RuntimeException} escaping mid-transaction (here, the
     * per-page context resolver blowing up) must hit {@code indexChunks}'s OWN
     * {@code catch(RuntimeException)} — rethrown unwrapped, distinct from the
     * {@code SQLException} branch above.
     */
    @Test
    void indexChunks_rethrowsRuntimeExceptionFromContextResolverUnwrapped() throws SQLException {
        final List< UUID > ids = seedChunks( 1 );
        final java.util.function.Function< String, EmbeddingTextBuilder.PageContext > explodingResolver =
            pageName -> { throw new IllegalStateException( "resolver exploded" ); };
        final EmbeddingIndexService svc =
            new EmbeddingIndexService( dataSource, client, 32, explodingResolver );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> svc.indexChunks( ids, MODEL ) );
        assertEquals( "resolver exploded", ex.getMessage() );
    }

    /**
     * The batch progress callback ({@code onBatchFlushed}) is best-effort: an exception it
     * throws must be logged and swallowed, never abort the indexing run.
     */
    @Test
    void indexAll_progressCallbackExceptionIsSwallowed() throws SQLException {
        seedChunks( 2 );
        stubBatchEmbed( 2, false );
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        final int embedded = svc.indexAll( MODEL, upserted -> { throw new RuntimeException( "callback boom" ); } );
        assertEquals( 2, embedded, "a failing progress callback must not abort the run" );
        assertEquals( 2, countRows() );
    }

    /**
     * An {@link InterruptedException} raised by the injected {@link
     * EmbeddingIndexService.Sleeper} during transient-retry backoff must restore the
     * interrupt flag and surface as a transient {@link EmbeddingException} — not be
     * silently dropped.
     */
    @Test
    void embedTransientRetry_interruptedDuringBackoff_restoresInterruptFlagAndAborts() throws SQLException {
        seedChunks( 1 );
        when( client.embed( ArgumentMatchers.anyList(), ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenThrow( new EmbeddingException( "transient 503", true ) );
        final EmbeddingIndexService.Sleeper interruptingSleeper =
            millis -> { throw new InterruptedException( "interrupted for test" ); };
        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        svc.configureTransientRetryForTest( 3, interruptingSleeper );

        try {
            final EmbeddingException ex = assertThrows( EmbeddingException.class, () -> svc.indexAll( MODEL ) );
            assertTrue( ex.isTransient() );
            assertTrue( Thread.currentThread().isInterrupted(), "interrupt flag must be restored" );
        } finally {
            Thread.interrupted(); // clear the flag so it doesn't leak into later tests
        }
    }

    /**
     * When the per-item fallback itself hits a TRANSIENT failure (the backend went down
     * mid-fallback, not the chunk's fault), it must propagate immediately rather than be
     * treated as a poisoned chunk.
     */
    @Test
    void embedPerItemFallback_transientFailurePropagatesImmediately() throws SQLException {
        seedChunks( 2 );
        final AtomicInteger call = new AtomicInteger();
        when( client.embed( ArgumentMatchers.anyList(), ArgumentMatchers.eq( EmbeddingKind.DOCUMENT ) ) )
            .thenAnswer( ( InvocationOnMock inv ) -> {
                final int n = call.incrementAndGet();
                if ( n == 1 ) {
                    // Non-transient batch failure triggers the per-item fallback.
                    throw new EmbeddingException( "bad response shape", false );
                }
                // First per-item call: backend now unavailable — transient, not poisoned.
                throw new EmbeddingException( "backend down mid-fallback", true );
            } );

        final EmbeddingIndexService svc = new EmbeddingIndexService( dataSource, client, 32 );
        final EmbeddingException ex = assertThrows( EmbeddingException.class, () -> svc.indexAll( MODEL ) );
        assertTrue( ex.isTransient(), "a transient per-item failure must not be treated as a poison chunk" );
        assertEquals( 0, countRows(), "nothing committed when the per-item fallback aborts" );
    }
}
