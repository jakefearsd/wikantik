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
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
}
