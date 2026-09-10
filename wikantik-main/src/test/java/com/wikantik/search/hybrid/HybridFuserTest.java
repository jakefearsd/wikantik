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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridFuserTest {

    /**
     * Guards the page-level fusion invariant behind a production defect found on
     * 2026-09-10. In weighted RRF a page at BM25 rank 1 contributes
     * {@code bm25Weight/(k+1)}, while the LAST dense page still contributing
     * (rank {@code truncate}) contributes {@code denseWeight/(k+truncate)}. Unless
     *
     * <pre>  bm25Weight / denseWeight  &gt;  (k + 1) / (k + truncate)</pre>
     *
     * a page found ONLY by BM25 scores below <em>every</em> contributing dense page.
     * Because callers cap the page list at or below {@code truncate}
     * ({@code ContextQuery.MAX_PAGES_CAP} = 20), such a page can never be returned:
     * the BM25 leg becomes unable to surface a page of its own and can only reorder
     * pages the dense leg already found.
     *
     * <p>The shipped defaults violated this until 2.4.23 (1.0/1.5 at k=60,
     * truncate=20 gives 0.667 against a required 0.7625). Searching the exact page
     * name {@code PlanningAMigrationChange} in production ranked that page #1 in
     * Lucene — score 12.5, and the searcher's ACL filter dropped nothing — yet the
     * API returned 20 results without it, because it fused at rank 21.</p>
     */
    @Test
    void shippedDefaults_letABm25RankOnePageReachTheResultWindow() {
        final int k = HybridConfig.DEFAULT_RRF_K;
        final double bm25Weight = HybridConfig.DEFAULT_BM25_WEIGHT;
        final double denseWeight = HybridConfig.DEFAULT_DENSE_WEIGHT;
        final int truncate = HybridConfig.DEFAULT_RRF_TRUNCATE;

        assertTrue( bm25Weight / ( k + 1 ) > denseWeight / ( k + truncate ),
                "Shipped fusion defaults make a BM25-only page unreachable: "
                + "bm25Weight/(k+1) = " + ( bm25Weight / ( k + 1 ) )
                + " must exceed denseWeight/(k+truncate) = " + ( denseWeight / ( k + truncate ) )
                + " (k=" + k + ", truncate=" + truncate + ")" );

        // Behavioural form of the same invariant: one BM25-only page against a
        // full dense list (wikantik.search.hybrid.dense.page-top = 100).
        final HybridFuser fuser = new HybridFuser( k, bm25Weight, denseWeight, truncate );
        final List< String > dense = new ArrayList<>();
        for( int i = 1; i <= 100; i++ ) {
            dense.add( "DensePage" + i );
        }

        final List< String > fused = fuser.fuse( List.of( "TargetPage" ), dense );
        final int rank = fused.indexOf( "TargetPage" ) + 1;

        assertTrue( rank >= 1, "TargetPage must appear in the fused union at all" );
        assertTrue( rank <= truncate,
                "A page ranked #1 by BM25 but absent from the dense list must still outrank the "
                + "worst contributing dense page, but it fused at rank " + rank );
    }

    /**
     * Worked example from the retrieval docs: a single page that appears at
     * BM25 rank 9 (contributes 1/(60+9) = 1/69) and dense rank 3 (contributes
     * 1/(60+3) = 1/63) with equal weights and k=60.
     * Expected fused score ≈ 0.014492753 + 0.015873015 ≈ 0.030365769.
     */
    @Test
    void workedExampleMatchesHandComputedRrfScore() throws Exception {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 0 );
        final List< String > bm25 = new ArrayList<>();
        for( int i = 1; i <= 8; i++ ) bm25.add( "filler-bm25-" + i );
        bm25.add( "PageOfInterest" );
        final List< String > dense = new ArrayList<>();
        for( int i = 1; i <= 2; i++ ) dense.add( "filler-dense-" + i );
        dense.add( "PageOfInterest" );

        final Map< String, Double > scores = rawScores( fuser, bm25, dense );
        final double expected = 1.0 / 69.0 + 1.0 / 63.0;
        assertEquals( expected, scores.get( "PageOfInterest" ), 1e-4 );
    }

    @Test
    void duplicatesSumAcrossLists() {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 0 );
        final List< String > a = List.of( "X", "Y", "Z" );
        final List< String > b = List.of( "X", "W", "V" );
        final List< String > out = fuser.fuse( a, b );
        assertEquals( "X", out.get( 0 ), "X appears at rank 1 in both lists" );
    }

    @Test
    void itemAtTruncatePlusOneContributesZero() {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 3 );
        final List< String > bm25 = List.of( "A", "B", "C", "D", "E" );
        final List< String > dense = List.of();
        final List< String > out = fuser.fuse( bm25, dense );
        assertTrue( out.contains( "A" ) );
        assertTrue( out.contains( "B" ) );
        assertTrue( out.contains( "C" ) );
        assertFalse( out.contains( "D" ), "rank 4 must be excluded when truncate=3" );
        assertFalse( out.contains( "E" ), "rank 5 must be excluded when truncate=3" );
    }

    @Test
    void truncateZeroMeansConsiderAll() {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 0 );
        final List< String > bm25 = List.of( "A", "B", "C", "D", "E" );
        final List< String > out = fuser.fuse( bm25, List.of() );
        assertEquals( 5, out.size() );
    }

    @Test
    void emptyInputsProduceEmptyOutput() {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 0 );
        assertTrue( fuser.fuse( List.of(), List.of() ).isEmpty() );
    }

    @Test
    void asymmetricListsBothContribute() {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 0 );
        final List< String > out = fuser.fuse( List.of( "A" ), List.of( "B", "C" ) );
        assertEquals( 3, out.size() );
    }

    @Test
    void nullListTreatedAsEmpty() {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 0 );
        final List< String > out = fuser.fuse( null, List.of( "A", "B" ) );
        assertEquals( List.of( "A", "B" ), out );
    }

    @Test
    void nullEntriesAreSkipped() {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.0, 0 );
        final List< String > withNull = Arrays.asList( "A", null, "B" );
        final List< String > out = fuser.fuse( withNull, Collections.emptyList() );
        assertEquals( List.of( "A", "B" ), out );
    }

    @Test
    void denseWeightDominatesWhenLargerAtSameRank() throws Exception {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.5, 0 );
        final List< String > bm25 = List.of( "BM_TOP" );
        final List< String > dense = List.of( "DENSE_TOP" );
        final Map< String, Double > raw = rawScores( fuser, bm25, dense );
        assertTrue( raw.get( "DENSE_TOP" ) > raw.get( "BM_TOP" ) );
        assertEquals( 1.5 / 61.0, raw.get( "DENSE_TOP" ), 1e-9 );
        assertEquals( 1.0 / 61.0, raw.get( "BM_TOP" ), 1e-9 );
    }

    @Test
    void bm25WeightDominatesWhenLarger() throws Exception {
        final HybridFuser fuser = new HybridFuser( 60, 2.0, 0.5, 0 );
        final Map< String, Double > raw = rawScores( fuser, List.of( "X" ), List.of( "Y" ) );
        assertTrue( raw.get( "X" ) > raw.get( "Y" ) );
    }

    @Test
    void zeroKRejected() {
        assertThrows( IllegalArgumentException.class, () -> new HybridFuser( 0, 1.0, 1.0, 0 ) );
    }

    @Test
    void negativeKRejected() {
        assertThrows( IllegalArgumentException.class, () -> new HybridFuser( -1, 1.0, 1.0, 0 ) );
    }

    @Test
    void outputSortedDescendingByScore() throws Exception {
        final HybridFuser fuser = new HybridFuser( 60, 1.0, 1.5, 20 );
        final List< String > bm25 = List.of( "P1", "P2", "P3", "P4", "P5" );
        final List< String > dense = List.of( "P5", "P4", "P3", "P2", "P1" );
        final List< String > out = fuser.fuse( bm25, dense );
        final Map< String, Double > raw = rawScores( fuser, bm25, dense );
        double prev = Double.POSITIVE_INFINITY;
        for( final String name : out ) {
            final double s = raw.get( name );
            assertTrue( s <= prev, "output must be sorted descending: " + out );
            prev = s;
        }
    }

    /** Recompute the raw fused-score map by exploiting the same algorithm the class uses. */
    private static Map< String, Double > rawScores( final HybridFuser fuser,
                                                    final List< String > bm25,
                                                    final List< String > dense ) throws Exception {
        final Field kF = HybridFuser.class.getDeclaredField( "k" );
        final Field bF = HybridFuser.class.getDeclaredField( "bm25Weight" );
        final Field dF = HybridFuser.class.getDeclaredField( "denseWeight" );
        final Field tF = HybridFuser.class.getDeclaredField( "truncate" );
        kF.setAccessible( true ); bF.setAccessible( true ); dF.setAccessible( true ); tF.setAccessible( true );
        final int k = (int) kF.get( fuser );
        final double wb = (double) bF.get( fuser );
        final double wd = (double) dF.get( fuser );
        final int trunc = (int) tF.get( fuser );
        final Map< String, Double > out = new HashMap<>();
        contribute( out, bm25, wb, k, trunc );
        contribute( out, dense, wd, k, trunc );
        return out;
    }

    private static void contribute( final Map< String, Double > out, final List< String > list,
                                    final double w, final int k, final int truncate ) {
        if( list == null ) return;
        final int limit = truncate > 0 ? Math.min( truncate, list.size() ) : list.size();
        for( int rank = 0; rank < limit; rank++ ) {
            final String id = list.get( rank );
            if( id == null ) continue;
            out.merge( id, w / ( k + ( rank + 1 ) ), Double::sum );
        }
    }
}
