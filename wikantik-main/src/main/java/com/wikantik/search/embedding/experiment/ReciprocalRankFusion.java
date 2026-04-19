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
package com.wikantik.search.embedding.experiment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion over page-level ranked lists. For each ranked list,
 * item at rank {@code r} (1-based) contributes {@code 1 / (k + r)} to its
 * fused score. Items missing from a list contribute nothing from that list.
 * Output is sorted by fused score descending.
 */
public final class ReciprocalRankFusion {

    /** RRF constant — Cormack et al. 2009 suggest 60 as a robust default. */
    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {}

    /** A single ranking of opaque string identifiers, best first. */
    public record Ranking( List< String > items ) {}

    public static List< String > fuse( final List< Ranking > rankings, final int k ) {
        final double[] weights = new double[ rankings.size() ];
        java.util.Arrays.fill( weights, 1.0 );
        return fuseWeighted( rankings, weights, k, 0 );
    }

    /**
     * Weighted RRF with an optional per-list truncation. If {@code truncate}
     * is positive, only the first {@code truncate} items of each ranking
     * contribute to fusion — ranks beyond that are ignored entirely (not even
     * given a tiny vote). Pass 0 (or negative) to consider every item.
     */
    public static List< String > fuseWeighted( final List< Ranking > rankings,
                                               final double[] weights,
                                               final int k,
                                               final int truncate ) {
        if( k <= 0 ) throw new IllegalArgumentException( "k must be positive" );
        if( weights.length != rankings.size() ) {
            throw new IllegalArgumentException( "weights must match rankings size" );
        }
        final Map< String, Double > scores = new HashMap<>();
        for( int li = 0; li < rankings.size(); li++ ) {
            final Ranking r = rankings.get( li );
            final double w = weights[ li ];
            final int limit = truncate > 0 ? Math.min( truncate, r.items().size() ) : r.items().size();
            for( int rank = 0; rank < limit; rank++ ) {
                final String id = r.items().get( rank );
                if( id == null ) continue;
                scores.merge( id, w / ( k + ( rank + 1 ) ), Double::sum );
            }
        }
        final List< Map.Entry< String, Double > > entries = new ArrayList<>( scores.entrySet() );
        entries.sort( Comparator.comparingDouble( Map.Entry< String, Double >::getValue ).reversed() );
        final List< String > out = new ArrayList<>( entries.size() );
        for( final Map.Entry< String, Double > e : entries ) out.add( e.getKey() );
        return out;
    }
}
