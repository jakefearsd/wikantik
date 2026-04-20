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
package com.wikantik.search.hybrid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates hybrid retrieval for a single query. Given a BM25-ordered list
 * of page names plus the configured {@link QueryEmbedder}, {@link DenseRetriever},
 * and {@link HybridFuser}, produces a fused page-name ordering that callers can
 * use to reorder (and augment) their BM25 {@code SearchResult} collection.
 *
 * <p>This class is the single choke point for the "fail closed to BM25-only"
 * guarantee — every abnormal path (embedder returning empty, dense index not
 * ready, dimension mismatch) is caught here and collapses to returning the
 * input BM25 list verbatim so the user still gets a working search page.</p>
 *
 * <p>Thread-safe and stateless — construct once at engine boot and reuse across
 * every {@code /api/search} request.</p>
 */
public final class HybridSearchService {

    private static final Logger LOG = LogManager.getLogger( HybridSearchService.class );

    private final QueryEmbedder embedder;
    private final DenseRetriever denseRetriever;
    private final HybridFuser fuser;
    private final boolean enabled;

    public HybridSearchService( final QueryEmbedder embedder,
                                final DenseRetriever denseRetriever,
                                final HybridFuser fuser,
                                final boolean enabled ) {
        if ( embedder == null ) throw new IllegalArgumentException( "embedder must not be null" );
        if ( denseRetriever == null ) throw new IllegalArgumentException( "denseRetriever must not be null" );
        if ( fuser == null ) throw new IllegalArgumentException( "fuser must not be null" );
        this.embedder = embedder;
        this.denseRetriever = denseRetriever;
        this.fuser = fuser;
        this.enabled = enabled;
    }

    /** Whether hybrid retrieval is configured as enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Rerank the {@code bm25PageNames} using a fused BM25 + dense score. When
     * hybrid retrieval is disabled, the embedder fails, or the dense index is
     * not ready, returns {@code bm25PageNames} unchanged. Pages that appeared
     * only in dense results are appended to the end of the fused order, so the
     * output set is always {@code bm25 ∪ dense}.
     *
     * @param query         user query string — never {@code null}
     * @param bm25PageNames ordered page names from the Lucene/BM25 pass; may be empty
     * @return fused ordered page names (superset of input when dense adds hits)
     */
    public List< String > rerank( final String query, final List< String > bm25PageNames ) {
        if ( query == null || query.isBlank() ) {
            return bm25PageNames == null ? List.of() : bm25PageNames;
        }
        final List< String > bm25 = bm25PageNames == null ? List.of() : bm25PageNames;
        if ( !enabled ) {
            return bm25;
        }
        final Optional< float[] > vec;
        try {
            vec = embedder.embed( query );
        } catch( final RuntimeException e ) {
            // QueryEmbedder contracts never-throws, but be defensive: a bug there
            // must not break search. Log and fall back to BM25.
            LOG.warn( "QueryEmbedder.embed threw despite never-throws contract; falling back to BM25: {}",
                e.getMessage(), e );
            return bm25;
        }
        return fuseWithEmbedding( bm25, vec );
    }

    /**
     * Kick off the query embedding asynchronously so the caller can run BM25 on
     * the request thread while the embedding warms up in parallel. The returned
     * future never throws — failures and disabled state collapse to
     * {@code Optional.empty()} so the consumer can simply pair it with
     * {@link #rerankWith(String, List, Optional)}.
     *
     * <p>Pairing pattern at the call site:</p>
     * <pre>{@code
     * CompletableFuture< Optional< float[] > > embed = svc.prefetchQueryEmbedding(q);
     * List< SearchResult > bm25 = searchManager.findPages(q, ctx);
     * Optional< float[] > vec = embed.get(timeout, MS);   // already warm
     * List< String > fused = svc.rerankWith(q, names, vec);
     * }</pre>
     */
    public CompletableFuture< Optional< float[] > > prefetchQueryEmbedding( final String query ) {
        if ( !enabled || query == null || query.isBlank() ) {
            return CompletableFuture.completedFuture( Optional.empty() );
        }
        return CompletableFuture.supplyAsync( () -> {
            try {
                return embedder.embed( query );
            } catch( final RuntimeException e ) {
                LOG.warn( "QueryEmbedder.embed threw despite never-throws contract; falling back to BM25: {}",
                    e.getMessage(), e );
                return Optional.empty();
            }
        } );
    }

    /**
     * Rerank using a pre-computed query embedding (typically obtained from
     * {@link #prefetchQueryEmbedding}). Skips the embedding call entirely so the
     * BM25 path can overlap with the embedding RPC. Same fallback semantics as
     * {@link #rerank}: empty embedding or disabled service returns BM25 verbatim.
     */
    public List< String > rerankWith( final String query,
                                      final List< String > bm25PageNames,
                                      final Optional< float[] > queryEmbedding ) {
        if ( query == null || query.isBlank() ) {
            return bm25PageNames == null ? List.of() : bm25PageNames;
        }
        final List< String > bm25 = bm25PageNames == null ? List.of() : bm25PageNames;
        if ( !enabled ) {
            return bm25;
        }
        return fuseWithEmbedding( bm25, queryEmbedding == null ? Optional.empty() : queryEmbedding );
    }

    private List< String > fuseWithEmbedding( final List< String > bm25, final Optional< float[] > vec ) {
        if ( vec.isEmpty() ) {
            LOG.debug( "Hybrid search: query embedding unavailable; falling back to BM25" );
            return bm25;
        }
        final List< ScoredPage > densePages;
        try {
            densePages = denseRetriever.retrieve( vec.get() );
        } catch( final RuntimeException e ) {
            LOG.warn( "DenseRetriever failed; falling back to BM25: {}", e.getMessage(), e );
            return bm25;
        }
        if ( densePages.isEmpty() ) {
            return bm25;
        }
        final List< String > denseNames = new ArrayList<>( densePages.size() );
        for ( final ScoredPage sp : densePages ) {
            denseNames.add( sp.pageName() );
        }
        final List< String > fused = fuser.fuse( bm25, denseNames );
        // HybridFuser respects the configured truncate window; pages outside
        // the window from both lists will be absent from the fused output.
        // Preserve any BM25 tail that got truncated so users still see those
        // matches below the fused block — reorder-not-remove is the contract.
        final LinkedHashSet< String > out = new LinkedHashSet<>( fused );
        for ( final String name : bm25 ) {
            out.add( name );
        }
        return Collections.unmodifiableList( new ArrayList<>( out ) );
    }
}
