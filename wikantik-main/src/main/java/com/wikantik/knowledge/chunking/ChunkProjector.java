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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.core.Context;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Save-time {@link PageFilter} that drives {@link ContentChunker} and persists
 * the resulting chunks via {@link ContentChunkRepository}. Registered by the
 * engine after {@link com.wikantik.knowledge.GraphProjector} so the graph is
 * updated first, then content chunks are diffed against what's in the database
 * and applied transactionally.
 *
 * <p>Failure-isolated: any exception during chunking or persistence is caught
 * and logged at {@code warn} with context so a chunking bug cannot block page
 * saves. Gated by a {@link BooleanSupplier} kill-switch wired to
 * {@code wikantik.chunker.enabled}.
 *
 * <p>Prometheus metrics registered (via the injected Micrometer registry):
 * <ul>
 *   <li>{@code wikantik_chunker_chunks_produced} — total chunks emitted</li>
 *   <li>{@code wikantik_chunker_duration_seconds} — per-projection timer</li>
 *   <li>{@code wikantik_chunker_failures_total} — failures, tagged by reason
 *       (exception class simple name)</li>
 *   <li>{@code wikantik_chunker_chunk_size_tokens} — per-chunk token-count
 *       distribution</li>
 * </ul>
 */
public class ChunkProjector implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( ChunkProjector.class );

    private final ContentChunker chunker;
    private final ContentChunkRepository repository;
    private final BooleanSupplier enabled;
    private final MeterRegistry meterRegistry;
    private final Counter chunksProduced;
    private final Timer projectionTimer;
    private final DistributionSummary chunkSizeTokens;

    /**
     * Backwards-compatible constructor — wires a no-op in-process Micrometer
     * registry so callers that don't own a shared {@link MeterRegistry} still
     * get working metric increments (observable via
     * {@link #meterRegistry()} for tests).
     */
    public ChunkProjector( final ContentChunker chunker,
                           final ContentChunkRepository repository,
                           final BooleanSupplier enabled ) {
        this( chunker, repository, enabled, new SimpleMeterRegistry() );
    }

    public ChunkProjector( final ContentChunker chunker,
                           final ContentChunkRepository repository,
                           final BooleanSupplier enabled,
                           final MeterRegistry meterRegistry ) {
        this.chunker = chunker;
        this.repository = repository;
        this.enabled = enabled;
        this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
        this.chunksProduced = Counter.builder( "wikantik_chunker_chunks_produced" )
            .description( "Total content chunks produced by the save-time chunker" )
            .register( this.meterRegistry );
        this.projectionTimer = Timer.builder( "wikantik_chunker_duration_seconds" )
            .description( "Duration of a single page chunk-projection run" )
            .register( this.meterRegistry );
        this.chunkSizeTokens = DistributionSummary.builder( "wikantik_chunker_chunk_size_tokens" )
            .description( "Per-chunk estimated token count distribution" )
            .register( this.meterRegistry );
    }

    /** Test accessor for the registry the projector publishes to. */
    public MeterRegistry meterRegistry() { return meterRegistry; }

    /**
     * PageFilter callback — chunks the saved page and persists the diff.
     * Never rethrows: failures are logged and the save is unaffected.
     */
    @Override
    public void postSave( final Context context, final String content ) {
        if( !enabled.getAsBoolean() ) {
            return;
        }
        final String pageName = context.getPage().getName();
        try {
            final ParsedPage parsed = FrontmatterParser.parse( content );
            projectPage( pageName, parsed.metadata(), parsed.body() );
        } catch( final Exception e ) {
            LOG.warn( "Content chunking failed for page '{}' during postSave: {}",
                pageName, e.getMessage(), e );
            recordFailure( e );
        }
    }

    /**
     * Runs the chunker for a page and applies the resulting diff to the
     * repository. Exposed as a public method so callers that already have
     * parsed frontmatter (e.g. the async rebuild service) can drive chunking
     * without re-parsing.
     */
    public void projectPage( final String pageName,
                             final Map< String, Object > frontmatter,
                             final String body ) {
        if( !enabled.getAsBoolean() ) {
            return;
        }
        final long startNanos = System.nanoTime();
        try {
            final ParsedPage pp = new ParsedPage( frontmatter == null ? Map.of() : frontmatter,
                body == null ? "" : body );
            final List< Chunk > produced = chunker.chunk( pageName, pp );
            final List< ChunkDiff.Stored > existing = repository.findByPage( pageName );
            final ChunkDiff.Diff diff = ChunkDiff.compute( existing, produced );
            repository.apply( pageName, diff );
            chunksProduced.increment( produced.size() );
            for ( final Chunk c : produced ) {
                chunkSizeTokens.record( c.tokenCountEstimate() );
            }
            LOG.info( "Chunked '{}' into {} chunks (+{} ~{} -{})",
                pageName, produced.size(),
                diff.inserts().size(), diff.updates().size(), diff.deletes().size() );
        } catch( final Exception e ) {
            LOG.warn( "Content chunking failed for page '{}': {}",
                pageName, e.getMessage(), e );
            recordFailure( e );
        } finally {
            projectionTimer.record( System.nanoTime() - startNanos, TimeUnit.NANOSECONDS );
        }
    }

    private void recordFailure( final Exception e ) {
        Counter.builder( "wikantik_chunker_failures_total" )
            .description( "Total chunker failures, tagged by exception class simple name" )
            .tag( "reason", e.getClass().getSimpleName() )
            .register( meterRegistry )
            .increment();
    }
}
