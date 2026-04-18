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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenseRetrieverTest {

    private static final double EPS = 1e-9;

    /** 20 chunks across 5 pages (A..E), 4 chunks per page, with hand-chosen scores. */
    private static List< ScoredChunk > fixture() {
        final String[] pages = { "A", "B", "C", "D", "E" };
        final double[][] scores = {
            { 0.95, 0.90, 0.85, 0.10 }, // A
            { 0.92, 0.50, 0.40, 0.05 }, // B
            { 0.70, 0.65, 0.60, 0.55 }, // C
            { 0.30, 0.25, 0.20, 0.15 }, // D
            { 0.80, 0.02, 0.01, 0.00 }  // E
        };
        final List< ScoredChunk > out = new ArrayList<>( 20 );
        for( int i = 0; i < pages.length; i++ ) {
            for( final double s : scores[ i ] ) {
                out.add( new ScoredChunk( UUID.randomUUID(), pages[ i ], s ) );
            }
        }
        return out;
    }

    private static class StubIndex implements ChunkVectorIndex {
        final List< ScoredChunk > all;
        boolean ready = true;
        int dim = 4;
        int lastK = -1;

        StubIndex( final List< ScoredChunk > all ) { this.all = all; }

        @Override
        public List< ScoredChunk > topKChunks( final float[] queryVec, final int k ) {
            lastK = k;
            final List< ScoredChunk > sorted = new ArrayList<>( all );
            sorted.sort( Comparator.comparingDouble( ScoredChunk::score ).reversed() );
            return sorted.subList( 0, Math.min( k, sorted.size() ) );
        }

        @Override public boolean isReady() { return ready; }
        @Override public int dimension() { return dim; }
    }

    @Test
    void maxStrategyRanksByBestChunkPerPage() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MAX, 500, 100 );
        final List< ScoredPage > out = r.retrieve( new float[]{ 0f, 0f, 0f, 0f } );

        assertEquals( 5, out.size() );
        assertEquals( "A", out.get( 0 ).pageName() );
        assertEquals( 0.95, out.get( 0 ).score(), EPS );
        assertEquals( "B", out.get( 1 ).pageName() );
        assertEquals( 0.92, out.get( 1 ).score(), EPS );
        assertEquals( "E", out.get( 2 ).pageName() );
        assertEquals( 0.80, out.get( 2 ).score(), EPS );
        assertEquals( "C", out.get( 3 ).pageName() );
        assertEquals( "D", out.get( 4 ).pageName() );
    }

    @Test
    void sumTop3RanksCThirdBecauseOfDenseMidScores() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.SUM_TOP_3, 500, 100 );
        final List< ScoredPage > out = r.retrieve( new float[]{ 0f, 0f, 0f, 0f } );

        // A: 0.95+0.90+0.85=2.70, B: 0.92+0.50+0.40=1.82, C: 0.70+0.65+0.60=1.95,
        // D: 0.30+0.25+0.20=0.75, E: 0.80+0.02+0.01=0.83.
        assertEquals( "A", out.get( 0 ).pageName() );
        assertEquals( 2.70, out.get( 0 ).score(), EPS );
        assertEquals( "C", out.get( 1 ).pageName() );
        assertEquals( 1.95, out.get( 1 ).score(), EPS );
        assertEquals( "B", out.get( 2 ).pageName() );
        assertEquals( 1.82, out.get( 2 ).score(), EPS );
    }

    @Test
    void sumTop5EqualsSumOfAllWhenPageHasFewerChunks() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.SUM_TOP_5, 500, 100 );
        final List< ScoredPage > out = r.retrieve( new float[]{ 0f, 0f, 0f, 0f } );
        // A total = 0.95 + 0.90 + 0.85 + 0.10 = 2.80.
        final ScoredPage a = out.stream().filter( p -> p.pageName().equals( "A" ) ).findFirst().orElseThrow();
        assertEquals( 2.80, a.score(), EPS );
    }

    @Test
    void meanTop3ReturnsMeanOfTopThreePerPage() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MEAN_TOP_3, 500, 100 );
        final List< ScoredPage > out = r.retrieve( new float[]{ 0f, 0f, 0f, 0f } );
        assertEquals( "A", out.get( 0 ).pageName() );
        assertEquals( 0.90, out.get( 0 ).score(), EPS );
    }

    @Test
    void pageTopTruncatesOutput() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MAX, 500, 2 );
        final List< ScoredPage > out = r.retrieve( new float[]{ 0f, 0f, 0f, 0f } );
        assertEquals( 2, out.size() );
        assertEquals( "A", out.get( 0 ).pageName() );
        assertEquals( "B", out.get( 1 ).pageName() );
    }

    @Test
    void chunkTopPassedToIndex() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MAX, 7, 100 );
        r.retrieve( new float[]{ 0f, 0f, 0f, 0f } );
        assertEquals( 7, index.lastK );
    }

    @Test
    void unreadyIndexYieldsEmptyList() {
        final StubIndex index = new StubIndex( fixture() );
        index.ready = false;
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MAX, 500, 100 );
        assertTrue( r.retrieve( new float[]{ 0f, 0f, 0f, 0f } ).isEmpty() );
    }

    @Test
    void mismatchedDimensionThrows() {
        final StubIndex index = new StubIndex( fixture() );
        index.dim = 8;
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MAX, 500, 100 );
        assertThrows( IllegalArgumentException.class,
            () -> r.retrieve( new float[]{ 0f, 0f, 0f, 0f } ) );
    }

    @Test
    void nullQueryVecThrows() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MAX, 500, 100 );
        assertThrows( IllegalArgumentException.class, () -> r.retrieve( null ) );
    }

    @Test
    void constructorRejectsInvalidArgs() {
        final StubIndex index = new StubIndex( fixture() );
        assertThrows( IllegalArgumentException.class,
            () -> new DenseRetriever( null, PageAggregation.MAX, 500, 100 ) );
        assertThrows( IllegalArgumentException.class,
            () -> new DenseRetriever( index, null, 500, 100 ) );
        assertThrows( IllegalArgumentException.class,
            () -> new DenseRetriever( index, PageAggregation.MAX, 0, 100 ) );
        assertThrows( IllegalArgumentException.class,
            () -> new DenseRetriever( index, PageAggregation.MAX, 500, 0 ) );
    }

    @Test
    void emptyIndexProducesEmptyResult() {
        final StubIndex index = new StubIndex( new ArrayList<>() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.MAX, 500, 100 );
        assertTrue( r.retrieve( new float[]{ 0f, 0f, 0f, 0f } ).isEmpty() );
    }

    @Test
    void outputIsDescendingByScore() {
        final StubIndex index = new StubIndex( fixture() );
        final DenseRetriever r = new DenseRetriever( index, PageAggregation.SUM_TOP_3, 500, 100 );
        final List< ScoredPage > out = r.retrieve( new float[]{ 0f, 0f, 0f, 0f } );
        double[] arr = out.stream().mapToDouble( ScoredPage::score ).toArray();
        double[] sorted = arr.clone();
        Arrays.sort( sorted );
        for( int i = 0; i < arr.length; i++ ) {
            assertEquals( sorted[ sorted.length - 1 - i ], arr[ i ], EPS );
        }
    }
}
