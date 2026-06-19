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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Code/identifier retrieval for the context bundle. Wraps the shipped hybrid bundle source
 * unchanged and injects sections that a code-aware BM25 index ranks highly AND dense ranked
 * coldly — serving code-symbol/config-lookup queries without regressing natural-language
 * (the injection self-gates on the dense-cold signal). See
 * {@code docs/superpowers/specs/2026-06-19-lexical-injection-code-retrieval-design.md}.
 *
 * <p>This step contains the inner records + the pure {@link #merge} gate logic; the
 * {@code SectionCandidateSource} implementation (constructor + {@code candidates}) is added next.
 */
public final class LexicalInjectionSource {

    /** A BM25(code) section candidate with its lexical score/rank and best dense rank. */
    public record Candidate(CandidateSection section, double bm25Score, int bm25Rank, int denseRank) {}

    private record Key(String slug, List<String> headingPath) {}
    private static Key key(final CandidateSection s) { return new Key(s.slug(), s.headingPath()); }

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
