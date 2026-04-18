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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses a list of per-chunk scores into a ranked list of pages using a
 * configurable {@link PageAggregation} strategy. Input chunks need not be
 * pre-sorted; this class sorts per-page scores descending before delegating
 * to the aggregation enum.
 */
public final class PageAggregator {

    public List< ScoredPage > aggregate( final List< ScoredChunk > topChunks,
                                         final PageAggregation strategy ) {
        if( strategy == null ) throw new IllegalArgumentException( "strategy must not be null" );
        if( topChunks == null || topChunks.isEmpty() ) return List.of();

        final Map< String, List< Double > > perPage = new LinkedHashMap<>();
        for( final ScoredChunk c : topChunks ) {
            perPage.computeIfAbsent( c.pageName(), p -> new ArrayList<>() ).add( c.score() );
        }
        final List< ScoredPage > out = new ArrayList<>( perPage.size() );
        for( final Map.Entry< String, List< Double > > e : perPage.entrySet() ) {
            final List< Double > scores = e.getValue();
            scores.sort( Collections.reverseOrder() );
            out.add( new ScoredPage( e.getKey(), strategy.aggregate( scores ) ) );
        }
        out.sort( Comparator.comparingDouble( ScoredPage::score ).reversed() );
        return out;
    }
}
