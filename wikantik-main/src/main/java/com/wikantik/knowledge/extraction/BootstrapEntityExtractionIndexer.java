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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admin-triggered batch that runs the configured {@link
 * com.wikantik.api.knowledge.EntityExtractor} against every chunk in
 * {@code kg_content_chunks}, filling {@code chunk_entity_mentions} and
 * {@code kg_proposals} for the whole corpus.
 *
 * <p>Execution path mirrors the save-time {@link AsyncEntityExtractionListener}
 * — same extractor, same persistence, same rate-limit and confidence rules —
 * except:
 *
 * <ul>
 *   <li>Pages are walked sequentially on a dedicated background thread, so
 *       a single Ollama host is never hit with more concurrency than the save
 *       pipeline would produce.</li>
 *   <li>Each chunk's existing mentions (from any prior extractor) are
 *       {@link ChunkEntityMentionRepository#deleteByChunkId cleared} first when
 *       {@code forceOverwrite=true}, so re-running is a true replace; with
 *       {@code forceOverwrite=false} the pre-existing rows are retained and
 *       the listener just appends new ones.</li>
 *   <li>Every page's run emits an INFO log line with elapsed wall-clock, so
 *       operators can gauge throughput without instrumenting separately.</li>
 * </ul>
 *
 * <p>Only one batch may run at a time; the state machine is
 * {@code IDLE → RUNNING → (COMPLETED | ERROR) → IDLE}.</p>
 */
public class BootstrapEntityExtractionIndexer implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( BootstrapEntityExtractionIndexer.class );

    public enum State { IDLE, RUNNING, COMPLETED, ERROR }

    public record Status(
        State state,
        int totalPages,
        int processedPages,
        int failedPages,
        int totalChunks,
        int processedChunks,
        int failedChunks,
        int mentionsWritten,
        int proposalsFiled,
        Instant startedAt,
        Instant finishedAt,
        long elapsedMs,
        String lastError,
        boolean forceOverwrite,
        int concurrency
    ) {}

    private final AsyncEntityExtractionListener listener;
    private final ContentChunkRepository chunkRepo;
    private final ChunkEntityMentionRepository mentionRepo;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ExecutorService workerPool;
    private final boolean ownsWorkerPool;
    private final int concurrency;

    private final AtomicBoolean running = new AtomicBoolean( false );
    private final AtomicBoolean cancelRequested = new AtomicBoolean( false );
    private final AtomicReference< State > state = new AtomicReference<>( State.IDLE );
    private final AtomicInteger totalPages = new AtomicInteger();
    private final AtomicInteger processedPages = new AtomicInteger();
    private final AtomicInteger failedPages = new AtomicInteger();
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger processedChunks = new AtomicInteger();
    private final AtomicInteger failedChunks = new AtomicInteger();
    private final AtomicInteger mentionsWritten = new AtomicInteger();
    private final AtomicInteger proposalsFiled = new AtomicInteger();
    private final AtomicLong startedNs = new AtomicLong();
    private final AtomicLong finishedNs = new AtomicLong();
    private final AtomicReference< Instant > startedAt = new AtomicReference<>();
    private final AtomicReference< Instant > finishedAt = new AtomicReference<>();
    private final AtomicReference< String > lastError = new AtomicReference<>();
    private final AtomicBoolean forceOverwrite = new AtomicBoolean();

    public BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                             final ContentChunkRepository chunkRepo,
                                             final ChunkEntityMentionRepository mentionRepo ) {
        this( listener, chunkRepo, mentionRepo, /*concurrency*/ 2 );
    }

    public BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                             final ContentChunkRepository chunkRepo,
                                             final ChunkEntityMentionRepository mentionRepo,
                                             final int concurrency ) {
        this( listener, chunkRepo, mentionRepo, defaultExecutor(), /*ownsExecutor*/ true,
              defaultWorkerPool( concurrency ), /*ownsWorkerPool*/ true,
              EntityExtractorConfig.clampConcurrency( concurrency ) );
    }

    public BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                             final ContentChunkRepository chunkRepo,
                                             final ChunkEntityMentionRepository mentionRepo,
                                             final ExecutorService executor ) {
        this( listener, chunkRepo, mentionRepo, executor, /*ownsExecutor*/ false,
              executor, /*ownsWorkerPool*/ false, /*concurrency*/ 1 );
    }

    public BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                             final ContentChunkRepository chunkRepo,
                                             final ChunkEntityMentionRepository mentionRepo,
                                             final ExecutorService executor,
                                             final ExecutorService workerPool,
                                             final int concurrency ) {
        this( listener, chunkRepo, mentionRepo, executor, /*ownsExecutor*/ false,
              workerPool, /*ownsWorkerPool*/ false,
              EntityExtractorConfig.clampConcurrency( concurrency ) );
    }

    private BootstrapEntityExtractionIndexer( final AsyncEntityExtractionListener listener,
                                              final ContentChunkRepository chunkRepo,
                                              final ChunkEntityMentionRepository mentionRepo,
                                              final ExecutorService executor,
                                              final boolean ownsExecutor,
                                              final ExecutorService workerPool,
                                              final boolean ownsWorkerPool,
                                              final int concurrency ) {
        if( listener == null ) throw new IllegalArgumentException( "listener must not be null" );
        if( chunkRepo == null ) throw new IllegalArgumentException( "chunkRepo must not be null" );
        if( mentionRepo == null ) throw new IllegalArgumentException( "mentionRepo must not be null" );
        if( executor == null ) throw new IllegalArgumentException( "executor must not be null" );
        if( workerPool == null ) throw new IllegalArgumentException( "workerPool must not be null" );
        this.listener = listener;
        this.chunkRepo = chunkRepo;
        this.mentionRepo = mentionRepo;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.workerPool = workerPool;
        this.ownsWorkerPool = ownsWorkerPool;
        this.concurrency = concurrency;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-entity-extract-bootstrap" );
            t.setDaemon( true );
            return t;
        } );
    }

    private static ExecutorService defaultWorkerPool( final int concurrency ) {
        final int clamped = EntityExtractorConfig.clampConcurrency( concurrency );
        final AtomicInteger idx = new AtomicInteger();
        final ThreadFactory factory = r -> {
            final Thread t = new Thread( r, "wikantik-entity-extract-worker-" + idx.incrementAndGet() );
            t.setDaemon( true );
            return t;
        };
        return Executors.newFixedThreadPool( clamped, factory );
    }

    /** Current concurrency setting (post-clamp). */
    public int concurrency() {
        return concurrency;
    }

    /**
     * Kicks off a full-corpus extraction run. Returns {@code true} if a new
     * run was started, {@code false} if one was already in flight. Never
     * blocks — the actual work happens on the background thread.
     *
     * @param forceOverwrite delete any existing mentions per chunk before
     *                       re-extracting. {@code false} keeps prior rows and
     *                       simply upserts new ones on conflict.
     */
    public boolean start( final boolean forceOverwrite ) {
        if( !running.compareAndSet( false, true ) ) {
            return false;
        }
        cancelRequested.set( false );
        // Reset counters for the new run. Done BEFORE submitting so a concurrent
        // status() call mid-submit doesn't see stale numbers.
        totalPages.set( 0 );
        processedPages.set( 0 );
        failedPages.set( 0 );
        totalChunks.set( 0 );
        processedChunks.set( 0 );
        failedChunks.set( 0 );
        mentionsWritten.set( 0 );
        proposalsFiled.set( 0 );
        startedNs.set( System.nanoTime() );
        finishedNs.set( 0L );
        startedAt.set( Instant.now() );
        finishedAt.set( null );
        lastError.set( null );
        this.forceOverwrite.set( forceOverwrite );
        state.set( State.RUNNING );

        executor.submit( () -> runSafely( forceOverwrite ) );
        return true;
    }

    public Status status() {
        final long elapsedMs;
        if( state.get() == State.RUNNING ) {
            elapsedMs = ( System.nanoTime() - startedNs.get() ) / 1_000_000L;
        } else if( finishedNs.get() > 0L ) {
            elapsedMs = ( finishedNs.get() - startedNs.get() ) / 1_000_000L;
        } else {
            elapsedMs = 0L;
        }
        return new Status(
            state.get(),
            totalPages.get(),
            processedPages.get(),
            failedPages.get(),
            totalChunks.get(),
            processedChunks.get(),
            failedChunks.get(),
            mentionsWritten.get(),
            proposalsFiled.get(),
            startedAt.get(),
            finishedAt.get(),
            Math.max( 0L, elapsedMs ),
            lastError.get(),
            forceOverwrite.get(),
            concurrency
        );
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Signals the running batch to stop between pages. The currently-in-flight
     * page's remaining chunks are still processed (the extractor is a blocking
     * RPC and we do not abort mid-call). Returns {@code true} if a running
     * batch was asked to cancel, {@code false} if nothing was running.
     */
    public boolean cancel() {
        if( !running.get() ) return false;
        cancelRequested.set( true );
        LOG.info( "Bootstrap extraction cancellation requested" );
        return true;
    }

    // ---- internals ----

    private void runSafely( final boolean overwrite ) {
        try {
            runBatch( overwrite );
            state.set( State.COMPLETED );
        } catch( final RuntimeException e ) {
            state.set( State.ERROR );
            lastError.set( e.getMessage() );
            // LOG.error justified: admin-triggered batch terminated before completion; the corpus is partially re-extracted and operator action is required to resume or investigate.
            LOG.error( "Bootstrap entity extraction failed: {}", e.getMessage(), e );
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            state.set( State.ERROR );
            lastError.set( "interrupted" );
            LOG.warn( "Bootstrap entity extraction interrupted" );
        } finally {
            finishedNs.set( System.nanoTime() );
            finishedAt.set( Instant.now() );
            running.set( false );
            logFinalSummary();
        }
    }

    private void runBatch( final boolean overwrite ) throws InterruptedException {
        final List< String > pages = chunkRepo.listDistinctPageNames();
        totalPages.set( pages.size() );
        // One COUNT(*) up front so progress can report chunks processed against
        // a fixed denominator. If chunks are added mid-run (they shouldn't be),
        // processedChunks can briefly overshoot; that's better than a moving
        // target in operator logs.
        try {
            totalChunks.set( chunkRepo.stats().totalChunks() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: failed to precompute total chunk count: {}", e.getMessage() );
        }
        LOG.info( "Bootstrap entity extraction starting: pages={}, chunks={}, forceOverwrite={}",
            pages.size(), totalChunks.get(), overwrite );

        int completed = 0;
        int failed = 0;
        for( final String page : pages ) {
            if( Thread.currentThread().isInterrupted() ) {
                throw new InterruptedException( "bootstrap extraction interrupted after " + completed + " pages" );
            }
            if( cancelRequested.get() ) {
                LOG.info( "Bootstrap extraction: cancellation acknowledged after {}/{} pages",
                    completed, pages.size() );
                break;
            }
            final long pageStartNs = System.nanoTime();
            final List< UUID > chunkIds;
            try {
                chunkIds = chunkRepo.listChunkIdsForPage( page );
            } catch( final RuntimeException e ) {
                failed++;
                failedPages.incrementAndGet();
                LOG.warn( "Bootstrap extraction: failed to list chunks for page '{}': {}",
                    page, e.getMessage() );
                continue;
            }
            if( chunkIds.isEmpty() ) {
                processedPages.incrementAndGet();
                continue;
            }

            if( overwrite ) {
                int cleared = 0;
                for( final UUID id : chunkIds ) {
                    try {
                        cleared += mentionRepo.deleteByChunkId( id );
                    } catch( final RuntimeException e ) {
                        LOG.warn( "Bootstrap extraction: failed to clear mentions for chunk {}: {}",
                            id, e.getMessage() );
                    }
                }
                if( LOG.isDebugEnabled() ) {
                    LOG.debug( "Bootstrap extraction: cleared {} pre-existing mention rows for page '{}'",
                        cleared, page );
                }
            }

            // Fan chunks out across the worker pool up to `concurrency`. The
            // fixed-size pool naturally throttles admission — every submitted
            // Future waits for a slot rather than piling up, so the inference
            // backend sees at most `concurrency` in-flight requests from this
            // indexer. Cancel is checked before each submit so a cancelled
            // run doesn't keep queuing new work; in-flight futures still
            // complete because we never abort the RPC mid-call.
            final List< Future< ChunkOutcome > > futures = new ArrayList<>( chunkIds.size() );
            boolean cancelledMidPage = false;
            for( final UUID chunkId : chunkIds ) {
                if( cancelRequested.get() ) {
                    cancelledMidPage = true;
                    break;
                }
                final String pageForTask = page;
                futures.add( workerPool.submit( () -> runChunk( chunkId, pageForTask ) ) );
            }

            int pageMentions = 0;
            int pageProposals = 0;
            int chunksProcessed = 0;
            boolean pageFailed = false;
            for( final Future< ChunkOutcome > f : futures ) {
                final ChunkOutcome outcome;
                try {
                    outcome = f.get();
                } catch( final InterruptedException ie ) {
                    Thread.currentThread().interrupt();
                    pageFailed = true;
                    lastError.set( "page=" + page + ": interrupted awaiting chunk" );
                    continue;
                } catch( final ExecutionException ee ) {
                    pageFailed = true;
                    final Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    lastError.set( "page=" + page + ": " + cause.getMessage() );
                    LOG.warn( "Bootstrap extraction: chunk task on page '{}' threw: {}",
                        page, cause.getMessage() );
                    continue;
                }
                if( outcome.failed() ) {
                    pageFailed = true;
                    continue;
                }
                chunksProcessed++;
                pageMentions += outcome.mentions();
                pageProposals += outcome.proposals();
            }

            if( cancelledMidPage ) {
                LOG.info( "Bootstrap extraction: cancellation acknowledged mid-page '{}' after {}/{} chunks",
                    page, chunksProcessed, chunkIds.size() );
            }

            // mentionsWritten / proposalsFiled are now incremented inside runChunk
            // for real-time progress visibility; the page loop keeps per-page
            // tallies only for the per-page summary log line below.
            if( pageFailed ) {
                failed++;
                failedPages.incrementAndGet();
            }
            completed++;
            processedPages.incrementAndGet();

            final long elapsedMs = ( System.nanoTime() - pageStartNs ) / 1_000_000L;
            LOG.info( "Bootstrap extraction: page='{}' chunks={}/{} mentions={} proposals={} elapsedMs={} concurrency={}",
                page, chunksProcessed, chunkIds.size(), pageMentions, pageProposals, elapsedMs, concurrency );

            if( cancelledMidPage ) break;
        }

        if( failed > 0 ) {
            LOG.warn( "Bootstrap extraction finished with {} failed pages of {}", failed, pages.size() );
        }
    }

    private void logFinalSummary() {
        final long elapsedMs = finishedNs.get() > 0L
            ? ( finishedNs.get() - startedNs.get() ) / 1_000_000L
            : 0L;
        final int donePages = processedPages.get();
        final int doneChunks = processedChunks.get();
        final long meanPerPageMs = donePages > 0 ? elapsedMs / (long) donePages : 0L;
        final long meanPerChunkMs = doneChunks > 0 ? elapsedMs / (long) doneChunks : 0L;
        LOG.info( "Bootstrap entity extraction {}: processedPages={}/{}, failedPages={}, "
                + "processedChunks={}/{}, failedChunks={}, "
                + "mentionsWritten={}, proposalsFiled={}, totalMs={}, meanPerPageMs={}, meanPerChunkMs={}",
            state.get(), donePages, totalPages.get(), failedPages.get(),
            doneChunks, totalChunks.get(), failedChunks.get(),
            mentionsWritten.get(), proposalsFiled.get(), elapsedMs, meanPerPageMs, meanPerChunkMs );
    }

    @Override
    public void close() {
        if( ownsExecutor ) {
            shutdown( executor, "Bootstrap extraction executor" );
        }
        if( ownsWorkerPool && workerPool != executor ) {
            shutdown( workerPool, "Bootstrap extraction worker pool" );
        }
    }

    private static void shutdown( final ExecutorService pool, final String label ) {
        pool.shutdown();
        try {
            if( !pool.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "{} did not drain within 5s; forcing shutdown", label );
                pool.shutdownNow();
            }
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /**
     * Runs extraction for a single chunk inside a worker thread. Catches
     * runtime failures from the listener so one bad chunk doesn't kill the
     * worker pool; aggregates timing so the per-chunk INFO line matches the
     * shape callers have learned to grep.
     */
    private ChunkOutcome runChunk( final UUID chunkId, final String page ) {
        final long chunkStartNs = System.nanoTime();
        try {
            final AsyncEntityExtractionListener.RunResult res = listener.runExtractionSync( List.of( chunkId ) );
            final long elapsedMs = ( System.nanoTime() - chunkStartNs ) / 1_000_000L;
            processedChunks.incrementAndGet();
            // Increment aggregate counters per-chunk so status() reflects real-time
            // progress, not page-granular. A poll between chunks of a 40-chunk page
            // previously showed mentions=0 proposals=0 until the whole page finished.
            mentionsWritten.addAndGet( res.mentionsWritten() );
            proposalsFiled.addAndGet( res.proposalsFiled() );
            LOG.info( "Bootstrap extraction: chunk={} page='{}' mentions={} proposals={} elapsedMs={} done={}/{}",
                chunkId, page, res.mentionsWritten(), res.proposalsFiled(), elapsedMs,
                processedChunks.get(), totalChunks.get() );
            return new ChunkOutcome( res.mentionsWritten(), res.proposalsFiled(), false );
        } catch( final RuntimeException e ) {
            processedChunks.incrementAndGet();
            failedChunks.incrementAndGet();
            lastError.set( "page=" + page + " chunk=" + chunkId + ": " + e.getMessage() );
            LOG.warn( "Bootstrap extraction: chunk {} on page '{}' failed: {}",
                chunkId, page, e.getMessage() );
            return new ChunkOutcome( 0, 0, true );
        }
    }

    private record ChunkOutcome( int mentions, int proposals, boolean failed ) {}
}
