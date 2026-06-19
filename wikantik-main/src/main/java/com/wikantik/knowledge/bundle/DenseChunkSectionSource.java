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

import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository.MentionableChunk;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.ScoredChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Global dense-chunk candidate source: embeds the query, pulls the top-K nearest chunks
 * from the {@link ChunkVectorIndex} across the WHOLE corpus (no page pre-select),
 * hydrates them, and groups to sections by {@code (slug, heading-path)} keeping the
 * best-scoring chunk per section.
 *
 * <p>The 2026-06-14 measurement showed this global path realises the section-recall
 * ceiling (recall@12 ≈ 0.735) where the page-gated {@link RetrievalSectionSource}
 * only reaches ~0.685 — the hybrid page pre-select drops retrievable sections whose
 * page ranks outside the top-N. Degrades to an empty list (never throws) when the
 * embedder or index is unavailable, so the bundle surfaces an empty result rather
 * than failing.</p>
 */
public final class DenseChunkSectionSource implements SectionCandidateSource {

    private static final Logger LOG = LogManager.getLogger( DenseChunkSectionSource.class );

    private final QueryEmbedder embedder;
    private final ChunkVectorIndex index;
    private final ContentChunkRepository chunkRepo;
    private final int topK;

    public DenseChunkSectionSource( final QueryEmbedder embedder, final ChunkVectorIndex index,
                                    final ContentChunkRepository chunkRepo, final int topK ) {
        this.embedder = embedder;
        this.index = index;
        this.chunkRepo = chunkRepo;
        this.topK = topK > 0 ? topK : 300;
    }

    @Override
    public List< CandidateSection > candidates( final String query ) {
        final Optional< float[] > qv = embedder.embed( query );
        if ( qv.isEmpty() ) {
            LOG.warn( "Query embedding unavailable; dense-chunk bundle candidates empty for '{}'", query );
            return List.of();
        }
        final List< ScoredChunk > top = index.topKChunks( qv.get(), topK );
        if ( top.isEmpty() ) return List.of();

        final List< UUID > ids = new ArrayList<>( top.size() );
        for ( final ScoredChunk sc : top ) ids.add( sc.chunkId() );
        final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();
        for ( final MentionableChunk c : chunkRepo.findByIds( ids ) ) byId.put( c.id(), c );

        // Group to sections, keeping the best-scoring chunk per (slug, heading-path).
        // Max-score (not first-seen), so correctness does not depend on topKChunks ordering.
        final Map< SectionKey, CandidateSection > best = new LinkedHashMap<>();
        for ( final ScoredChunk sc : top ) {
            final MentionableChunk c = byId.get( sc.chunkId() );
            if ( c == null ) continue;
            final SectionKey k = new SectionKey( c.pageName(), c.headingPath() );
            final CandidateSection cur = best.get( k );
            if ( cur == null || sc.score() > cur.denseScore() ) {
                best.put( k, new CandidateSection( c.pageName(), c.headingPath(), c.text(), sc.score() ) );
            }
        }
        final List< CandidateSection > out = new ArrayList<>( best.values() );
        out.sort( Comparator.comparingDouble( CandidateSection::denseScore ).reversed() );
        return out;
    }
}
