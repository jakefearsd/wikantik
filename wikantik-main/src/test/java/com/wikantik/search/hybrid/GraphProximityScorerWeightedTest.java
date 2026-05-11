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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GraphProximityScorer#scoreWeighted}: verifies the
 * provenance-weighted variant assigns higher proximity to paths through
 * high-trust (human-tier) edges than equal-hop paths through low-trust
 * (machine-tier) edges, that mention confidence attenuates proximity, and
 * that the floor clamp protects against confidence outliers.
 */
class GraphProximityScorerWeightedTest {

    private final UUID q  = UUID.randomUUID();
    private final UUID a  = UUID.randomUUID();
    private final UUID b  = UUID.randomUUID();
    private final UUID c  = UUID.randomUUID();
    private final UUID d  = UUID.randomUUID();

    @Test
    void allHumanEdgesMatchUnweightedScoring() {
        // q --(human, w=1)-- a --(human, w=1)-- b
        // Two-hop human path: weighted distance = 2.0 → proximity = 1/3
        // Matches the unweighted BFS exactly.
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( q, a, 1.0 );
        idx.link( a, b, 1.0 );

        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = Map.of( "P", Map.of( b, 1.0 ) );

        final Map< String, Double > scores = scorer.scoreWeighted( Set.of( q ), pages, 4.0, 0.5 );

        assertEquals( 1.0 / 3.0, scores.get( "P" ), 1e-9 );
    }

    @Test
    void machineEdgeDownweightsProximity() {
        // q --(human, w=1)-- a --(machine, w=0.5)-- b
        // Effective hops: 1 + 2 = 3 → proximity = 1/(1+3) = 0.25
        // The pure human equivalent would be 1/3 ≈ 0.333. Machine edge cost wins.
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( q, a, 1.0 );
        idx.link( a, b, 0.5 );

        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = Map.of( "P", Map.of( b, 1.0 ) );

        final Map< String, Double > scores = scorer.scoreWeighted( Set.of( q ), pages, 4.0, 0.5 );

        assertEquals( 0.25, scores.get( "P" ), 1e-9 );
    }

    @Test
    void weightedDijkstraPicksCheaperPathWhenAlternateExists() {
        // Two paths from q to d:
        //   q -(human)- a -(human)- d           total cost 2
        //   q -(machine)- c -(machine)- d        total cost 4
        // Dijkstra must pick the cheaper path → proximity = 1/3.
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( q, a, 1.0 );
        idx.link( a, d, 1.0 );
        idx.link( q, c, 0.5 );
        idx.link( c, d, 0.5 );

        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = Map.of( "P", Map.of( d, 1.0 ) );

        final Map< String, Double > scores = scorer.scoreWeighted( Set.of( q ), pages, 5.0, 0.5 );

        assertEquals( 1.0 / 3.0, scores.get( "P" ), 1e-9 );
    }

    @Test
    void mentionConfidenceAttenuatesProximity() {
        // q -(human)- a  with mention confidence 0.8 on the page.
        // Raw proximity = 1/(1+1) = 0.5; multiplied by 0.8 = 0.4.
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( q, a, 1.0 );

        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = Map.of( "P", Map.of( a, 0.8 ) );

        final Map< String, Double > scores = scorer.scoreWeighted( Set.of( q ), pages, 4.0, 0.5 );

        assertEquals( 0.4, scores.get( "P" ), 1e-9 );
    }

    @Test
    void mentionConfidenceFloorClampsLowOutliers() {
        // Mention confidence 0.1 would tank a strong-signal page; floor=0.5 clamps up.
        // Raw proximity = 0.5; multiplied by floored confidence 0.5 = 0.25.
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( q, a, 1.0 );

        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = Map.of( "P", Map.of( a, 0.1 ) );

        final Map< String, Double > scores = scorer.scoreWeighted( Set.of( q ), pages, 4.0, 0.5 );

        assertEquals( 0.25, scores.get( "P" ), 1e-9 );
    }

    @Test
    void perPageScoreIsMaxAcrossMentions() {
        // Same page mentions two entities: a (1 hop, conf 1.0) and b (2 hops, conf 1.0).
        // Both via human edges. Score should be max → 0.5 (the closer one).
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( q, a, 1.0 );
        idx.link( a, b, 1.0 );

        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = new HashMap<>();
        final Map< UUID, Double > mentions = new HashMap<>();
        mentions.put( a, 1.0 );
        mentions.put( b, 1.0 );
        pages.put( "P", mentions );

        final Map< String, Double > scores = scorer.scoreWeighted( Set.of( q ), pages, 4.0, 0.5 );

        assertEquals( 0.5, scores.get( "P" ), 1e-9 );
    }

