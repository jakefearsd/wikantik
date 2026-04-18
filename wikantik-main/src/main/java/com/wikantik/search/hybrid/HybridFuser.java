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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted Reciprocal Rank Fusion for exactly two ranked lists (BM25 and
 * dense). For each list the item at rank {@code r} (1-based) contributes
 * {@code weight / (k + r)} to its fused score. Items missing from a list
 * contribute nothing from that list; duplicates across lists sum. When
 * {@code truncate > 0}, only the first {@code truncate} items of each list
 * contribute — ranks beyond that are ignored entirely.
 */
public final class HybridFuser {

    private final int k;
    private final double bm25Weight;
    private final double denseWeight;
    private final int truncate;

    public HybridFuser( final int k, final double bm25Weight, final double denseWeight, final int truncate ) {
        if( k <= 0 ) throw new IllegalArgumentException( "k must be positive" );
        this.k = k;
        this.bm25Weight = bm25Weight;
        this.denseWeight = denseWeight;
        this.truncate = truncate;
    }

    public List< String > fuse( final List< String > bm25Ranked, final List< String > denseRanked ) {
        final Map< String, Double > scores = new HashMap<>();
        contribute( scores, bm25Ranked, bm25Weight );
        contribute( scores, denseRanked, denseWeight );
        final List< Map.Entry< String, Double > > entries = new ArrayList<>( scores.entrySet() );
        entries.sort( Comparator.comparingDouble( Map.Entry< String, Double >::getValue ).reversed() );
        final List< String > out = new ArrayList<>( entries.size() );
        for( final Map.Entry< String, Double > e : entries ) out.add( e.getKey() );
        return out;
    }

    private void contribute( final Map< String, Double > scores, final List< String > ranked, final double weight ) {
        if( ranked == null ) return;
        final int limit = truncate > 0 ? Math.min( truncate, ranked.size() ) : ranked.size();
        for( int rank = 0; rank < limit; rank++ ) {
            final String id = ranked.get( rank );
            if( id == null ) continue;
            scores.merge( id, weight / ( k + ( rank + 1 ) ), Double::sum );
        }
    }
}
