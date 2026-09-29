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
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import com.wikantik.api.knowledge.PageExtractor;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link BootstrapEntityExtractionIndexer} branches that
 * {@link BootstrapEntityExtractionIndexerTest} doesn't reach: rarely-used builder
 * setters, the {@code sharedExecutors} partial-pair validation, the plain getters,
 * {@code status()} before any run has ever started, cancelling a run that is
 * genuinely still in flight, the caught-and-recorded {@link RuntimeException} /
 * {@link InterruptedException} paths of {@code runSafely}, a {@code Verdict.Rewrite}
 * judge outcome, and {@code close()}'s owned-pool shutdown path (including the
 * private {@code shutdown(...)} helper's timeout/interrupt branches, exercised
 * directly via reflection since the public API gives no way to hand the indexer an
 * "owned" pool that won't actually terminate within the real 5-second budget).
 */
class BootstrapEntityExtractionIndexerBranchTest {

    private static ContentChunkRepository emptyChunkRepo() {
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of() );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );
        return chunkRepo;
    }

    private static PageExtractor noopExtractor() {
        final PageExtractor extractor = mock( PageExtractor.class );
        when( extractor.code() ).thenReturn( "ollama:test" );
        return extractor;
    }

    private static KgNodeRepository emptyKgNodes() {
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );
        return kgNodes;
    }

    @Test
    void rarelyUsedBuilderSettersAreHonored() {
        final ContentChunkRepository chunkRepo = emptyChunkRepo();
        final KgNodeRepository kgNodes = emptyKgNodes();
        final KgNodeEmbeddingService embeddingService = mock( KgNodeEmbeddingService.class );
        when( embeddingService.warmUp( any() ) ).thenReturn( new KgNodeEmbeddingService.Result( 1, 0, 0 ) );

        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( chunkRepo ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( kgNodes )
            .consolidator( new ProposalConsolidator() )          // line 187
            .embeddingService( embeddingService )                // line 189
            .embeddingRepo( mock( com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository.class ) )
            .pageEmbeddings( PageEmbeddingProvider.EMPTY )        // line 191
            .dictionaryTopK( 5 )
            .maxEntitiesPerPage( 20 )
            .maxRelationsPerPage( 10 )
            .sharedExecutors( directExecutor(), directExecutor() )
            .build();

        assertTrue( indexer.start( false ) );
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, indexer.status().state() );
        verify( embeddingService ).warmUp( any() );
        assertFalse( indexer.isRunning(), "isRunning() must reflect completion" );
    }

    @Test
    void sharedExecutorsRejectsAPartialPair() {
        final ExecutorService one = directExecutor();
        assertThrows( IllegalStateException.class, () -> BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( emptyChunkRepo() ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( one, null )
            .build() );
        assertThrows( IllegalStateException.class, () -> BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( emptyChunkRepo() ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( null, one )
            .build() );
    }

    @Test
    void concurrencyAndIsDryRunGettersReflectState() {
        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( emptyChunkRepo() ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .concurrency( 1 )
            .sharedExecutors( directExecutor(), directExecutor() )
            .build();

        assertEquals( 1, indexer.concurrency() );
        assertFalse( indexer.isDryRun() );
        indexer.setDryRun( true );
        assertTrue( indexer.isDryRun() );
    }

    @Test
    void statusBeforeAnyRunReportsZeroElapsed() {
        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( emptyChunkRepo() ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( directExecutor(), directExecutor() )
            .build();

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.IDLE, s.state() );
        assertEquals( 0L, s.elapsedMs() );
    }

    @Test
    void cancelOnAGenuinelyRunningBatchRequestsCancellationAndReturnsTrue() {
        final BlockingExecutor blocking = new BlockingExecutor();
        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( emptyChunkRepo() ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( blocking, directExecutor() )
            .build();

        assertTrue( indexer.start( false ) );
        assertEquals( BootstrapEntityExtractionIndexer.State.RUNNING, indexer.status().state() );
        assertTrue( indexer.cancel(), "cancel() must succeed while a batch is genuinely running" );

        blocking.release();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, indexer.status().state() );
    }

    @Test
    void runtimeExceptionDuringTheBatchIsCaughtAndRecordedAsError() {
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenThrow( new RuntimeException( "boom" ) );

        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( chunkRepo ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( directExecutor(), directExecutor() )
            .build();

        assertTrue( indexer.start( false ) );
        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.ERROR, s.state() );
        assertEquals( "boom", s.lastError() );
    }

    @Test
    void interruptionBeforeTheFirstPageIsCaughtAndRecordedAsError() {
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 1, 0, 0, 0 ) );

        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( chunkRepo ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( directExecutor(), directExecutor() )   // direct: runs on this thread
            .build();

        Thread.currentThread().interrupt();
        try {
            assertTrue( indexer.start( false ) );
            final BootstrapEntityExtractionIndexer.Status s = indexer.status();
            assertEquals( BootstrapEntityExtractionIndexer.State.ERROR, s.state() );
            assertEquals( "interrupted", s.lastError() );
        } finally {
            Thread.interrupted();   // clear the flag so it doesn't leak into later tests
        }
    }

    @Test
    void rewriteVerdictIncrementsRewrittenCounterAndUpsertsTheRewrittenProposal() {
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        final java.util.UUID c1 = java.util.UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( List.of( c1 ) );
        when( chunkRepo.findByIds( List.of( c1 ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( c1, "P", 0, List.of(), "Some concept text." ) ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 1, 0, 0, 0 ) );

        final PageExtractor extractor = noopExtractor();
        when( extractor.extract( any( Page.class ), any( ExtractionContext.class ) ) ).thenReturn(
            new PageExtractionResult( "ollama:test", "P",
                List.of( new ExtractedEntity( "Concept", "Concept", "Concept", 0.8 ) ), List.of(),
                new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) ) );

        final ConsolidatedProposal rewritten = ConsolidatedProposal.newNode(
            "rewritten-sig", "RewrittenConcept", "Concept", List.of(), 0.95 );
        final ProposalJudge rewriter = new ProposalJudge() {
            @Override public String code() { return "test:rewrite"; }
            @Override public Verdict judge( final ConsolidatedProposal p, final JudgeContext ctx ) {
                return new Verdict.Rewrite( rewritten, "normalized display name" );
            }
        };

        final ProposalUpserter upserter = mock( ProposalUpserter.class );
        when( upserter.upsert( eq( rewritten ) ) ).thenReturn( new ProposalUpserter.Result( true, 1 ) );

        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( extractor ).judge( rewriter ).upserter( upserter )
            .chunkRepo( chunkRepo ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( directExecutor(), directExecutor() )
            .build();

        assertTrue( indexer.start( false ) );
        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 1, s.judgeRewritten() );
        assertEquals( 0, s.judgeAccepted() );
        assertEquals( 1, s.proposalsInserted() );
        verify( upserter ).upsert( eq( rewritten ) );
    }

    @Test
    void closeShutsDownOwnedExecutorAndWorkerPool() {
        // No sharedExecutors(...) call -> build() creates and owns the default pools, so
        // close() must actually shut both down (lines 477/480/482/485). At least one page
        // must actually be submitted to the worker pool, or its ThreadFactory (lines 281-283)
        // never fires — a fixed thread pool creates threads lazily, on first submission.
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 1, 0, 0, 0 ) );
        when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( List.of() );
        when( chunkRepo.findByIds( List.of() ) ).thenReturn( List.of() );

        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( noopExtractor() ).upserter( mock( ProposalUpserter.class ) )
            .chunkRepo( chunkRepo ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .concurrency( 1 )
            .build();

        assertTrue( indexer.start( false ) );
        awaitCompletion( indexer );

        indexer.close();   // must not throw; both owned pools terminate promptly (nothing queued)
    }

    private static void awaitCompletion( final BootstrapEntityExtractionIndexer indexer ) {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos( 5 );
        while ( indexer.isRunning() && System.nanoTime() < deadline ) {
            Thread.onSpinWait();
        }
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, indexer.status().state() );
    }

    @Test
    void rewriteAndMergedProposalsCounterIsIncrementedOnAMergeResult() {
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        final java.util.UUID c1 = java.util.UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( List.of( c1 ) );
        when( chunkRepo.findByIds( List.of( c1 ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( c1, "P", 0, List.of(), "Some text." ) ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 1, 0, 0, 0 ) );

        final PageExtractor extractor = noopExtractor();
        when( extractor.extract( any( Page.class ), any( ExtractionContext.class ) ) ).thenReturn(
            new PageExtractionResult( "ollama:test", "P",
                List.of( new ExtractedEntity( "Existing", "Concept", "Existing", 0.9 ) ), List.of(),
                new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) ) );

        final ProposalUpserter upserter = mock( ProposalUpserter.class );
        // inserted=false -> the proposal merged into an already-existing row.
        when( upserter.upsert( any() ) ).thenReturn( new ProposalUpserter.Result( false, 2 ) );

        final BootstrapEntityExtractionIndexer indexer = BootstrapEntityExtractionIndexer.builder()
            .pageExtractor( extractor ).upserter( upserter )
            .chunkRepo( chunkRepo ).mentionRepo( mock( ChunkEntityMentionRepository.class ) )
            .kgNodes( emptyKgNodes() )
            .sharedExecutors( directExecutor(), directExecutor() )
            .build();

        assertTrue( indexer.start( false ) );
        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( 1, s.proposalsMerged() );
        assertEquals( 0, s.proposalsInserted() );
        assertEquals( 1, s.proposalsFiled() );
    }

    @Test
    void shutdownHelperForcesShutdownNowWhenAwaitTerminationTimesOut() throws Exception {
        final ExecutorService pool = mock( ExecutorService.class );
        when( pool.awaitTermination( 5, TimeUnit.SECONDS ) ).thenReturn( false );

        invokePrivateShutdown( pool, "test-pool" );

        verify( pool ).shutdown();
        verify( pool ).shutdownNow();
    }

    @Test
    void shutdownHelperForcesShutdownNowWhenAwaitTerminationIsInterrupted() throws Exception {
        final ExecutorService pool = mock( ExecutorService.class );
        when( pool.awaitTermination( 5, TimeUnit.SECONDS ) ).thenThrow( new InterruptedException( "stop" ) );

        try {
            invokePrivateShutdown( pool, "test-pool" );
            verify( pool ).shutdownNow();
        } finally {
            Thread.interrupted();   // the helper re-interrupts this thread; clear it for later tests
        }
    }

    private static void invokePrivateShutdown( final ExecutorService pool, final String label ) throws Exception {
        final Method m = BootstrapEntityExtractionIndexer.class
            .getDeclaredMethod( "shutdown", ExecutorService.class, String.class );
        m.setAccessible( true );
        m.invoke( null, pool, label );
    }

    // ---- helpers (mirrors BootstrapEntityExtractionIndexerTest's fixtures) ----

    private static ExecutorService directExecutor() {
        return new DirectExecutorService();
    }

    private static final class DirectExecutorService
            extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean shutdown;
        @Override public void execute( final Runnable command ) { command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List< Runnable > shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination( final long timeout, final TimeUnit unit ) {
            return true;
        }
    }

    private static final class BlockingExecutor extends java.util.concurrent.AbstractExecutorService {
        private Runnable pending;
        @Override public void execute( final Runnable command ) {
            this.pending = command;
        }
        void release() {
            if ( pending != null ) {
                pending.run();
                pending = null;
            }
        }
        @Override public void shutdown() {}
        @Override public List< Runnable > shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination( final long timeout, final TimeUnit unit ) {
            return true;
        }
    }
}
