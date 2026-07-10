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

/** Bounded confidence tie-breaker: boosts the top-{@code window} candidates' INCOMING RANK
 *  POSITION (not their {@code denseScore}), by computing {@code adjusted_key[i] = i − positions ·
 *  sign(confidence)} (AUTHORITATIVE +1, PROVISIONAL/null 0, STALE −1) for i = index within the
 *  window, then stable-sorting the window ascending by that key. An AUTHORITATIVE item overtakes
 *  a STALE one only within {@code 2 · positions} positions of it — it cannot leapfrog an arbitrarily
 *  large gap. Because it never reads {@code denseScore}, it composes cleanly with any upstream
 *  ordering (e.g. MMR) instead of nullifying it. Reorder-only — never drops — and returns the
 *  input unchanged when the lookup is null, {@code positions} is 0, there is ≤1 section, or
 *  anything throws. */
final class MetadataBoostSectionReranker implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( MetadataBoostSectionReranker.class );

    private final Function< String, Confidence > confidenceOf;
    private final double positions;
    private final int window;

    MetadataBoostSectionReranker( final Function< String, Confidence > confidenceOf,
                                  final double positions, final int window ) {
        this.confidenceOf = confidenceOf;
        this.positions = ( positions < 0.0 || positions > 10.0 ) ? 1.5 : positions;
        this.window = window > 0 ? window : 24;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null ) return List.of();
        if ( confidenceOf == null || positions == 0.0 || sections.size() <= 1 ) return sections;
        try {
            final int w = Math.min( window, sections.size() );
            // decorate: adjusted rank key = incoming index − positions·sign(confidence), computed once per element
            final List< Ranked > decorated = new ArrayList<>( w );
            for ( int i = 0; i < w; i++ ) {
                final CandidateSection c = sections.get( i );
                decorated.add( new Ranked( c, i - positions * sign( confidenceOf.apply( c.slug() ) ) ) );
            }
            decorated.sort( ( a, b ) -> Double.compare( a.key(), b.key() ) );  // ascending; stable → ties keep input order
            final List< CandidateSection > out = new ArrayList<>( sections.size() );
            for ( final Ranked r : decorated ) out.add( r.section() );
            out.addAll( sections.subList( w, sections.size() ) );  // beyond the window: untouched
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Metadata-boost rerank failed ({}); using prior order", e.getMessage() );
            return sections;
        }
    }

    private static int sign( final Confidence conf ) {
        if ( conf == Confidence.AUTHORITATIVE ) return 1;
        if ( conf == Confidence.STALE ) return -1;
        return 0;  // PROVISIONAL or null
    }

    /** A candidate decorated with its adjusted rank key (lower = earlier). */
    private record Ranked( CandidateSection section, double key ) {}
}
