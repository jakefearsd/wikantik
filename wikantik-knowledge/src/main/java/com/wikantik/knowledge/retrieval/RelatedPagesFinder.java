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
import com.wikantik.knowledge.MentionIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds pages related to a candidate via shared extractor mentions, for
 * {@link com.wikantik.knowledge.DefaultContextRetrievalService}. Extracted
 * verbatim from that class (Task 4 decomposition) — no change to retrieval
 * ordering, scoring, or dedup behavior.
 */
public final class RelatedPagesFinder {

    private static final Logger LOG = LogManager.getLogger( RelatedPagesFinder.class );

    private static final int RELATED_PAGES_LIMIT = 5;
    private static final int REASON_ENTITY_LIMIT = 3;

    private final MentionIndex mentionIndex;

    public RelatedPagesFinder( final MentionIndex mentionIndex ) {
        this.mentionIndex = mentionIndex;
    }

    /**
     * Finds pages related to {@code pageName} via shared extractor mentions.
     * Uses {@link MentionIndex#findRelatedPages} which walks
     * {@code chunk_entity_mentions} directly — no reliance on a KG node
     * keyed by page name (those stopped existing after the cycle-6
     * GraphProjector retirement).
     *
     * <p>The {@code reason} string lists the top shared entity names, so
     * the agent sees concrete evidence ("shared entities: BM25, Qwen3")
     * instead of an opaque similarity score.</p>
     */
    public List< RelatedPage > fetchRelatedPages( final String pageName ) {
        if ( mentionIndex == null ) return List.of();
        final List< MentionIndex.RelatedByMention > matches;
        try {
            matches = mentionIndex.findRelatedPages( pageName, RELATED_PAGES_LIMIT );
        } catch ( final RuntimeException e ) {
            LOG.info( "MentionIndex.findRelatedPages failed for '{}' — returning no related pages: {}",
                pageName, e.getMessage() );
            return List.of();
        }
        if ( matches.isEmpty() ) return List.of();
        final List< RelatedPage > out = new ArrayList<>( matches.size() );
        for ( final MentionIndex.RelatedByMention m : matches ) {
            out.add( new RelatedPage( m.pageName(), describeSharedEntities( m ) ) );
        }
        return out;
    }

    /**
     * Batched sibling of {@link #fetchRelatedPages}. Returns a per-page map;
     * missing input names map to {@link List#of()} (callers should use
     * {@code Map::getOrDefault} with the empty list). One DBCP acquire and
     * one PreparedStatement preparation across the N inputs — replaces the
     * N+1 pattern in {@code DefaultContextRetrievalService.retrieve}.
     */
    public Map< String, List< RelatedPage > > fetchRelatedPagesBatch(
            final List< String > pageNames ) {
        if ( mentionIndex == null || pageNames == null || pageNames.isEmpty() ) {
            return Map.of();
        }
        final Map< String, List< MentionIndex.RelatedByMention > > batched;
        try {
            batched = mentionIndex.findRelatedPagesBatch( pageNames, RELATED_PAGES_LIMIT );
        } catch ( final RuntimeException e ) {
            LOG.info( "MentionIndex.findRelatedPagesBatch failed for {} pages — returning empty: {}",
                pageNames.size(), e.getMessage() );
            return Map.of();
        }
        final Map< String, List< RelatedPage > > out = new LinkedHashMap<>();
        for ( final var entry : batched.entrySet() ) {
            final List< MentionIndex.RelatedByMention > matches = entry.getValue();
            if ( matches.isEmpty() ) {
                out.put( entry.getKey(), List.of() );
                continue;
            }
            final List< RelatedPage > shaped = new ArrayList<>( matches.size() );
            for ( final MentionIndex.RelatedByMention m : matches ) {
                shaped.add( new RelatedPage( m.pageName(), describeSharedEntities( m ) ) );
            }
            out.put( entry.getKey(), shaped );
        }
        return out;
    }

    private static String describeSharedEntities( final MentionIndex.RelatedByMention m ) {
        final List< String > names = m.sharedEntityNames();
        if ( names.isEmpty() ) {
            return "shared entities: " + m.sharedCount();
        }
        final int take = Math.min( names.size(), REASON_ENTITY_LIMIT );
        final String joined = String.join( ", ", names.subList( 0, take ) );
        final String more = names.size() > take ? " (+" + ( names.size() - take ) + " more)" : "";
        return "shared entities: " + joined + more;
    }
}
