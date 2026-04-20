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
package com.wikantik.search.embedding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot background bootstrap that fills {@code content_chunk_embeddings}
 * when it is empty for the configured model. Invoked once at engine startup
 * via {@link #startIfNeeded()}; later re-runs require an explicit
 * {@link #forceStart()} (e.g., admin UI button) so we never accidentally
 * double-dispatch the heavy indexAll work.
 *
 * <p>Progress is exposed two ways so the admin surface stays honest even when
 * the executor is opaque:</p>
 * <ul>
 *   <li>The snapshot returned by {@link #progress()} — state machine + totals +
 *       timestamps + last error.</li>
 *   <li>The {@code rowCount} already surfaced by
 *       {@link EmbeddingIndexService#status(String)} — incrementing live as the
 *       run upserts rows. The admin payload joins both so the UI can show a
 *       proper "X of Y chunks" progress bar.</li>
 * </ul>
 *
 * <p>Lifecycle guarantees:</p>
 * <ul>
 *   <li>Construction is cheap — no DB I/O, no threads spawned.</li>
 *   <li>{@link #startIfNeeded()} is idempotent: once the bootstrap has begun or
 *       completed, subsequent calls are no-ops.</li>
 *   <li>{@link #close()} shuts down the internal executor so engine shutdown
 *       does not block.</li>
 * </ul>
 */
public final class BootstrapEmbeddingIndexer implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( BootstrapEmbeddingIndexer.class );

    private static final String COUNT_CHUNKS_SQL = "SELECT COUNT(*) FROM kg_content_chunks";

    /** Lifecycle of a bootstrap run. */
    public enum State {
        /** No run has started yet. */
        IDLE,
        /** Bootstrap determined the table was already populated — nothing to do. */
        SKIPPED_ALREADY_POPULATED,
        /** There are no chunks in the database, so nothing to embed. */
        SKIPPED_NO_CHUNKS,
        /** A run is currently executing on the background executor. */
        RUNNING,
        /** A run finished successfully. */
        COMPLETED,
        /** A run ended in failure; see {@link Progress#errorMessage()}. */
        FAILED
    }

    /**
     * Immutable progress snapshot. {@code chunksProcessed} is deliberately not
     * tracked here — callers should read the live count from
     * {@link EmbeddingIndexService#status(String)} to avoid lying during a
     * long-running batch.
     *
     * @param state          current lifecycle state
     * @param chunksTotal    count of chunk rows observed when the run started,
     *                       or {@code 0} before any run has begun
     * @param startedAt      wall-clock time the run began, or {@code null}
     *                       before a run begins
     * @param completedAt    wall-clock time the run ended (success OR failure),
     *                       or {@code null} while still running or before start
     * @param errorMessage   short summary of the failure when {@code state ==
     *                       FAILED}, otherwise {@code null}
     */
    public record Progress(
        State state,
        long chunksTotal,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
    ) {
        public static Progress idle() {
            return new Progress( State.IDLE, 0L, null, null, null );
        }
    }

    private final DataSource dataSource;
    private final EmbeddingIndexService indexService;
    private final String modelCode;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final Runnable postRunCallback;

    private final AtomicReference< Progress > progress = new AtomicReference<>( Progress.idle() );

    /**
     * @param dataSource      used only to count chunks for the initial denominator
     * @param indexService    delegated to for the actual batch embedding run
     * @param modelCode       which model row to check for existing embeddings
     * @param postRunCallback invoked on the executor after a successful run —
     *                        typically {@code vectorIndex::reload}. Pass
     *                        {@code null} to skip. Exceptions from the callback
     *                        are swallowed (logged at warn).
     */
    public BootstrapEmbeddingIndexer( final DataSource dataSource,
                                      final EmbeddingIndexService indexService,
                                      final String modelCode,
                                      final Runnable postRunCallback ) {
        this( dataSource, indexService, modelCode, postRunCallback,
            defaultExecutor(), /*ownsExecutor*/ true );
    }

    /** DI-friendly constructor used by tests to supply a deterministic executor. */
    public BootstrapEmbeddingIndexer( final DataSource dataSource,
                                      final EmbeddingIndexService indexService,
                                      final String modelCode,
                                      final Runnable postRunCallback,
                                      final ExecutorService executor ) {
        this( dataSource, indexService, modelCode, postRunCallback, executor, /*ownsExecutor*/ false );
    }

    private BootstrapEmbeddingIndexer( final DataSource dataSource,
                                       final EmbeddingIndexService indexService,
                                       final String modelCode,
                                       final Runnable postRunCallback,
                                       final ExecutorService executor,
                                       final boolean ownsExecutor ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if ( indexService == null ) throw new IllegalArgumentException( "indexService must not be null" );
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        if ( executor == null ) throw new IllegalArgumentException( "executor must not be null" );
        this.dataSource = dataSource;
        this.indexService = indexService;
        this.modelCode = modelCode;
        this.postRunCallback = postRunCallback;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** The model this bootstrap targets. */
    public String modelCode() {
        return modelCode;
    }

    /** Latest lifecycle snapshot; never {@code null}. */
    public Progress progress() {
        return progress.get();
    }

    /**
     * Starts the background run if and only if the embeddings table currently
     * has no rows for {@code modelCode}. Idempotent — after the first call the
     * progress latches into a non-IDLE state and repeat calls are no-ops.
     */
    public synchronized void startIfNeeded() {
        final Progress current = progress.get();
        if ( current.state() != State.IDLE ) {
            return;
        }
        final int existing;
        try {
            existing = indexService.status( modelCode ).rowCount();
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap indexer: failed to read embedding status for model={}: {}",
                modelCode, e.getMessage(), e );
            progress.set( new Progress( State.FAILED, 0L, Instant.now(), Instant.now(),
                "status query failed: " + e.getMessage() ) );
            return;
        }
        if ( existing > 0 ) {
            LOG.info( "Bootstrap indexer: model={} already has {} rows; skipping full reindex",
                modelCode, existing );
            progress.set( new Progress( State.SKIPPED_ALREADY_POPULATED, existing,
                Instant.now(), Instant.now(), null ) );
            return;
        }
        final long chunkCount = countChunks();
        if ( chunkCount == 0L ) {
            LOG.info( "Bootstrap indexer: no chunks to embed; model={}", modelCode );
            progress.set( new Progress( State.SKIPPED_NO_CHUNKS, 0L,
                Instant.now(), Instant.now(), null ) );
            return;
        }
        submit( chunkCount );
    }

    /**
     * Force-starts a run regardless of current progress state. Intended for an
     * admin "reindex embeddings" action. Throws {@link IllegalStateException}
     * if a run is already in flight — the caller can surface this as a 409 at
     * the REST layer.
     *
     * <p>Force-reindex first deletes the model's existing rows so the live row
     * count starts from zero and the admin progress bar accurately tracks
     * 0 → N as new embeddings stream in. Without this, {@link EmbeddingIndexService#indexAll}
     * upserts in place and the bar stays pinned at 100% — useless during a
     * substantial chunking-driven re-embed. Hybrid retrieval falls back to
     * BM25 for the duration of the rebuild, which is the documented contract.</p>
     */
    public synchronized void forceStart() {
        final Progress current = progress.get();
        if ( current.state() == State.RUNNING ) {
            throw new IllegalStateException( "Bootstrap indexer already running" );
        }
        final long chunkCount = countChunks();
        if ( chunkCount == 0L ) {
            LOG.info( "Bootstrap indexer forceStart: no chunks to embed; model={}", modelCode );
            progress.set( new Progress( State.SKIPPED_NO_CHUNKS, 0L,
                Instant.now(), Instant.now(), null ) );
            return;
        }
        try {
            final int removed = indexService.deleteByModel( modelCode );
            LOG.info( "Bootstrap indexer forceStart: cleared {} existing rows for model={}", removed, modelCode );
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap indexer forceStart: deleteByModel failed (model={}): {}",
                modelCode, e.getMessage(), e );
            progress.set( new Progress( State.FAILED, chunkCount, Instant.now(), Instant.now(),
                "deleteByModel failed: " + e.getMessage() ) );
            return;
        }
        submit( chunkCount );
    }

    @Override
    public void close() {
        if ( !ownsExecutor ) {
            return;
        }
        executor.shutdown();
        try {
            if ( !executor.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "BootstrapEmbeddingIndexer executor did not drain within 5s; forcing shutdown" );
                executor.shutdownNow();
            }
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    // ---- internals ----

    private void submit( final long chunkCount ) {
        final Instant startedAt = Instant.now();
        progress.set( new Progress( State.RUNNING, chunkCount, startedAt, null, null ) );
        LOG.info( "Bootstrap indexer starting: model={} chunksTotal={}", modelCode, chunkCount );
        try {
            executor.submit( () -> runIndex( chunkCount, startedAt ) );
        } catch( final RuntimeException reject ) {
            // RejectedExecutionException: executor is shutting down — land in FAILED so admin sees it.
            LOG.warn( "Bootstrap indexer rejected by executor (model={}): {}",
                modelCode, reject.getMessage() );
            progress.set( new Progress( State.FAILED, chunkCount, startedAt, Instant.now(),
                "executor rejected task: " + reject.getMessage() ) );
        }
    }

    private void runIndex( final long chunkCount, final Instant startedAt ) {
        final long t0 = System.nanoTime();
        try {
            final int upserted = indexService.indexAll( modelCode );
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 );
            LOG.info( "Bootstrap indexer COMPLETED: model={} upserted={} of chunksTotal={} elapsedMs={}",
                modelCode, upserted, chunkCount, elapsedMs );
            progress.set( new Progress( State.COMPLETED, chunkCount, startedAt, Instant.now(), null ) );
            invokePostRun();
        } catch( final RuntimeException e ) {
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - t0 );
            // LOG.error justified: terminal state of a one-shot bootstrap that cannot self-recover; hybrid retrieval stays empty until an operator intervenes.
            LOG.error( "Bootstrap indexer FAILED: model={} chunksTotal={} elapsedMs={}: {}",
                modelCode, chunkCount, elapsedMs, e.getMessage(), e );
            progress.set( new Progress( State.FAILED, chunkCount, startedAt, Instant.now(),
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage() ) );
        }
    }

    private void invokePostRun() {
        if ( postRunCallback == null ) return;
        try {
            postRunCallback.run();
        } catch( final RuntimeException e ) {
            LOG.warn( "Bootstrap indexer post-run callback failed (model={}): {}",
                modelCode, e.getMessage(), e );
        }
    }

    private long countChunks() {
        try( final Connection c = dataSource.getConnection();
             final PreparedStatement ps = c.prepareStatement( COUNT_CHUNKS_SQL );
             final ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getLong( 1 ) : 0L;
        } catch( final SQLException e ) {
            LOG.warn( "Bootstrap indexer: count-chunks query failed: {}", e.getMessage(), e );
            return 0L;
        }
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-embedding-bootstrap" );
            t.setDaemon( true );
            return t;
        } );
    }
}
