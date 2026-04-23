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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphProximityScorerTest {

    private final UUID napoleon  = UUID.randomUUID();
    private final UUID waterloo  = UUID.randomUUID();
    private final UUID wellington= UUID.randomUUID();
    private final UUID blucher   = UUID.randomUUID();
    private final UUID isolated  = UUID.randomUUID(); // far away, unreachable in 2 hops
    private final UUID far       = UUID.randomUUID(); // 3 hops away from napoleon

    /**
     * Graph (undirected):
     *   Napoleon — Waterloo — Wellington
     *   Napoleon — Blucher
     *   Wellington — Far
     *   (isolated has no edges)
     */
    private GraphNeighborIndex fixedIndex() {
        final Map< UUID, Set< UUID > > adj = new HashMap<>();
        link( adj, napoleon,  waterloo );
        link( adj, waterloo,  wellington );
        link( adj, napoleon,  blucher );
        link( adj, wellington, far );
        return new MapIndex( adj );
    }

    @Test
    void zeroOverlapReturnsEmptyMap() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        final Map< String, Set< UUID > > pages = Map.of( "P1", Set.of( isolated ) );
        assertTrue( scorer.score( Set.of( napoleon ), pages, 2 ).isEmpty() );
    }

    @Test
    void selfMatchScoresOne() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        final Map< String, Set< UUID > > pages = Map.of( "P1", Set.of( napoleon ) );
        final Map< String, Double > scores = scorer.score( Set.of( napoleon ), pages, 2 );
        assertEquals( 1.0, scores.get( "P1" ), 1e-9 );
    }

    @Test
    void oneHopScoresOneOverTwo() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        // Waterloo is 1 hop from Napoleon.
        final Map< String, Set< UUID > > pages = Map.of( "P1", Set.of( waterloo ) );
        final Map< String, Double > scores = scorer.score( Set.of( napoleon ), pages, 2 );
        assertEquals( 0.5, scores.get( "P1" ), 1e-9 );
    }

    @Test
    void twoHopsScoresOneOverThree() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        // Wellington is 2 hops from Napoleon via Waterloo.
        final Map< String, Set< UUID > > pages = Map.of( "P1", Set.of( wellington ) );
        final Map< String, Double > scores = scorer.score( Set.of( napoleon ), pages, 2 );
        assertEquals( 1.0 / 3.0, scores.get( "P1" ), 1e-9 );
    }

    @Test
    void maxHopsCutoffOmitsUnreachable() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        // Far is 3 hops from Napoleon; with maxHops=2 it's unreachable.
        final Map< String, Set< UUID > > pages = Map.of( "P1", Set.of( far ) );
        assertTrue( scorer.score( Set.of( napoleon ), pages, 2 ).isEmpty() );

        // With maxHops=3 it becomes reachable.
        final Map< String, Double > scores = scorer.score( Set.of( napoleon ), pages, 3 );
        assertEquals( 0.25, scores.get( "P1" ), 1e-9 );
    }

    @Test
    void pageScoreIsMaxAcrossMentions() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        // P1 mentions Wellington (2-hop, score 1/3) AND Waterloo (1-hop, score 1/2).
        // Expected page score = max = 1/2.
        final Map< String, Set< UUID > > pages = Map.of( "P1", Set.of( wellington, waterloo ) );
        final Map< String, Double > scores = scorer.score( Set.of( napoleon ), pages, 2 );
        assertEquals( 0.5, scores.get( "P1" ), 1e-9 );
    }

    @Test
    void multipleQueryEntitiesTakeMinHops() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        // Query = {napoleon, wellington}. Waterloo is 1 hop from both; distance=1 → score 1/2.
        // Blucher is 1 hop from napoleon but 3+ from wellington → min is 1 hop, score 1/2.
        final Map< String, Set< UUID > > pages = new LinkedHashMap<>();
        pages.put( "PageA", Set.of( waterloo ) );
        pages.put( "PageB", Set.of( blucher ) );
        final Map< String, Double > scores = scorer.score( Set.of( napoleon, wellington ), pages, 2 );
        assertEquals( 0.5, scores.get( "PageA" ), 1e-9 );
        assertEquals( 0.5, scores.get( "PageB" ), 1e-9 );
    }

    @Test
    void emptyQueryOrPagesYieldEmpty() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        assertTrue( scorer.score( Set.of(), Map.of( "P", Set.of( napoleon ) ), 2 ).isEmpty() );
        assertTrue( scorer.score( Set.of( napoleon ), Map.of(), 2 ).isEmpty() );
        assertTrue( scorer.score( null, Map.of( "P", Set.of( napoleon ) ), 2 ).isEmpty() );
        assertTrue( scorer.score( Set.of( napoleon ), null, 2 ).isEmpty() );
    }

    @Test
    void pagesWithEmptyMentionsSkipped() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        final Map< String, Set< UUID > > pages = new HashMap<>();
        pages.put( "P1", Set.of() );
        pages.put( "P2", null );
        pages.put( "P3", Set.of( waterloo ) );
        final Map< String, Double > scores = scorer.score( Set.of( napoleon ), pages, 2 );
        assertFalse( scores.containsKey( "P1" ) );
        assertFalse( scores.containsKey( "P2" ) );
        assertEquals( 0.5, scores.get( "P3" ), 1e-9 );
    }

    @Test
    void negativeMaxHopsRejected() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        final Map< String, Set< UUID > > pages = Map.of( "P1", Set.of( napoleon ) );
        assertThrows( IllegalArgumentException.class, () -> scorer.score( Set.of( napoleon ), pages, -1 ) );
    }

    @Test
    void zeroHopsMatchesOnlyDirectOverlap() {
        final GraphProximityScorer scorer = new GraphProximityScorer( fixedIndex() );
        final Map< String, Set< UUID > > pages = new LinkedHashMap<>();
        pages.put( "Direct", Set.of( napoleon ) );
        pages.put( "Neighbor", Set.of( waterloo ) );
        final Map< String, Double > scores = scorer.score( Set.of( napoleon ), pages, 0 );
        assertEquals( 1.0, scores.get( "Direct" ), 1e-9 );
        assertFalse( scores.containsKey( "Neighbor" ) );
    }

    /** Symmetric map-backed fake used throughout the tests. */
    private static void link( final Map< UUID, Set< UUID > > adj, final UUID a, final UUID b ) {
        adj.computeIfAbsent( a, k -> new HashSet<>() ).add( b );
        adj.computeIfAbsent( b, k -> new HashSet<>() ).add( a );
    }

    private static final class MapIndex implements GraphNeighborIndex {
        private final Map< UUID, Set< UUID > > adj;
        MapIndex( final Map< UUID, Set< UUID > > adj ) { this.adj = adj; }
        @Override public Set< UUID > neighbors( final UUID nodeId ) {
            return adj.getOrDefault( nodeId, Set.of() );
        }
        @Override public boolean isReady() { return !adj.isEmpty(); }
        @Override public int nodeCount() { return adj.size(); }
    }
}
