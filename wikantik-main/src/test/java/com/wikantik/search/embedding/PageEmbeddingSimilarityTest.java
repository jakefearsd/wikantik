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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.search.embedding.PageEmbeddingSimilarity.ScoredPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JDBC-backed tests for {@link PageEmbeddingSimilarity}: page-to-page similarity from the centroid
 * of each page's chunk embeddings — no Knowledge Graph involved.
 */
@RequiresPostgres
class PageEmbeddingSimilarityTest {

    private static final String MODEL_CODE = "test-model";

    private static DataSource dataSource;
    private final AtomicLong clock = new AtomicLong( 0L );
    private PageEmbeddingSimilarity similarity;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
        similarity = new PageEmbeddingSimilarity( dataSource, MODEL_CODE, clock::get );
    }

    @Test
    void ranksOtherPagesByCosineOfChunkCentroidsAndExcludesTheQueryPage() {
        // Alpha's two chunks average to the (1,1,0,0) direction.
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Alpha", 1, 0f, 1f, 0f, 0f );
        chunk( "Beta", 0, 2f, 2f, 0f, 0f );      // same direction as Alpha's centroid
        chunk( "Gamma", 0, 1f, 0f, 0f, 0f );     // 45 degrees off
        chunk( "Delta", 0, 0f, 0f, 1f, 0f );     // orthogonal

        final List< ScoredPage > out = similarity.similarTo( "Alpha", 10, name -> true );

        assertEquals( List.of( "Beta", "Gamma", "Delta" ), out.stream().map( ScoredPage::name ).toList() );
        assertEquals( 1.0, out.get( 0 ).score(), 1e-5 );
        assertEquals( 1.0 / Math.sqrt( 2.0 ), out.get( 1 ).score(), 1e-5 );
        assertEquals( 0.0, out.get( 2 ).score(), 1e-5 );
    }

    @Test
    void rejectedCandidatesAreSkippedAndTheLimitIsFilledFromFurtherDown() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0.1f, 0f, 0f );
        chunk( "Gamma", 0, 1f, 0.5f, 0f, 0f );
        chunk( "Delta", 0, 1f, 1f, 0f, 0f );

        final List< ScoredPage > out = similarity.similarTo( "Alpha", 2, name -> !name.equals( "Beta" ) );

        assertEquals( List.of( "Gamma", "Delta" ), out.stream().map( ScoredPage::name ).toList() );
    }

    @Test
    void limitTruncatesTheRanking() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0.1f, 0f, 0f );
        chunk( "Gamma", 0, 1f, 0.5f, 0f, 0f );

        assertEquals( List.of( "Beta" ),
            similarity.similarTo( "Alpha", 1, name -> true ).stream().map( ScoredPage::name ).toList() );
        assertEquals( List.of(), similarity.similarTo( "Alpha", 0, name -> true ) );
    }

    @Test
    void pageWithoutEmbeddingsHasNoSimilarPages() {
        chunk( "Beta", 0, 1f, 0f, 0f, 0f );

        assertEquals( List.of(), similarity.similarTo( "Missing", 5, name -> true ) );
        assertEquals( List.of(), similarity.similarTo( null, 5, name -> true ) );
        assertEquals( List.of(), similarity.similarTo( " ", 5, name -> true ) );
    }

    @Test
    void embeddingsFromOtherModelsAreIgnored() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0f, 0f, 0f );
        chunkWithModel( "Gamma", 0, "other-model", new float[]{ 1f, 0f, 0f, 0f } );

        assertEquals( List.of( "Beta" ),
            similarity.similarTo( "Alpha", 5, name -> true ).stream().map( ScoredPage::name ).toList() );
    }

    @Test
    void corruptVectorBytesAreSkippedNotThrown() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0f, 0f, 0f );
        final UUID bad = insertChunk( "Gamma", 0 );
        insertEmbedding( bad, MODEL_CODE, 4, new byte[]{ 1, 2, 3 } );   // 3 bytes, dim says 4 floats

        assertEquals( List.of( "Beta" ),
            similarity.similarTo( "Alpha", 5, name -> true ).stream().map( ScoredPage::name ).toList() );
    }

    @Test
    void emptyLoadIsCachedOnlyBrieflySoNewEmbeddingsAppearSoon() {
        assertFalse( similarity.isReady() );
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        assertFalse( similarity.isReady(),
            "an empty load is negatively cached, so a crawler on an unembedded wiki can't force a scan per request" );

        clock.addAndGet( PageEmbeddingSimilarity.RETRY_TTL.toNanos() + 1 );
        assertTrue( similarity.isReady() );
    }

    @Test
    void failedRefreshKeepsServingTheLastGoodSnapshot() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0f, 0f, 0f );
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( dataSource );
        final PageEmbeddingSimilarity flaky = new PageEmbeddingSimilarity( faulty, MODEL_CODE, clock::get );
        assertEquals( 1, flaky.similarTo( "Alpha", 5, name -> true ).size() );

        faulty.reset();   // the statement counter is cumulative; the first load already used statement 1
        faulty.failOn( 1, new IllegalStateException( "database unavailable" ) );
        clock.addAndGet( PageEmbeddingSimilarity.CACHE_TTL.toNanos() + 1 );

        assertEquals( List.of( "Beta" ),
            flaky.similarTo( "Alpha", 5, name -> true ).stream().map( ScoredPage::name ).toList(),
            "a transient failure must not blank the panel" );
    }

    @Test
    void examinesABoundedNumberOfCandidatesWhenMostAreRejected() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        final int pages = PageEmbeddingSimilarity.MAX_CANDIDATES_EXAMINED + 5;
        for ( int i = 0; i < pages; i++ ) {
            chunk( String.format( "P%04d", i ), 0, 1f, i / 100f, 0f, 0f );   // P0000 most similar, rising angle
        }
        final java.util.concurrent.atomic.AtomicInteger examined = new java.util.concurrent.atomic.AtomicInteger();

        final List< ScoredPage > out = similarity.similarTo( "Alpha", 5, name -> {
            examined.incrementAndGet();
            return false;
        } );

        assertEquals( List.of(), out );
        assertEquals( PageEmbeddingSimilarity.MAX_CANDIDATES_EXAMINED, examined.get() );
    }

    @Test
    void centroidsAreCachedUntilTheTtlElapses() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0f, 0f, 0f );
        assertEquals( 1, similarity.similarTo( "Alpha", 5, name -> true ).size() );

        chunk( "Gamma", 0, 1f, 0f, 0f, 0f );
        assertEquals( 1, similarity.similarTo( "Alpha", 5, name -> true ).size(),
            "within the TTL the cached snapshot is served" );

        clock.addAndGet( PageEmbeddingSimilarity.CACHE_TTL.toNanos() + 1 );
        assertEquals( 2, similarity.similarTo( "Alpha", 5, name -> true ).size(),
            "after the TTL the snapshot is rebuilt and the new page appears" );
    }

    @Test
    void rankingIsComputedOncePerPagePerSnapshotWhileTheFilterStaysPerCall() {
        // The 2026-11-13 soak profile: retrieve_context/bundle call similarTo once per result page per
        // request, and re-scoring every centroid each time was 44% of all CPU. The ranking depends only
        // on (page, snapshot), so it is memoised; the caller's filter and limit still apply per call.
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0.1f, 0f, 0f );
        chunk( "Gamma", 0, 1f, 0.5f, 0f, 0f );
        chunk( "Delta", 0, 1f, 1f, 0f, 0f );

        assertEquals( List.of( "Beta", "Gamma" ),
            similarity.similarTo( "Alpha", 2, name -> true ).stream().map( ScoredPage::name ).toList() );
        assertEquals( List.of( "Gamma", "Delta" ),
            similarity.similarTo( "Alpha", 2, name -> !name.equals( "Beta" ) ).stream().map( ScoredPage::name ).toList(),
            "a different caller filter over the memoised ranking" );
        assertEquals( List.of( "Beta" ),
            similarity.similarTo( "Alpha", 1, name -> true ).stream().map( ScoredPage::name ).toList() );
        assertEquals( 1, similarity.rankingsComputed(), "Alpha is ranked once, however many callers ask" );

        similarity.similarTo( "Beta", 5, name -> true );
        assertEquals( 2, similarity.rankingsComputed(), "each page gets its own ranking" );

        clock.addAndGet( PageEmbeddingSimilarity.CACHE_TTL.toNanos() + 1 );
        chunk( "Epsilon", 0, 1f, 0f, 0f, 0f );   // identical direction to Alpha: must now rank first
        assertEquals( "Epsilon", similarity.similarTo( "Alpha", 1, name -> true ).get( 0 ).name(),
            "a refreshed snapshot drops the old rankings" );
        assertEquals( 3, similarity.rankingsComputed() );
    }

    @Test
    void databaseFailureDegradesToNoSuggestions() {
        chunk( "Alpha", 0, 1f, 0f, 0f, 0f );
        chunk( "Beta", 0, 1f, 0f, 0f, 0f );
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( dataSource );
        faulty.failOn( 1, new IllegalStateException( "database unavailable" ) );
        final PageEmbeddingSimilarity broken = new PageEmbeddingSimilarity( faulty, MODEL_CODE, clock::get );

        assertEquals( List.of(), broken.similarTo( "Alpha", 5, name -> true ) );
    }

    @Test
    void constructorRejectsMissingArguments() {
        assertThrows( IllegalArgumentException.class, () -> new PageEmbeddingSimilarity( null, MODEL_CODE ) );
        assertThrows( IllegalArgumentException.class, () -> new PageEmbeddingSimilarity( dataSource, " " ) );
    }

    // ---- fixtures ----

    private void chunk( final String page, final int index, final float... vec ) {
        chunkWithModel( page, index, MODEL_CODE, vec );
    }

    private void chunkWithModel( final String page, final int index, final String model, final float[] vec ) {
        final UUID id = insertChunk( page, index );
        final ByteBuffer buf = ByteBuffer.allocate( vec.length * Float.BYTES ).order( ByteOrder.LITTLE_ENDIAN );
        for ( final float f : vec ) buf.putFloat( f );
        insertEmbedding( id, model, vec.length, buf.array() );
    }

    private UUID insertChunk( final String pageName, final int chunkIndex ) {
        final UUID id = UUID.randomUUID();
        final String text = pageName + " chunk " + chunkIndex;
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

    private void insertEmbedding( final UUID chunkId, final String model, final int dim, final byte[] raw ) {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO content_chunk_embeddings (chunk_id, model_code, dim, vec) VALUES (?, ?, ?, ?)" ) ) {
            ps.setObject( 1, chunkId );
            ps.setString( 2, model );
            ps.setInt( 3, dim );
            ps.setBytes( 4, raw );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
    }
}
