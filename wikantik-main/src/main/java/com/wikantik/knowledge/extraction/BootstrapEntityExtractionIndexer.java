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
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import com.wikantik.api.knowledge.PageExtractor;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingService;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * Admin-triggered batch that drives the per-page extraction pipeline:
 *
 * <ol>
 *   <li>Warm the {@code kg_node_embeddings} cache via
 *       {@link KgNodeEmbeddingService#warmUp}.</li>
 *   <li>Walk every distinct page name in {@code kg_content_chunks}.</li>
 *   <li>For each page (concurrent across a worker pool): stitch the body from
 *       its chunks, look up a top-K dictionary of existing nodes, and call
 *       {@link PageExtractor#extract} once per page.</li>
 *   <li>Consolidate per-page results into one
 *       {@link com.wikantik.api.knowledge.ConsolidatedProposal} per logical
 *       claim, run each through {@link ProposalJudge}, and upsert
 *       {@code Accept}/{@code Rewrite} verdicts via
 *       {@link ProposalUpserter}.</li>
 *   <li>For every accepted name that already maps to a {@code kg_nodes} row,
 *       run {@link MentionAttributor} over each chunk and bulk-upsert
 *       {@code chunk_entity_mentions}. Names without a node yet (most on a
 *       first run) are skipped — mentions light up after admin approval
 *       creates the node.</li>
 * </ol>
 *
 * <p>Save-time extraction (the {@link AsyncEntityExtractionListener} chain)
 * is unrelated and unaffected by this batch — the two pipelines are wired
 * separately in {@code WikiEngine}.</p>
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

    private final PageExtractor pageExtractor;
    private final ProposalJudge judge;
    private final ProposalConsolidator consolidator;
    private final ProposalUpserter upserter;
    private final KgNodeEmbeddingService embeddingService;
    private final KgNodeEmbeddingRepository embeddingRepo;
    private final ContentChunkRepository chunkRepo;
    private final ChunkEntityMentionRepository mentionRepo;
    private final JdbcKnowledgeRepository kgRepo;
    private final MentionAttributor mentionAttributor;
    private final PageEmbeddingProvider pageEmbeddings;
    private final KgExcludedPagesRepository excludedPages;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final ExecutorService workerPool;
    private final boolean ownsWorkerPool;
    private final int concurrency;
    private final int dictionaryTopK;
    private final int maxEntitiesPerPage;
    private final int maxRelationsPerPage;

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
                                             final JdbcKnowledgeRepository kgRepo,
                                             final MentionAttributor mentionAttributor,
                                             final PageEmbeddingProvider pageEmbeddings,
                                             final KgExcludedPagesRepository excludedPages,
                                             final int concurrency,
                                             final int dictionaryTopK,
                                             final int maxEntitiesPerPage,
                                             final int maxRelationsPerPage ) {
        this( pageExtractor, judge, consolidator, upserter, embeddingService, embeddingRepo,
              chunkRepo, mentionRepo, kgRepo, mentionAttributor, pageEmbeddings, excludedPages,
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
                                             final JdbcKnowledgeRepository kgRepo,
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
              chunkRepo, mentionRepo, kgRepo, mentionAttributor, pageEmbeddings, excludedPages,
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
                                              final JdbcKnowledgeRepository kgRepo,
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
        if( pageExtractor == null ) throw new IllegalArgumentException( "pageExtractor must not be null" );
        if( judge == null ) throw new IllegalArgumentException( "judge must not be null" );
        if( consolidator == null ) throw new IllegalArgumentException( "consolidator must not be null" );
        if( upserter == null ) throw new IllegalArgumentException( "upserter must not be null" );
        if( chunkRepo == null ) throw new IllegalArgumentException( "chunkRepo must not be null" );
        if( mentionRepo == null ) throw new IllegalArgumentException( "mentionRepo must not be null" );
        if( kgRepo == null ) throw new IllegalArgumentException( "kgRepo must not be null" );
        if( mentionAttributor == null ) throw new IllegalArgumentException( "mentionAttributor must not be null" );
        if( executor == null ) throw new IllegalArgumentException( "executor must not be null" );
        if( workerPool == null ) throw new IllegalArgumentException( "workerPool must not be null" );
        this.pageExtractor = pageExtractor;
        this.judge = judge;
        this.consolidator = consolidator;
        this.upserter = upserter;
        this.embeddingService = embeddingService;
        this.embeddingRepo = embeddingRepo;
        this.chunkRepo = chunkRepo;
        this.mentionRepo = mentionRepo;
        this.kgRepo = kgRepo;
        this.mentionAttributor = mentionAttributor;
        this.pageEmbeddings = pageEmbeddings == null ? PageEmbeddingProvider.EMPTY : pageEmbeddings;
        this.excludedPages = excludedPages;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.workerPool = workerPool;
        this.ownsWorkerPool = ownsWorkerPool;
        this.concurrency = concurrency;
        this.dictionaryTopK = Math.max( 0, dictionaryTopK );
        this.maxEntitiesPerPage = maxEntitiesPerPage;
        this.maxRelationsPerPage = maxRelationsPerPage;
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
     *
     * @param forceOverwrite when {@code true}, clear {@code chunk_entity_mentions}
     *                       for every chunk on the processed pages before
     *                       re-attributing. Stored on {@link Status} for the
     *                       admin REST surface; the new pipeline's mention
     *                       attribution is idempotent regardless.
     */
    public boolean start( final boolean forceOverwrite ) {
        return start( forceOverwrite, /*maxPages*/ 0 );
    }

    /**
     * Variant that caps the run at the first {@code maxPages} page-names
     * (alphabetical — same order as {@code listDistinctPageNames()}). Useful
     * for smoke runs that exercise the whole pipeline without committing to a
     * multi-hour full pass.
     */
    public boolean start( final boolean forceOverwrite, final int maxPages ) {
        if( !running.compareAndSet( false, true ) ) {
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
            // LOG.error justified: admin-triggered batch terminated before completion; the corpus is partially extracted and operator action is required to resume or investigate.
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
        // Step 1 — warm node embeddings (best-effort).
        warmNodeEmbeddings();

        // Step 2 — list pages, apply --max-pages cap.
        List< String > pages = chunkRepo.listDistinctPageNames();
        final int cap = maxPages.get();
        if( cap > 0 && cap < pages.size() ) {
            pages = pages.subList( 0, cap );
            LOG.info( "Bootstrap extraction: --max-pages={} cap applied (full corpus has {} pages)",
                cap, chunkRepo.listDistinctPageNames().size() );
        }
        totalPages.set( pages.size() );
        try {
            totalChunks.set( chunkRepo.stats().totalChunks() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: failed to precompute total chunk count: {}", e.getMessage() );
        }
        LOG.info( "Bootstrap entity extraction starting: pages={}, chunks={}, forceOverwrite={}, dryRun={}",
            pages.size(), totalChunks.get(), overwrite, dryRun.get() );

        // Step 3 — extract per page, in parallel up to `concurrency`.
        final List< Future< PageOutcome > > futures = new ArrayList<>( pages.size() );
        boolean cancelledMidRun = false;
        for( final String page : pages ) {
            if( Thread.currentThread().isInterrupted() ) {
                throw new InterruptedException( "bootstrap extraction interrupted before page '" + page + "'" );
            }
            if( cancelRequested.get() ) {
                LOG.info( "Bootstrap extraction: cancellation acknowledged before submitting more pages" );
                cancelledMidRun = true;
                break;
            }
            if( excludedPages != null && excludedPages.findReason( page ).isPresent() ) {
                LOG.debug( "Bootstrap extraction: skip excluded page '{}'", page );
                excludedSkipped.incrementAndGet();
                continue;
            }
            futures.add( workerPool.submit( () -> extractOnePage( page ) ) );
        }

        final List< PageOutcome > outcomes = new ArrayList<>( futures.size() );
        for( final Future< PageOutcome > f : futures ) {
            try {
                outcomes.add( f.get() );
            } catch( final InterruptedException ie ) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch( final ExecutionException ee ) {
                final Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                lastError.set( "extract task failed: " + cause.getMessage() );
                LOG.warn( "Bootstrap extraction: extract task threw: {}", cause.getMessage() );
                failedPages.incrementAndGet();
            }
        }

        // Steps 4-5 — consolidate then judge.
        final List< ConsolidatedProposal > consolidated = consolidator.consolidate(
            outcomes.stream().map( PageOutcome::result ) );
        consolidatedCandidates.set( consolidated.size() );
        LOG.info( "Bootstrap extraction: consolidated {} candidates from {} page results",
            consolidated.size(), outcomes.size() );

        final JudgeContext judgeCtx = new JudgeContext( Map.of(), List.of() );
        final List< ConsolidatedProposal > accepted = new ArrayList<>();
        for( final ConsolidatedProposal cp : consolidated ) {
            if( cancelRequested.get() ) {
                cancelledMidRun = true;
                break;
            }
            final Verdict v;
            try {
                v = judge.judge( cp, judgeCtx );
            } catch( final RuntimeException e ) {
                LOG.warn( "Judge '{}' threw on signature {}: {} — accepting fail-open",
                    judge.code(), cp.signature(), e.getMessage() );
                accepted.add( cp );
                judgeAccepted.incrementAndGet();
                continue;
            }
            if( v instanceof Verdict.Accept a ) {
                judgeAccepted.incrementAndGet();
                accepted.add( cp );
            } else if( v instanceof Verdict.Rewrite rw ) {
                judgeRewritten.incrementAndGet();
                accepted.add( rw.rewritten() );
            } else if( v instanceof Verdict.Reject r ) {
                judgeRejected.incrementAndGet();
                rejectionReasons.merge( r.reasonCode(), 1, Integer::sum );
            }
        }

        // Step 6 — upsert accepted proposals (skipped on dry-run).
        if( !dryRun.get() ) {
            for( final ConsolidatedProposal cp : accepted ) {
                if( cancelRequested.get() ) {
                    cancelledMidRun = true;
                    break;
                }
                try {
                    final ProposalUpserter.Result r = upserter.upsert( cp );
                    if( r.inserted() ) {
                        proposalsInserted.incrementAndGet();
                    } else {
                        proposalsMerged.incrementAndGet();
                    }
                } catch( final RuntimeException e ) {
                    lastError.set( "upsert failed for " + cp.signature() + ": " + e.getMessage() );
                    LOG.warn( "Bootstrap extraction: upsert failed for {}: {}", cp.signature(), e.getMessage() );
                }
            }
        } else {
            LOG.info( "Bootstrap extraction: dry-run — skipping upsert of {} accepted proposals", accepted.size() );
        }

        // Step 7 — mention attribution for accepted entity names that already
        // resolve to a kg_nodes row. First-run runs typically attribute zero;
        // mentions light up after admin approval creates real nodes.
        attributeMentions( outcomes, accepted, overwrite );

        if( cancelledMidRun ) {
            LOG.info( "Bootstrap extraction: cancellation acknowledged after {} pages", processedPages.get() );
        }
    }

    private void warmNodeEmbeddings() {
        if( embeddingService == null ) return;
        try {
            final List< KgNode > all = kgRepo.getAllNodes();
            final KgNodeEmbeddingService.Result r = embeddingService.warmUp( all );
            LOG.info( "Bootstrap extraction: node embedding warmup cached={} reEmbedded={} errors={}",
                r.cached(), r.reEmbedded(), r.errors() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: node embedding warmup failed: {}", e.getMessage() );
        }
    }

    private PageOutcome extractOnePage( final String pageName ) {
        final long startNs = System.nanoTime();
        final List< UUID > chunkIds;
        try {
            chunkIds = chunkRepo.listChunkIdsForPage( pageName );
        } catch( final RuntimeException e ) {
            failedPages.incrementAndGet();
            LOG.warn( "Bootstrap extraction: failed to list chunks for page '{}': {}",
                pageName, e.getMessage() );
            return new PageOutcome( pageName, List.of(), emptyResult( pageName ) );
        }
        if( chunkIds.isEmpty() ) {
            processedPages.incrementAndGet();
            return new PageOutcome( pageName, List.of(), emptyResult( pageName ) );
        }

        final List< ContentChunkRepository.MentionableChunk > chunks;
        try {
            chunks = chunkRepo.findByIds( chunkIds );
        } catch( final RuntimeException e ) {
            failedPages.incrementAndGet();
            LOG.warn( "Bootstrap extraction: failed to hydrate chunks for page '{}': {}",
                pageName, e.getMessage() );
            return new PageOutcome( pageName, List.of(), emptyResult( pageName ) );
        }

        final String body = stitchBody( chunks );
        final Page page = new Page( pageName, /*pageId*/ null, body, /*summary*/ "", List.of() );

        // Step 3b/3c — page-mean embedding → top-K dictionary. Best-effort:
        // empty dictionary still produces correct extractions, just less
        // anchored to existing nodes.
        List< KgNode > dictionary = List.of();
        try {
            final Optional< float[] > mean = pageEmbeddings.meanFor( pageName );
            if( mean.isPresent() && embeddingRepo != null && embeddingService != null
                                 && dictionaryTopK > 0 ) {
                dictionary = embeddingRepo.findTopKByPageEmbedding(
                    mean.get(), dictionaryTopK, embeddingService.modelTag() );
            }
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: dictionary lookup failed for page '{}': {}",
                pageName, e.getMessage() );
        }

        final ExtractionContext ctx = new ExtractionContext( pageName, dictionary, Map.of() );
        final PageExtractionResult result;
        try {
            result = pageExtractor.extract( page, ctx );
        } catch( final RuntimeException e ) {
            failedPages.incrementAndGet();
            lastError.set( "page=" + pageName + ": " + e.getMessage() );
            LOG.warn( "Bootstrap extraction: extractor threw on page '{}': {}", pageName, e.getMessage() );
            return new PageOutcome( pageName, chunks, emptyResult( pageName ) );
        }

        processedPages.incrementAndGet();
        processedChunks.addAndGet( chunks.size() );
        final long elapsedMs = ( System.nanoTime() - startNs ) / 1_000_000L;
        LOG.info( "Bootstrap extraction: page='{}' chunks={} entities={} relations={} elapsedMs={}",
            pageName, chunks.size(), result.entities().size(), result.relations().size(), elapsedMs );
        return new PageOutcome( pageName, chunks, result );
    }

    private static String stitchBody( final List< ContentChunkRepository.MentionableChunk > chunks ) {
        // findByIds doesn't guarantee ordering; sort by chunk_index defensively.
        final List< ContentChunkRepository.MentionableChunk > ordered = new ArrayList<>( chunks );
        ordered.sort( ( a, b ) -> Integer.compare( a.chunkIndex(), b.chunkIndex() ) );
        final StringBuilder sb = new StringBuilder();
        for( final ContentChunkRepository.MentionableChunk c : ordered ) {
            if( sb.length() > 0 ) sb.append( "\n\n" );
            sb.append( c.text() );
        }
        return sb.toString();
    }

    private PageExtractionResult emptyResult( final String pageName ) {
        return PageExtractionResult.empty( pageExtractor.code(), pageName, java.time.Duration.ZERO );
    }

    private void attributeMentions( final List< PageOutcome > outcomes,
                                     final List< ConsolidatedProposal > accepted,
                                     final boolean overwrite ) {
        if( accepted.isEmpty() || outcomes.isEmpty() ) {
            return;
        }
        // Build (name -> nodeId) from accepted node-kind proposals that already
        // resolve to a kg_nodes row. Names without a node are silently skipped.
        final Map< String, UUID > nameToNodeId = new LinkedHashMap<>();
        for( final ConsolidatedProposal cp : accepted ) {
            if( cp.kind() != ConsolidatedProposal.Kind.NEW_NODE ) continue;
            final String name = cp.displayName();
            if( name == null || nameToNodeId.containsKey( name ) ) continue;
            try {
                final KgNode existing = kgRepo.getNodeByName( name );
                if( existing != null ) {
                    nameToNodeId.put( name, existing.id() );
                }
            } catch( final RuntimeException e ) {
                LOG.debug( "getNodeByName failed for '{}': {}", name, e.getMessage() );
            }
        }
        if( nameToNodeId.isEmpty() ) {
            LOG.info( "Bootstrap extraction: no accepted entity names map to existing kg_nodes — "
                + "mention attribution skipped (admin approval will materialize nodes)" );
            return;
        }

        final List< MentionAttributor.NameMapping > mappings = new ArrayList<>( nameToNodeId.size() );
        for( final Map.Entry< String, UUID > e : nameToNodeId.entrySet() ) {
            mappings.add( new MentionAttributor.NameMapping( e.getValue(), e.getKey() ) );
        }

        final List< ChunkEntityMentionRepository.Row > rows = new ArrayList<>();
        for( final PageOutcome po : outcomes ) {
            for( final ContentChunkRepository.MentionableChunk c : po.chunks() ) {
                if( overwrite ) {
                    try {
                        mentionRepo.deleteByChunkId( c.id() );
                    } catch( final RuntimeException e ) {
                        LOG.warn( "Bootstrap extraction: clear mentions failed for chunk {}: {}",
                            c.id(), e.getMessage() );
                    }
                }
                final List< MentionAttributor.ChunkMention > attributed =
                    mentionAttributor.attribute( c.id(), c.text(), mappings );
                for( final MentionAttributor.ChunkMention m : attributed ) {
                    rows.add( new ChunkEntityMentionRepository.Row(
                        m.chunkId(), m.nodeId(), /*confidence*/ 1.0, pageExtractor.code() ) );
                }
            }
        }

        if( rows.isEmpty() ) {
            return;
        }
        try {
            final int written = mentionRepo.upsertAll( rows );
            mentionsWritten.addAndGet( written );
            LOG.info( "Bootstrap extraction: attributed {} mention rows across {} pages",
                written, outcomes.size() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: mention upsert failed ({}): {}",
                rows.size(), e.getMessage() );
        }
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
            LOG.info( "{} shutdown wait interrupted — forcing shutdownNow: {}", label, ie.getMessage() );
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /** Per-page bundle: the chunks fetched + the extractor's result. */
    private record PageOutcome(
        String pageName,
        List< ContentChunkRepository.MentionableChunk > chunks,
        PageExtractionResult result
    ) {}
}
