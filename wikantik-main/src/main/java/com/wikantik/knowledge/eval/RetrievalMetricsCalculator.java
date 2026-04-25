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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-function retrieval metrics — nDCG@K, Recall@20, MRR — over a
 * predicted ordered list of canonical_ids and a set of relevant
 * (expected) canonical_ids. Binary relevance only: each expected id
 * has gain 1, every other prediction has gain 0.
 *
 * <p>Empty expected sets are treated as "not scoreable" — the per-query
 * {@link #score(List, Set)} call returns {@code null} so the aggregator
 * can drop the query rather than fold a synthetic zero into the
 * average.</p>
 */
public final class RetrievalMetricsCalculator {

    private RetrievalMetricsCalculator() {}

    /** Per-query metric bundle. {@code null} fields propagate "no score". */
    public record QueryScore( double ndcgAt5, double ndcgAt10, double recallAt20, double mrr ) {}

    /**
     * Aggregate over a list of {@link QueryScore} (each may be {@code null}
     * for a non-scoreable query). Aggregate fields are {@code Double} so the
     * "no scoreable queries" case surfaces as {@code null} rather than NaN.
     */
    public record Aggregate(
        Double ndcgAt5,
        Double ndcgAt10,
        Double recallAt20,
        Double mrr,
        int queriesScored
    ) {}

    /**
     * Compute per-query metrics. Returns {@code null} when {@code expected}
     * is empty so the aggregator can skip the query.
     */
    public static QueryScore score( final List< String > predicted, final Set< String > expected ) {
        if ( expected == null || expected.isEmpty() ) return null;
        final List< String > pred = predicted == null ? List.of() : predicted;

        final double ndcg5  = ndcg( pred, expected, 5 );
        final double ndcg10 = ndcg( pred, expected, 10 );
        final double recall = recall( pred, expected, 20 );
        final double mrr    = mrr( pred, expected );
        return new QueryScore( ndcg5, ndcg10, recall, mrr );
    }

    /** Mean per-metric over the non-null query scores. */
    public static Aggregate aggregate( final List< QueryScore > perQuery ) {
        double n5 = 0, n10 = 0, r20 = 0, mrr = 0;
        int count = 0;
        if ( perQuery != null ) {
            for ( final QueryScore q : perQuery ) {
                if ( q == null ) continue;
                n5  += q.ndcgAt5();
                n10 += q.ndcgAt10();
                r20 += q.recallAt20();
                mrr += q.mrr();
                count++;
            }
        }
        if ( count == 0 ) {
            return new Aggregate( null, null, null, null, 0 );
        }
        return new Aggregate( n5 / count, n10 / count, r20 / count, mrr / count, count );
    }

    // ---- internals ----

    private static double ndcg( final List< String > pred, final Set< String > expected, final int k ) {
        final int relevantCount = expected.size();
        if ( relevantCount == 0 || pred.isEmpty() ) return 0.0;
        double dcg = 0.0;
        final int cap = Math.min( k, pred.size() );
        for ( int i = 0; i < cap; i++ ) {
            if ( expected.contains( pred.get( i ) ) ) {
                dcg += 1.0 / log2( i + 2 );          // i+1 is rank, denominator is log2(rank+1)
            }
        }
        // Ideal DCG: place min(relevantCount, k) ones in the top-k slots.
        final int idealHits = Math.min( relevantCount, k );
        double idcg = 0.0;
        for ( int i = 0; i < idealHits; i++ ) {
            idcg += 1.0 / log2( i + 2 );
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double recall( final List< String > pred, final Set< String > expected, final int k ) {
        final int relevantCount = expected.size();
        if ( relevantCount == 0 ) return 0.0;
        final int cap = Math.min( k, pred.size() );
        final Set< String > seen = new HashSet<>();
        for ( int i = 0; i < cap; i++ ) {
            final String id = pred.get( i );
            if ( expected.contains( id ) ) {
                seen.add( id );
            }
        }
        return (double) seen.size() / relevantCount;
    }

    private static double mrr( final List< String > pred, final Set< String > expected ) {
        for ( int i = 0; i < pred.size(); i++ ) {
            if ( expected.contains( pred.get( i ) ) ) {
                return 1.0 / ( i + 1 );
            }
        }
        return 0.0;
    }

    private static double log2( final int x ) {
        return Math.log( x ) / Math.log( 2 );
    }
}
