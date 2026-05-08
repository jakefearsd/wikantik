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

import com.wikantik.api.knowledge.PageExtractor;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingService;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admin-triggered batch that drives the per-page extraction pipeline.
 *
 * <ol>
 *   <li>Warm the {@code kg_node_embeddings} cache via
 *       {@link KgNodeEmbeddingService#warmUp}.</li>
 *   <li>Walk every distinct page name in {@code kg_content_chunks}.</li>
 *   <li>For each page (concurrent across a worker pool): stitch the body from
 *       its chunks, look up a top-K dictionary of existing nodes, and call
 *       {@link PageExtractor#extract} once per page.</li>
 *   <li>Consolidate per-page results into one
 *       {@link ConsolidatedProposal} per logical claim, run each through
 *       {@link ProposalJudge}, and upsert {@code Accept}/{@code Rewrite}
 *       verdicts via {@link ProposalUpserter}.</li>
 *   <li>For every accepted name that already maps to a {@code kg_nodes} row,
 *       run {@link MentionAttributor} over each chunk and bulk-upsert
 *       {@code chunk_entity_mentions}.</li>
 * </ol>
 *
 * <p><b>Phase 11 Ckpt 6:</b> heavy batch logic extracted to
 * {@link ExtractionBatchRunner} (pages → consolidate → judge → upsert) and
 * {@link MentionAttributionRunner} (chunk-mention attribution). This class owns
 * state counters, the lifecycle (start / status / cancel / close), and
 * {@code runSafely}.
 *
 * <p>State machine: {@code IDLE → RUNNING → (COMPLETED | ERROR) → IDLE}.
 * Only one batch may be in flight at a time.</p>
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
        int concurrency,
        int skippedChunks,
        Map< String, Integer > skipReasons,
        int excludedSkipped,
        // ---- new pipeline counters ----
        int consolidatedCandidates,
        int judgeAccepted,
        int judgeRejected,
        int judgeRewritten,
        int proposalsInserted,
        int proposalsMerged,
        Map< String, Integer > rejectionReasons
    ) {}

    private final KgNodeRepository          kgNodes;
    private final PageEmbeddingProvider     pageEmbeddings;
    private final ExecutorService           executor;
    private final boolean                   ownsExecutor;
    private final ExecutorService           workerPool;
    private final boolean                   ownsWorkerPool;
    private final int                       concurrency;

    // Composed helpers (Phase 11 Ckpt 6)
    private final ExtractionBatchRunner      batchRunner;
    private final MentionAttributionRunner   mentionRunner;

    private final AtomicBoolean running = new AtomicBoolean( false );
    private final AtomicBoolean cancelRequested = new AtomicBoolean( false );
    private final AtomicBoolean dryRun = new AtomicBoolean( false );
    private final AtomicReference< State > state = new AtomicReference<>( State.IDLE );
    private final AtomicInteger totalPages = new AtomicInteger();
    private final AtomicInteger processedPages = new AtomicInteger();
    private final AtomicInteger failedPages = new AtomicInteger();
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger processedChunks = new AtomicInteger();
    private final AtomicInteger failedChunks = new AtomicInteger();
    private final AtomicInteger mentionsWritten = new AtomicInteger();
    private final AtomicInteger excludedSkipped = new AtomicInteger();
    private final AtomicInteger consolidatedCandidates = new AtomicInteger();
    private final AtomicInteger judgeAccepted = new AtomicInteger();
    private final AtomicInteger judgeRejected = new AtomicInteger();
    private final AtomicInteger judgeRewritten = new AtomicInteger();
    private final AtomicInteger proposalsInserted = new AtomicInteger();
    private final AtomicInteger proposalsMerged = new AtomicInteger();
    private final Map< String, Integer > rejectionReasons = new ConcurrentHashMap<>();
    private final AtomicLong startedNs = new AtomicLong();
    private final AtomicLong finishedNs = new AtomicLong();
    private final AtomicReference< Instant > startedAt = new AtomicReference<>();
    private final AtomicReference< Instant > finishedAt = new AtomicReference<>();
    private final AtomicReference< String > lastError = new AtomicReference<>();
    private final AtomicBoolean forceOverwrite = new AtomicBoolean();
    private final AtomicInteger maxPages = new AtomicInteger( 0 );

    public BootstrapEntityExtractionIndexer( final PageExtractor pageExtractor,
                                             final ProposalJudge judge,
                                             final ProposalConsolidator consolidator,
                                             final ProposalUpserter upserter,
                                             final KgNodeEmbeddingService embeddingService,
                                             final KgNodeEmbeddingRepository embeddingRepo,
                                             final ContentChunkRepository chunkRepo,
                                             final ChunkEntityMentionRepository mentionRepo,
                                             final KgNodeRepository kgNodes,
                                             final MentionAttributor mentionAttributor,
                                             final PageEmbeddingProvider pageEmbeddings,
                                             final KgExcludedPagesRepository excludedPages,
                                             final int concurrency,
                                             final int dictionaryTopK,
                                             final int maxEntitiesPerPage,
                                             final int maxRelationsPerPage ) {
        this( pageExtractor, judge, consolidator, upserter, embeddingService, embeddingRepo,
              chunkRepo, mentionRepo, kgNodes, mentionAttributor, pageEmbeddings, excludedPages,
              defaultExecutor(), /*ownsExecutor*/ true,
              defaultWorkerPool( concurrency ), /*ownsWorkerPool*/ true,
              EntityExtractorConfig.clampConcurrency( concurrency ),
              dictionaryTopK, maxEntitiesPerPage, maxRelationsPerPage );
    }

    /** Test-friendly variant: caller supplies an in-process executor and worker pool. */
    public BootstrapEntityExtractionIndexer( final PageExtractor pageExtractor,
                                             final ProposalJudge judge,
                                             final ProposalConsolidator consolidator,
                                             final ProposalUpserter upserter,
                                             final KgNodeEmbeddingService embeddingService,
                                             final KgNodeEmbeddingRepository embeddingRepo,
                                             final ContentChunkRepository chunkRepo,
                                             final ChunkEntityMentionRepository mentionRepo,
                                             final KgNodeRepository kgNodes,
                                             final MentionAttributor mentionAttributor,
                                             final PageEmbeddingProvider pageEmbeddings,
                                             final KgExcludedPagesRepository excludedPages,
                                             final ExecutorService executor,
                                             final ExecutorService workerPool,
                                             final int concurrency,
                                             final int dictionaryTopK,
                                             final int maxEntitiesPerPage,
                                             final int maxRelationsPerPage ) {
        this( pageExtractor, judge, consolidator, upserter, embeddingService, embeddingRepo,
              chunkRepo, mentionRepo, kgNodes, mentionAttributor, pageEmbeddings, excludedPages,
              executor, /*ownsExecutor*/ false,
              workerPool, /*ownsWorkerPool*/ false,
              EntityExtractorConfig.clampConcurrency( concurrency ),
              dictionaryTopK, maxEntitiesPerPage, maxRelationsPerPage );
    }

    private BootstrapEntityExtractionIndexer( final PageExtractor pageExtractor,
                                              final ProposalJudge judge,
                                              final ProposalConsolidator consolidator,
                                              final ProposalUpserter upserter,
                                              final KgNodeEmbeddingService embeddingService,
                                              final KgNodeEmbeddingRepository embeddingRepo,
                                              final ContentChunkRepository chunkRepo,
                                              final ChunkEntityMentionRepository mentionRepo,
                                              final KgNodeRepository kgNodes,
                                              final MentionAttributor mentionAttributor,
                                              final PageEmbeddingProvider pageEmbeddings,
                                              final KgExcludedPagesRepository excludedPages,
                                              final ExecutorService executor,
                                              final boolean ownsExecutor,
                                              final ExecutorService workerPool,
                                              final boolean ownsWorkerPool,
                                              final int concurrency,
                                              final int dictionaryTopK,
                                              final int maxEntitiesPerPage,
                                              final int maxRelationsPerPage ) {
        if ( pageExtractor == null ) throw new IllegalArgumentException( "pageExtractor must not be null" );
        if ( judge == null ) throw new IllegalArgumentException( "judge must not be null" );
        if ( consolidator == null ) throw new IllegalArgumentException( "consolidator must not be null" );
        if ( upserter == null ) throw new IllegalArgumentException( "upserter must not be null" );
        if ( chunkRepo == null ) throw new IllegalArgumentException( "chunkRepo must not be null" );
        if ( mentionRepo == null ) throw new IllegalArgumentException( "mentionRepo must not be null" );
        if ( kgNodes == null ) throw new IllegalArgumentException( "kgNodes must not be null" );
        if ( mentionAttributor == null ) throw new IllegalArgumentException( "mentionAttributor must not be null" );
        if ( executor == null ) throw new IllegalArgumentException( "executor must not be null" );
        if ( workerPool == null ) throw new IllegalArgumentException( "workerPool must not be null" );
        this.kgNodes         = kgNodes;
        this.pageEmbeddings  = pageEmbeddings == null ? PageEmbeddingProvider.EMPTY : pageEmbeddings;
        this.executor        = executor;
        this.ownsExecutor    = ownsExecutor;
        this.workerPool      = workerPool;
        this.ownsWorkerPool  = ownsWorkerPool;
        this.concurrency     = concurrency;
        this.batchRunner = new ExtractionBatchRunner(
            pageExtractor, judge, consolidator, upserter,
            embeddingService, embeddingRepo, chunkRepo, excludedPages,
            workerPool, Math.max( 0, dictionaryTopK ) );
        this.mentionRunner = new MentionAttributionRunner(
            kgNodes, mentionRepo, mentionAttributor, pageExtractor.code() );
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

    public int concurrency() {
        return concurrency;
    }

    /** When set, the upsert step is skipped — useful for offline smoke runs. */
    public void setDryRun( final boolean enabled ) {
        this.dryRun.set( enabled );
    }

    public boolean isDryRun() {
        return dryRun.get();
    }

    /**
     * Kicks off a full-corpus extraction. Returns {@code true} if a new run
     * was started, {@code false} if one was already in flight.
     */
    public boolean start( final boolean forceOverwrite ) {
        return start( forceOverwrite, /*maxPages*/ 0 );
    }

    /**
     * Variant that caps the run at the first {@code maxPages} page-names.
     */
    public boolean start( final boolean forceOverwrite, final int maxPages ) {
        if ( !running.compareAndSet( false, true ) ) {
            return false;
        }
        cancelRequested.set( false );
        totalPages.set( 0 );
        processedPages.set( 0 );
        failedPages.set( 0 );
        totalChunks.set( 0 );
        processedChunks.set( 0 );
        failedChunks.set( 0 );
        mentionsWritten.set( 0 );
        excludedSkipped.set( 0 );
        consolidatedCandidates.set( 0 );
        judgeAccepted.set( 0 );
        judgeRejected.set( 0 );
        judgeRewritten.set( 0 );
        proposalsInserted.set( 0 );
        proposalsMerged.set( 0 );
        rejectionReasons.clear();
        startedNs.set( System.nanoTime() );
        finishedNs.set( 0L );
        startedAt.set( Instant.now() );
        finishedAt.set( null );
        lastError.set( null );
        this.forceOverwrite.set( forceOverwrite );
        this.maxPages.set( Math.max( 0, maxPages ) );
        state.set( State.RUNNING );

        executor.submit( () -> runSafely( forceOverwrite ) );
        return true;
    }

    public Status status() {
        final long elapsedMs;
        if ( state.get() == State.RUNNING ) {
            elapsedMs = ( System.nanoTime() - startedNs.get() ) / 1_000_000L;
        } else if ( finishedNs.get() > 0L ) {
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
            /*proposalsFiled=*/ proposalsInserted.get() + proposalsMerged.get(),
            startedAt.get(),
            finishedAt.get(),
            Math.max( 0L, elapsedMs ),
            lastError.get(),
            forceOverwrite.get(),
            concurrency,
            /*skippedChunks (legacy chunk-prefilter; unused in page pipeline)=*/ 0,
            /*skipReasons (legacy chunk-prefilter)=*/ Map.of(),
            excludedSkipped.get(),
            consolidatedCandidates.get(),
            judgeAccepted.get(),
            judgeRejected.get(),
            judgeRewritten.get(),
            proposalsInserted.get(),
            proposalsMerged.get(),
            Map.copyOf( rejectionReasons )
        );
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Signals the running batch to stop between pages. The currently-in-flight
     * page completes; subsequent pages are not started.
     */
    public boolean cancel() {
        if ( !running.get() ) return false;
        cancelRequested.set( true );
        LOG.info( "Bootstrap extraction cancellation requested" );
        return true;
    }

    // ---- internals ----

    private void runSafely( final boolean overwrite ) {
        try {
            batchRunner.warmNodeEmbeddings( kgNodes );
            final ExtractionBatchRunner.BatchResult batch =
                batchRunner.runBatch( overwrite, pageEmbeddings, buildCounters() );
            // Step 7 — mention attribution using accepted proposals from the batch.
            final int written = mentionRunner.attribute( batch.outcomes(), batch.accepted(), overwrite );
            mentionsWritten.addAndGet( written );
            state.set( State.COMPLETED );
        } catch ( final RuntimeException e ) {
            state.set( State.ERROR );
            lastError.set( e.getMessage() );
            // LOG.error justified: admin-triggered batch terminated before completion; operator action required.
            LOG.error( "Bootstrap entity extraction failed: {}", e.getMessage(), e );
        } catch ( final InterruptedException ie ) {
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

    private ExtractionBatchRunner.Counters buildCounters() {
        return new ExtractionBatchRunner.Counters() {
            @Override public void setTotalPages( final int n )           { totalPages.set( n ); }
            @Override public void setTotalChunks( final int n )          { totalChunks.set( n ); }
            @Override public void incrementProcessedPages()              { processedPages.incrementAndGet(); }
            @Override public void incrementFailedPages()                 { failedPages.incrementAndGet(); }
            @Override public void addProcessedChunks( final int n )      { processedChunks.addAndGet( n ); }
            @Override public void incrementExcludedSkipped()             { excludedSkipped.incrementAndGet(); }
            @Override public void setConsolidatedCandidates( final int n ) { consolidatedCandidates.set( n ); }
            @Override public void incrementJudgeAccepted()               { judgeAccepted.incrementAndGet(); }
            @Override public void incrementJudgeRejected()               { judgeRejected.incrementAndGet(); }
            @Override public void incrementJudgeRewritten()              { judgeRewritten.incrementAndGet(); }
            @Override public void incrementProposalsInserted()           { proposalsInserted.incrementAndGet(); }
            @Override public void incrementProposalsMerged()             { proposalsMerged.incrementAndGet(); }
            @Override public void mergeRejectionReason( final String r ) { rejectionReasons.merge( r, 1, Integer::sum ); }
            @Override public void setLastError( final String msg )       { lastError.set( msg ); }
            @Override public boolean isDryRun()                          { return dryRun.get(); }
            @Override public boolean isCancelRequested()                 { return cancelRequested.get(); }
            @Override public int maxPages()                              { return maxPages.get(); }
        };
    }

    private void logFinalSummary() {
        final long elapsedMs = finishedNs.get() > 0L
            ? ( finishedNs.get() - startedNs.get() ) / 1_000_000L
            : 0L;
        final int donePages = processedPages.get();
        final long meanPerPageMs = donePages > 0 ? elapsedMs / (long) donePages : 0L;
        LOG.info( "Bootstrap entity extraction {}: processedPages={}/{}, failedPages={}, "
                + "excludedSkipped={}, consolidated={}, accepted={}, rejected={}, rewritten={}, "
                + "inserted={}, merged={}, mentionsWritten={}, totalMs={}, meanPerPageMs={}, "
                + "rejectionReasons={}",
            state.get(), donePages, totalPages.get(), failedPages.get(), excludedSkipped.get(),
            consolidatedCandidates.get(), judgeAccepted.get(), judgeRejected.get(), judgeRewritten.get(),
            proposalsInserted.get(), proposalsMerged.get(), mentionsWritten.get(),
            elapsedMs, meanPerPageMs, Map.copyOf( rejectionReasons ) );
    }

    @Override
    public void close() {
        if ( ownsExecutor ) {
            shutdown( executor, "Bootstrap extraction executor" );
        }
        if ( ownsWorkerPool && workerPool != executor ) {
            shutdown( workerPool, "Bootstrap extraction worker pool" );
        }
    }

    private static void shutdown( final ExecutorService pool, final String label ) {
        pool.shutdown();
        try {
            if ( !pool.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "{} did not drain within 5s; forcing shutdown", label );
                pool.shutdownNow();
            }
        } catch ( final InterruptedException ie ) {
            LOG.info( "{} shutdown wait interrupted — forcing shutdownNow: {}", label, ie.getMessage() );
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
