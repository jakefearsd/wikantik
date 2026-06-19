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
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

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
}
