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

import com.wikantik.knowledge.bundle.LexicalInjectionSource.Candidate;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository.MentionableChunk;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.LuceneBm25ChunkIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.ScoredChunk;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LexicalInjectionSourceTest {
    private static CandidateSection sec(String slug, String head, String text, double score) {
        return new CandidateSection(slug, List.of(head), text, score);
    }
    // Candidate(section, bm25Score, bm25Rank, denseRank)
    private static Candidate cand(String slug, String head, double bm25, int bm25Rank, int denseRank) {
        return new Candidate(sec(slug, head, "t-" + slug, bm25), bm25, bm25Rank, denseRank);
    }
    private static Properties props(boolean enabled) {
        Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.enabled", String.valueOf(enabled));
        return p;
    }

    @Test
    void injectsDenseColdHighConfidenceSection() {
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0), sec("P1","H1","b1",0.9),
            sec("P2","H2","b2",0.8), sec("P3","H3","b3",0.7));
        List<Candidate> cands = List.of(cand("GOLD","HG", 10.0, 0, 99));  // rank 0, top score, dense rank 99 (>C=50)
        InjectionConfig cfg = InjectionConfig.fromProperties(props(true));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, cfg);
        assertEquals("GOLD", out.get(3).slug());  // inserted at position 3 (default P)
        assertEquals(5, out.size());
    }

    @Test
    void skipsDenseWarmSection() {  // dense rank 10 <= C=50 → not cold → no inject
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("WARM","HW", 10.0, 0, 10));
        assertEquals(1, LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true))).size());
    }

    @Test
    void skipsLowRelativeScore() {  // score 2.0 < 0.3 * top(10.0) = 3.0 → skip
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("WEAK","HW", 2.0, 0, 99));
        assertEquals(1, LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true))).size());
    }

    @Test
    void skipsBeyondRankMax() {  // bm25Rank 25 >= J=20 → skip
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("FAR","HF", 10.0, 25, 99));
        assertEquals(1, LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true))).size());
    }

    @Test
    void dedupsAgainstBase() {  // candidate already in base (same slug+heading) → skip
        List<CandidateSection> base = List.of(sec("DUP","HD","b",1.0));
        List<Candidate> cands = List.of(cand("DUP","HD", 10.0, 0, 99));
        assertEquals(1, LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true))).size());
    }

    @Test
    void capsAtMaxInject() {  // 5 eligible, maxInject=3
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(
            cand("G1","H1",10,0,99), cand("G2","H2",9,1,99), cand("G3","H3",8,2,99),
            cand("G4","H4",7,3,99), cand("G5","H5",6,4,99));
        assertEquals(4, LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(true))).size());
    }

    @Test
    void disabledReturnsBaseUnchanged() {
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0));
        List<Candidate> cands = List.of(cand("GOLD","HG",10,0,99));
        List<CandidateSection> out = LexicalInjectionSource.merge(base, cands, 10.0, false, InjectionConfig.fromProperties(props(false)));
        assertSame(base, out);
    }

    @Test
    void symbolBoostRelaxesGates() {  // rank 30 (>J=20 but <jBoost=50) injects only with boost
        List<CandidateSection> base = List.of(sec("P0","H0","b0",1.0), sec("P1","H1","b1",0.9),
            sec("P2","H2","b2",0.8), sec("P3","H3","b3",0.7));
        List<Candidate> cands = List.of(cand("GOLD","HG", 10.0, 30, 99));
        InjectionConfig cfg = InjectionConfig.fromProperties(props(true));
        assertEquals(4, LexicalInjectionSource.merge(base, cands, 10.0, false, cfg).size());   // no boost → skip
        assertEquals(5, LexicalInjectionSource.merge(base, cands, 10.0, true, cfg).size());     // boost → inject
    }

    @Test @SuppressWarnings("unchecked")
    void candidatesInjectsDenseColdCodeSection() {
        final UUID goldId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        final QueryEmbedder embedder = mock(QueryEmbedder.class);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f}));
        final ChunkVectorIndex dense = mock(ChunkVectorIndex.class);   // dense returns base page only → gold cold
        when(dense.topKChunks(any(), anyInt())).thenReturn(List.of(new ScoredChunk(UUID.randomUUID(), "P0", 0.9)));
        final LuceneBm25ChunkIndex bm25code = mock(LuceneBm25ChunkIndex.class);  // bm25(code) ranks the gold chunk #0
        when(bm25code.topKChunks(anyString(), anyInt())).thenReturn(List.of(new ScoredChunk(goldId, "GoldPage", 12.0)));
        final ContentChunkRepository repo = mock(ContentChunkRepository.class);
        when(repo.findByIds(anyList())).thenReturn(List.of(
            new MentionableChunk(goldId, "GoldPage", 0, List.of("Gold Section"), "gold text")));
        final SectionCandidateSource base = q -> new ArrayList<>(List.of(
            new CandidateSection("P0", List.of("H0"), "b0", 1.0)));
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.enabled", "true");
        p.setProperty("wikantik.bundle.inject.position", "0");
        final LexicalInjectionSource src = new LexicalInjectionSource(
            base, embedder, dense, bm25code, repo, InjectionConfig.fromProperties(p));
        final List<CandidateSection> out = src.candidates("the gold thing");
        assertEquals("GoldPage", out.get(0).slug());     // injected at position 0
        assertEquals("gold text", out.get(0).text());
    }

    @Test @SuppressWarnings("unchecked")
    void candidatesFailsOpenWhenBm25Throws() {
        final QueryEmbedder embedder = mock(QueryEmbedder.class);
        when(embedder.embed(anyString())).thenReturn(Optional.of(new float[]{0.1f}));
        final ChunkVectorIndex dense = mock(ChunkVectorIndex.class);
        when(dense.topKChunks(any(), anyInt())).thenReturn(List.of());
        final LuceneBm25ChunkIndex bm25code = mock(LuceneBm25ChunkIndex.class);
        when(bm25code.topKChunks(anyString(), anyInt())).thenThrow(new RuntimeException("boom"));
        final ContentChunkRepository repo = mock(ContentChunkRepository.class);
        final List<CandidateSection> baseList = List.of(new CandidateSection("P0", List.of("H0"), "b0", 1.0));
        final SectionCandidateSource base = q -> baseList;
        final Properties p = new Properties();
        p.setProperty("wikantik.bundle.inject.enabled", "true");
        final LexicalInjectionSource src = new LexicalInjectionSource(
            base, embedder, dense, bm25code, repo, InjectionConfig.fromProperties(p));
        assertEquals(baseList, src.candidates("q"));   // base unchanged on failure
    }
}
