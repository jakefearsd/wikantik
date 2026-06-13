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

import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups each page's contributing chunks into sections (best chunk per heading-path) and keeps
 *  the top-S sections per page — the per-page shortlist the spike sweep validated. */
public final class SectionAssembler {

    private final int sectionsPerPage;

    public SectionAssembler( final int sectionsPerPage ) {
        if ( sectionsPerPage <= 0 ) {
            throw new IllegalArgumentException( "sectionsPerPage must be positive" );
        }
        this.sectionsPerPage = sectionsPerPage;
    }

    public List< CandidateSection > assemble( final RetrievalResult result ) {
        final List< CandidateSection > out = new ArrayList<>();
        for ( final RetrievedPage page : result.pages() ) {
            // heading-path -> best (score, text)
            final Map< List< String >, double[] > bestScore = new LinkedHashMap<>();
            final Map< List< String >, String > bestText = new LinkedHashMap<>();
            for ( final RetrievedChunk c : page.contributingChunks() ) {
                final List< String > key = c.headingPath();
                final double[] cur = bestScore.get( key );
                if ( cur == null || c.chunkScore() > cur[ 0 ] ) {
                    bestScore.put( key, new double[]{ c.chunkScore() } );
                    bestText.put( key, c.text() );
                }
            }
            bestScore.entrySet().stream()
                .sorted( Comparator.comparingDouble( ( Map.Entry< List< String >, double[] > e ) -> e.getValue()[ 0 ] ).reversed() )
                .limit( sectionsPerPage )
                .forEach( e -> out.add( new CandidateSection(
                    page.name(), e.getKey(), bestText.get( e.getKey() ), e.getValue()[ 0 ] ) ) );
        }
        return out;
    }
}
