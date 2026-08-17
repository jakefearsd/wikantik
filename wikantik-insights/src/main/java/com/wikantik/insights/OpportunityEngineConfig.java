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
package com.wikantik.insights;

/**
 * Configurable thresholds for {@link OpportunityEngine} (content-intelligence design §7.3, §10).
 * The defaults are the values named in the design doc's rule tables and §10's configuration table
 * (revised 2026-08-17 for the §7.3.0 traffic gate and the per-rule priority weights).
 *
 * <p>{@code globalFloorMinImpressions} and {@code globalFloorMinOccurrences} are the backstop
 * from §7.3 "Cross-rule constraints": <strong>no rule fires below these numbers regardless of its
 * own threshold</strong>. Every rule method composes its own threshold with the floor via
 * {@code Math.max}, so a misconfigured (too-low) per-rule threshold can never fire below the
 * floor -- see {@code OpportunityEngineTest#globalFloorOverridesMisconfiguredPerRuleThreshold}.
 * {@code divergenceMinImpressionsWeak} is a deliberate exception: its default (5) sits below the
 * global floor (10) on purpose -- see its own doc below -- so it is used as-is, never composed
 * with the floor.</p>
 *
 * @param agentGapMinOccurrences          rule 1 minimum support: occurrences in 28 days (default 2)
 * @param agentGapMinDistinctSessions     rule 1 minimum support: distinct calls (default 2)
 * @param engineDivergenceMinImpressions  rule 2 minimum support: impressions on the stronger
 *                                        engine in 28 days (default 20, raised 2026-08-17 from
 *                                        100's page-rollup-grain successor value -- see §7.3.0)
 * @param engineDivergenceMinSharedQueries rule 2's original per-query-grain minimum support
 *                                        (shared queries, default 10). Retained for
 *                                        config-shape/history compatibility only: the §7.3 rule 2
 *                                        "grain correction" (2026-08-17) moved
 *                                        {@link OpportunityEngine#evaluateEngineDivergence} onto
 *                                        page-rollup rows because no query row in the store
 *                                        carries a page (see design §12.1 item J1), so this field
 *                                        is not currently consulted by that method
 * @param engineDivergenceMinPositionGap  rule 2 minimum support: position gap (default 10.0)
 * @param vocabularyGapMinClicks          rule 3 minimum support: clicks to the page in 28 days
 *                                        (default 5)
 * @param staleHighTrafficMinImpressions  rule 4 minimum support: page-level impressions in 28
 *                                        days (default 20, lowered 2026-08-17 from 200 -- see
 *                                        §7.3.0; both are below the site-level traffic gate that
 *                                        actually keeps this rule dormant at today's traffic)
 * @param staleDays                       rule 4 minimum support: age past which
 *                                        {@code verified_at} counts as stale (default 180)
 * @param globalFloorMinImpressions       cross-rule floor on impressions (default 10)
 * @param globalFloorMinOccurrences       cross-rule floor on occurrences (default 2)
 * @param cooldownDays                    per-page cooldown between automatic optimizations
 *                                        (default 60)
 * @param gateImpressions28d              §7.3.0 site-level traffic gate: below this many total
 *                                        site impressions in 28 days, the three visibility-driven
 *                                        rules ({@code ENGINE_DIVERGENCE}, {@code VOCABULARY_GAP},
 *                                        {@code STALE_HIGH_TRAFFIC}) do not run at all (default
 *                                        5000). {@code AGENT_GAP} is never gated -- its
 *                                        denominator is MCP traffic, not search impressions
 * @param divergenceMinImpressionsWeak    rule 2 minimum support: impressions on the weaker engine
 *                                        (default 5). Deliberately <em>not</em> composed with
 *                                        {@code globalFloorMinImpressions} (10) -- without a
 *                                        separate, lower floor a well-sampled strong-engine
 *                                        position could never be compared against a genuinely
 *                                        thin weak-engine sample, which is exactly the comparison
 *                                        this rule exists to make
 * @param weightAgentGap                  rule 1 priority weight: {@code occurrences x weight}
 *                                        (default 2.0)
 * @param weightEngineDivergence          rule 2 priority weight: applied to the expected-CTR
 *                                        uplift (default 1.0)
 * @param weightVocabularyGap             rule 3 priority weight: {@code impressions x weight}
 *                                        (default 0.05)
 * @param weightStaleHighTraffic          rule 4 priority weight: {@code impressions x weight}
 *                                        (default 0.02)
 */
public record OpportunityEngineConfig(
        int agentGapMinOccurrences,
        int agentGapMinDistinctSessions,
        int engineDivergenceMinImpressions,
        int engineDivergenceMinSharedQueries,
        double engineDivergenceMinPositionGap,
        int vocabularyGapMinClicks,
        int staleHighTrafficMinImpressions,
        int staleDays,
        int globalFloorMinImpressions,
        int globalFloorMinOccurrences,
        int cooldownDays,
        int gateImpressions28d,
        int divergenceMinImpressionsWeak,
        double weightAgentGap,
        double weightEngineDivergence,
        double weightVocabularyGap,
        double weightStaleHighTraffic ) {

    /**
     * @return the design doc's default thresholds and weights (§7.3, §10)
     */
    public static OpportunityEngineConfig defaults() {
        return new OpportunityEngineConfig( 2, 2, 20, 10, 10.0, 5, 20, 180, 10, 2, 60,
                5000, 5, 2.0, 1.0, 0.05, 0.02 );
    }

    /** @return {@code agentGapMinOccurrences} composed with the global floor */
    int effectiveAgentGapMinOccurrences() {
        return Math.max( agentGapMinOccurrences, globalFloorMinOccurrences );
    }

    /** @return {@code engineDivergenceMinImpressions} composed with the global floor */
    int effectiveEngineDivergenceMinImpressions() {
        return Math.max( engineDivergenceMinImpressions, globalFloorMinImpressions );
    }

    /**
     * @return {@code vocabularyGapMinClicks} composed with the global floor. Clicks are, like
     *         occurrences, a count of discrete events, so the occurrence floor -- not the
     *         impression floor -- is the applicable backstop.
     */
    int effectiveVocabularyGapMinClicks() {
        return Math.max( vocabularyGapMinClicks, globalFloorMinOccurrences );
    }

    /** @return {@code staleHighTrafficMinImpressions} composed with the global floor */
    int effectiveStaleHighTrafficMinImpressions() {
        return Math.max( staleHighTrafficMinImpressions, globalFloorMinImpressions );
    }
}
