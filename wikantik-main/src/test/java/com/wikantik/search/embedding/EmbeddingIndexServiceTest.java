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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int DIM = 4;

    private static DataSource dataSource;
    private TextEmbeddingClient client;

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
        client = mock( TextEmbeddingClient.class );
        when( client.dimension() ).thenReturn( DIM );
        when( client.modelName() ).thenReturn( MODEL );
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

        // second embedding returns a different vector — ON CONFLICT should update
        final float[] different = new float[]{ 9f, 9f, 9f, 9f };
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
        return new float[]{ 0.1f, 0.2f, 0.3f, 0.4f };
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
}
