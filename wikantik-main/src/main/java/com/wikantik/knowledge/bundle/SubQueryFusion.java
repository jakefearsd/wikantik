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
 * Fuses per-sub-query {@link SectionCandidates} into one list, keyed by
 * {@code (slug, headingPath)}. Two strategies:
 *
 * <ul>
 *   <li>{@link Mode#RRF} — N-ary reciprocal-rank fusion: each input list contributes
 *       {@code 1/(rrfK + rank)} (1-based) to a section's fused score, summed across lists.
 *       Generalises {@link com.wikantik.search.hybrid.HybridFuser} to N lists. <b>Measured
 *       to HURT multi-hop recall</b> (2026-07-11, {@code baseline-notes.md}): score-summing
 *       lets a topic co-mentioned across several sub-queries outrank a gold section present
 *       in only one list, so it amplifies the majority topic and buries the minority side.</li>
 *   <li>{@link Mode#ROUND_ROBIN} — interleave by rank: rank-0 of every list, then rank-1 of
 *       every list, etc., deduping by key. This RESERVES per-sub-query position, so every
 *       list's top section lands within the first {@code #non-empty-lists} slots — the
 *       minority side of a comparative query survives the top-N cut instead of being crowded
 *       out. Targets the exact RRF failure mode above.</li>
 * </ul>
 *
 * <p>Both strategies keep the max {@code denseScore} per key, {@code topSimilarity} = max of
 * inputs, and {@code denseCosineScale} = true iff every non-empty input was cosine-scale.
 */
final class SubQueryFusion {

    /** Fusion strategy. See the class doc for the recall trade-off. */
    enum Mode { RRF, ROUND_ROBIN }

    private final double rrfK;
    private final Mode mode;

    SubQueryFusion( final double rrfK ) {
        this( rrfK, Mode.RRF );
    }

    SubQueryFusion( final double rrfK, final Mode mode ) {
        this.rrfK = rrfK > 0 ? rrfK : 60;
        this.mode = mode == null ? Mode.RRF : mode;
    }

    SectionCandidates fuse( final List< SectionCandidates > perQuery ) {
        if ( perQuery == null || perQuery.isEmpty() ) {
            return SectionCandidates.of( List.of(), -1.0 );
        }
        if ( perQuery.size() == 1 ) return perQuery.get( 0 );
        return mode == Mode.ROUND_ROBIN ? fuseRoundRobin( perQuery ) : fuseRrf( perQuery );
    }

    private SectionCandidates fuseRrf( final List< SectionCandidates > perQuery ) {
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

    /**
     * Rank-interleave: for rank = 0,1,2,… take that-rank section from each input list in
     * turn, skipping keys already emitted. Preserves per-list priority so every list's top
     * section is emitted before any list's second, guaranteeing minority-side representation.
     */
    private SectionCandidates fuseRoundRobin( final List< SectionCandidates > perQuery ) {
        final Map< SectionKey, CandidateSection > emitted = new LinkedHashMap<>();
        double topSim = -1.0;
        boolean cosineScale = true;
        boolean anyNonEmpty = false;
        int maxLen = 0;
        for ( final SectionCandidates cand : perQuery ) {
            if ( cand == null ) continue;
            topSim = Math.max( topSim, cand.topSimilarity() );
            if ( !cand.sections().isEmpty() ) {
                anyNonEmpty = true;
                if ( !cand.denseCosineScale() ) cosineScale = false;
                maxLen = Math.max( maxLen, cand.sections().size() );
            }
        }
        if ( !anyNonEmpty ) return SectionCandidates.of( List.of(), topSim, cosineScale );

        for ( int rank = 0; rank < maxLen; rank++ ) {
            for ( final SectionCandidates cand : perQuery ) {
                if ( cand == null || rank >= cand.sections().size() ) continue;
                final CandidateSection cs = cand.sections().get( rank );
                final SectionKey key = new SectionKey( cs.slug(), cs.headingPath() );
                // first placement wins position; keep the higher denseScore if seen again
                emitted.merge( key, cs, ( a, b ) -> a.denseScore() >= b.denseScore() ? a : b );
            }
        }
        return SectionCandidates.of( new ArrayList<>( emitted.values() ), topSim, cosineScale );
    }
}
