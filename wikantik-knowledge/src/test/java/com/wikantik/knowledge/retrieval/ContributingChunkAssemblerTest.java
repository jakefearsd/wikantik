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

    @Test
    void chunkRowsHoldTheTokenAndAreServedExpandedTextAndHeadings() {
        final com.wikantik.api.livevalues.LiveValues lv = mock( com.wikantik.api.livevalues.LiveValues.class );
        when( lv.expandBody( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0, String.class ).replace( "{{wiki:articles|round=100}}", "1,100+" ) );
        when( lv.expandText( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0, String.class ).replace( "{{wiki:articles|round=100}}", "1,100+" ) );
        final UUID chunkId = UUID.randomUUID();
        final FakeChunkRepository chunkRepo = new FakeChunkRepository();
        chunkRepo.addChunk( chunkId, "Alpha", 0, List.of( "Over {{wiki:articles|round=100}} articles" ),
            "We hold {{wiki:articles|round=100}} articles." );
        final FakeChunkVectorIndex chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        final ContributingChunkAssembler assembler = new ContributingChunkAssembler(
            chunkIndex, chunkRepo, mock( HybridSearchService.class ), () -> lv );

        final RetrievedChunk served = assembler.fetchContributingChunks( "q", ONE_PAGE, 1,
            Optional.of( List.of( new ScoredChunk( chunkId, "Alpha", 0.9 ) ) ) ).get( "Alpha" ).get( 0 );

        assertEquals( "We hold 1,100+ articles.", served.text() );
        assertEquals( List.of( "Over 1,100+ articles" ), served.headingPath() );
        assertTrue( chunkRepo.findByIds( List.of( chunkId ) ).get( 0 ).text().contains( "{{wiki:" ),
            "the stored row keeps the token" );
    }

    @Test
    void noLiveValuesServiceServesChunksVerbatim() {
        final UUID chunkId = UUID.randomUUID();
        final FakeChunkRepository chunkRepo = new FakeChunkRepository();
        chunkRepo.addChunk( chunkId, "Alpha", 0, List.of(), "We hold {{wiki:articles}} articles." );
        final FakeChunkVectorIndex chunkIndex = new FakeChunkVectorIndex();
        chunkIndex.setEnabled( true );
        final ContributingChunkAssembler assembler = new ContributingChunkAssembler(
            chunkIndex, chunkRepo, mock( HybridSearchService.class ), () -> null );

        assertEquals( "We hold {{wiki:articles}} articles.", assembler.fetchContributingChunks( "q", ONE_PAGE, 1,
            Optional.of( List.of( new ScoredChunk( chunkId, "Alpha", 0.9 ) ) ) ).get( "Alpha" ).get( 0 ).text() );
    }

    private static FakeChunkVectorIndex readyIndex() {
        final FakeChunkVectorIndex idx = new FakeChunkVectorIndex();
        idx.setEnabled( true );
        return idx;
    }

    @Test
    void anyMissingCollaboratorDegradesToAnEmptyMapRatherThanFailing() {
        final UUID id = UUID.randomUUID();
        final FakeChunkRepository repo = new FakeChunkRepository();
        repo.addChunk( id, "Alpha", 0, List.of(), "t" );
        final Optional< List< ScoredChunk > > reusable = Optional.of( List.of( new ScoredChunk( id, "Alpha", 0.9 ) ) );
        final HybridSearchService hybrid = mock( HybridSearchService.class );

        assertEquals( Map.of(), new ContributingChunkAssembler( null, repo, hybrid ).fetchContributingChunks( "q", ONE_PAGE, 1, reusable ) );
        assertEquals( Map.of(), new ContributingChunkAssembler( readyIndex(), null, hybrid ).fetchContributingChunks( "q", ONE_PAGE, 1, reusable ) );
        assertEquals( Map.of(), new ContributingChunkAssembler( readyIndex(), repo, null ).fetchContributingChunks( "q", ONE_PAGE, 1, reusable ) );
        // sanity: with all three present the same input does produce a chunk
        assertEquals( 1, new ContributingChunkAssembler( readyIndex(), repo, hybrid ).fetchContributingChunks( "q", ONE_PAGE, 1, reusable ).get( "Alpha" ).size() );
    }

    @Test
    void aNegativeChunksPerPageIsTreatedLikeZero() {
        final HybridSearchService hybrid = mock( HybridSearchService.class );
        assertEquals( Map.of(), new ContributingChunkAssembler( readyIndex(), new FakeChunkRepository(), hybrid )
                .fetchContributingChunks( "q", ONE_PAGE, -1, Optional.empty() ) );
        verifyNoInteractions( hybrid );
    }

    @Test
    void eachPageKeepsAtMostChunksPerPageChunksInScanOrder() {
        final FakeChunkRepository repo = new FakeChunkRepository();
        final List< ScoredChunk > scan = new java.util.ArrayList<>();
        for ( int i = 0; i < 3; i++ ) {
            final UUID id = UUID.randomUUID();
            repo.addChunk( id, "Alpha", i, List.of(), "chunk" + i );
            scan.add( new ScoredChunk( id, "Alpha", 0.9 - i * 0.1 ) );
        }
        final var out = new ContributingChunkAssembler( readyIndex(), repo, mock( HybridSearchService.class ) )
                .fetchContributingChunks( "q", ONE_PAGE, 2, Optional.of( scan ) );
        assertEquals( List.of( "chunk0", "chunk1" ), out.get( "Alpha" ).stream().map( RetrievedChunk::text ).toList() );
    }

    @Test
    void reusableChunksExactlyMeetingTheNeedAreReusedButOneFewerTriggersAFreshScan() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final FakeChunkRepository repo = new FakeChunkRepository();
        repo.addChunk( a, "Alpha", 0, List.of(), "a" );
        repo.addChunk( b, "Alpha", 1, List.of(), "b" );
        final FakeChunkVectorIndex idx = readyIndex();
        idx.setTopK( List.of( new ScoredChunk( a, "Alpha", 0.5 ) ) );
        final HybridSearchService exact = mock( HybridSearchService.class );

        // needs 1 page x 2 chunks = 2; exactly 2 supplied -> reused, no embedding fetch
        new ContributingChunkAssembler( idx, repo, exact ).fetchContributingChunks( "q", ONE_PAGE, 2,
                Optional.of( List.of( new ScoredChunk( a, "Alpha", 0.9 ), new ScoredChunk( b, "Alpha", 0.8 ) ) ) );
        verify( exact, never() ).prefetchQueryEmbedding( anyString() );

        // only 1 supplied -> not sufficient, so the embedding is fetched and the index scanned
        final HybridSearchService fresh = FakeHybridSearch.enabledReturning( List.of( "Alpha" ) );
        final var out = new ContributingChunkAssembler( idx, repo, fresh ).fetchContributingChunks( "q", ONE_PAGE, 2,
                Optional.of( List.of( new ScoredChunk( a, "Alpha", 0.9 ) ) ) );
        verify( fresh ).prefetchQueryEmbedding( "q" );
        assertEquals( List.of( "a" ), out.get( "Alpha" ).stream().map( RetrievedChunk::text ).toList() );
    }

    @Test
    void theNeedScalesWithTheNumberOfPagesNotJustTheChunkCount() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final FakeChunkRepository repo = new FakeChunkRepository();
        repo.addChunk( a, "Alpha", 0, List.of(), "a" );
        repo.addChunk( b, "Beta", 0, List.of(), "b" );
        final FakeChunkVectorIndex idx = readyIndex();
        idx.setTopK( List.of( new ScoredChunk( a, "Alpha", 0.5 ), new ScoredChunk( b, "Beta", 0.4 ) ) );
        final HybridSearchService hybrid = FakeHybridSearch.enabledReturning( List.of( "Alpha", "Beta" ) );
        final List< SearchResult > two = List.of( FakeSearchResult.of( "Alpha", 2 ), FakeSearchResult.of( "Beta", 1 ) );

        // 2 pages x 2 chunks per page = 4 needed; 2 supplied is not enough
        new ContributingChunkAssembler( idx, repo, hybrid ).fetchContributingChunks( "q", two, 2,
                Optional.of( List.of( new ScoredChunk( a, "Alpha", 0.9 ), new ScoredChunk( b, "Beta", 0.8 ) ) ) );

        verify( hybrid ).prefetchQueryEmbedding( "q" );
    }

    @Test
    void noPagesMeansNothingIsNeededButTheReusableListIsNotTrustedEither() {
        final HybridSearchService hybrid = FakeHybridSearch.enabledReturning( List.of() );
        final UUID a = UUID.randomUUID();
        final var out = new ContributingChunkAssembler( readyIndex(), new FakeChunkRepository(), hybrid )
                .fetchContributingChunks( "q", List.of(), 2, Optional.of( List.of( new ScoredChunk( a, "Alpha", 0.9 ) ) ) );
        assertEquals( Map.of(), out );
        verify( hybrid ).prefetchQueryEmbedding( "q" );
    }

    @Test
    void anEmptyQueryEmbeddingMeansNoChunks() {
        final HybridSearchService hybrid = mock( HybridSearchService.class );
        when( hybrid.prefetchQueryEmbedding( anyString() ) )
                .thenReturn( java.util.concurrent.CompletableFuture.completedFuture( Optional.empty() ) );
        final FakeChunkVectorIndex idx = readyIndex();
        idx.setTopK( List.of( new ScoredChunk( UUID.randomUUID(), "Alpha", 0.5 ) ) );
        assertEquals( Map.of(), new ContributingChunkAssembler( idx, new FakeChunkRepository(), hybrid )
                .fetchContributingChunks( "q", ONE_PAGE, 1, Optional.empty() ) );
    }

    @Test
    void aFailedEmbeddingFetchMeansNoChunksRatherThanAnError() {
        final HybridSearchService hybrid = mock( HybridSearchService.class );
        when( hybrid.prefetchQueryEmbedding( anyString() ) )
                .thenReturn( java.util.concurrent.CompletableFuture.failedFuture( new IllegalStateException( "embedder down" ) ) );
        assertEquals( Map.of(), new ContributingChunkAssembler( readyIndex(), new FakeChunkRepository(), hybrid )
                .fetchContributingChunks( "q", ONE_PAGE, 1, Optional.empty() ) );
    }

    @Test
    void anEmptyScanMeansNoChunks() {
        final HybridSearchService hybrid = FakeHybridSearch.enabledReturning( List.of( "Alpha" ) );
        assertEquals( Map.of(), new ContributingChunkAssembler( readyIndex(), new FakeChunkRepository(), hybrid )
                .fetchContributingChunks( "q", ONE_PAGE, 1, Optional.empty() ) );
    }

    @Test
    void aNullLiveValuesSupplierServesChunksVerbatimAndKeepsAHeadingPathIntact() {
        final UUID id = UUID.randomUUID();
        final FakeChunkRepository repo = new FakeChunkRepository();
        repo.addChunk( id, "Alpha", 0, List.of( "Top", "Sub" ), "{{wiki:x}} text" );
        final var out = new ContributingChunkAssembler( readyIndex(), repo, mock( HybridSearchService.class ), null )
                .fetchContributingChunks( "q", ONE_PAGE, 1, Optional.of( List.of( new ScoredChunk( id, "Alpha", 0.9 ) ) ) );
        assertEquals( "{{wiki:x}} text", out.get( "Alpha" ).get( 0 ).text() );
        assertEquals( List.of( "Top", "Sub" ), out.get( "Alpha" ).get( 0 ).headingPath() );
    }

    @Test
    void aScoredChunkWhoseRowHasGoneIsDroppedWhileTheOthersStay() {
        final UUID kept = UUID.randomUUID();
        final UUID gone = UUID.randomUUID();
        final FakeChunkRepository repo = new FakeChunkRepository();
        repo.addChunk( kept, "Alpha", 0, List.of(), "kept" );
        final var out = new ContributingChunkAssembler( readyIndex(), repo, mock( HybridSearchService.class ) )
                .fetchContributingChunks( "q", ONE_PAGE, 2, Optional.of( List.of(
                        new ScoredChunk( gone, "Alpha", 0.9 ), new ScoredChunk( kept, "Alpha", 0.8 ) ) ) );
        assertEquals( List.of( "kept" ), out.get( "Alpha" ).stream().map( RetrievedChunk::text ).toList() );
        assertEquals( 0.8, out.get( "Alpha" ).get( 0 ).chunkScore(), 0.0 );
    }
}
