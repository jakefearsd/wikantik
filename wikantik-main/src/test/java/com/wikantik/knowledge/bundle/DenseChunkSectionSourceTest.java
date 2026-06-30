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
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.ScoredChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the shipped default bundle source — pure Mockito over the
 * embedder / vector index / chunk repository, no DB or live model.
 */
class DenseChunkSectionSourceTest {

    private static final UUID A1 = UUID.fromString( "00000000-0000-0000-0000-0000000000a1" );
    private static final UUID A2 = UUID.fromString( "00000000-0000-0000-0000-0000000000a2" );
    private static final UUID B1 = UUID.fromString( "00000000-0000-0000-0000-0000000000b1" );

    private static MentionableChunk chunk( final UUID id, final String page, final List< String > hp, final String text ) {
        return new MentionableChunk( id, page, 0, hp, text );
    }

    private static ScoredChunk scored( final UUID id, final String page, final double score ) {
        return new ScoredChunk( id, page, score );
    }

    @Test
    void candidates_groupsToSections_keepsBestScoringChunkPerSection_sortedDesc() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex index = mock( ChunkVectorIndex.class );
        // A1 (0.9) and A2 (0.95) are the same section (PageA > Intro); A2 must win on max-score
        // regardless of index order. B1 (0.8) is a distinct, lower-scoring section.
        when( index.topKChunks( any(), anyInt() ) ).thenReturn( List.of(
            scored( A1, "PageA", 0.9 ), scored( B1, "PageB", 0.8 ), scored( A2, "PageA", 0.95 ) ) );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );
        when( repo.findByIds( anyList() ) ).thenReturn( List.of(
            chunk( A1, "PageA", List.of( "Intro" ), "a1 text" ),
            chunk( A2, "PageA", List.of( "Intro" ), "a2 text" ),
            chunk( B1, "PageB", List.of( "Details" ), "b1 text" ) ) );

        final List< CandidateSection > out = new DenseChunkSectionSource( embedder, index, repo, 50 )
            .candidates( "query" ).sections();

        assertEquals( 2, out.size() );
        assertEquals( "PageA", out.get( 0 ).slug() );
        assertEquals( List.of( "Intro" ), out.get( 0 ).headingPath() );
        assertEquals( "a2 text", out.get( 0 ).text() );        // higher-scoring chunk wins, not A1
        assertEquals( 0.95, out.get( 0 ).denseScore(), 1e-9 );
        assertEquals( "PageB", out.get( 1 ).slug() );
        assertTrue( out.get( 0 ).denseScore() > out.get( 1 ).denseScore(), "sections sorted by dense score desc" );
    }

    @Test
    void candidates_embedEmpty_returnsEmpty() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.empty() );
        final ChunkVectorIndex index = mock( ChunkVectorIndex.class );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );

        final List< CandidateSection > out = new DenseChunkSectionSource( embedder, index, repo, 50 )
            .candidates( "query" ).sections();

        assertTrue( out.isEmpty() );
        verify( index, org.mockito.Mockito.never() ).topKChunks( any(), anyInt() );
    }

    @Test
    void candidates_indexEmpty_returnsEmpty() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex index = mock( ChunkVectorIndex.class );
        when( index.topKChunks( any(), anyInt() ) ).thenReturn( List.of() );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );

        final List< CandidateSection > out = new DenseChunkSectionSource( embedder, index, repo, 50 )
            .candidates( "query" ).sections();

        assertTrue( out.isEmpty() );
        verify( repo, org.mockito.Mockito.never() ).findByIds( anyList() );
    }

    @Test
    void nonPositiveTopK_defaultsTo300() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex index = mock( ChunkVectorIndex.class );
        when( index.topKChunks( any(), anyInt() ) ).thenReturn( List.of() );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );

        new DenseChunkSectionSource( embedder, index, repo, 0 ).candidates( "q" );

        final ArgumentCaptor< Integer > k = ArgumentCaptor.forClass( Integer.class );
        verify( index ).topKChunks( any(), k.capture() );
        assertEquals( 300, k.getValue(), "topK<=0 falls back to 300" );
    }

    @Test
    void topSimilarityIsMaxCosine() {
        // existing fixture wiring: embedder returns a vector, index returns scored chunks.
        // The highest ScoredChunk.score() must surface as topSimilarity.
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.of( new float[]{ 0.1f } ) );
        final ChunkVectorIndex index = mock( ChunkVectorIndex.class );
        when( index.topKChunks( any(), anyInt() ) ).thenReturn( List.of(
            scored( A1, "PageA", 0.9 ), scored( B1, "PageB", 0.8 ), scored( A2, "PageA", 0.95 ) ) );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );
        when( repo.findByIds( anyList() ) ).thenReturn( List.of(
            chunk( A1, "PageA", List.of( "Intro" ), "a1 text" ),
            chunk( A2, "PageA", List.of( "Intro" ), "a2 text" ),
            chunk( B1, "PageB", List.of( "Details" ), "b1 text" ) ) );

        final SectionCandidates c = new DenseChunkSectionSource( embedder, index, repo, 50 )
            .candidates( "deploy" );
        assertEquals( c.sections().get( 0 ).denseScore(), c.topSimilarity(), 1e-9 );
    }

    @Test
    void embedderUnavailableYieldsMinusOne() {
        final QueryEmbedder embedder = mock( QueryEmbedder.class );
        when( embedder.embed( anyString() ) ).thenReturn( Optional.empty() );
        final ChunkVectorIndex index = mock( ChunkVectorIndex.class );
        final ContentChunkRepository repo = mock( ContentChunkRepository.class );

        final SectionCandidates c = new DenseChunkSectionSource( embedder, index, repo, 50 )
            .candidates( "deploy" );
        assertTrue( c.sections().isEmpty() );
        assertEquals( -1.0, c.topSimilarity() );
    }
}
