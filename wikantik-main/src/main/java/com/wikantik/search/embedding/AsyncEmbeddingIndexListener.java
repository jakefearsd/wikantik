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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Listener that re-indexes dense-vector embeddings off the save thread.
 * Registered as the {@code postChunkSink} on {@code ChunkProjector}; for
 * every batch of chunk IDs handed over post-save it submits a task that
 * calls {@link EmbeddingIndexService#indexChunks} on a single-thread
 * executor.
 *
 * <p>Single-threaded so we naturally serialise: a saved page will never
 * race with itself, and two saves to different pages queue instead of
 * hammering the embedding backend with concurrent HTTP calls. Saturation
 * shows up as queue depth on the executor, not as dropped work.</p>
 *
 * <p>Failures are isolated per-task — a broken embedding backend must not
 * cascade into subsequent saves, so exceptions are logged at {@code warn}
 * and swallowed inside the task body.</p>
 */
public class AsyncEmbeddingIndexListener implements Consumer< List< UUID > >, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( AsyncEmbeddingIndexListener.class );

    private final EmbeddingIndexService indexer;
    private final String modelCode;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private volatile Consumer< List< UUID > > postIndexCallback;

    /**
     * Builds a listener backed by a private single-thread executor. Caller
     * must {@link #close()} the listener at shutdown to drain outstanding
     * tasks; a daemon thread prevents the JVM from hanging if close is missed.
     */
    public AsyncEmbeddingIndexListener( final EmbeddingIndexService indexer,
                                        final String modelCode ) {
        this( indexer, modelCode, defaultExecutor(), /*ownsExecutor*/ true );
    }

    /**
     * Test/DI constructor: runs tasks on the given executor without owning
     * its lifecycle. {@link #close()} will leave the caller's executor
     * running.
     */
    public AsyncEmbeddingIndexListener( final EmbeddingIndexService indexer,
                                        final String modelCode,
                                        final ExecutorService executor ) {
        this( indexer, modelCode, executor, /*ownsExecutor*/ false );
    }

    private AsyncEmbeddingIndexListener( final EmbeddingIndexService indexer,
                                         final String modelCode,
                                         final ExecutorService executor,
                                         final boolean ownsExecutor ) {
        if ( indexer == null ) {
            throw new IllegalArgumentException( "indexer must not be null" );
        }
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        if ( executor == null ) {
            throw new IllegalArgumentException( "executor must not be null" );
        }
        this.indexer = indexer;
        this.modelCode = modelCode;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Registers a callback invoked on the executor thread after a successful
     * batch {@code indexChunks} call. The callback receives the list of chunk ids
     * that were just upserted so downstream consumers can apply incremental
     * updates — e.g., {@code InMemoryChunkVectorIndex.upsertChunks(ids)} — instead
     * of triggering a full reload. Pass {@code null} to clear. Exceptions from the
     * callback are logged at warn and swallowed so a broken downstream does not
     * poison subsequent reindex tasks.
     */
    public void setPostIndexCallback( final Consumer< List< UUID > > callback ) {
        this.postIndexCallback = callback;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-embedding-index" );
            t.setDaemon( true );
            return t;
        } );
    }

    @Override
    public void accept( final List< UUID > chunkIds ) {
        if ( chunkIds == null || chunkIds.isEmpty() ) {
            return;
        }
        // Defensive copy — the caller may mutate after return.
        final List< UUID > snapshot = List.copyOf( chunkIds );
        try {
            executor.submit( () -> runIndex( snapshot ) );
        } catch( final RuntimeException reject ) {
            // RejectedExecutionException subclasses RuntimeException; log and
            // continue so a post-shutdown save doesn't blow up the caller.
            LOG.warn( "Rejected embedding reindex of {} chunks: {}",
                snapshot.size(), reject.getMessage() );
        }
    }

    private void runIndex( final List< UUID > chunkIds ) {
        boolean indexed = false;
        try {
            final int upserted = indexer.indexChunks( chunkIds, modelCode );
            indexed = true;
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "Async embedding reindex upserted {} of {} chunks (model={})",
                    upserted, chunkIds.size(), modelCode );
            }
        } catch( final RuntimeException e ) {
            LOG.warn( "Async embedding reindex failed (model={}, chunks={}): {}",
                modelCode, chunkIds.size(), e.getMessage(), e );
        }
        if ( indexed ) {
            final Consumer< List< UUID > > cb = this.postIndexCallback;
            if ( cb != null ) {
                try {
                    cb.accept( chunkIds );
                } catch( final RuntimeException e ) {
                    LOG.warn( "Post-index callback failed (model={}): {}",
                        modelCode, e.getMessage(), e );
                }
            }
        }
    }

    /**
     * Drains the private executor, if this listener owns it. Waits up to
     * 5 seconds before falling back to shutdownNow() so the engine's
     * shutdown path isn't blocked indefinitely by a hung HTTP call.
     */
    @Override
    public void close() {
        if ( !ownsExecutor ) {
            return;
        }
        executor.shutdown();
        try {
            if ( !executor.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "Embedding-index executor did not drain within 5s; forcing shutdown" );
                executor.shutdownNow();
            }
        } catch( final InterruptedException ie ) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