    @Test
    void unreachableNodesOmitted() {
        // q is isolated; no edges. Page mentions a far entity not reachable.
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( a, b, 1.0 );   // disconnected from q

        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = Map.of( "P", Map.of( b, 1.0 ) );

        assertTrue( scorer.scoreWeighted( Set.of( q ), pages, 4.0, 0.5 ).isEmpty() );
    }

    @Test
    void emptyInputsYieldEmptyOutput() {
        final GraphProximityScorer scorer = new GraphProximityScorer( new WeightedMapIndex() );
        assertTrue( scorer.scoreWeighted( Set.of(), Map.of( "P", Map.of( a, 1.0 ) ), 4.0, 0.5 ).isEmpty() );
        assertTrue( scorer.scoreWeighted( Set.of( q ), Map.of(), 4.0, 0.5 ).isEmpty() );
        assertTrue( scorer.scoreWeighted( null, Map.of( "P", Map.of( a, 1.0 ) ), 4.0, 0.5 ).isEmpty() );
        assertTrue( scorer.scoreWeighted( Set.of( q ), null, 4.0, 0.5 ).isEmpty() );
    }

    @Test
    void pagesWithEmptyOrNullMentionsSkipped() {
        final WeightedMapIndex idx = new WeightedMapIndex();
        idx.link( q, a, 1.0 );
        final GraphProximityScorer scorer = new GraphProximityScorer( idx );
        final Map< String, Map< UUID, Double > > pages = new HashMap<>();
        pages.put( "P1", Map.of() );
        pages.put( "P2", null );
        pages.put( "P3", Map.of( a, 1.0 ) );

        final Map< String, Double > scores = scorer.scoreWeighted( Set.of( q ), pages, 4.0, 0.5 );

        assertFalse( scores.containsKey( "P1" ) );
        assertFalse( scores.containsKey( "P2" ) );
        assertEquals( 0.5, scores.get( "P3" ), 1e-9 );
    }

    @Test
    void rejectsInvalidArgs() {
        final GraphProximityScorer scorer = new GraphProximityScorer( new WeightedMapIndex() );
        final Map< String, Map< UUID, Double > > pages = Map.of( "P", Map.of( a, 1.0 ) );
        assertThrows( IllegalArgumentException.class,
            () -> scorer.scoreWeighted( Set.of( q ), pages, -1.0, 0.5 ) );
        assertThrows( IllegalArgumentException.class,
            () -> scorer.scoreWeighted( Set.of( q ), pages, Double.POSITIVE_INFINITY, 0.5 ) );
        assertThrows( IllegalArgumentException.class,
            () -> scorer.scoreWeighted( Set.of( q ), pages, 4.0, -0.1 ) );
        assertThrows( IllegalArgumentException.class,
            () -> scorer.scoreWeighted( Set.of( q ), pages, 4.0, 1.1 ) );
    }

    /** Weighted fake graph used across these tests. Symmetric edges. */
    private static final class WeightedMapIndex implements GraphNeighborIndex {
        private final Map< UUID, Set< UUID > > adj = new HashMap<>();
        private final Map< UUID, Map< UUID, Double > > weights = new HashMap<>();

        void link( final UUID x, final UUID y, final double w ) {
            adj.computeIfAbsent( x, k -> new HashSet<>() ).add( y );
            adj.computeIfAbsent( y, k -> new HashSet<>() ).add( x );
            weights.computeIfAbsent( x, k -> new HashMap<>() ).put( y, w );
            weights.computeIfAbsent( y, k -> new HashMap<>() ).put( x, w );
        }

        @Override public Set< UUID > neighbors( final UUID nodeId ) {
            return adj.getOrDefault( nodeId, Set.of() );
        }

        @Override public boolean isReady() { return !adj.isEmpty(); }

        @Override public int nodeCount() { return adj.size(); }

        @Override public double edgeWeight( final UUID src, final UUID tgt ) {
            final Map< UUID, Double > row = weights.get( src );
            if( row == null ) return 1.0;
            final Double w = row.get( tgt );
            return w == null ? 1.0 : w;
        }
    }
}
