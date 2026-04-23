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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3 graph-aware rerank: applies a per-page proximity boost on top of the
 * hybrid BM25+dense fused ordering. The step is purely reordering — it never
 * adds or removes pages, so the contract with downstream consumers of
 * {@link HybridSearchService#rerankWith} is preserved.
 *
 * <p>Each fused candidate gets a base score derived from its 0-based rank
 * ({@code 1.0 - rank / N}, so rank 0 → 1.0 and rank N-1 → 1/N) plus
 * {@code boost * proximity} where {@code proximity ∈ [0,1]} is returned by
 * {@link GraphProximityScorer}. Pages with no proximity signal keep exactly
 * the base score — i.e. their relative order is preserved. Stable sort means
 * tied scores retain their prior ordering, so a zero-match fused list comes
 * out of the rerank step bit-identical to its input.</p>
 *
 * <p>The step fails closed to the identity order on any of: boost == 0, index
 * not ready, no query entities resolved, no mentions loaded for the candidate
 * set. That set of conditions matches the "graceful degradation" pattern of
 * the existing hybrid path — a flaky graph store must never break search.</p>
 */
public final class GraphRerankStep {

    private static final Logger LOG = LogManager.getLogger( GraphRerankStep.class );

    private final QueryEntityResolver resolver;
    private final PageMentionsLoader mentionsLoader;
    private final GraphProximityScorer scorer;
    private final GraphNeighborIndex neighborIndex;
    private final GraphRerankConfig config;

    public GraphRerankStep( final QueryEntityResolver resolver,
                            final PageMentionsLoader mentionsLoader,
                            final GraphProximityScorer scorer,
                            final GraphNeighborIndex neighborIndex,
                            final GraphRerankConfig config ) {
        if( resolver == null ) throw new IllegalArgumentException( "resolver must not be null" );
        if( mentionsLoader == null ) throw new IllegalArgumentException( "mentionsLoader must not be null" );
        if( scorer == null ) throw new IllegalArgumentException( "scorer must not be null" );
        if( neighborIndex == null ) throw new IllegalArgumentException( "neighborIndex must not be null" );
        if( config == null ) throw new IllegalArgumentException( "config must not be null" );
        this.resolver = resolver;
        this.mentionsLoader = mentionsLoader;
        this.scorer = scorer;
        this.neighborIndex = neighborIndex;
        this.config = config;
    }

    /**
     * Reorder {@code fusedPageNames} with the graph-proximity boost applied.
     * Returns the input list verbatim whenever the graph signal is absent or
     * the feature is disabled. Never throws.
     */
    public List< String > rerank( final String query, final List< String > fusedPageNames ) {
        if( fusedPageNames == null || fusedPageNames.isEmpty() ) {
            return fusedPageNames == null ? List.of() : fusedPageNames;
        }
        if( !config.enabled() ) return fusedPageNames;
        if( !neighborIndex.isReady() ) return fusedPageNames;
        if( query == null || query.isBlank() ) return fusedPageNames;

        final Set< UUID > queryEntities;
        try {
            queryEntities = resolver.resolve( query );
        } catch( final RuntimeException e ) {
            LOG.warn( "Graph rerank: query entity resolution failed; falling back to fused order: {}",
                e.getMessage(), e );
            return fusedPageNames;
        }
        if( queryEntities.isEmpty() ) return fusedPageNames;

        final Map< String, Set< UUID > > pageMentions;
        try {
            pageMentions = mentionsLoader.loadFor( fusedPageNames );
        } catch( final RuntimeException e ) {
            LOG.warn( "Graph rerank: mentions load failed; falling back to fused order: {}",
                e.getMessage(), e );
            return fusedPageNames;
        }
        if( pageMentions.isEmpty() ) return fusedPageNames;

        final Map< String, Double > proximity = scorer.score( queryEntities, pageMentions, config.maxHops() );
        if( proximity.isEmpty() ) return fusedPageNames;

        return applyBoost( fusedPageNames, proximity, config.boost() );
    }

    /**
     * Sort stability guarantee: when {@code proximity} is empty for every
     * candidate the base scores come out in strictly decreasing order so the
     * output equals the input. This is load-bearing for the Phase 3
     * regression test and for the {@code boost=0.2 but no graph signal} runtime
     * case — it means graph rerank never warps search in a graph-free deploy.
     */
    private static List< String > applyBoost( final List< String > fused,
                                              final Map< String, Double > proximity,
                                              final double boost ) {
        final int n = fused.size();
        final List< Scored > scored = new ArrayList<>( n );
        for( int r = 0; r < n; r++ ) {
            final String name = fused.get( r );
            final double base = 1.0 - ( (double) r ) / ( (double) n );
            final double p = proximity.getOrDefault( name, 0.0 );
            scored.add( new Scored( name, r, base + boost * p ) );
        }
        // Stable sort by score descending; ties break on original rank ascending,
        // so zero-boost inputs come out identical to the fused order.
        scored.sort( Comparator.< Scored >comparingDouble( s -> -s.score() )
                .thenComparingInt( Scored::rank ) );

        final List< String > out = new ArrayList<>( n );
        for( final Scored s : scored ) out.add( s.name() );
        return out;
    }

    private record Scored( String name, int rank, double score ) {}
}
