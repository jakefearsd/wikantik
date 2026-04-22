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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.Chunk;
import com.wikantik.knowledge.chunking.ChunkDiff;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.search.embedding.EmbeddingIndexService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Singleton orchestrator for asynchronously rebuilding the combined Lucene
 * search index and the {@code kg_content_chunks} projection.
 *
 * <p>This class is the state machine plus trigger contract. The actual
 * rebuild body ({@link #runRebuild()}) is a stub in this scaffold and is
 * filled in by a follow-up task. The seam is left {@code protected} so tests
 * can subclass and block, and so the full implementation can simply replace
 * the stub in-place without changing the public contract.</p>
 *
 * <p><b>Concurrency:</b> {@link #triggerRebuild()} is {@code synchronized} and
 * consults an {@link AtomicReference} state guard; only one rebuild can be
 * in flight per JVM. Progress counters are {@code volatile} so
 * {@link #snapshot()} is lock-free.</p>
 */
public class ContentIndexRebuildService {

    private static final Logger LOG = LogManager.getLogger( ContentIndexRebuildService.class );

    /** Lifecycle states for the rebuild orchestrator. */
    public enum State { IDLE, STARTING, RUNNING, DRAINING_LUCENE }

    /** Thrown when a trigger is rejected because a rebuild is already in flight. */
    public static class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IndexStatusSnapshot current;
        public ConflictException( final IndexStatusSnapshot current ) {
            super( "rebuild already in state " + current.rebuild().state() );
            this.current = current;
        }
        public IndexStatusSnapshot current() { return current; }
    }

    /** Thrown when a trigger is rejected by the kill-switch flag. */
    public static class DisabledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DisabledException() { super( "rebuild is disabled by configuration" ); }
    }

    private final PageManager pages;
    private final SystemPageRegistry systemPages;
    private final LuceneReindexQueue lucene;
    private final ContentChunkRepository chunkRepo;
    private final ContentChunker chunker;
    private final BooleanSupplier rebuildEnabled;
    private final long luceneDrainPollMs;

    private final AtomicReference< State > state = new AtomicReference<>( State.IDLE );

    // Progress counters — reset on each trigger. Atomic so snapshot() reads are
    // fresh without taking a lock and so the increments are race-safe even
    // though only the rebuild thread writes in the current design. Protected
    // so subclass tests (Task 10's run-loop body) can mutate directly.
    protected volatile Instant startedAt;
    protected volatile int pagesTotal;
    protected final AtomicInteger pagesIterated = new AtomicInteger();
    protected final AtomicInteger pagesChunked = new AtomicInteger();
    protected final AtomicInteger systemPagesSkipped = new AtomicInteger();
    protected final AtomicInteger luceneQueued = new AtomicInteger();
    protected final AtomicInteger chunksWritten = new AtomicInteger();
    protected final List< IndexStatusSnapshot.RebuildError > errors = new CopyOnWriteArrayList<>();

    // --- embedding hook (Phase 1 of hybrid retrieval) --------------------
    // Optional collaborators: when both are set, the rebuild thread invokes
    // EmbeddingIndexService.indexAll(modelCode) after chunks are rewritten but
    // before DRAINING_LUCENE exits. Left null in tests and in deployments that
    // haven't enabled hybrid retrieval yet — embedding errors are isolated
    // from the rebuild outcome counter.
    private volatile EmbeddingIndexService embeddingIndex;
    private volatile String embeddingModelCode;
    protected volatile int embeddingsIndexed;

    // --- metrics ---------------------------------------------------------
    private final MeterRegistry meterRegistry;
    /** Gauge-backed integer: 0=IDLE, 1=STARTING, 2=RUNNING, 3=DRAINING_LUCENE. */
    private final AtomicInteger stateMetric = new AtomicInteger( 0 );
    private final Timer durationTimer;
    /** Error count at the moment this run transitioned to STARTING, for outcome classification. */
    private volatile int errorsAtRunStart;

    public ContentIndexRebuildService( final PageManager pages,
                                       final SystemPageRegistry systemPages,
                                       final LuceneReindexQueue lucene,
                                       final ContentChunkRepository chunkRepo,
                                       final ContentChunker chunker,
                                       final BooleanSupplier rebuildEnabled,
                                       final long luceneDrainPollMs ) {
        this( pages, systemPages, lucene, chunkRepo, chunker, rebuildEnabled,
            luceneDrainPollMs, new SimpleMeterRegistry() );
    }

    public ContentIndexRebuildService( final PageManager pages,
                                       final SystemPageRegistry systemPages,
                                       final LuceneReindexQueue lucene,
                                       final ContentChunkRepository chunkRepo,
                                       final ContentChunker chunker,
                                       final BooleanSupplier rebuildEnabled,
                                       final long luceneDrainPollMs,
                                       final MeterRegistry meterRegistry ) {
        this.pages = pages;
        this.systemPages = systemPages;
        this.lucene = lucene;
        this.chunkRepo = chunkRepo;
        this.chunker = chunker;
        this.rebuildEnabled = rebuildEnabled;
        this.luceneDrainPollMs = luceneDrainPollMs;
        this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();

        Gauge.builder( "wikantik_rebuild_state", stateMetric, AtomicInteger::get )
            .description( "Rebuild state: 0=IDLE 1=STARTING 2=RUNNING 3=DRAINING_LUCENE" )
            .register( this.meterRegistry );
        Gauge.builder( "wikantik_rebuild_pages_iterated", this,
                ContentIndexRebuildService::getPagesIterated )
            .description( "Pages visited by the current/last rebuild" )
            .register( this.meterRegistry );
        Gauge.builder( "wikantik_rebuild_pages_chunked", this,
                ContentIndexRebuildService::getPagesChunked )
            .description( "Pages chunked by the current/last rebuild" )
            .register( this.meterRegistry );
        Gauge.builder( "wikantik_rebuild_system_pages_skipped", this,
                ContentIndexRebuildService::getSystemPagesSkipped )
            .description( "System pages skipped for chunking in current/last rebuild" )
            .register( this.meterRegistry );
        this.durationTimer = Timer.builder( "wikantik_rebuild_duration_seconds" )
            .description( "End-to-end rebuild duration (STARTING through IDLE)" )
            .register( this.meterRegistry );
    }

    /** Test/production accessor for the registry this service publishes to. */
    public MeterRegistry meterRegistry() { return meterRegistry; }

    /**
     * Wires the optional embedding indexer that runs after chunks are
     * rewritten. Both arguments must be non-null to enable the hook; passing
     * either as null clears the wiring (used by tests). Invoked from
     * engine startup when {@link EmbeddingIndexService} is available.
     *
     * @param service   the indexer that will own the embedding-indexing work
     * @param modelCode the model identifier written into {@code model_code};
     *                  see {@code EmbeddingConfig.PROP_MODEL}
     */
    public void setEmbeddingHook( final EmbeddingIndexService service, final String modelCode ) {
        if ( service == null || modelCode == null || modelCode.isBlank() ) {
            this.embeddingIndex = null;
            this.embeddingModelCode = null;
            return;
        }
        this.embeddingIndex = service;
        this.embeddingModelCode = modelCode;
    }

    // Micrometer gauge callbacks — kept package-private so the registry can
    // reach them without widening the public surface.
    int getPagesIterated() { return pagesIterated.get(); }
    int getPagesChunked() { return pagesChunked.get(); }
    int getSystemPagesSkipped() { return systemPagesSkipped.get(); }

    /**
     * Atomically starts a rebuild if the service is idle and not kill-switched.
     *
     * @return a snapshot taken immediately after the state transition
     * @throws DisabledException if {@code wikantik.rebuild.enabled} is false
     * @throws ConflictException if a rebuild is already in flight
     */
    public synchronized IndexStatusSnapshot triggerRebuild() {
        if ( !rebuildEnabled.getAsBoolean() ) {
            throw new DisabledException();
        }
        if ( state.get() != State.IDLE ) {
            throw new ConflictException( snapshot() );
        }
        state.set( State.STARTING );
        startedAt = Instant.now();
        resetCounters();
        final Thread t = new Thread( this::runRebuild, "wikantik-rebuild" );
        t.setDaemon( true );
        t.start();
        return snapshot();
    }

    /**
     * Returns a read-only snapshot of live counts. Safe to call at any time;
     * does not block the rebuild thread.
     */
    public IndexStatusSnapshot snapshot() {
        final ContentChunkRepository.AggregateStats agg = chunkRepo.stats();

        int systemPageCount = 0;
        final Collection< Page > all = safeGetAllPages();
        final int totalPages = all.size();
        for ( final Page p : all ) {
            if ( systemPages.isSystemPage( p.getName() ) ) {
                systemPageCount++;
            }
        }
        final int indexable = totalPages - systemPageCount;

        return new IndexStatusSnapshot(
            new IndexStatusSnapshot.Pages( totalPages, systemPageCount, indexable ),
            new IndexStatusSnapshot.Lucene(
                lucene.documentCount(),
                lucene.queueDepth(),
                lucene.lastUpdateInstant() ),
            new IndexStatusSnapshot.Chunks(
                agg.pagesWithChunks(),
                Math.max( 0, indexable - agg.pagesWithChunks() ),
                agg.totalChunks(),
                agg.avgTokens(),
                agg.minTokens(),
                agg.maxTokens() ),
            embeddingSnapshot(),
            new IndexStatusSnapshot.Rebuild(
                state.get().name(),
                startedAt,
                pagesTotal,
                pagesIterated.get(),
                pagesChunked.get(),
                systemPagesSkipped.get(),
                luceneQueued.get(),
                chunksWritten.get(),
                List.copyOf( errors ) ) );
    }

    /**
     * Full rebuild loop. Runs on a dedicated daemon thread started by
     * {@link #triggerRebuild()}. Phases:
     * <ol>
     *   <li>{@link State#STARTING} — wipe both indexes. A failure here is
     *       fatal to this run; error is recorded and we return to IDLE.</li>
     *   <li>{@link State#RUNNING} — iterate every page. For non-system pages
     *       compute chunks and diff-apply them; for every page (system or
     *       not) enqueue a Lucene reindex — system pages become no-ops in
     *       Lucene thanks to the preflight filter. Per-page failures are
     *       captured into {@link #errors} and the loop continues.</li>
     *   <li>{@link State#DRAINING_LUCENE} — poll the Lucene queue depth at
     *       {@code luceneDrainPollMs}; exit after two consecutive zero
     *       readings.</li>
     * </ol>
     */
    protected void runRebuild() {
        final long startNanos = System.nanoTime();
        errorsAtRunStart = errors.size();
        try {
            setState( State.STARTING );
            try {
                lucene.clearIndex();
                chunkRepo.deleteAll();
            } catch ( final Exception fatal ) {
                // LOG.error justified: index wipe failed; both indexes in indeterminate state, operator action required.
                LOG.error( "STARTING phase failed: {}", fatal.getMessage(), fatal );
                recordError( "<starting>", fatal );
                return;
            }

            final Collection< ? extends Page > all;
            try {
                all = pages.getAllPages();
            } catch ( final ProviderException e ) {
                // LOG.error justified: page provider unreachable after indexes already cleared; rebuild cannot continue.
                LOG.error( "getAllPages failed: {}", e.getMessage(), e );
                recordError( "<get-all-pages>", e );
                return;
            } catch ( final RuntimeException e ) {
                // LOG.error justified: page provider unreachable after indexes already cleared; rebuild cannot continue.
                LOG.error( "getAllPages failed: {}", e.getMessage(), e );
                recordError( "<get-all-pages>", e );
                return;
            }
            pagesTotal = all.size();
            setState( State.RUNNING );

            for ( final Page page : all ) {
                pagesIterated.incrementAndGet();
                final String name = page.getName();
                final boolean isSystem = systemPages.isSystemPage( name );
                if ( isSystem ) {
                    systemPagesSkipped.incrementAndGet();
                } else {
                    try {
                        final String content = pages.getPureText( page );
                        final ParsedPage parsed = FrontmatterParser.parse(
                            content == null ? "" : content );
                        final List< Chunk > produced = chunker.chunk( name, parsed );
                        final List< ChunkDiff.Stored > existing = chunkRepo.findByPage( name );
                        final ChunkDiff.Diff diff = ChunkDiff.compute( existing, produced );
                        chunkRepo.apply( name, diff );
                        pagesChunked.incrementAndGet();
                        chunksWritten.addAndGet( produced.size() );
                    } catch ( final Exception e ) {
                        LOG.warn( "Chunker failed for page '{}': {}", name, e.getMessage(), e );
                        recordError( name, e );
                    }
                }
                try {
                    lucene.reindexPage( page );
                    luceneQueued.incrementAndGet();
                } catch ( final Exception e ) {
                    LOG.warn( "Lucene enqueue failed for page '{}': {}", name, e.getMessage(), e );
                    recordError( name, e );
                }
            }

            // Post-chunk hook: refresh dense-vector embeddings now that the
            // chunk rows are in their final state. This runs synchronously on
            // the rebuild thread (not on the save path) because it can take
            // several seconds at corpus scale.
            runEmbeddingIndex();

            setState( State.DRAINING_LUCENE );
            drainLucene();
        } catch ( final Exception fatal ) {
            // LOG.error justified: unexpected fatal exception escaped per-page isolation; run aborts with partial state.
            LOG.error( "Rebuild fatal: {}", fatal.getMessage(), fatal );
            recordError( "<rebuild>", fatal );
        } finally {
            durationTimer.record( System.nanoTime() - startNanos, TimeUnit.NANOSECONDS );
            final String outcome = ( errors.size() > errorsAtRunStart ) ? "failed" : "completed";
            Counter.builder( "wikantik_rebuild_runs_total" )
                .description( "Total rebuild runs, tagged by outcome (completed|failed)" )
                .tag( "outcome", outcome )
                .register( meterRegistry )
                .increment();
            setState( State.IDLE );
        }
    }

    /**
     * Builds the embeddings section of the snapshot by querying
     * {@link EmbeddingIndexService#status(String)} when the hook is wired.
     * Snapshot reads are diagnostic — a transient failure degrades the
     * snapshot to empty rather than propagating up the servlet stack.
     */
    private IndexStatusSnapshot.Embeddings embeddingSnapshot() {
        final EmbeddingIndexService svc = embeddingIndex;
        final String model = embeddingModelCode;
        if ( svc == null || model == null ) {
            return IndexStatusSnapshot.Embeddings.empty();
        }
        try {
            final EmbeddingIndexService.Status s = svc.status( model );
            return new IndexStatusSnapshot.Embeddings(
                s.modelCode(), s.dim(), s.rowCount(), s.lastUpdated() );
        } catch( final RuntimeException e ) {
            LOG.warn( "Embedding status lookup failed for model={}: {}",
                model, e.getMessage(), e );
            return new IndexStatusSnapshot.Embeddings( model, 0, 0, null );
        }
    }

    /**
     * Runs a full embedding reindex against the chunks that were just
     * rewritten. Called after the per-page loop completes so chunk IDs are
     * already committed. Errors are recorded in {@link #errors} and logged
     * at {@code warn}, but never thrown — a broken embedding backend must
     * not mark the whole rebuild as failed when Lucene+chunks succeeded.
     */
    private void runEmbeddingIndex() {
        final EmbeddingIndexService svc = embeddingIndex;
        final String model = embeddingModelCode;
        if ( svc == null || model == null ) {
            return;
        }
        try {
            final int indexed = svc.indexAll( model );
            embeddingsIndexed = indexed;
            LOG.info( "Embedding rebuild complete: model={} indexed={}", model, indexed );
        } catch( final RuntimeException e ) {
            LOG.warn( "Embedding rebuild failed for model={}: {}", model, e.getMessage(), e );
            recordError( "<embedding-indexer>", e );
        }
    }

    private void recordError( final String page, final Exception e ) {
        errors.add( new IndexStatusSnapshot.RebuildError(
            page,
            e.getClass().getSimpleName() + ": " + String.valueOf( e.getMessage() ),
            Instant.now() ) );
    }

    /**
     * Polls {@link LuceneReindexQueue#queueDepth()} until it reads {@code 0}
     * on two consecutive polls. Restores the interrupt flag and returns early
     * if interrupted.
     */
    private void drainLucene() {
        int zeroStreak = 0;
        while ( zeroStreak < 2 ) {
            try {
                Thread.sleep( luceneDrainPollMs );
            } catch ( final InterruptedException ie ) {
                Thread.currentThread().interrupt();
                return;
            }
            if ( lucene.queueDepth() == 0 ) {
                zeroStreak++;
            } else {
                zeroStreak = 0;
            }
        }
    }

    /** Test/subclass accessor. Also updates the Prometheus state gauge. */
    protected void setState( final State s ) {
        state.set( s );
        stateMetric.set( s.ordinal() );
    }

    /** Test/subclass accessor. */
    protected State currentState() { return state.get(); }

    /**
     * PageManager.getAllPages declares a checked {@link ProviderException}.
     * Snapshot reads are diagnostic — a failing provider should degrade the
     * snapshot to empty counts rather than propagate up the servlet stack,
     * so we log and return an empty collection instead.
     */
    private Collection< Page > safeGetAllPages() {
        try {
            return pages.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "getAllPages failed while building snapshot: {}", e.getMessage(), e );
            return Collections.emptyList();
        }
    }

    private void resetCounters() {
        pagesTotal = 0;
        pagesIterated.set( 0 );
        pagesChunked.set( 0 );
        systemPagesSkipped.set( 0 );
        luceneQueued.set( 0 );
        chunksWritten.set( 0 );
        embeddingsIndexed = 0;
        errors.clear();
    }
}
