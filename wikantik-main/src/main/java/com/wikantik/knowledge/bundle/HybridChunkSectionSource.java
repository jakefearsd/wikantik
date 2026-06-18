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
import com.wikantik.search.hybrid.HybridFuser;
import com.wikantik.search.hybrid.LuceneBm25ChunkIndex;
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
 * Chunk-level hybrid candidate source: RRF-fuses the dense vector ranking
 * ({@link ChunkVectorIndex}) with the lexical BM25 ranking
 * ({@link LuceneBm25ChunkIndex}) over the same chunk pool, then groups to sections
 * by {@code (slug, heading-path)} keeping the best-fused chunk per section.
 *
 * <p>The shipped {@link DenseChunkSectionSource} is dense-only; this adds the lexical
 * half of "hybrid" at the granularity the bundle actually uses, behind
 * {@code wikantik.bundle.bm25.enabled}. Fusion reuses {@link HybridFuser} (the same
 * weighted-RRF the page-level hybrid uses) so dense and lexical signals combine
 * consistently. Degrades to whichever ranker is available (never throws).
 */
public final class HybridChunkSectionSource implements SectionCandidateSource {

    private static final Logger LOG = LogManager.getLogger( HybridChunkSectionSource.class );

    private final QueryEmbedder embedder;
    private final ChunkVectorIndex denseIndex;
    private final LuceneBm25ChunkIndex bm25Index;
    private final ContentChunkRepository chunkRepo;
    private final HybridFuser fuser;
    private final int topK;

    public HybridChunkSectionSource( final QueryEmbedder embedder, final ChunkVectorIndex denseIndex,
                                     final LuceneBm25ChunkIndex bm25Index, final ContentChunkRepository chunkRepo,
                                     final HybridFuser fuser, final int topK ) {
        this.embedder = embedder;
        this.denseIndex = denseIndex;
        this.bm25Index = bm25Index;
        this.chunkRepo = chunkRepo;
        this.fuser = fuser;
        this.topK = topK > 0 ? topK : 300;
    }

    @Override
    public List< CandidateSection > candidates( final String query ) {
        final Optional< float[] > qv = embedder.embed( query );
        final List< String > denseRanked = qv.isPresent()
            ? rankedIds( denseIndex.topKChunks( qv.get(), topK ) ) : List.of();
        final List< String > bm25Ranked = rankedIds( bm25Index.topKChunks( query, topK ) );
        if ( denseRanked.isEmpty() && bm25Ranked.isEmpty() ) {
            LOG.warn( "Hybrid chunk source: no dense and no BM25 candidates for '{}'", query );
            return List.of();
        }
        final List< String > fusedIds = fuser.fuse( bm25Ranked, denseRanked );

        final List< UUID > ids = new ArrayList<>( fusedIds.size() );
        for ( final String s : fusedIds ) ids.add( UUID.fromString( s ) );
        final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();
        for ( final MentionableChunk c : chunkRepo.findByIds( ids ) ) byId.put( c.id(), c );

        return groupToSections( fusedIds, byId );
    }

    /** One ranked chunk for the debug/sweep endpoint: id + raw ranker score. */
    public record DebugRank( String chunkId, double score ) {}

    /**
     * Spike sweep support: return the raw dense and BM25 chunk rankings (id + score, best
     * first) for {@code query}, each truncated to {@code k}. The offline sweep harness fuses +
     * groups these itself, so it can explore fusion weights / rrf_k / top_k / grouping strategy
     * without a server restart per combo. Not wired to any production surface beyond the
     * gated {@code /api/bundle?debug=rankings} endpoint.
     */
    public Map< String, List< DebugRank > > debugRankings( final String query, final int k ) {
        final int kk = k > 0 ? k : topK;
        final Optional< float[] > qv = embedder.embed( query );
        final List< ScoredChunk > dense = qv.isPresent() ? denseIndex.topKChunks( qv.get(), kk ) : List.of();
        final List< ScoredChunk > bm25 = bm25Index.topKChunks( query, kk );
        final Map< String, List< DebugRank > > out = new LinkedHashMap<>();
        out.put( "dense", toDebugRanks( dense ) );
        out.put( "bm25", toDebugRanks( bm25 ) );
        return out;
    }

    private static List< DebugRank > toDebugRanks( final List< ScoredChunk > scored ) {
        return scored.stream()
            .sorted( Comparator.comparingDouble( ScoredChunk::score ).reversed() )
            .map( sc -> new DebugRank( sc.chunkId().toString(), sc.score() ) )
            .toList();
    }

    /** Score chunks desc, return their ids as a rank-ordered list (best first). */
    private static List< String > rankedIds( final List< ScoredChunk > scored ) {
        return scored.stream()
            .sorted( Comparator.comparingDouble( ScoredChunk::score ).reversed() )
            .map( sc -> sc.chunkId().toString() )
            .toList();
    }

    /**
     * Group the fused chunk order to sections, keeping the first (best-fused) chunk per
     * {@code (slug, heading-path)}. Returns sections in fused order; the section score is a
     * monotonically-decreasing proxy of fused rank (the bundle takes them in order, the
     * value is for display). Package-private + static so the fusion/grouping is unit-tested
     * without a live embedder or Lucene index.
     */
    static List< CandidateSection > groupToSections( final List< String > fusedIds,
                                                     final Map< UUID, MentionableChunk > byId ) {
        final Map< SectionKey, CandidateSection > best = new LinkedHashMap<>();
        int pos = 0;
        for ( final String fid : fusedIds ) {
            final MentionableChunk c = byId.get( UUID.fromString( fid ) );
            if ( c == null ) continue;
            final SectionKey key = new SectionKey( c.pageName(), c.headingPath() );
            if ( best.containsKey( key ) ) continue;
            best.put( key, new CandidateSection( c.pageName(), c.headingPath(), c.text(), 1.0 / ( 1 + pos ) ) );
            pos++;
        }
        return new ArrayList<>( best.values() );
    }

    private record SectionKey( String slug, List< String > headingPath ) {}
}
