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

import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.search.SearchResult;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.ScoredChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Assembles the per-page contributing-chunk lists surfaced alongside each
 * {@link com.wikantik.api.knowledge.RetrievedPage} in
 * {@link com.wikantik.knowledge.DefaultContextRetrievalService}. Extracted
 * verbatim from that class (Task 4 decomposition) — no change to the dense
 * scan / reuse guards or chunk shaping.
 *
 * <p>Needs {@link HybridSearchService} in addition to the {@link ChunkVectorIndex}
 * and {@link ContentChunkRepository} named in the extraction plan: the
 * embedding-reuse guard (Guard #2 below) falls back to
 * {@link HybridSearchService#prefetchQueryEmbedding} when the first dense
 * scan's chunks aren't already sufficient.</p>
 */
public final class ContributingChunkAssembler {

    private static final Logger LOG = LogManager.getLogger( ContributingChunkAssembler.class );

    private final ChunkVectorIndex chunkIndex;
    private final ContentChunkRepository chunkRepo;
    private final HybridSearchService hybridSearch;

    public ContributingChunkAssembler(
            final ChunkVectorIndex chunkIndex,
            final ContentChunkRepository chunkRepo,
            final HybridSearchService hybridSearch ) {
        this.chunkIndex = chunkIndex;
        this.chunkRepo = chunkRepo;
        this.hybridSearch = hybridSearch;
    }

    public Map< String, List< RetrievedChunk > > fetchContributingChunks(
            final String query,
            final List< SearchResult > ordered,
            final int chunksPerPage,
            final Optional< List< ScoredChunk > > reusableChunks ) {
        // Guard #1 — short-circuit when the caller doesn't want contributing
        // chunks. Skips both the embedding fetch AND the brute-force scan.
        if ( chunksPerPage <= 0 ) return Map.of();

        if ( chunkIndex == null || !chunkIndex.isReady()
                || chunkRepo == null || hybridSearch == null ) {
            return Map.of();
        }

        // Guard #2 — reuse the chunks from the first dense scan when they're
        // already sufficient (chunks count >= pages × chunksPerPage). Skips
        // the second topKChunks scan entirely.
        final int neededChunks = ordered.size() * chunksPerPage;
        final List< ScoredChunk > topChunks;
        if ( reusableChunks.isPresent() && reusableChunks.get().size() >= neededChunks
                && neededChunks > 0 ) {
            topChunks = reusableChunks.get();
        } else {
            final Optional< float[] > embedding;
            try {
                embedding = hybridSearch.prefetchQueryEmbedding( query ).get( 2500, TimeUnit.MILLISECONDS );
            } catch ( final Exception e ) {
                LOG.info( "Embedding fetch for contributing-chunk scoring failed — continuing with unscored chunks: {}",
                    e.getMessage() );
                return Map.of();
            }
            if ( embedding.isEmpty() ) return Map.of();
            topChunks = chunkIndex.topKChunks( embedding.get(), 200 );
        }
        if ( topChunks.isEmpty() ) return Map.of();

        final Map< String, List< ScoredChunk > > grouped =
            groupChunksByInterestingPage( topChunks, interestingPageNames( ordered ), chunksPerPage );
        return shapeChunkOutput( grouped );
    }

    private static Set< String > interestingPageNames( final List< SearchResult > ordered ) {
        final Set< String > out = new HashSet<>();
        for ( final SearchResult sr : ordered ) {
            if ( sr.getPage() != null ) out.add( sr.getPage().getName() );
        }
        return out;
    }

    /**
     * Groups scored chunks by page name, pre-truncating each per-page list to
     * {@code chunksPerPage} so downstream callers don't re-slice.
     */
    private static Map< String, List< ScoredChunk > > groupChunksByInterestingPage(
            final List< ScoredChunk > topChunks,
            final Set< String > interestingPages,
            final int chunksPerPage ) {
        final Map< String, List< ScoredChunk > > grouped = new LinkedHashMap<>();
        for ( final ScoredChunk sc : topChunks ) {
            if ( !interestingPages.contains( sc.pageName() ) ) continue;
            final List< ScoredChunk > list = grouped.computeIfAbsent(
                sc.pageName(), k -> new ArrayList<>() );
            if ( list.size() < chunksPerPage ) list.add( sc );
        }
        return grouped;
    }

    /** Loads chunk bodies via {@link ContentChunkRepository} and shapes per-page RetrievedChunk lists. */
    private Map< String, List< RetrievedChunk > > shapeChunkOutput(
            final Map< String, List< ScoredChunk > > grouped ) {
        final List< UUID > allIds = new ArrayList<>();
        for ( final List< ScoredChunk > list : grouped.values() ) {
            for ( final ScoredChunk sc : list ) allIds.add( sc.chunkId() );
        }
        final Map< UUID, ContentChunkRepository.MentionableChunk > byId = new HashMap<>();
        for ( final ContentChunkRepository.MentionableChunk mc : chunkRepo.findByIds( allIds ) ) {
            byId.put( mc.id(), mc );
        }
        final Map< String, List< RetrievedChunk > > out = new LinkedHashMap<>();
        for ( final Map.Entry< String, List< ScoredChunk > > entry : grouped.entrySet() ) {
            final List< RetrievedChunk > pageChunks = new ArrayList<>( entry.getValue().size() );
            for ( final ScoredChunk sc : entry.getValue() ) {
                final ContentChunkRepository.MentionableChunk mc = byId.get( sc.chunkId() );
                if ( mc != null ) {
                    pageChunks.add( new RetrievedChunk(
                        mc.headingPath(), mc.text(), sc.score(), List.of() ) );
                }
            }
            out.put( entry.getKey(), pageChunks );
        }
        return out;
    }
}
