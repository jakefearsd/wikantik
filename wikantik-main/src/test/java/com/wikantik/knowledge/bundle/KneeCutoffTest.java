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
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KneeCutoffTest {

    private static CandidateSection sec( final double score ) {
        return new CandidateSection( "p" + score, List.of( "H" ), "t", score );
    }

    // topSimilarity is the top dense cosine (== denseScore of the first candidate for the dense path).
    private static int n( final KneeCutoff k, final List< CandidateSection > s, final int max ) {
        return k.effectiveN( s, s.isEmpty() ? -1.0 : s.get( 0 ).denseScore(), max );
    }

    @Test
    void disabled_alwaysReturnsMaxSections() {
        final var s = List.of( sec( 0.9 ), sec( 0.1 ), sec( 0.05 ) );
        assertEquals( 12, n( KneeCutoff.disabled(), s, 12 ) );
    }

    @Test
    void thinQuery_cutsBelowTheRetainLine() {
        // top 0.90; retainRatio 0.5 -> keep >= 0.45. Scores 0.90,0.80,0.20,0.10 -> keep 2.
        final var s = List.of( sec( 0.90 ), sec( 0.80 ), sec( 0.20 ), sec( 0.10 ) );
        assertEquals( 2, n( KneeCutoff.of( true, 0.5 ), s, 12 ) );
    }

    @Test
    void flatStrongQuery_keepsAllUpToMax() {
        // all within ratio of the top -> keep all, capped at maxSections.
        final var s = List.of( sec( 0.90 ), sec( 0.88 ), sec( 0.85 ), sec( 0.82 ) );
        assertEquals( 4, n( KneeCutoff.of( true, 0.5 ), s, 12 ) );
        assertEquals( 3, n( KneeCutoff.of( true, 0.5 ), s, 3 ), "never exceeds maxSections" );
    }

    @Test
    void alwaysKeepsAtLeastTheTop() {
        // even a lone strong section above a cliff keeps >= 1.
        final var s = List.of( sec( 0.90 ), sec( 0.10 ), sec( 0.05 ) );
        assertEquals( 1, n( KneeCutoff.of( true, 0.5 ), s, 12 ) );
    }

    @Test
    void noPerSectionCosine_topSimilarityNegative_returnsMaxSections() {
        // page-gated path: topSimilarity -1 -> knee cannot apply -> maxSections.
        final var s = List.of( sec( 0.0 ), sec( 0.0 ) );
        assertEquals( 12, KneeCutoff.of( true, 0.5 ).effectiveN( s, -1.0, 12 ) );
    }

    @Test
    void emptyCandidates_returnsMaxSections() {
        assertEquals( 12, KneeCutoff.of( true, 0.5 ).effectiveN( List.of(), -1.0, 12 ) );
    }

    @Test
    void retainRatioClampedToDefault_whenOutOfRange() {
        // ratio 2.0 is invalid -> default 0.5; scores 0.90,0.80,0.20 -> keep 2 (>=0.45).
        final var s = List.of( sec( 0.90 ), sec( 0.80 ), sec( 0.20 ) );
        assertEquals( 2, n( KneeCutoff.of( true, 2.0 ), s, 12 ) );
    }

    @Test
    void countsAllAboveLine_notJustLeadingRun_forFusedOrderInput() {
        // fused (non-descending) order: 0.90 (above), 0.20 (below), 0.80 (above); top 0.90, ratio 0.5 -> line 0.45.
        // leading-run logic would stop at 0.20 -> 1; correct count-above-line -> 2.
        final var s = List.of( sec( 0.90 ), sec( 0.20 ), sec( 0.80 ) );
        assertEquals( 2, KneeCutoff.of( true, 0.5 ).effectiveN( s, 0.90, 12 ) );
    }
}
