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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Orchestration tests for {@link GraphRerankStep}. All collaborators are
 * swapped for in-memory fakes so the test is pure — the goal is to pin down
 * the composition rules (when do we degrade? how does boost combine with
 * rank?) without relying on JDBC.
 */
class GraphRerankStepTest {

    private final UUID e1 = UUID.randomUUID(); // query entity, self-match on page "E1"
    private final UUID e2 = UUID.randomUUID(); // 1 hop from e1

    @Test
    void regressionBoostZeroReturnsInputUntouched() {
        final GraphRerankStep step = stepWith( 0.0, fakeResolver( Set.of( e1 ) ),
            fakeMentions( Map.of( "PageA", Set.of( e1 ), "PageB", Set.of( e2 ) ) ),
            fakeIndex( Map.of( e1, Set.of( e2 ), e2, Set.of( e1 ) ) ) );
        final List< String > fused = List.of( "PageB", "PageA", "PageC" );
        final List< String > out = step.rerank( "anything", fused );
        assertSame( fused, out, "boost=0 must return the exact same list reference" );
    }

    @Test
    void regressionIndexNotReadyReturnsInputUntouched() {
        final GraphRerankStep step = stepWith( 0.2, fakeResolver( Set.of( e1 ) ),
            fakeMentions( Map.of( "PageA", Set.of( e1 ) ) ),
            fakeIndex( Map.of() ) ); // empty adjacency → isReady=false
        final List< String > fused = List.of( "PageA", "PageB" );
        assertSame( fused, step.rerank( "anything", fused ) );
    }

    @Test
    void regressionNoQueryEntitiesReturnsInputUntouched() {
        final GraphRerankStep step = stepWith( 0.2, fakeResolver( Set.of() ),
            fakeMentions( Map.of( "PageA", Set.of( e1 ) ) ),
            fakeIndex( Map.of( e1, Set.of( e2 ), e2, Set.of( e1 ) ) ) );
        final List< String > fused = List.of( "PageA", "PageB" );
        assertSame( fused, step.rerank( "anything", fused ) );
    }

    @Test
    void regressionNoMentionsReturnsInputUntouched() {
        final GraphRerankStep step = stepWith( 0.2, fakeResolver( Set.of( e1 ) ),
            fakeMentions( Map.of() ),
            fakeIndex( Map.of( e1, Set.of( e2 ), e2, Set.of( e1 ) ) ) );
        final List< String > fused = List.of( "PageA", "PageB" );
        assertSame( fused, step.rerank( "anything", fused ) );
    }

    @Test
    void boostLiftsProximityMatchAboveWeakBaseRank() {
        // 5 pages; PageX at rank 4 (weakest) has proximity 1.0. boost=0.5 lifts it above PageA at rank 0.
        // base(rank=0) = 1 - 0/5 = 1.0; base(rank=4) = 1 - 4/5 = 0.2; 0.2 + 0.5*1.0 = 0.7 (still below 1.0).
        // Use boost=1.0: 0.2 + 1.0 = 1.2 > 1.0, so PageX wins.
        final GraphRerankStep step = stepWith( 1.0, fakeResolver( Set.of( e1 ) ),
            fakeMentions( Map.of( "PageX", Set.of( e1 ) ) ),
            fakeIndex( Map.of( e1, Set.of( e2 ), e2, Set.of( e1 ) ) ) );
        final List< String > fused = List.of( "PageA", "PageB", "PageC", "PageD", "PageX" );
        final List< String > out = step.rerank( "anything", fused );
        assertEquals( "PageX", out.get( 0 ) );
        assertEquals( "PageA", out.get( 1 ) );
    }

    @Test
    void tiedScoresPreserveOriginalRank() {
        // No proximity hits ⇒ all base scores strictly descending; output order == input order.
        final GraphRerankStep step = stepWith( 0.2, fakeResolver( Set.of( e1 ) ),
            fakeMentions( Map.of( "OtherPage", Set.of( e1 ) ) ),
            fakeIndex( Map.of( e1, Set.of( e2 ), e2, Set.of( e1 ) ) ) );
        // Pages in fused don't mention any query entity, so scores fall back to base rank only.
        final List< String > fused = List.of( "A", "B", "C", "D" );
        assertEquals( fused, step.rerank( "anything", fused ) );
    }

