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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
        private final IndexStatusSnapshot current;
        public ConflictException( final IndexStatusSnapshot current ) {
            super( "rebuild already in state " + current.rebuild().state() );
            this.current = current;
        }
        public IndexStatusSnapshot current() { return current; }
    }

    /** Thrown when a trigger is rejected by the kill-switch flag. */
    public static class DisabledException extends RuntimeException {
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

    // Progress counters — reset on each trigger. Volatile so snapshot() reads
    // are fresh without taking a lock. Protected so Task 10's run-loop body
    // (which lands in this same class) can mutate directly.
    protected volatile Instant startedAt;
    protected volatile int pagesTotal;
    protected volatile int pagesIterated;
    protected volatile int pagesChunked;
    protected volatile int systemPagesSkipped;
    protected volatile int luceneQueued;
    protected volatile int chunksWritten;
    protected final List< IndexStatusSnapshot.RebuildError > errors = new CopyOnWriteArrayList<>();

    public ContentIndexRebuildService( final PageManager pages,
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

        int totalPages = 0;
        int systemPageCount = 0;
        final Collection< Page > all = safeGetAllPages();
        totalPages = all.size();
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
            new IndexStatusSnapshot.Rebuild(
                state.get().name(),
                startedAt,
                pagesTotal,
                pagesIterated,
                pagesChunked,
                systemPagesSkipped,
                luceneQueued,
                chunksWritten,
                List.copyOf( errors ) ) );
    }

    /**
     * Test/extension seam: the real rebuild loop lands here in Task 10.
     * In the current scaffold it immediately returns to {@link State#IDLE}.
     */
    protected void runRebuild() {
        // Scaffold: immediately return to IDLE; Task 10 replaces this body.
        state.set( State.IDLE );
    }

    /** Test/subclass accessor. */
    protected void setState( final State s ) { state.set( s ); }

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
        pagesIterated = 0;
        pagesChunked = 0;
        systemPagesSkipped = 0;
        luceneQueued = 0;
        chunksWritten = 0;
        errors.clear();
    }
}
