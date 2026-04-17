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
package com.wikantik.admin;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test-support factory for {@link ContentIndexRebuildService}. Hand-rolls
 * lightweight doubles so state-machine tests don't need the testcontainers
 * harness — only the run-loop body will need real storage in Task 10.
 */
final class RebuildTestFactory {

    private RebuildTestFactory() {}

    /**
     * Service over a PageManager that returns zero pages. Rebuild is enabled;
     * the stub {@code runRebuild()} immediately returns to IDLE.
     */
    static ContentIndexRebuildService buildWithNoPages() {
        return baseBuilder( () -> true ).build();
    }

    /**
     * Service whose stub run loop blocks on a latch, so the test can observe
     * a non-IDLE state after the first trigger and assert that a second
     * trigger throws {@link ContentIndexRebuildService.ConflictException}.
     *
     * <p>The blocking is implemented by subclassing the production class and
     * overriding the {@code runRebuild()} seam — the production class stays
     * clean of test hooks.</p>
     */
    static BlockingHandle buildWithBlockingPage() {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final Builder b = baseBuilder( () -> true );
        final ContentIndexRebuildService svc = new ContentIndexRebuildService(
                b.pages, b.systemPages, b.lucene, b.chunkRepo, b.chunker,
                b.rebuildEnabled, b.luceneDrainPollMs ) {
            @Override
            protected void runRebuild() {
                setState( State.RUNNING );
                entered.countDown();
                try {
                    if ( !release.await( 10, TimeUnit.SECONDS ) ) {
                        LOG.warn( "release latch timed out in blocking test runRebuild" );
                    }
                } catch ( final InterruptedException ie ) {
                    Thread.currentThread().interrupt();
                    LOG.warn( "runRebuild interrupted while awaiting release latch", ie );
                } finally {
                    setState( State.IDLE );
                }
            }
        };
        return new BlockingHandle( svc, entered, release );
    }

    /** Service with the kill-switch supplier returning false. */
    static ContentIndexRebuildService buildWithRebuildDisabled() {
        return baseBuilder( () -> false ).build();
    }

    // ---- internals ----

    private static final Logger LOG = LogManager.getLogger( RebuildTestFactory.class );

    /**
     * Handle returned by {@link #buildWithBlockingPage()} so the test can
     * await the RUNNING transition and release the stub loop at end-of-test.
     */
    static final class BlockingHandle {
        final ContentIndexRebuildService service;
        final CountDownLatch entered;
        final CountDownLatch release;

        BlockingHandle( final ContentIndexRebuildService svc,
                        final CountDownLatch entered,
                        final CountDownLatch release ) {
            this.service = svc;
            this.entered = entered;
            this.release = release;
        }

        boolean awaitRunning( final long millis ) throws InterruptedException {
            return entered.await( millis, TimeUnit.MILLISECONDS );
        }

        void releaseRunLoop() {
            release.countDown();
        }
    }

    private static Builder baseBuilder( final BooleanSupplier enabled ) {
        final PageManager pm = mock( PageManager.class );
        try {
            lenient().when( pm.getAllPages() ).thenReturn( Collections.< Page >emptyList() );
        } catch ( final Exception e ) {
            // Mockito's lenient stub cannot throw at stubbing time — this try/catch
            // only exists because getAllPages() declares a checked ProviderException.
            throw new AssertionError( "unreachable: stubbing cannot throw", e );
        }

        final SystemPageRegistry sp = mock( SystemPageRegistry.class );
        lenient().when( sp.isSystemPage( anyString() ) ).thenReturn( false );

        final LuceneReindexQueue lucene = new NoopLuceneQueue();

        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        when( chunkRepo.stats() )
            .thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );

        final ContentChunker chunker =
            new ContentChunker( new ContentChunker.Config( 600, 900, 180, 8 ) );

        return new Builder( pm, sp, lucene, chunkRepo, chunker, enabled, 25L );
    }

    private static final class Builder {
        final PageManager pages;
        final SystemPageRegistry systemPages;
        final LuceneReindexQueue lucene;
        final ContentChunkRepository chunkRepo;
        final ContentChunker chunker;
        final BooleanSupplier rebuildEnabled;
        final long luceneDrainPollMs;

        Builder( final PageManager pages,
                 final SystemPageRegistry systemPages,
                 final LuceneReindexQueue lucene,
                 final ContentChunkRepository chunkRepo,
                 final ContentChunker chunker,
                 final BooleanSupplier rebuildEnabled,
                 final long luceneDrainPollMs ) {
            this.pages = pages;
            this.systemPages = systemPages;
            this.lucene = lucene;
            this.chunkRepo = chunkRepo;
            this.chunker = chunker;
            this.rebuildEnabled = rebuildEnabled;
            this.luceneDrainPollMs = luceneDrainPollMs;
        }

        ContentIndexRebuildService build() {
            return new ContentIndexRebuildService(
                pages, systemPages, lucene, chunkRepo, chunker,
                rebuildEnabled, luceneDrainPollMs );
        }
    }

    /** Minimal {@link LuceneReindexQueue} for tests that never index. */
    private static final class NoopLuceneQueue implements LuceneReindexQueue {
        @Override public void reindexPage( final Page page ) {}
        @Override public int queueDepth() { return 0; }
        @Override public int documentCount() { return 0; }
        @Override public Instant lastUpdateInstant() { return null; }
        @Override public void clearIndex() {}
    }
}
