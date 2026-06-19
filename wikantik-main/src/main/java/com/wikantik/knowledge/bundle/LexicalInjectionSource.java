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
import com.wikantik.search.hybrid.LuceneBm25ChunkIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.ScoredChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Code/identifier retrieval for the context bundle. Wraps the shipped hybrid bundle source
 * unchanged and injects sections that a code-aware BM25 index ranks highly AND dense ranked
 * coldly — serving code-symbol/config-lookup queries without regressing natural-language
 * (the injection self-gates on the dense-cold signal). See
 * {@code docs/superpowers/specs/2026-06-19-lexical-injection-code-retrieval-design.md}.
 *
 * <p>Fails open: any failure computing the injection (embed/index/hydrate) returns the base
 * bundle unchanged — a flaky code index can never break {@code /api/bundle}.
 */
public final class LexicalInjectionSource implements SectionCandidateSource {

    private static final Logger LOG = LogManager.getLogger(LexicalInjectionSource.class);

    /** A BM25(code) section candidate with its lexical score/rank and best dense rank. */
    public record Candidate(CandidateSection section, double bm25Score, int bm25Rank, int denseRank) {}

    private record Key(String slug, List<String> headingPath) {}
    private static Key key(final CandidateSection s) { return new Key(s.slug(), s.headingPath()); }

    private final SectionCandidateSource base;
    private final QueryEmbedder embedder;
    private final ChunkVectorIndex denseIndex;
    private final LuceneBm25ChunkIndex bm25CodeIndex;
    private final ContentChunkRepository chunkRepo;
    private final InjectionConfig config;

    public LexicalInjectionSource(final SectionCandidateSource base, final QueryEmbedder embedder,
                                  final ChunkVectorIndex denseIndex, final LuceneBm25ChunkIndex bm25CodeIndex,
                                  final ContentChunkRepository chunkRepo, final InjectionConfig config) {
        this.base = base; this.embedder = embedder; this.denseIndex = denseIndex;
        this.bm25CodeIndex = bm25CodeIndex; this.chunkRepo = chunkRepo; this.config = config;
    }

    @Override
    public List<CandidateSection> candidates(final String query) {
        final List<CandidateSection> baseSections = base.candidates(query);
        if (!config.enabled()) return baseSections;
        try {
            final List<ScoredChunk> bm25 = bm25CodeIndex.topKChunks(query,
                Math.max(config.bm25RankMax(), config.jBoost()));
            if (bm25.isEmpty()) return baseSections;
            final List<ScoredChunk> dense = embedder.embed(query)
                .map(qv -> denseIndex.topKChunks(qv, config.denseScanK())).orElseGet(List::of);

            final List<ScoredChunk> denseSorted = sortedDesc(dense);
            final Map<UUID, MentionableChunk> hydr = hydrate(bm25, denseSorted);

            // best (lowest) dense rank per section; sections absent from the dense scan are cold.
            final Map<String, Integer> denseRankBySection = new HashMap<>();
            for (int r = 0; r < denseSorted.size(); r++) {
                final MentionableChunk c = hydr.get(denseSorted.get(r).chunkId());
                if (c == null) continue;
                final String key = sectionKey(c.pageName(), c.headingPath());
                final int rr = r;
                denseRankBySection.merge(key, rr, Math::min);
            }

            // BM25(code) section candidates: first (best) chunk per section, in bm25 rank order.
            final List<ScoredChunk> bm25Sorted = sortedDesc(bm25);
            final double topScore = bm25Sorted.get(0).score();
            final Set<String> seen = new LinkedHashSet<>();
            final List<Candidate> cands = new ArrayList<>();
            for (int r = 0; r < bm25Sorted.size(); r++) {
                final ScoredChunk sc = bm25Sorted.get(r);
                final MentionableChunk c = hydr.get(sc.chunkId());
                if (c == null) continue;
                final String key = sectionKey(c.pageName(), c.headingPath());
                if (!seen.add(key)) continue;
                final int denseRank = denseRankBySection.getOrDefault(key, config.denseScanK());
                cands.add(new Candidate(
                    new CandidateSection(c.pageName(), c.headingPath(), c.text(), sc.score()), sc.score(), r, denseRank));
            }
            final boolean boost = config.symbolBoost() && SymbolDetector.hasCodeSymbol(query);
            return merge(baseSections, cands, topScore, boost, config);
        } catch (final RuntimeException e) {
            LOG.warn("Lexical injection failed for '{}'; returning base bundle: {}", query, e.getMessage());
            return baseSections;
        }
    }

