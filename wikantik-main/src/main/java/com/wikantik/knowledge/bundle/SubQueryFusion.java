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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * N-ary reciprocal-rank fusion over per-sub-query {@link SectionCandidates}.
 * Each section is keyed by {@code (slug, headingPath)}; each input list
 * contributes {@code 1/(rrfK + rank)} (1-based rank) to a section's fused score,
 * summed across lists. Generalises {@link com.wikantik.search.hybrid.HybridFuser}
 * (which is hard-coded to two lists) to the N sub-queries a decomposition emits.
 */
final class SubQueryFusion {

    private final double rrfK;

    SubQueryFusion( final double rrfK ) {
        this.rrfK = rrfK > 0 ? rrfK : 60;
    }

    SectionCandidates fuse( final List< SectionCandidates > perQuery ) {
        if ( perQuery == null || perQuery.isEmpty() ) {
            return SectionCandidates.of( List.of(), -1.0 );
        }
        if ( perQuery.size() == 1 ) return perQuery.get( 0 );

        final Map< SectionKey, CandidateSection > bestByKey = new LinkedHashMap<>();
        final Map< SectionKey, Double > scoreByKey = new LinkedHashMap<>();
        double topSim = -1.0;
        boolean cosineScale = true;
        boolean anyNonEmpty = false;

        for ( final SectionCandidates cand : perQuery ) {
            if ( cand == null ) continue;
            topSim = Math.max( topSim, cand.topSimilarity() );
            final List< CandidateSection > list = cand.sections();
            if ( !list.isEmpty() ) {
                anyNonEmpty = true;
                if ( !cand.denseCosineScale() ) cosineScale = false;
            }
            for ( int rank = 0; rank < list.size(); rank++ ) {
                final CandidateSection cs = list.get( rank );
                final SectionKey key = new SectionKey( cs.slug(), cs.headingPath() );
                scoreByKey.merge( key, 1.0 / ( rrfK + rank + 1 ), Double::sum );
                bestByKey.merge( key, cs, ( a, b ) -> a.denseScore() >= b.denseScore() ? a : b );
            }
        }
        if ( !anyNonEmpty ) return SectionCandidates.of( List.of(), topSim, cosineScale );

        final List< CandidateSection > fused = new ArrayList<>( bestByKey.values() );
        fused.sort( ( a, b ) -> Double.compare(
            scoreByKey.get( new SectionKey( b.slug(), b.headingPath() ) ),
            scoreByKey.get( new SectionKey( a.slug(), a.headingPath() ) ) ) );
        return SectionCandidates.of( fused, topSim, cosineScale );
    }
}
