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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins two non-obvious guarantees of {@link HybridSearchService} that the
 * existing test file does not cover:
 *
 * <ol>
 *   <li>A {@link DenseRetriever} that throws {@link RuntimeException} during
 *       {@code retrieve} — e.g. the in-memory vector index blows up on a bad
 *       cast — must be caught and collapsed to BM25-only output. This is the
 *       user-facing contract: "search still works if embeddings break."</li>
 *   <li>When the {@link HybridFuser}'s truncate window is smaller than the
 *       BM25 input list, any BM25 items the fuser drops must be re-appended
 *       to the tail of the result so a user's original Lucene hits never
 *       disappear. Reorder-not-remove is the stated contract and a regression
 *       here would silently delete search hits that used to show.</li>
 * </ol>
 */
class HybridSearchServiceFailureTest {

    private static final int DIM = 4;

    /** ChunkVectorIndex whose topKChunks unconditionally throws. */
    private static ChunkVectorIndex throwingIndex() {
        return new ChunkVectorIndex() {
            @Override public List< ScoredChunk > topKChunks( final float[] q, final int k ) {
                throw new IllegalStateException( "vector index corruption" );
            }
            @Override public boolean isReady() { return true; }
            @Override public int dimension() { return DIM; }
        };
    }

    /** ChunkVectorIndex returning a fixed canned list. */
    private static ChunkVectorIndex fixedIndex( final List< ScoredChunk > chunks ) {
        return new ChunkVectorIndex() {
            @Override public List< ScoredChunk > topKChunks( final float[] q, final int k ) { return chunks; }
            @Override public boolean isReady() { return true; }
            @Override public int dimension() { return DIM; }
        };
    }

    @Test
    void denseRetrieverThrowingFallsBackToBm25() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) )
                .thenReturn( Optional.of( new float[]{ 1f, 0f, 0f, 0f } ) );
        final DenseRetriever dense =
                new DenseRetriever( throwingIndex(), PageAggregation.SUM_TOP_3, 100, 100 );
        final HybridSearchService svc =
                new HybridSearchService( embedder, dense, new HybridFuser( 60, 1.0, 1.5, 20 ), true );

        // User ran a valid query, embedding succeeded, then vector index exploded.
        // Search must still return the BM25 list verbatim so the page works.
        final List< String > bm25 = List.of( "A", "B", "C" );
        assertEquals( bm25, svc.rerank( "legit query", bm25 ),
                "DenseRetriever RuntimeException must be caught and collapsed to BM25 passthrough — "
                        + "otherwise a corrupt vector index takes down /api/search" );
    }

    @Test
    void bm25TailPreservedWhenFuserTruncationWouldDropItems() {
        // Fuser configured with truncate=2 — the fused output can contain at most
        // 2 items, even though BM25 has 5. Without the tail-preservation loop in
        // HybridSearchService, items C/D/E would vanish from the user's result page.
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) )
                .thenReturn( Optional.of( new float[]{ 1f, 0f, 0f, 0f } ) );

        // Dense strongly favours X → X lands at top of fused list with truncate=2.
        final List< ScoredChunk > chunks = List.of(
                new ScoredChunk( UUID.randomUUID(), "X", 0.99 )
        );
        final DenseRetriever dense =
                new DenseRetriever( fixedIndex( chunks ), PageAggregation.SUM_TOP_3, 100, 100 );
        final HybridFuser narrowFuser = new HybridFuser( 60, 1.0, 1.5, /*truncate*/ 2 );
        final HybridSearchService svc =
                new HybridSearchService( embedder, dense, narrowFuser, true );

        final List< String > bm25 = List.of( "A", "B", "C", "D", "E" );
        final List< String > fused = svc.rerank( "legit query", bm25 );

        assertTrue( fused.contains( "X" ),
                "Dense-only hit must appear in fused output" );
        // The key contract: every BM25 page is still present in the result.
        for ( final String name : bm25 ) {
            assertTrue( fused.contains( name ),
                    "BM25 page '" + name + "' was dropped by truncation — "
                            + "reorder-not-remove contract broken" );
        }
        // Order contract: the fused block comes first, then the BM25 tail.
        // X should appear before any BM25-only item that was pushed out by truncation.
        final int xIdx = fused.indexOf( "X" );
        final int eIdx = fused.indexOf( "E" );
        assertTrue( xIdx < eIdx,
                "Fused block must come before re-appended BM25 tail (X=" + xIdx + ", E=" + eIdx + ")" );
        // No duplicates — LinkedHashSet dedupe guarantee.
        assertEquals( fused.size(), fused.stream().distinct().count(),
                "Result list must be free of duplicates" );
    }

    @Test
    void fusedOutputIsUnmodifiable() {
        // Downstream REST layer treats the fused list as a reorder key; if a
        // caller ever tried to mutate it we want a hard failure, not silent
        // state drift between requests.
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) )
                .thenReturn( Optional.of( new float[]{ 1f, 0f, 0f, 0f } ) );
        final DenseRetriever dense = new DenseRetriever(
                fixedIndex( List.of( new ScoredChunk( UUID.randomUUID(), "X", 0.9 ) ) ),
                PageAggregation.SUM_TOP_3, 100, 100 );
        final HybridSearchService svc = new HybridSearchService(
                embedder, dense, new HybridFuser( 60, 1.0, 1.5, 20 ), true );

        final List< String > fused = svc.rerank( "q", List.of( "A", "B" ) );

        assertFalse( fused.isEmpty() );
        try {
            fused.add( "Z" );
            assertTrue( false, "fused result must be unmodifiable" );
        } catch ( final UnsupportedOperationException expected ) {
            // contract holds
        }
    }
}
