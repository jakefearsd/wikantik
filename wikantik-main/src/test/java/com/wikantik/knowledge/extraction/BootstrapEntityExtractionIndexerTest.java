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

import com.wikantik.api.kgpolicy.ExclusionReason;
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
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-page-pipeline {@link BootstrapEntityExtractionIndexer}.
 * The synchronous executor variants run extraction inline so assertions don't
 * race the background thread.
 */
class BootstrapEntityExtractionIndexerTest {

    /**
     * Two pages each emit one "Python" entity. The consolidator must collapse
     * them to a single ConsolidatedProposal; the NoOp judge accepts; the
     * upserter inserts one row.
     */
    @Test
    void newPipelineDrivesPageExtractorAndConsolidator() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "PageA", "PageB" ) );
        final UUID a1 = UUID.randomUUID();
        final UUID b1 = UUID.randomUUID();
        when( chunkRepo.listChunkIdsForPage( "PageA" ) ).thenReturn( List.of( a1 ) );
        when( chunkRepo.listChunkIdsForPage( "PageB" ) ).thenReturn( List.of( b1 ) );
        when( chunkRepo.findByIds( List.of( a1 ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( a1, "PageA", 0, List.of(),
                "Python is a programming language." ) ) );
        when( chunkRepo.findByIds( List.of( b1 ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( b1, "PageB", 0, List.of(),
                "Python rocks." ) ) );
        when( chunkRepo.stats() ).thenReturn(
            new ContentChunkRepository.AggregateStats( 0, 0, 2, 0, 0, 0 ) );

        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );

        final PageExtractor extractor = Mockito.mock( PageExtractor.class );
        when( extractor.code() ).thenReturn( "ollama:test" );
        when( extractor.extract( any( Page.class ), any( ExtractionContext.class ) ) ).thenAnswer( inv -> {
            final Page p = inv.getArgument( 0 );
            return new PageExtractionResult(
                "ollama:test", p.name(),
                List.of( new ExtractedEntity( "Python", "Technology", "Python", 0.9 ) ),
                List.of(),
                new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) );
        } );

        final ProposalUpserter upserter = Mockito.mock( ProposalUpserter.class );
        when( upserter.upsert( any( ConsolidatedProposal.class ) ) )
            .thenReturn( new ProposalUpserter.Result( /*inserted*/ true, /*supportCount*/ 2 ) );

        final KgNodeRepository kgNodes = Mockito.mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );
        when( kgNodes.getNodeByName( any() ) ).thenReturn( null );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, new NoOpProposalJudge(), new ProposalConsolidator(), upserter,
            /*embeddingService*/ null, /*embeddingRepo*/ null,
            chunkRepo, mentionRepo, kgNodes, new MentionAttributor(),
            PageEmbeddingProvider.EMPTY, /*excludedPages*/ null,
            directExecutor(), directExecutor(), /*concurrency*/ 1,
            /*dictionaryTopK*/ 0, /*maxEntitiesPerPage*/ 12, /*maxRelationsPerPage*/ 8 );

        assertTrue( indexer.start( /*forceOverwrite*/ false ) );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 2, s.totalPages() );
        assertEquals( 2, s.processedPages() );
        assertEquals( 1, s.consolidatedCandidates(), "two extractions of 'Python' must collapse to 1" );
        assertEquals( 1, s.judgeAccepted() );
        assertEquals( 0, s.judgeRejected() );
        assertEquals( 1, s.proposalsInserted() );
        assertEquals( 0, s.proposalsMerged() );
        assertEquals( 1, s.proposalsFiled(), "proposalsFiled aliases inserted+merged" );
        verify( upserter, times( 1 ) ).upsert( any() );
    }

    @Test
    void rejectVerdictBypassesUpserterAndCountsReason() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final UUID c1 = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( List.of( c1 ) );
        when( chunkRepo.findByIds( List.of( c1 ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( c1, "P", 0, List.of(), "Concept text." ) ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 1, 0, 0, 0 ) );

        final PageExtractor extractor = Mockito.mock( PageExtractor.class );
        when( extractor.code() ).thenReturn( "ollama:test" );
        when( extractor.extract( any(), any() ) ).thenReturn( new PageExtractionResult(
            "ollama:test", "P",
            List.of( new ExtractedEntity( "Concept", "Concept", "Concept", 0.7 ) ),
            List.of(),
            new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) ) );

        final ProposalJudge rejector = new ProposalJudge() {
            @Override public String code() { return "test:reject" ; }
            @Override public Verdict judge( final ConsolidatedProposal p, final JudgeContext ctx ) {
                return new Verdict.Reject( "too_generic", "Concept is the universal placeholder" );
            }
        };

        final ProposalUpserter upserter = Mockito.mock( ProposalUpserter.class );
        final KgNodeRepository kgNodes = Mockito.mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, rejector, new ProposalConsolidator(), upserter,
            null, null, chunkRepo, Mockito.mock( ChunkEntityMentionRepository.class ),
            kgNodes, new MentionAttributor(), PageEmbeddingProvider.EMPTY, null,
            directExecutor(), directExecutor(), 1, 0, 12, 8 );
        assertTrue( indexer.start( false ) );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 1, s.consolidatedCandidates() );
        assertEquals( 0, s.judgeAccepted() );
        assertEquals( 1, s.judgeRejected() );
        assertEquals( 0, s.proposalsInserted() );
        assertEquals( 1, s.rejectionReasons().getOrDefault( "too_generic", 0 ).intValue() );
        verify( upserter, never() ).upsert( any() );
    }

    @Test
    void dryRunSkipsUpsertButRunsConsolidationAndJudge() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final UUID c1 = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( List.of( c1 ) );
        when( chunkRepo.findByIds( List.of( c1 ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( c1, "P", 0, List.of(), "x" ) ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 1, 0, 0, 0 ) );

        final PageExtractor extractor = Mockito.mock( PageExtractor.class );
        when( extractor.code() ).thenReturn( "ollama:test" );
        when( extractor.extract( any(), any() ) ).thenReturn( new PageExtractionResult(
            "ollama:test", "P",
            List.of( new ExtractedEntity( "X", "Concept", "X", 0.9 ) ), List.of(),
            new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) ) );

        final ProposalUpserter upserter = Mockito.mock( ProposalUpserter.class );
        final KgNodeRepository kgNodes = Mockito.mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, new NoOpProposalJudge(), new ProposalConsolidator(), upserter,
            null, null, chunkRepo, Mockito.mock( ChunkEntityMentionRepository.class ),
            kgNodes, new MentionAttributor(), PageEmbeddingProvider.EMPTY, null,
            directExecutor(), directExecutor(), 1, 0, 12, 8 );
        indexer.setDryRun( true );
        assertTrue( indexer.start( false ) );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( 1, s.judgeAccepted() );
        assertEquals( 0, s.proposalsInserted() );
        verify( upserter, never() ).upsert( any() );
    }

    @Test
    void excludedPagesAreSkippedBeforeExtractor() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "Skip", "Keep" ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );
        final UUID kc = UUID.randomUUID();
        when( chunkRepo.listChunkIdsForPage( "Keep" ) ).thenReturn( List.of( kc ) );
        when( chunkRepo.findByIds( List.of( kc ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( kc, "Keep", 0, List.of(), "Kept text." ) ) );

        final KgExcludedPagesRepository excluded = Mockito.mock( KgExcludedPagesRepository.class );
        when( excluded.findReason( "Skip" ) ).thenReturn( Optional.of( ExclusionReason.SYSTEM_PAGE ) );
        when( excluded.findReason( "Keep" ) ).thenReturn( Optional.empty() );

        final PageExtractor extractor = Mockito.mock( PageExtractor.class );
        when( extractor.code() ).thenReturn( "ollama:test" );
        when( extractor.extract( any(), any() ) ).thenReturn( new PageExtractionResult(
            "ollama:test", "Keep",
            List.of( new ExtractedEntity( "Foo", "Concept", "Foo", 0.9 ) ), List.of(),
            new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) ) );

        final ProposalUpserter upserter = Mockito.mock( ProposalUpserter.class );
        when( upserter.upsert( any() ) ).thenReturn( new ProposalUpserter.Result( true, 1 ) );
        final KgNodeRepository kgNodes = Mockito.mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, new NoOpProposalJudge(), new ProposalConsolidator(), upserter,
            null, null, chunkRepo, Mockito.mock( ChunkEntityMentionRepository.class ),
            kgNodes, new MentionAttributor(), PageEmbeddingProvider.EMPTY, excluded,
            directExecutor(), directExecutor(), 1, 0, 12, 8 );
        assertTrue( indexer.start( false ) );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( 1, s.excludedSkipped() );
        // Extractor only ever sees the un-excluded page.
        verify( extractor, times( 1 ) ).extract( any(), any() );
    }

    @Test
    void extractorFailureForOnePageIsIsolatedAndCountsAsFailure() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "Alpha", "Beta" ) );
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        when( chunkRepo.listChunkIdsForPage( "Alpha" ) ).thenReturn( List.of( a ) );
        when( chunkRepo.listChunkIdsForPage( "Beta" ) ).thenReturn( List.of( b ) );
        when( chunkRepo.findByIds( List.of( a ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( a, "Alpha", 0, List.of(), "x" ) ) );
        when( chunkRepo.findByIds( List.of( b ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( b, "Beta", 0, List.of(), "y" ) ) );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 2, 0, 0, 0 ) );

        final PageExtractor extractor = Mockito.mock( PageExtractor.class );
        when( extractor.code() ).thenReturn( "ollama:test" );
        when( extractor.extract( any(), any() ) )
            .thenThrow( new RuntimeException( "ollama down" ) )
            .thenReturn( new PageExtractionResult(
                "ollama:test", "Beta",
                List.of( new ExtractedEntity( "Beta", "Concept", "Beta", 0.9 ) ), List.of(),
                new PageExtractionResult.Stats( 1, 0, 0, 0, Duration.ZERO ) ) );

        final ProposalUpserter upserter = Mockito.mock( ProposalUpserter.class );
        when( upserter.upsert( any() ) ).thenReturn( new ProposalUpserter.Result( true, 1 ) );
        final KgNodeRepository kgNodes = Mockito.mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, new NoOpProposalJudge(), new ProposalConsolidator(), upserter,
            null, null, chunkRepo, Mockito.mock( ChunkEntityMentionRepository.class ),
            kgNodes, new MentionAttributor(), PageEmbeddingProvider.EMPTY, null,
            directExecutor(), directExecutor(), 1, 0, 12, 8 );
        indexer.start( false );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 1, s.failedPages(), "the failing page is counted" );
        // The surviving page still produces one consolidated proposal.
        assertEquals( 1, s.consolidatedCandidates() );
        assertEquals( 1, s.proposalsInserted() );
    }

    @Test
    void secondStartIsRejectedWhileFirstRunning() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of() );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );

        final BlockingExecutor blocking = new BlockingExecutor();
        final PageExtractor extractor = Mockito.mock( PageExtractor.class );
        when( extractor.code() ).thenReturn( "ollama:test" );
        final KgNodeRepository kgNodes = Mockito.mock( KgNodeRepository.class );
        when( kgNodes.getAllNodes() ).thenReturn( List.of() );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, new NoOpProposalJudge(), new ProposalConsolidator(),
            Mockito.mock( ProposalUpserter.class ), null, null, chunkRepo,
            Mockito.mock( ChunkEntityMentionRepository.class ), kgNodes,
            new MentionAttributor(), PageEmbeddingProvider.EMPTY, null,
            blocking, directExecutor(), 1, 0, 12, 8 );

        assertTrue( indexer.start( false ) );
        assertEquals( BootstrapEntityExtractionIndexer.State.RUNNING, indexer.status().state() );
        assertFalse( indexer.start( false ), "second start must be rejected while first is running" );

        blocking.release();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, indexer.status().state() );
        assertTrue( indexer.start( false ) );
    }

    // ---- helpers ----

    private static ExecutorService directExecutor() {
        return new DirectExecutorService();
    }

    /** Executor that runs submitted tasks on the caller's thread. */
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

    /**
     * Holds the bootstrap task in RUNNING state until {@link #release()} is
     * called, so the test can observe the "second start rejected" transition
     * without races.
     */
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
        @SuppressWarnings( "unused" ) private void unused( final Executor e ) {}
    }
}
