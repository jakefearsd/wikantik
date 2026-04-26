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

import com.wikantik.knowledge.chunking.ContentChunkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BootstrapEntityExtractionIndexer}. Runs on an
 * in-process synchronous executor so assertions don't race the background
 * thread.
 */
class BootstrapEntityExtractionIndexerTest {

    @Test
    void walksEveryPageAndRecordsAggregateCounts() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();
        final UUID c3 = UUID.randomUUID();

        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "A", "B" ) );
        when( chunkRepo.listChunkIdsForPage( "A" ) ).thenReturn( List.of( c1, c2 ) );
        when( chunkRepo.listChunkIdsForPage( "B" ) ).thenReturn( List.of( c3 ) );
        when( listener.runExtractionSync( List.of( c1 ) ) )
                .thenReturn( new AsyncEntityExtractionListener.RunResult( 3, 1 ) );
        when( listener.runExtractionSync( List.of( c2 ) ) )
                .thenReturn( new AsyncEntityExtractionListener.RunResult( 2, 0 ) );
        when( listener.runExtractionSync( List.of( c3 ) ) )
                .thenReturn( new AsyncEntityExtractionListener.RunResult( 2, 0 ) );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                listener, chunkRepo, mentionRepo, directExecutor() );
        assertTrue( indexer.start( /*forceOverwrite*/ false ) );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 2, s.totalPages() );
        assertEquals( 2, s.processedPages() );
        assertEquals( 0, s.failedPages() );
        assertEquals( 7, s.mentionsWritten() );
        assertEquals( 1, s.proposalsFiled() );
        // No overwrite requested → no clears per chunk.
        verify( mentionRepo, never() ).deleteByChunkId( any() );
    }

    @Test
    void forceOverwriteClearsEachChunkBeforeExtraction() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "PageOne" ) );
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();
        when( chunkRepo.listChunkIdsForPage( "PageOne" ) ).thenReturn( List.of( c1, c2 ) );
        when( listener.runExtractionSync( any() ) )
                .thenReturn( AsyncEntityExtractionListener.RunResult.EMPTY );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                listener, chunkRepo, mentionRepo, directExecutor() );
        assertTrue( indexer.start( /*forceOverwrite*/ true ) );

        final ArgumentCaptor< UUID > cleared = ArgumentCaptor.forClass( UUID.class );
        verify( mentionRepo, times( 2 ) ).deleteByChunkId( cleared.capture() );
        assertTrue( cleared.getAllValues().contains( c1 ) );
        assertTrue( cleared.getAllValues().contains( c2 ) );
        assertTrue( indexer.status().forceOverwrite() );
    }

    @Test
    void secondStartIsRejectedWhileFirstRunning() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

        // Executor that keeps the first submitted task pending so state stays RUNNING.
        final BlockingExecutor blocking = new BlockingExecutor();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of() );
        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                listener, chunkRepo, mentionRepo, blocking );

        assertTrue( indexer.start( false ) );
        assertEquals( BootstrapEntityExtractionIndexer.State.RUNNING, indexer.status().state() );
        assertFalse( indexer.start( false ), "second start must be rejected while first is running" );

        blocking.release();
        // After the blocked task has run, state transitions to COMPLETED.
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, indexer.status().state() );
        // And a fresh start is allowed.
        assertTrue( indexer.start( false ) );
    }

    @Test
    void extractorFailureForOnePageIsIsolatedAndCountsAsFailure() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "Alpha", "Beta" ) );
        when( chunkRepo.listChunkIdsForPage( "Alpha" ) ).thenReturn( List.of( UUID.randomUUID() ) );
        when( chunkRepo.listChunkIdsForPage( "Beta" ) ).thenReturn( List.of( UUID.randomUUID() ) );
        when( listener.runExtractionSync( any() ) )
                .thenThrow( new RuntimeException( "ollama down" ) )
                .thenReturn( new AsyncEntityExtractionListener.RunResult( 3, 0 ) );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                listener, chunkRepo, mentionRepo, directExecutor() );
        indexer.start( false );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 2, s.totalPages() );
        // Both pages count as processed even if individual chunks fail — the
        // failure tracking is at page granularity, so a single failed chunk
        // flags the page without discarding successful ones.
        assertEquals( 2, s.processedPages() );
        assertEquals( 1, s.failedPages() );
        assertEquals( 3, s.mentionsWritten() );
    }

    @Test
    void configuredConcurrencyRunsChunksInParallel() throws Exception {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        final List< UUID > chunks = List.of(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() );
        when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( chunks );

        // Each extraction call blocks on a latch until all 3 workers are in flight.
        // If the indexer accidentally runs chunks serially the latch will never trip.
        final CountDownLatch inFlight = new CountDownLatch( 3 );
        final AtomicInteger concurrentPeak = new AtomicInteger();
        final AtomicInteger concurrentNow = new AtomicInteger();
        when( listener.runExtractionSync( any() ) ).thenAnswer( inv -> {
            final int now = concurrentNow.incrementAndGet();
            concurrentPeak.accumulateAndGet( now, Math::max );
            inFlight.countDown();
            inFlight.await( 5, TimeUnit.SECONDS );
            concurrentNow.decrementAndGet();
            return new AsyncEntityExtractionListener.RunResult( 1, 0 );
        } );

        final ExecutorService driver = Executors.newSingleThreadExecutor();
        final ExecutorService workers = Executors.newFixedThreadPool( 3 );
        try {
            final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                    listener, chunkRepo, mentionRepo, driver, workers, 3 );
            assertTrue( indexer.start( false ) );
            // Wait for completion.
            for( int i = 0; i < 50 && indexer.status().state() == BootstrapEntityExtractionIndexer.State.RUNNING; i++ ) {
                Thread.sleep( 100 );
            }
            assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, indexer.status().state() );
            assertEquals( 4, indexer.status().mentionsWritten() );
            assertEquals( 3, concurrentPeak.get(), "at least 3 chunks must have run concurrently" );
        } finally {
            driver.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Test
    void prefilterSkipsChunksAndIncrementsCounters() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

        final UUID keep = UUID.randomUUID();
        final UUID skipCode = UUID.randomUUID();
        final UUID skipNoProper = UUID.randomUUID();

        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "PageOne" ) );
        when( chunkRepo.listChunkIdsForPage( "PageOne" ) )
                .thenReturn( List.of( keep, skipCode, skipNoProper ) );
        when( chunkRepo.findByIds( List.of( keep, skipCode, skipNoProper ) ) ).thenReturn( List.of(
            new ContentChunkRepository.MentionableChunk( keep, "PageOne", 0, List.of(),
                "PostgreSQL is a database." ),
            new ContentChunkRepository.MentionableChunk( skipCode, "PageOne", 1, List.of(),
                "```\nselect 1;\n```" ),
            new ContentChunkRepository.MentionableChunk( skipNoProper, "PageOne", 2, List.of(),
                "lower case prose only." )
        ) );
        when( listener.runExtractionSync( List.of( keep ) ) )
                .thenReturn( new AsyncEntityExtractionListener.RunResult( 1, 0 ) );

        // Length predicate intentionally off — this test exercises the
        // code/proper-noun rules with short fixture chunks that would
        // otherwise all trip the too_short rule.
        final ChunkExtractionPrefilter filter = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ false, /*minTokens*/ 0 );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                listener, chunkRepo, mentionRepo, directExecutor(), filter );
        assertTrue( indexer.start( /*forceOverwrite*/ false ) );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( BootstrapEntityExtractionIndexer.State.COMPLETED, s.state() );
        assertEquals( 1, s.processedChunks(), "only one chunk reaches the extractor" );
        assertEquals( 2, s.skippedChunks() );
        assertEquals( 1, s.skipReasons().getOrDefault( "pure_code", 0 ).intValue() );
        assertEquals( 1, s.skipReasons().getOrDefault( "no_proper_noun", 0 ).intValue() );
        verify( listener, times( 1 ) ).runExtractionSync( List.of( keep ) );
        verify( listener, never() ).runExtractionSync( List.of( skipCode ) );
        verify( listener, never() ).runExtractionSync( List.of( skipNoProper ) );
    }

    @Test
    void prefilterDisabledByDefaultMatchesLegacyBehaviour() {
        final ContentChunkRepository chunkRepo = Mockito.mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = Mockito.mock( ChunkEntityMentionRepository.class );
        final AsyncEntityExtractionListener listener = Mockito.mock( AsyncEntityExtractionListener.class );

        final UUID c = UUID.randomUUID();
        when( chunkRepo.listDistinctPageNames() ).thenReturn( List.of( "P" ) );
        when( chunkRepo.listChunkIdsForPage( "P" ) ).thenReturn( List.of( c ) );
        when( listener.runExtractionSync( List.of( c ) ) )
                .thenReturn( new AsyncEntityExtractionListener.RunResult( 0, 0 ) );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                listener, chunkRepo, mentionRepo, directExecutor() );
        assertTrue( indexer.start( /*forceOverwrite*/ false ) );

        final BootstrapEntityExtractionIndexer.Status s = indexer.status();
        assertEquals( 0, s.skippedChunks() );
        assertTrue( s.skipReasons().isEmpty() );
        // No findByIds call when filter is passthrough — legacy path is byte-identical.
        verify( chunkRepo, never() ).findByIds( any() );
        verify( listener ).runExtractionSync( List.of( c ) );
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
     * Lets us hold the bootstrap task in RUNNING state deterministically so the
     * test can observe the "second start rejected" transition without races.
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
        // unused
        @SuppressWarnings( "unused" ) private void unused( final Executor e ) {}
    }
}
