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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Maximal Marginal Relevance reorder: trades query-relevance (min-max normalized {@code denseScore})
 *  against novelty (1 − max lexical cosine to already-selected sections), λ balancing the two. The
 *  first pick is the highest-relevance section, so the top result is preserved. Reorders only —
 *  never drops a section — and returns the input unchanged on {@code ≤ 1} section or any failure. */
final class MmrSectionReranker implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( MmrSectionReranker.class );

    private final double lambda;
    private final BiFunction< Map< String, Integer >, Map< String, Integer >, Double > similarity;

    MmrSectionReranker( final double lambda ) {
        this( lambda, LexicalSimilarity::cosine );
    }

    MmrSectionReranker( final double lambda,
                        final BiFunction< Map< String, Integer >, Map< String, Integer >, Double > similarity ) {
        this.lambda = ( lambda < 0.0 || lambda > 1.0 ) ? 0.7 : lambda;
        this.similarity = similarity;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null ) return List.of();
        if ( sections.size() <= 1 ) return sections;
        try {
            final int n = sections.size();
            final double[] rel = normalizedRelevance( sections );
            final List< Map< String, Integer > > vecs = new ArrayList<>( n );      // precompute once
            for ( final CandidateSection c : sections ) vecs.add( LexicalSimilarity.vector( c.text() ) );

            final boolean[] picked = new boolean[ n ];
            final List< CandidateSection > out = new ArrayList<>( n );
            final double[] maxSimToSelected = new double[ n ];

            for ( int step = 0; step < n; step++ ) {
                int best = -1;
                double bestScore = Double.NEGATIVE_INFINITY;
                for ( int i = 0; i < n; i++ ) {
                    if ( picked[ i ] ) continue;
                    final double mmr = lambda * rel[ i ] - ( 1.0 - lambda ) * maxSimToSelected[ i ];
                    if ( mmr > bestScore ) { bestScore = mmr; best = i; }
                }
                picked[ best ] = true;
                out.add( sections.get( best ) );
                for ( int i = 0; i < n; i++ ) {
                    if ( picked[ i ] ) continue;
                    maxSimToSelected[ i ] = Math.max( maxSimToSelected[ i ],
                        similarity.apply( vecs.get( i ), vecs.get( best ) ) );
                }
            }
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "MMR rerank failed ({}); using prior order", e.getMessage() );
            return sections;
        }
    }

    /** Min-max normalize denseScore to [0,1]; uniform 1.0 when all scores are equal (no signal). */
    private static double[] normalizedRelevance( final List< CandidateSection > s ) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for ( final CandidateSection c : s ) {
            min = Math.min( min, c.denseScore() );
            max = Math.max( max, c.denseScore() );
        }
        final double range = max - min;
        final double[] rel = new double[ s.size() ];
        for ( int i = 0; i < s.size(); i++ ) {
            rel[ i ] = range <= 0.0 ? 1.0 : ( s.get( i ).denseScore() - min ) / range;
        }
        return rel;
    }
}
