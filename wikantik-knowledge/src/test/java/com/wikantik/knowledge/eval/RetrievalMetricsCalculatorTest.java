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
package com.wikantik.knowledge.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RetrievalMetricsCalculatorTest {

    private static final double EPS = 1e-9;

    @Test
    void perfectRetrievalGivesPerfectScores() {
        final List< String > predicted = List.of( "A", "B", "C" );
        final Set< String > expected   = Set.of( "A", "B", "C" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        assertEquals( 1.0, s.ndcgAt5(),    EPS );
        assertEquals( 1.0, s.ndcgAt10(),   EPS );
        assertEquals( 1.0, s.recallAt20(), EPS );
        assertEquals( 1.0, s.mrr(),        EPS );
    }

    @Test
    void completeMissGivesZero() {
        final List< String > predicted = List.of( "X", "Y", "Z" );
        final Set< String > expected   = Set.of( "A" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        assertEquals( 0.0, s.ndcgAt5(),    EPS );
        assertEquals( 0.0, s.ndcgAt10(),   EPS );
        assertEquals( 0.0, s.recallAt20(), EPS );
        assertEquals( 0.0, s.mrr(),        EPS );
    }

    @Test
    void singleHitAtRankOneIsMrrOne() {
        final List< String > predicted = List.of( "A", "X", "Y" );
        final Set< String > expected   = Set.of( "A" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        assertEquals( 1.0, s.mrr(), EPS );
        // Ideal nDCG with one expected at rank 1 is also 1.
        assertEquals( 1.0, s.ndcgAt5(),  EPS );
        assertEquals( 1.0, s.ndcgAt10(), EPS );
        assertEquals( 1.0, s.recallAt20(), EPS );
    }

    @Test
    void singleHitAtRankThreeYieldsKnownScores() {
        final List< String > predicted = List.of( "X", "Y", "A", "Z", "Q" );
        final Set< String > expected   = Set.of( "A" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        // MRR = 1/3
        assertEquals( 1.0 / 3.0, s.mrr(), EPS );
        // nDCG@5: DCG = 1/log2(3+1) = 1/2; ideal DCG (1 relevant doc) = 1.
        assertEquals( 0.5, s.ndcgAt5(), EPS );
        assertEquals( 0.5, s.ndcgAt10(), EPS );
        assertEquals( 1.0, s.recallAt20(), EPS );
    }

    @Test
    void hitOutsideTopFiveIsZeroAtFive() {
        // Predict 6 items; expected hit is at rank 6.
        final List< String > predicted = List.of( "X", "Y", "Z", "Q", "R", "A" );
        final Set< String > expected   = Set.of( "A" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        assertEquals( 0.0, s.ndcgAt5(), EPS );
        // nDCG@10: 1/log2(6+1) = 1/log2(7); ideal = 1.
        assertEquals( 1.0 / ( Math.log( 7 ) / Math.log( 2 ) ), s.ndcgAt10(), EPS );
        assertEquals( 1.0, s.recallAt20(), EPS );
        assertEquals( 1.0 / 6.0, s.mrr(), EPS );
    }

    @Test
    void recallAt20CountsAllExpectedFoundInTop20() {
        // 25 predictions, 3 expected. Two of them are in top-20, one is at rank 25.
        final java.util.ArrayList< String > pred = new java.util.ArrayList<>();
        for ( int i = 0; i < 25; i++ ) pred.add( "doc-" + i );
        pred.set( 4,  "A" );
        pred.set( 12, "B" );
        pred.set( 24, "C" );
        final Set< String > expected = Set.of( "A", "B", "C" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( pred, expected );
        assertEquals( 2.0 / 3.0, s.recallAt20(), EPS );
    }

    @Test
    void emptyExpectedReturnsNullScore() {
        final List< String > predicted = List.of( "A", "B" );
        final Set< String > expected   = Set.of();
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        // Empty expected => not scoreable.
        assertNull( s );
    }

    @Test
    void emptyPredictedIsAllZeros() {
        final List< String > predicted = List.of();
        final Set< String > expected   = Set.of( "A" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        assertEquals( 0.0, s.ndcgAt5(), EPS );
        assertEquals( 0.0, s.ndcgAt10(), EPS );
        assertEquals( 0.0, s.recallAt20(), EPS );
        assertEquals( 0.0, s.mrr(), EPS );
    }

    @Test
    void aggregateAveragesQueryScoresIgnoringNulls() {
        final RetrievalMetricsCalculator.QueryScore q1 =
            new RetrievalMetricsCalculator.QueryScore( 1.0, 1.0, 1.0, 1.0 );
        final RetrievalMetricsCalculator.QueryScore q2 =
            new RetrievalMetricsCalculator.QueryScore( 0.0, 0.0, 0.0, 0.0 );
        final RetrievalMetricsCalculator.Aggregate agg =
            RetrievalMetricsCalculator.aggregate( java.util.Arrays.asList( q1, null, q2 ) );
        assertEquals( 0.5, agg.ndcgAt5(),    EPS );
        assertEquals( 0.5, agg.ndcgAt10(),   EPS );
        assertEquals( 0.5, agg.recallAt20(), EPS );
        assertEquals( 0.5, agg.mrr(),        EPS );
        assertEquals( 2,   agg.queriesScored() );
    }

    @Test
    void aggregateOfAllNullsIsEmptyAggregate() {
        final RetrievalMetricsCalculator.Aggregate agg =
            RetrievalMetricsCalculator.aggregate( java.util.Arrays.asList( null, null ) );
        assertNull( agg.ndcgAt5() );
        assertNull( agg.ndcgAt10() );
        assertNull( agg.recallAt20() );
        assertNull( agg.mrr() );
        assertEquals( 0, agg.queriesScored() );
    }

    @Test
    void duplicatesInPredictedDoNotInflateRecall() {
        // Same expected hit appearing twice in predictions counts once.
        final List< String > predicted = List.of( "A", "A", "X", "Y" );
        final Set< String > expected   = Set.of( "A", "B" );
        final RetrievalMetricsCalculator.QueryScore s =
            RetrievalMetricsCalculator.score( predicted, expected );
        assertEquals( 0.5, s.recallAt20(), EPS );
    }
}
