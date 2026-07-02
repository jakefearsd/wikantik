/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge.retrieval;

import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.search.SearchResult;
import com.wikantik.knowledge.testfakes.FakeChunkRepository;
import com.wikantik.knowledge.testfakes.FakeChunkVectorIndex;
import com.wikantik.knowledge.testfakes.FakeHybridSearch;
import com.wikantik.knowledge.testfakes.FakeSearchResult;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContributingChunkAssemblerTest {

    private static final List< SearchResult > ONE_PAGE = List.of( FakeSearchResult.of( "Alpha", 10 ) );

    @Test
    void chunksPerPageZeroOrLess_shortCircuitsWithoutTouchingCollaborators() {
        final FakeChunkVectorIndex chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        final HybridSearchService hybridSearch = mock( HybridSearchService.class );
        final ContributingChunkAssembler assembler =
            new ContributingChunkAssembler( chunkIndex, new FakeChunkRepository(), hybridSearch );

        final Map< String, List< RetrievedChunk > > out =
            assembler.fetchContributingChunks( "query", ONE_PAGE, 0, Optional.empty() );

        assertEquals( Map.of(), out );
        verifyNoInteractions( hybridSearch );
    }

    @Test
    void chunkIndexNotReady_degradesToEmptyMap() {
        final FakeChunkVectorIndex chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( false );
        final ContributingChunkAssembler assembler = new ContributingChunkAssembler(
            chunkIndex, new FakeChunkRepository(), FakeHybridSearch.enabledReturning( List.of( "Alpha" ) ) );

        final Map< String, List< RetrievedChunk > > out =
            assembler.fetchContributingChunks( "query", ONE_PAGE, 2, Optional.empty() );

        assertEquals( Map.of(), out );
    }

    @Test
    void reusableChunksAlreadySufficient_skipsEmbeddingFetchAndUsesThemDirectly() {
        final UUID chunkId = UUID.randomUUID();
        final FakeChunkRepository chunkRepo = new FakeChunkRepository();
        chunkRepo.addChunk( chunkId, "Alpha", 0, List.of( "Intro" ), "chunk text" );
        final FakeChunkVectorIndex chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        final HybridSearchService hybridSearch = mock( HybridSearchService.class );
        final ContributingChunkAssembler assembler =
            new ContributingChunkAssembler( chunkIndex, chunkRepo, hybridSearch );

        final List< ScoredChunk > reusable = List.of( new ScoredChunk( chunkId, "Alpha", 0.9 ) );
        final Map< String, List< RetrievedChunk > > out =
            assembler.fetchContributingChunks( "query", ONE_PAGE, 1, Optional.of( reusable ) );

        assertEquals( 1, out.get( "Alpha" ).size() );
        assertEquals( "chunk text", out.get( "Alpha" ).get( 0 ).text() );
        // Guard #2 took the reuse branch — the embedding-fetch collaborator method
        // must never have been invoked.
        verify( hybridSearch, never() ).prefetchQueryEmbedding( anyString() );
    }

    @Test
    void reusableChunksInsufficient_fallsBackToEmbeddingFetchAndTopKScan() {
        final UUID chunkId = UUID.randomUUID();
        final FakeChunkRepository chunkRepo = new FakeChunkRepository();
        chunkRepo.addChunk( chunkId, "Alpha", 0, List.of( "Intro" ), "fresh scan text" );
        final FakeChunkVectorIndex chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        chunkIndex.setTopK( List.of( new ScoredChunk( chunkId, "Alpha", 0.5 ) ) );
        final HybridSearchService hybridSearch = FakeHybridSearch.enabledReturning( List.of( "Alpha" ) );
        final ContributingChunkAssembler assembler =
            new ContributingChunkAssembler( chunkIndex, chunkRepo, hybridSearch );

        // Empty reusable Optional forces the embedding-fetch + topKChunks path.
        final Map< String, List< RetrievedChunk > > out =
            assembler.fetchContributingChunks( "query", ONE_PAGE, 1, Optional.empty() );

        assertEquals( 1, out.get( "Alpha" ).size() );
        assertEquals( "fresh scan text", out.get( "Alpha" ).get( 0 ).text() );
        verify( hybridSearch ).prefetchQueryEmbedding( "query" );
    }

    @Test
    void chunksNotBelongingToAnyOrderedPage_areFilteredOut() {
        final UUID keep = UUID.randomUUID();
        final UUID drop = UUID.randomUUID();
        final FakeChunkRepository chunkRepo = new FakeChunkRepository();
        chunkRepo.addChunk( keep, "Alpha", 0, List.of(), "kept" );
        chunkRepo.addChunk( drop, "NotInResults", 0, List.of(), "dropped" );
        final FakeChunkVectorIndex chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        final HybridSearchService hybridSearch = mock( HybridSearchService.class );
        final ContributingChunkAssembler assembler =
            new ContributingChunkAssembler( chunkIndex, chunkRepo, hybridSearch );

        // chunksPerPage=1 with a single ordered page needs only 1 reusable
        // chunk, so the 2-chunk reusable list is "sufficient" (Guard #2) and
        // the embedding-fetch branch is never taken.
        final List< ScoredChunk > reusable = List.of(
            new ScoredChunk( keep, "Alpha", 0.9 ),
            new ScoredChunk( drop, "NotInResults", 0.8 ) );
        final Map< String, List< RetrievedChunk > > out =
            assembler.fetchContributingChunks( "query", ONE_PAGE, 1, Optional.of( reusable ) );

        assertEquals( java.util.Set.of( "Alpha" ), out.keySet() );
        assertEquals( 1, out.get( "Alpha" ).size() );
    }
}
