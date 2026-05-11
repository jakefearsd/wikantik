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

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Pure scoring helper for Phase 3 graph-aware rerank. Given the query's
 * resolved entities plus a map of {@code pageName -> mentioned entity ids},
 * walks the KG neighborhood around the query entities via a multi-source BFS
 * and reports a per-page proximity score in {@code [0.0, 1.0]}.
 *
 * <p>Score per candidate entity {@code e} is {@code 1 / (1 + minHops(Q, e))}
 * where {@code Q} is the set of query entities and {@code minHops} is the
 * shortest undirected hop distance through {@link GraphNeighborIndex}. Nodes
 * unreachable within {@code maxHops} contribute nothing. A page's score is
 * the {@code max} proximity over its mentioned entities — aggregating by
 * {@code max} (rather than sum or mean) means a single well-connected mention
 * is enough to boost the page, which matches the intuition that one explicit
 * entity match is strong signal regardless of how many unrelated entities the
 * page also happens to mention.</p>
 *
 * <p>Stateless and thread-safe — the scorer holds a reference to the index
 * and delegates all mutable state there.</p>
 */
public final class GraphProximityScorer {

    private final GraphNeighborIndex neighborIndex;

    public GraphProximityScorer( final GraphNeighborIndex neighborIndex ) {
        if( neighborIndex == null ) throw new IllegalArgumentException( "neighborIndex must not be null" );
        this.neighborIndex = neighborIndex;
    }

    /**
     * Returns page name → proximity score for each page in {@code pageMentions}
     * whose mentioned entities include at least one entity reachable from some
     * query entity within {@code maxHops} hops. Pages with no reachable
     * mention are omitted from the result so callers can treat the map as a
     * sparse boost layer.
     *
     * @param queryEntities  set of resolved query-entity UUIDs; empty set yields an empty map
     * @param pageMentions   page name → mentioned entity ids; empty map yields an empty map
     * @param maxHops        search radius (inclusive); must be >= 0, where 0 only matches direct overlap
     */
    public Map< String, Double > score( final Set< UUID > queryEntities,
                                        final Map< String, Set< UUID > > pageMentions,
                                        final int maxHops ) {
        if( queryEntities == null || queryEntities.isEmpty()
                || pageMentions == null || pageMentions.isEmpty() ) {
            return Map.of();
        }
        if( maxHops < 0 ) {
            throw new IllegalArgumentException( "maxHops must be >= 0, got: " + maxHops );
        }

        final Map< UUID, Integer > distances = bfs( queryEntities, maxHops );
        if( distances.isEmpty() ) return Map.of();

        final Map< String, Double > out = new LinkedHashMap<>();
        for( final Map.Entry< String, Set< UUID > > e : pageMentions.entrySet() ) {
            final Set< UUID > mentions = e.getValue();
            if( mentions == null || mentions.isEmpty() ) continue;
            double best = 0.0;
            for( final UUID m : mentions ) {
                final Integer d = distances.get( m );
                if( d == null ) continue;
                final double s = 1.0 / ( 1.0 + d );
                if( s > best ) best = s;
            }
            if( best > 0.0 ) out.put( e.getKey(), best );
        }
        return out;
    }

