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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.ExtractedEntity;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import com.wikantik.api.knowledge.PageExtractor;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;
import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingService;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExtractionBatchRunner}. Uses a real {@link ProposalConsolidator}
 * (pure) and mocks everything else; {@link ExtractionBatchRunner.Counters} is mocked too —
 * its boolean/int getters default to {@code false}/{@code 0} via Mockito, which matches the
 * production {@code isDryRun=false, isCancelRequested=false, maxPages=0} common case, so each
 * test only stubs the getters relevant to the branch under test.
 */
class ExtractionBatchRunnerTest {

    private PageExtractor pageExtractor;
    private ProposalJudge judge;
    private ProposalUpserter upserter;
    private KgNodeEmbeddingService embeddingService;
    private KgNodeEmbeddingRepository embeddingRepo;
    private ContentChunkRepository chunkRepo;
    private KgExcludedPagesRepository excludedPages;
    private ExecutorService workerPool;
    private ExtractionBatchRunner.Counters counters;

    @BeforeEach
    void setUp() {
        pageExtractor = mock( PageExtractor.class );
        judge = mock( ProposalJudge.class );
        upserter = mock( ProposalUpserter.class );
        embeddingService = mock( KgNodeEmbeddingService.class );
        embeddingRepo = mock( KgNodeEmbeddingRepository.class );
        chunkRepo = mock( ContentChunkRepository.class );
        excludedPages = mock( KgExcludedPagesRepository.class );
        workerPool = Executors.newFixedThreadPool( 2 );
        counters = mock( ExtractionBatchRunner.Counters.class );
        when( pageExtractor.code() ).thenReturn( "test-extractor" );
    }

    @AfterEach
    void tearDown() {
        workerPool.shutdownNow();
    }

    private ExtractionBatchRunner runner() {
        return runner( excludedPages );
    }

    private ExtractionBatchRunner runner( final KgExcludedPagesRepository excluded ) {
        return new ExtractionBatchRunner( pageExtractor, judge, new ProposalConsolidator(), upserter,
            embeddingService, embeddingRepo, chunkRepo, excluded, workerPool, 5 );
    }

    private static ContentChunkRepository.MentionableChunk chunk( final String text ) {
        return new ContentChunkRepository.MentionableChunk( UUID.randomUUID(), "PageA", 0, List.of(), text );
    }

    // ---- warmNodeEmbeddings ----

    @Test
    void warmNodeEmbeddings_noOpWhenEmbeddingServiceNull() {
        final ExtractionBatchRunner r = new ExtractionBatchRunner( pageExtractor, judge, new ProposalConsolidator(),
            upserter, /*embeddingService*/ null, embeddingRepo, chunkRepo, excludedPages, workerPool, 5 );
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );

        r.warmNodeEmbeddings( kgNodes );

