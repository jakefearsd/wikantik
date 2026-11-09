/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge.retrieval;

import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.search.embedding.PageEmbeddingSimilarity;
import com.wikantik.search.embedding.PageEmbeddingSimilarity.ScoredPage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds pages related to a candidate by embedding-centroid similarity
 * ({@link PageEmbeddingSimilarity}), for
 * {@link com.wikantik.knowledge.DefaultContextRetrievalService}. Degrades to an empty result when the
 * similarity service is absent, has no embeddings yet, or fails.
 *
 * <p>The {@code accept} predicate is applied inside the similarity ranking, so related names are never
 * pages that no longer exist. It is caller-agnostic: each consumer still filters by the caller's view
 * permission before showing {@code relatedPages}.</p>
 */
public final class RelatedPagesFinder {

    private static final Logger LOG = LogManager.getLogger( RelatedPagesFinder.class );

    private static final int RELATED_PAGES_LIMIT = 5;

    private final PageEmbeddingSimilarity similarity;
    private final Predicate< String > accept;

    /**
     * @param similarity nullable; null yields no related pages
     * @param accept     candidate filter (typically "page still exists"); null accepts everything
     */
    public RelatedPagesFinder( final PageEmbeddingSimilarity similarity, final Predicate< String > accept ) {
        this.similarity = similarity;
        this.accept = accept == null ? n -> true : accept;
    }

    /** Pages most similar in content to {@code pageName}, best first; empty on any failure. */
    public List< RelatedPage > fetchRelatedPages( final String pageName ) {
        if ( similarity == null ) return List.of();
        final List< ScoredPage > matches;
        try {
            matches = similarity.similarTo( pageName, RELATED_PAGES_LIMIT, accept );
        } catch ( final RuntimeException e ) {
            LOG.warn( "PageEmbeddingSimilarity.similarTo failed for '{}' — returning no related pages: {}",
                pageName, e.getMessage() );
            return List.of();
        }
        if ( matches == null || matches.isEmpty() ) return List.of();
        final List< RelatedPage > out = new ArrayList<>( matches.size() );
        for ( final ScoredPage m : matches ) {
            out.add( new RelatedPage( m.name(), String.format( Locale.ROOT, "similar content (%.2f)", m.score() ) ) );
        }
        return out;
    }

    /** Per-page map of {@link #fetchRelatedPages}; the lookup is in-memory so there is no round-trip to batch. */
    public Map< String, List< RelatedPage > > fetchRelatedPagesBatch( final List< String > pageNames ) {
        if ( similarity == null || pageNames == null || pageNames.isEmpty() ) {
            return Map.of();
        }
        final Map< String, List< RelatedPage > > out = new LinkedHashMap<>();
        for ( final String name : pageNames ) {
            out.put( name, fetchRelatedPages( name ) );
        }
        return out;
    }
}
