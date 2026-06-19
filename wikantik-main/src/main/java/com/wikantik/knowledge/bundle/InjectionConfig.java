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

import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Properties;

/**
 * Tuning knobs for {@link LexicalInjectionSource}. A section is injected when BM25(code)
 * ranks it within {@code bm25RankMax} with score ≥ {@code scoreFrac} × the query's top BM25
 * score, AND dense ranked it worse than {@code denseColdMin} (the self-gating signal). Up to
 * {@code maxInject} sections are spliced in at {@code position}. When the query contains a
 * literal code symbol and {@code symbolBoost} is on, {@code jBoost}/{@code alphaBoost} relax
 * the rank/score bars. {@code denseScanK} = how deep to scan dense for the cold signal.
 */
public record InjectionConfig(
        boolean enabled, int bm25RankMax, int denseColdMin, double scoreFrac,
        int maxInject, int position, boolean symbolBoost, int jBoost, double alphaBoost,
        int denseScanK) {

    private static final Logger LOG = LogManager.getLogger(InjectionConfig.class);

    public static InjectionConfig fromProperties(final Properties p) {
        return new InjectionConfig(
            bool(p, "wikantik.bundle.inject.enabled", false),
            intp(p, "wikantik.bundle.inject.bm25_rank_max", 20),
            intp(p, "wikantik.bundle.inject.dense_cold_min", 50),
            TextUtil.getDoubleProperty(p, "wikantik.bundle.inject.score_frac", 0.3),
            intp(p, "wikantik.bundle.inject.max_inject", 3),
            intp(p, "wikantik.bundle.inject.position", 3),
            bool(p, "wikantik.bundle.inject.symbol_boost", true),
            intp(p, "wikantik.bundle.inject.j_boost", 50),
            TextUtil.getDoubleProperty(p, "wikantik.bundle.inject.alpha_boost", 0.1),
            intp(p, "wikantik.bundle.inject.dense_scan_k", 300));
    }

    private static boolean bool(final Properties p, final String k, final boolean def) {
        final String v = p == null ? null : p.getProperty(k);
        return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim());
    }
    private static int intp(final Properties p, final String k, final int def) {
        final String v = p == null ? null : p.getProperty(k);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (final NumberFormatException e) { LOG.warn("Invalid {} '{}'; using {}", k, v, def); return def; }
    }
}