        verify( kgNodes, never() ).getAllNodes();
    }

    @Test
    void warmNodeEmbeddings_warmsCacheAndLogsResult() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );
        when( embeddingService.warmUp( any() ) ).thenReturn( new KgNodeEmbeddingService.Result( 3, 1, 0 ) );

        runner().warmNodeEmbeddings( kgNodes );

        verify( embeddingService ).warmUp( List.of() );
    }

    @Test
    void warmNodeEmbeddings_logsWarnAndSwallowsExceptionWhenGetAllNodesThrows() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenThrow( new RuntimeException( "db down" ) );

        runner().warmNodeEmbeddings( kgNodes );

        verify( embeddingService, never() ).warmUp( any() );
    }

    // ---- runBatch: page listing / cap / exclusion ----

    @Test
    void runBatch_appliesMaxPagesCap() throws InterruptedException {
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A", "B", "C" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of() );
        when( counters.maxPages() ).thenReturn( 1 );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.outcomes().size() );
        assertEquals( "A", result.outcomes().get( 0 ).pageName() );
        verify( counters ).setTotalPages( 1 );
        verify( chunkRepo, never() ).listChunkIdsForPage( "B" );
    }

    @Test
    void runBatch_skipsExcludedPages() throws InterruptedException {
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A", "B" ) );
        when( excludedPages.findReason( "A" ) ).thenReturn( Optional.of( ExclusionReason.SYSTEM_PAGE ) );
        when( excludedPages.findReason( "B" ) ).thenReturn( Optional.empty() );
        when( chunkRepo.listChunkIdsForPage( "B" ) ).thenReturn( List.of() );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.outcomes().size() );
        assertEquals( "B", result.outcomes().get( 0 ).pageName() );
        verify( counters ).incrementExcludedSkipped();
        verify( chunkRepo, never() ).listChunkIdsForPage( "A" );
    }

    @Test
    void runBatch_worksWithNullExcludedPagesRepository() throws InterruptedException {
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of() );

        final ExtractionBatchRunner.BatchResult result = runner( null ).runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.outcomes().size() );
    }

    @Test
    void runBatch_capturesStatsFailureWithoutThrowing() throws InterruptedException {
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of() );
        when( chunkRepo.stats() ).thenThrow( new RuntimeException( "count query failed" ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertTrue( result.outcomes().isEmpty() );
        verify( counters, never() ).setTotalChunks( anyInt() );
    }

    @Test
    void runBatch_stopsSubmittingMorePagesWhenCancelledMidLoop() throws InterruptedException {
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A", "B", "C" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of() );
        when( counters.isCancelRequested() ).thenReturn( false, true );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.outcomes().size() );
        verify( chunkRepo, never() ).listChunkIdsForPage( "B" );
        verify( chunkRepo, never() ).listChunkIdsForPage( "C" );
    }

    @Test
    void runBatch_throwsInterruptedExceptionWhenThreadAlreadyInterrupted() {
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        Thread.currentThread().interrupt();
        try {
            assertThrows( InterruptedException.class,
                () -> runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters ) );
        } finally {
            Thread.interrupted(); // clear the flag so it doesn't leak into other tests
        }
    }

    // ---- runBatch: per-page extraction failures (extractOnePage) ----

    @Test
    void runBatch_recordsFailedPageWhenListingChunkIdsThrows() throws InterruptedException {
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenThrow( new RuntimeException( "db timeout" ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.outcomes().size() );
        assertTrue( result.outcomes().get( 0 ).chunks().isEmpty() );
        verify( counters ).incrementFailedPages();
    }

    @Test
    void runBatch_recordsFailedPageWhenHydratingChunksThrows() throws InterruptedException {
        final UUID chunkId = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of( chunkId ) );
        when( chunkRepo.findByIds( List.of( chunkId ) ) ).thenThrow( new RuntimeException( "hydrate failed" ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.outcomes().size() );
        verify( counters ).incrementFailedPages();
    }

    @Test
    void runBatch_recordsFailedPageWhenExtractorThrows() throws InterruptedException {
        final UUID chunkId = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of( chunkId ) );
        when( chunkRepo.findByIds( List.of( chunkId ) ) ).thenReturn( List.of( chunk( "Napoleon fought." ) ) );
        when( pageExtractor.extract( any( Page.class ), any() ) ).thenThrow( new RuntimeException( "llm down" ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.outcomes().size() );
        assertTrue( result.outcomes().get( 0 ).chunks().size() == 1 );
        verify( counters ).incrementFailedPages();
        verify( counters ).setLastError( org.mockito.ArgumentMatchers.contains( "page=A" ) );
    }

    @Test
    void runBatch_looksUpDictionaryWhenPageEmbeddingPresent() throws InterruptedException {
        final UUID chunkId = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of( chunkId ) );
        when( chunkRepo.findByIds( List.of( chunkId ) ) ).thenReturn( List.of( chunk( "Napoleon fought." ) ) );
        final float[] mean = { 0.1f, 0.2f };
        when( embeddingService.modelTag() ).thenReturn( "model-x" );
        when( embeddingRepo.findTopKByPageEmbedding( eq( mean ), eq( 5 ), eq( "model-x" ) ) )
            .thenReturn( List.of() );
        when( pageExtractor.extract( any( Page.class ), any() ) )
            .thenReturn( PageExtractionResult.empty( "test-extractor", "A", Duration.ZERO ) );

        runner().runBatch( false, name -> Optional.of( mean ), counters );

        verify( embeddingRepo ).findTopKByPageEmbedding( mean, 5, "model-x" );
    }

    @Test
    void runBatch_logsWarnAndContinuesWhenDictionaryLookupThrows() throws InterruptedException {
        final UUID chunkId = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of( chunkId ) );
        when( chunkRepo.findByIds( List.of( chunkId ) ) ).thenReturn( List.of( chunk( "Napoleon fought." ) ) );
        final float[] mean = { 0.1f, 0.2f };
        when( embeddingService.modelTag() ).thenReturn( "model-x" );
        when( embeddingRepo.findTopKByPageEmbedding( any(), anyInt(), anyString() ) )
            .thenThrow( new RuntimeException( "vector lookup failed" ) );
        when( pageExtractor.extract( any( Page.class ), any() ) )
            .thenReturn( PageExtractionResult.empty( "test-extractor", "A", Duration.ZERO ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, name -> Optional.of( mean ), counters );

        assertEquals( 1, result.outcomes().size() );
    }

    @Test
    void runBatch_recordsExecutionExceptionFromWorkerAsFailedPage() throws InterruptedException {
        // A blank page name reaches new Page(...) uncaught (not wrapped by extractOnePage's
        // try/catch blocks), so the worker future completes exceptionally and the ExecutionException
        // branch in the outer collection loop runs.
        final UUID chunkId = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( " " ) );
        when( chunkRepo.listChunkIdsForPage( " " ) ).thenReturn( List.of( chunkId ) );
        when( chunkRepo.findByIds( List.of( chunkId ) ) ).thenReturn( List.of( chunk( "text" ) ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertTrue( result.outcomes().isEmpty() );
        verify( counters ).incrementFailedPages();
        verify( counters ).setLastError( org.mockito.ArgumentMatchers.contains( "extract task failed" ) );
    }

    // ---- runBatch: consolidate / judge / upsert ----

    private void stubOnePageWithOneEntity( final String name, final String type, final double confidence ) {
        final UUID chunkId = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of( chunkId ) );
        when( chunkRepo.findByIds( List.of( chunkId ) ) ).thenReturn( List.of( chunk( name + " appears here." ) ) );
        when( pageExtractor.extract( any( Page.class ), any() ) ).thenReturn(
            new PageExtractionResult( "test-extractor", "A",
                List.of( new ExtractedEntity( name, type, name, confidence ) ), List.of(),
                new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) ) );
    }

    @Test
    void runBatch_acceptedProposalIsUpsertedWhenNotDryRun() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenReturn( new Verdict.Accept( 0.9, "ok" ) );
        when( upserter.upsert( any() ) ).thenReturn( new ProposalUpserter.Result( true, 1 ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.accepted().size() );
        verify( counters ).incrementJudgeAccepted();
        verify( counters ).incrementProposalsInserted();
    }

    @Test
    void runBatch_mergedProposalIncrementsMergedCounter() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenReturn( new Verdict.Accept( 0.9, "ok" ) );
        when( upserter.upsert( any() ) ).thenReturn( new ProposalUpserter.Result( false, 2 ) );

        runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        verify( counters ).incrementProposalsMerged();
    }

    @Test
    void runBatch_dryRunSkipsUpsertEntirely() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenReturn( new Verdict.Accept( 0.9, "ok" ) );
        when( counters.isDryRun() ).thenReturn( true );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.accepted().size() );
        verify( upserter, never() ).upsert( any() );
    }

    @Test
    void runBatch_judgeThrowsAcceptsFailOpen() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenThrow( new RuntimeException( "judge crashed" ) );
        when( judge.code() ).thenReturn( "test-judge" );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.accepted().size() );
        verify( counters ).incrementJudgeAccepted();
    }

    @Test
    void runBatch_judgeRejectsExcludesProposalAndMergesReason() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenReturn( new Verdict.Reject( "weak_support", "too little evidence" ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertTrue( result.accepted().isEmpty() );
        verify( counters ).incrementJudgeRejected();
        verify( counters ).mergeRejectionReason( "weak_support" );
    }

    @Test
    void runBatch_judgeRewriteReplacesAcceptedProposal() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        final ConsolidatedProposal rewritten =
            ConsolidatedProposal.newNode( "sig-rewritten", "Napoleon Bonaparte", "Person", List.of(), 0.95 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenReturn( new Verdict.Rewrite( rewritten, "canonicalized name" ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.accepted().size() );
        assertEquals( "Napoleon Bonaparte", result.accepted().get( 0 ).displayName() );
        verify( counters ).incrementJudgeRewritten();
    }

    @Test
    void runBatch_upsertThrowsSetsLastErrorButDoesNotPropagate() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenReturn( new Verdict.Accept( 0.9, "ok" ) );
        when( upserter.upsert( any() ) ).thenThrow( new RuntimeException( "constraint violation" ) );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.accepted().size() );
        verify( counters ).setLastError( org.mockito.ArgumentMatchers.contains( "upsert failed" ) );
    }

    @Test
    void runBatch_stopsUpsertingWhenCancelledDuringUpsertLoop() throws InterruptedException {
        stubOnePageWithOneEntity( "Napoleon", "Person", 0.9 );
        when( judge.judge( any( ConsolidatedProposal.class ), any( JudgeContext.class ) ) )
            .thenReturn( new Verdict.Accept( 0.9, "ok" ) );
        // False through page-submission + judge loops (one candidate each), true on the
        // upsert loop's first check so it breaks before upserting the accepted proposal.
        when( counters.isCancelRequested() ).thenReturn( false, false, true );

        final ExtractionBatchRunner.BatchResult result = runner().runBatch( false, PageEmbeddingProvider.EMPTY, counters );

        assertEquals( 1, result.accepted().size() );
        verify( upserter, never() ).upsert( any() );
    }
}
