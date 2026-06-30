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
package com.wikantik.knowledge.bundle;

import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository.MentionableChunk;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.HybridFuser;
import com.wikantik.search.hybrid.LuceneBm25ChunkIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.ScoredChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link HybridChunkSectionSource#candidates} and {@link HybridChunkSectionSource#debugRankings}
 * end-to-end with mocked dense/BM25 rankers and a real {@link HybridFuser}. The pure
 * grouping helper is covered separately by {@link HybridChunkSectionSourceTest}.
 */
class HybridChunkSectionSourceCandidatesTest {

    private static final UUID A1 = UUID.fromString( "00000000-0000-0000-0000-0000000000a1" );
    private static final UUID B1 = UUID.fromString( "00000000-0000-0000-0000-0000000000b1" );
    private static final double EXPECTED_MAX_DENSE_SCORE = 0.9;

    private static MentionableChunk chunk( final UUID id, final String page, final String head, final String text ) {
        return new MentionableChunk( id, page, 0, List.of( head ), text );
    }

    private static ScoredChunk scored( final UUID id, final String page, final double score ) {
        return new ScoredChunk( id, page, score );
    }

    private static HybridFuser fuser() {
        return new HybridFuser( 60, 0.5, 0.5, 0 );
    }

    @Test
    void candidates_fusesDenseAndBm25_andGroupsToSections() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex dense = mock( ChunkVectorIndex.class );
        when( dense.topKChunks( any(), anyInt() ) ).thenReturn( List.of(
            scored( A1, "PageA", 0.9 ), scored( B1, "PageB", 0.8 ) ) );
        final LuceneBm25ChunkIndex bm25 = mock( LuceneBm25ChunkIndex.class );
        when( bm25.topKChunks( anyString(), anyInt() ) ).thenReturn( List.of(
            scored( A1, "PageA", 5.0 ), scored( B1, "PageB", 3.0 ) ) );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );
        when( repo.findByIds( anyList() ) ).thenReturn( List.of(
            chunk( A1, "PageA", "Intro", "a1 text" ), chunk( B1, "PageB", "Details", "b1 text" ) ) );

        final List< CandidateSection > out = new HybridChunkSectionSource(
            embedder, dense, bm25, repo, fuser(), 50 ).candidates( "query" ).sections();

        // A1 ranks first in both lists → fuses ahead of B1; one section per (slug, heading).
        assertEquals( 2, out.size() );
        assertEquals( "PageA", out.get( 0 ).slug() );
        assertEquals( "a1 text", out.get( 0 ).text() );
        assertEquals( "PageB", out.get( 1 ).slug() );
        assertTrue( out.get( 0 ).denseScore() > out.get( 1 ).denseScore(), "fused-order proxy score decreases" );
    }

    @Test
    void candidates_denseUnavailable_fallsBackToBm25Only() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.empty() );   // dense unavailable
        final ChunkVectorIndex dense = mock( ChunkVectorIndex.class );
        final LuceneBm25ChunkIndex bm25 = mock( LuceneBm25ChunkIndex.class );
        when( bm25.topKChunks( anyString(), anyInt() ) ).thenReturn( List.of( scored( B1, "PageB", 5.0 ) ) );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );
        when( repo.findByIds( anyList() ) ).thenReturn( List.of( chunk( B1, "PageB", "Details", "b1 text" ) ) );

        final List< CandidateSection > out = new HybridChunkSectionSource(
            embedder, dense, bm25, repo, fuser(), 50 ).candidates( "query" ).sections();

        assertEquals( 1, out.size() );
        assertEquals( "PageB", out.get( 0 ).slug() );
        verify( dense, org.mockito.Mockito.never() ).topKChunks( any(), anyInt() );
    }

    @Test
    void candidates_bothEmpty_returnsEmpty() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.empty() );
        final ChunkVectorIndex dense = mock( ChunkVectorIndex.class );
        final LuceneBm25ChunkIndex bm25 = mock( LuceneBm25ChunkIndex.class );
        when( bm25.topKChunks( anyString(), anyInt() ) ).thenReturn( List.of() );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );

        final List< CandidateSection > out = new HybridChunkSectionSource(
            embedder, dense, bm25, repo, fuser(), 50 ).candidates( "query" ).sections();

        assertTrue( out.isEmpty() );
        verify( repo, org.mockito.Mockito.never() ).findByIds( anyList() );
    }

    @Test
    void debugRankings_returnsDenseAndBm25_sortedScoreDesc() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex dense = mock( ChunkVectorIndex.class );
        // intentionally unsorted input → toDebugRanks must sort score-desc
        when( dense.topKChunks( any(), anyInt() ) ).thenReturn( List.of(
            scored( A1, "PageA", 0.3 ), scored( B1, "PageB", 0.9 ) ) );
        final LuceneBm25ChunkIndex bm25 = mock( LuceneBm25ChunkIndex.class );
        when( bm25.topKChunks( anyString(), anyInt() ) ).thenReturn( List.of( scored( B1, "PageB", 1.0 ) ) );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );

        final Map< String, List< HybridChunkSectionSource.DebugRank > > ranks =
            new HybridChunkSectionSource( embedder, dense, bm25, repo, fuser(), 50 ).debugRankings( "q", 25 );

        assertEquals( java.util.Set.of( "dense", "bm25" ), ranks.keySet() );
        assertEquals( B1.toString(), ranks.get( "dense" ).get( 0 ).chunkId() );    // 0.9 sorts first
        assertEquals( 0.9, ranks.get( "dense" ).get( 0 ).score(), 1e-9 );
        assertEquals( A1.toString(), ranks.get( "dense" ).get( 1 ).chunkId() );
        assertEquals( 1, ranks.get( "bm25" ).size() );

        // the requested k is forwarded to both rankers (the truncation lever for the sweep)
        verify( dense ).topKChunks( any(), eq( 25 ) );
        verify( bm25 ).topKChunks( anyString(), eq( 25 ) );
    }

    @Test
    void debugRankings_nonPositiveK_usesTopK() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex dense = mock( ChunkVectorIndex.class );
        when( dense.topKChunks( any(), anyInt() ) ).thenReturn( List.of() );
        final LuceneBm25ChunkIndex bm25 = mock( LuceneBm25ChunkIndex.class );
        when( bm25.topKChunks( anyString(), anyInt() ) ).thenReturn( List.of() );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );

        new HybridChunkSectionSource( embedder, dense, bm25, repo, fuser(), 77 ).debugRankings( "q", 0 );

        final ArgumentCaptor< Integer > k = ArgumentCaptor.forClass( Integer.class );
        verify( dense ).topKChunks( any(), k.capture() );
        assertEquals( 77, k.getValue(), "k<=0 falls back to the configured topK" );
    }

    @Test
    void topSimilarityIsMaxDenseCosineBeforeFusion() {
        // dense index returns scored chunks; the max score must surface even though
        // the fused section order uses the rank proxy.
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex dense = mock( ChunkVectorIndex.class );
        when( dense.topKChunks( any(), anyInt() ) ).thenReturn( List.of(
            scored( A1, "PageA", 0.9 ), scored( B1, "PageB", 0.8 ) ) );
        final LuceneBm25ChunkIndex bm25 = mock( LuceneBm25ChunkIndex.class );
        when( bm25.topKChunks( anyString(), anyInt() ) ).thenReturn( List.of(
            scored( A1, "PageA", 5.0 ), scored( B1, "PageB", 3.0 ) ) );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );
        when( repo.findByIds( anyList() ) ).thenReturn( List.of(
            chunk( A1, "PageA", "Intro", "a1 text" ), chunk( B1, "PageB", "Details", "b1 text" ) ) );

        final SectionCandidates c = new HybridChunkSectionSource(
            embedder, dense, bm25, repo, fuser(), 50 ).candidates( "deploy" );
        assertEquals( EXPECTED_MAX_DENSE_SCORE, c.topSimilarity(), 1e-9 );
    }

    @Test
    void noDenseRankingYieldsMinusOne() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.empty() );
        final ChunkVectorIndex dense = mock( ChunkVectorIndex.class );
        final LuceneBm25ChunkIndex bm25 = mock( LuceneBm25ChunkIndex.class );
        when( bm25.topKChunks( anyString(), anyInt() ) ).thenReturn( List.of( scored( B1, "PageB", 5.0 ) ) );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );
        when( repo.findByIds( anyList() ) ).thenReturn( List.of( chunk( B1, "PageB", "Details", "b1 text" ) ) );

        final SectionCandidates c = new HybridChunkSectionSource(
            embedder, dense, bm25, repo, fuser(), 50 ).candidates( "deploy" );
        assertEquals( -1.0, c.topSimilarity() );
    }
}