    private Map<UUID, MentionableChunk> hydrate(final List<ScoredChunk> a, final List<ScoredChunk> b) {
        final LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (final ScoredChunk s : a) ids.add(s.chunkId());
        for (final ScoredChunk s : b) ids.add(s.chunkId());
        final Map<UUID, MentionableChunk> byId = new HashMap<>();
        for (final MentionableChunk c : chunkRepo.findByIds(new ArrayList<>(ids))) byId.put(c.id(), c);
        return byId;
    }

    private static List<ScoredChunk> sortedDesc(final List<ScoredChunk> in) {
        final List<ScoredChunk> out = new ArrayList<>(in);
        out.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return out;
    }

    private static String sectionKey(final String slug, final List<String> hp) {
        return slug + " " + String.join("", hp == null ? List.of() : hp);
    }

    /** Debug rankings for the offline sweep: dense + bm25_standard (from base) + bm25_code. */
    public Map<String, List<HybridChunkSectionSource.DebugRank>> debugRankings(final String query, final int k) {
        final Map<String, List<HybridChunkSectionSource.DebugRank>> out = new LinkedHashMap<>();
        if (base instanceof HybridChunkSectionSource h) out.putAll(h.debugRankings(query, k));  // dense + bm25
        if (out.containsKey("bm25")) out.put("bm25_standard", out.remove("bm25"));
        final List<HybridChunkSectionSource.DebugRank> code = new ArrayList<>();
        for (final ScoredChunk sc : sortedDesc(bm25CodeIndex.topKChunks(query, k)))
            code.add(new HybridChunkSectionSource.DebugRank(sc.chunkId().toString(), sc.score()));
        out.put("bm25_code", code);
        return out;
    }

    /**
     * Pure gate + splice. Returns {@code base} unchanged (same reference) when disabled. Otherwise
     * selects up to {@code maxInject} candidates that are (a) not already in base, (b) dense-cold
     * (denseRank &gt; denseColdMin), (c) within the rank bar, and (d) above the relative-score bar,
     * and inserts them at {@code position}. {@code symbolBoost} relaxes the rank/score bars.
     */
    static List<CandidateSection> merge(final List<CandidateSection> base, final List<Candidate> candidates,
                                        final double topBm25Score, final boolean symbolBoost,
                                        final InjectionConfig cfg) {
        if (!cfg.enabled()) return base;
        final int rankMax = symbolBoost ? cfg.jBoost() : cfg.bm25RankMax();
        final double alpha = symbolBoost ? cfg.alphaBoost() : cfg.scoreFrac();
        final double scoreBar = alpha * topBm25Score;

        final Set<Key> baseKeys = new LinkedHashSet<>();
        for (final CandidateSection s : base) baseKeys.add(key(s));

        final List<CandidateSection> picked = new ArrayList<>();
        for (final Candidate c : candidates) {
            if (picked.size() >= cfg.maxInject()) break;
            if (c.bm25Rank() >= rankMax) continue;
            if (c.bm25Score() < scoreBar) continue;
            if (c.denseRank() <= cfg.denseColdMin()) continue;   // dense-warm → dense already has it
            if (baseKeys.contains(key(c.section()))) continue;   // dedup
            baseKeys.add(key(c.section()));
            picked.add(c.section());
        }
        if (picked.isEmpty()) return base;

        final List<CandidateSection> out = new ArrayList<>(base);
        final int pos = Math.max(0, Math.min(cfg.position(), out.size()));
        out.addAll(pos, picked);
        return out;
    }
}
