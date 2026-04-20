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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link HybridSearchService}. All dense retrieval and
 * embedder calls go through mocks so the fuser and fallback policy can be
 * exercised deterministically.
 */
class HybridSearchServiceTest {

    private static final int DIM = 4;

    /** Hand-built vector index that returns canned top-k chunk lists. */
    private static ChunkVectorIndex fixedIndex( final List< ScoredChunk > chunks, final boolean ready ) {
        return new ChunkVectorIndex() {
            @Override public List< ScoredChunk > topKChunks( final float[] q, final int k ) { return chunks; }
            @Override public boolean isReady() { return ready; }
            @Override public int dimension() { return DIM; }
        };
    }

    private static DenseRetriever denseFromChunks( final List< ScoredChunk > chunks ) {
        return new DenseRetriever( fixedIndex( chunks, true ), PageAggregation.SUM_TOP_3, 100, 100 );
    }

    private static HybridFuser defaultFuser() {
        return new HybridFuser( 60, 1.0, 1.5, 20 );
    }

    @Test
    void disabledInstancePassesThroughBm25() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        final DenseRetriever dense = denseFromChunks( List.of() );
        final HybridSearchService svc =
            new HybridSearchService( embedder, dense, defaultFuser(), /*enabled*/ false );
        assertEquals( List.of( "A", "B", "C" ), svc.rerank( "q", List.of( "A", "B", "C" ) ) );
        verifyNoInteractions( embedder );
    }

    @Test
    void emptyEmbeddingFallsBackToBm25() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.empty() );
        final DenseRetriever dense = denseFromChunks( List.of() );
        final HybridSearchService svc =
            new HybridSearchService( embedder, dense, defaultFuser(), true );
        final List< String > input = List.of( "A", "B", "C" );
        assertEquals( input, svc.rerank( "q", input ) );
    }

    @Test
    void embedderThrowingFallsBackToBm25() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenThrow( new RuntimeException( "boom" ) );
        final DenseRetriever dense = denseFromChunks( List.of() );
        final HybridSearchService svc =
            new HybridSearchService( embedder, dense, defaultFuser(), true );
        assertEquals( List.of( "A", "B" ), svc.rerank( "q", List.of( "A", "B" ) ) );
    }

    @Test
    void denseOnlyHitsAreAppendedToFusedOrder() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 1f, 0f, 0f, 0f } ) );
        // Dense retrieval surfaces X and B (B also in BM25); BM25 has A, B.
        // RRF should place B high (both lists), then A or X depending on weights.
        final List< ScoredChunk > chunks = List.of(
            new ScoredChunk( java.util.UUID.randomUUID(), "X", 0.95 ),
            new ScoredChunk( java.util.UUID.randomUUID(), "B", 0.88 )
        );
        final DenseRetriever dense = denseFromChunks( chunks );
        final HybridSearchService svc =
            new HybridSearchService( embedder, dense, defaultFuser(), true );
        final List< String > fused = svc.rerank( "q", List.of( "A", "B" ) );
        assertTrue( fused.contains( "A" ), "BM25 hits must remain" );
        assertTrue( fused.contains( "B" ) );
        assertTrue( fused.contains( "X" ), "dense-only hit must be surfaced" );
    }

    @Test
    void emptyBm25WithDenseHitsYieldsDenseOrder() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0f, 1f, 0f, 0f } ) );
        final List< ScoredChunk > chunks = List.of(
            new ScoredChunk( java.util.UUID.randomUUID(), "P1", 0.9 ),
            new ScoredChunk( java.util.UUID.randomUUID(), "P2", 0.8 )
        );
        final DenseRetriever dense = denseFromChunks( chunks );
        final HybridSearchService svc =
            new HybridSearchService( embedder, dense, defaultFuser(), true );
        final List< String > fused = svc.rerank( "q", List.of() );
        assertEquals( List.of( "P1", "P2" ), fused );
    }

    @Test
    void nullOrBlankQueryIsPassthrough() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        final DenseRetriever dense = denseFromChunks( List.of() );
        final HybridSearchService svc =
            new HybridSearchService( embedder, dense, defaultFuser(), true );
        final List< String > bm25 = List.of( "A", "B" );
        assertSame( bm25, svc.rerank( null, bm25 ) );
        assertSame( bm25, svc.rerank( "   ", bm25 ) );
        assertEquals( List.of(), svc.rerank( null, null ) );
    }

    @Test
    void indexNotReadyReturnsBm25Unchanged() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 1f, 0f, 0f, 0f } ) );
        // isReady == false ⇒ DenseRetriever.retrieve returns empty list.
        final DenseRetriever dense =
            new DenseRetriever( fixedIndex( List.of(), false ), PageAggregation.SUM_TOP_3, 100, 100 );
        final HybridSearchService svc =
            new HybridSearchService( embedder, dense, defaultFuser(), true );
        assertEquals( List.of( "A", "B" ), svc.rerank( "q", List.of( "A", "B" ) ) );
    }

    @Test
    void prefetchReturnsEmbedderResultWhenEnabled() throws Exception {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        final float[] vec = { 1f, 0f, 0f, 0f };
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( vec ) );
        final HybridSearchService svc =
            new HybridSearchService( embedder, denseFromChunks( List.of() ), defaultFuser(), true );
        final Optional< float[] > result = svc.prefetchQueryEmbedding( "q" )
            .get( 5, TimeUnit.SECONDS );
        assertTrue( result.isPresent() );
        assertArrayEquals( vec, result.get(), 0.0001f );
    }

    @Test
    void prefetchReturnsEmptyWhenDisabled() throws Exception {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        final HybridSearchService svc =
            new HybridSearchService( embedder, denseFromChunks( List.of() ), defaultFuser(), /*enabled*/ false );
        final Optional< float[] > result = svc.prefetchQueryEmbedding( "q" )
            .get( 5, TimeUnit.SECONDS );
        assertTrue( result.isEmpty() );
        verifyNoInteractions( embedder );
    }

    @Test
    void prefetchReturnsEmptyForBlankOrNullQuery() throws Exception {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        final HybridSearchService svc =
            new HybridSearchService( embedder, denseFromChunks( List.of() ), defaultFuser(), true );
        assertTrue( svc.prefetchQueryEmbedding( null ).get( 5, TimeUnit.SECONDS ).isEmpty() );
        assertTrue( svc.prefetchQueryEmbedding( "   " ).get( 5, TimeUnit.SECONDS ).isEmpty() );
        verifyNoInteractions( embedder );
    }

    @Test
    void rerankWithUsesProvidedEmbeddingAndSkipsEmbedderCall() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        // Dense surfaces X (only) so we can prove dense+fuse path ran.
        final List< ScoredChunk > chunks = List.of(
            new ScoredChunk( java.util.UUID.randomUUID(), "X", 0.9 )
        );
        final HybridSearchService svc =
            new HybridSearchService( embedder, denseFromChunks( chunks ), defaultFuser(), true );
        final List< String > fused = svc.rerankWith(
            "q", List.of( "A", "B" ), Optional.of( new float[]{ 1f, 0f, 0f, 0f } ) );
        assertTrue( fused.contains( "A" ) );
        assertTrue( fused.contains( "B" ) );
        assertTrue( fused.contains( "X" ), "dense-only hit must be surfaced" );
        verify( embedder, never() ).embed( anyString() );
    }

    @Test
    void rerankWithEmptyEmbeddingFallsBackToBm25() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        final HybridSearchService svc =
            new HybridSearchService( embedder, denseFromChunks( List.of() ), defaultFuser(), true );
        final List< String > bm25 = List.of( "A", "B", "C" );
        assertEquals( bm25, svc.rerankWith( "q", bm25, Optional.empty() ) );
        verify( embedder, never() ).embed( anyString() );
    }

    @Test
    void rerankWithDisabledServicePassesThroughWithoutEmbedder() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        final HybridSearchService svc =
            new HybridSearchService( embedder, denseFromChunks( List.of() ), defaultFuser(), /*enabled*/ false );
        final List< String > bm25 = List.of( "A", "B" );
        assertEquals( bm25, svc.rerankWith( "q", bm25, Optional.of( new float[]{ 1f, 0f, 0f, 0f } ) ) );
        verifyNoInteractions( embedder );
    }

    @Test
    void constructorRejectsNulls() {
        final DenseRetriever dense = denseFromChunks( List.of() );
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        assertThrows( IllegalArgumentException.class,
            () -> new HybridSearchService( null, dense, defaultFuser(), true ) );
        assertThrows( IllegalArgumentException.class,
            () -> new HybridSearchService( embedder, null, defaultFuser(), true ) );
        assertThrows( IllegalArgumentException.class,
            () -> new HybridSearchService( embedder, dense, null, true ) );
    }
}