    @Test
    void emptyFusedReturnsEmpty() {
        final GraphRerankStep step = stepWith( 0.2, fakeResolver( Set.of( e1 ) ),
            fakeMentions( Map.of() ),
            fakeIndex( Map.of( e1, Set.of( e2 ), e2, Set.of( e1 ) ) ) );
        assertEquals( List.of(), step.rerank( "q", List.of() ) );
    }

    // ---- helpers ----

    private GraphRerankStep stepWith( final double boost,
                                       final QueryEntityResolver resolver,
                                       final PageMentionsLoader mentionsLoader,
                                       final GraphNeighborIndex index ) {
        final Properties p = new Properties();
        p.setProperty( GraphRerankConfig.PROP_BOOST, Double.toString( boost ) );
        final GraphRerankConfig cfg = GraphRerankConfig.fromProperties( p );
        final GraphProximityScorer scorer = new GraphProximityScorer( index );
        return new GraphRerankStep( resolver, mentionsLoader, scorer, index, cfg );
    }

    private static QueryEntityResolver fakeResolver( final Set< UUID > ids ) {
        // Can't easily subclass QueryEntityResolver (final), so wrap via anonymous anonymous
        // interface would require refactoring — instead use a concrete fake subclass.
        return new QueryEntityResolver( new com.wikantik.search.hybrid.GraphRerankStepTest.NoopDataSource(),
                GraphRerankConfig.fromProperties( new Properties() ) ) {
            @Override
            public Set< UUID > resolve( final String query ) {
                return ids;
            }
        };
    }

    private static PageMentionsLoader fakeMentions( final Map< String, Set< UUID > > mentions ) {
        return new PageMentionsLoader( new com.wikantik.search.hybrid.GraphRerankStepTest.NoopDataSource() ) {
            @Override
            public Map< String, Set< UUID > > loadFor( final java.util.Collection< String > pageNames ) {
                final Map< String, Set< UUID > > out = new HashMap<>();
                for( final String name : pageNames ) {
                    final Set< UUID > s = mentions.get( name );
                    if( s != null && !s.isEmpty() ) out.put( name, s );
                }
                return out;
            }
        };
    }

    private static GraphNeighborIndex fakeIndex( final Map< UUID, Set< UUID > > adj ) {
        final Map< UUID, Set< UUID > > sym = new HashMap<>();
        for( final Map.Entry< UUID, Set< UUID > > e : adj.entrySet() ) {
            sym.computeIfAbsent( e.getKey(), k -> new HashSet<>() ).addAll( e.getValue() );
            for( final UUID v : e.getValue() ) {
                sym.computeIfAbsent( v, k -> new HashSet<>() ).add( e.getKey() );
            }
        }
        return new GraphNeighborIndex() {
            @Override public Set< UUID > neighbors( final UUID nodeId ) {
                return sym.getOrDefault( nodeId, Set.of() );
            }
            @Override public boolean isReady() { return !sym.isEmpty(); }
            @Override public int nodeCount() { return sym.size(); }
        };
    }

    /**
     * Stub DataSource — never used because the fakes override the methods that
     * touch JDBC. Exists only so the concrete {@link QueryEntityResolver} and
     * {@link PageMentionsLoader} constructors can be satisfied.
     */
    static final class NoopDataSource implements javax.sql.DataSource {
        @Override public java.sql.Connection getConnection() { throw new UnsupportedOperationException( "fake" ); }
        @Override public java.sql.Connection getConnection( final String u, final String p ) { throw new UnsupportedOperationException( "fake" ); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter( final java.io.PrintWriter out ) {}
        @Override public void setLoginTimeout( final int seconds ) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger( "noop" ); }
        @Override public < T > T unwrap( final Class< T > iface ) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor( final Class< ? > iface ) { return false; }
    }
}