    /**
     * Weighted variant of {@link #score}: traverses the graph via Dijkstra on
     * {@link GraphNeighborIndex#edgeWeight} so lower-trust edges accumulate
     * higher effective distance, then multiplies the per-page proximity by
     * the page's max mention confidence (clamped to {@code mentionConfFloor}).
     *
     * <p>{@code maxDistance} caps the weighted shortest-path radius. The
     * caller is expected to compute it as {@code maxHops / minEdgeWeight} so
     * the weighted reachability set matches the unweighted hop-radius — the
     * goal of the weighted variant is to differentiate <em>proximity scores</em>
     * by edge trust, not to silently shrink the boosted set. With defaults of
     * {@code maxHops=2} and {@code machineWeight=0.5} the budget is {@code 4.0},
     * which matches the integer 2-hop reach of the unweighted BFS.</p>
     *
     * @param queryEntities   resolved query entity ids
     * @param pageMentions    page name → (entity id → mention confidence)
     * @param maxDistance     weighted-distance budget; nodes beyond this are pruned
     * @param mentionConfFloor lower clamp for mention confidence multiplier in {@code [0, 1]}
     */
    public Map< String, Double > scoreWeighted( final Set< UUID > queryEntities,
                                                final Map< String, Map< UUID, Double > > pageMentions,
                                                final double maxDistance,
                                                final double mentionConfFloor ) {
        if( queryEntities == null || queryEntities.isEmpty()
                || pageMentions == null || pageMentions.isEmpty() ) {
            return Map.of();
        }
        if( Double.isNaN( maxDistance ) || Double.isInfinite( maxDistance ) || maxDistance < 0.0 ) {
            throw new IllegalArgumentException( "maxDistance must be a finite number >= 0, got: " + maxDistance );
        }
        if( Double.isNaN( mentionConfFloor ) || mentionConfFloor < 0.0 || mentionConfFloor > 1.0 ) {
            throw new IllegalArgumentException( "mentionConfFloor must be in [0, 1], got: " + mentionConfFloor );
        }

        final Map< UUID, Double > distances = weightedShortestPath( queryEntities, maxDistance );
        if( distances.isEmpty() ) return Map.of();

        final Map< String, Double > out = new LinkedHashMap<>();
        for( final Map.Entry< String, Map< UUID, Double > > e : pageMentions.entrySet() ) {
            final Map< UUID, Double > mentions = e.getValue();
            if( mentions == null || mentions.isEmpty() ) continue;
            double best = 0.0;
            for( final Map.Entry< UUID, Double > m : mentions.entrySet() ) {
                final Double d = distances.get( m.getKey() );
                if( d == null ) continue;
                final double confRaw = m.getValue() == null ? 1.0 : m.getValue();
                final double conf = Math.max( mentionConfFloor, Math.min( 1.0, confRaw ) );
                final double s = conf * 1.0 / ( 1.0 + d );
                if( s > best ) best = s;
            }
            if( best > 0.0 ) out.put( e.getKey(), best );
        }
        return out;
    }

    /**
     * Multi-source BFS from every {@code queryEntity} up to {@code maxHops}
     * levels. Returns a map of reachable {@code nodeId -> minHops} including
     * the sources themselves at distance 0.
     */
    private Map< UUID, Integer > bfs( final Set< UUID > queryEntities, final int maxHops ) {
        final Map< UUID, Integer > dist = new HashMap<>();
        final Deque< UUID > queue = new ArrayDeque<>();
        for( final UUID q : queryEntities ) {
            if( q == null ) continue;
            if( dist.putIfAbsent( q, 0 ) == null ) {
                queue.add( q );
            }
        }
        while( !queue.isEmpty() ) {
            final UUID u = queue.pollFirst();
            final int du = dist.get( u );
            if( du >= maxHops ) continue;
            final Set< UUID > nbrs = neighborIndex.neighbors( u );
            if( nbrs == null || nbrs.isEmpty() ) continue;
            for( final UUID v : nbrs ) {
                if( v == null ) continue;
                if( dist.putIfAbsent( v, du + 1 ) == null ) {
                    queue.addLast( v );
                }
            }
        }
        return dist;
    }

    /**
     * Multi-source Dijkstra. Edge cost from {@code u} to {@code v} is
     * {@code 1 / max(weight, 1e-9)}, where {@code weight = neighborIndex.edgeWeight(u,v)}.
     * Nodes whose tentative distance exceeds {@code maxDistance} are not
     * enqueued. Returns {@code nodeId -> shortest weighted distance} for every
     * reachable node (sources at 0.0).
     */
    private Map< UUID, Double > weightedShortestPath( final Set< UUID > queryEntities, final double maxDistance ) {
        final Map< UUID, Double > dist = new HashMap<>();
        final PriorityQueue< Entry > pq = new PriorityQueue<>( Comparator.comparingDouble( e -> e.dist ) );
        for( final UUID q : queryEntities ) {
            if( q == null ) continue;
            if( dist.putIfAbsent( q, 0.0 ) == null ) {
                pq.add( new Entry( q, 0.0 ) );
            }
        }
        while( !pq.isEmpty() ) {
            final Entry cur = pq.poll();
            final Double recorded = dist.get( cur.node );
            // Stale heap entry (we already found a shorter path).
            if( recorded == null || cur.dist > recorded ) continue;
            if( cur.dist >= maxDistance ) continue;
            final Set< UUID > nbrs = neighborIndex.neighbors( cur.node );
            if( nbrs == null || nbrs.isEmpty() ) continue;
            for( final UUID v : nbrs ) {
                if( v == null ) continue;
                final double w = Math.max( neighborIndex.edgeWeight( cur.node, v ), 1e-9 );
                final double next = cur.dist + ( 1.0 / w );
                if( next > maxDistance ) continue;
                final Double prior = dist.get( v );
                if( prior == null || next < prior ) {
                    dist.put( v, next );
                    pq.add( new Entry( v, next ) );
                }
            }
        }
        return dist;
    }

    private record Entry( UUID node, double dist ) {}
}
