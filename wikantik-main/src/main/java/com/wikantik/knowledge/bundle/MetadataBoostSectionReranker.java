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

import com.wikantik.api.pagegraph.Confidence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Bounded confidence tie-breaker: reorders the top-{@code window} candidates by
 *  {@code denseScore · (1 + factor · sign(confidence))} (AUTHORITATIVE +1, PROVISIONAL 0, STALE −1),
 *  so a more-verified section wins a near-tie without overriding a real relevance gap. Reorder-only —
 *  never drops — and returns the input unchanged when the lookup is null, factor is 0, there is ≤1
 *  section, or anything throws. */
final class MetadataBoostSectionReranker implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( MetadataBoostSectionReranker.class );

    private final Function< String, Confidence > confidenceOf;
    private final double factor;
    private final int window;

    MetadataBoostSectionReranker( final Function< String, Confidence > confidenceOf,
                                  final double factor, final int window ) {
        this.confidenceOf = confidenceOf;
        this.factor = ( factor < 0.0 || factor > 0.5 ) ? 0.05 : factor;
        this.window = window > 0 ? window : 24;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null ) return List.of();
        if ( confidenceOf == null || factor == 0.0 || sections.size() <= 1 ) return sections;
        try {
            final int w = Math.min( window, sections.size() );
            final List< CandidateSection > head = new ArrayList<>( sections.subList( 0, w ) );
            // stable sort by boosted score descending (List.sort is stable → ties keep input order)
            head.sort( ( a, b ) -> Double.compare( boosted( b ), boosted( a ) ) );
            final List< CandidateSection > out = new ArrayList<>( sections.size() );
            out.addAll( head );
            out.addAll( sections.subList( w, sections.size() ) );  // beyond the window: untouched
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Metadata-boost rerank failed ({}); using prior order", e.getMessage() );
            return sections;
        }
    }

    private double boosted( final CandidateSection c ) {
        return c.denseScore() * ( 1.0 + factor * sign( confidenceOf.apply( c.slug() ) ) );
    }

    private static int sign( final Confidence conf ) {
        if ( conf == Confidence.AUTHORITATIVE ) return 1;
        if ( conf == Confidence.STALE ) return -1;
        return 0;  // PROVISIONAL or null
    }
}
