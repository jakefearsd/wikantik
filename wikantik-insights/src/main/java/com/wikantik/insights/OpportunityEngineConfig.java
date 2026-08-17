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
 * The defaults are the values named in the design doc's rule tables.
 *
 * <p>{@code globalFloorMinImpressions} and {@code globalFloorMinOccurrences} are the backstop
 * from §7.3 "Cross-rule constraints": <strong>no rule fires below these numbers regardless of its
 * own threshold</strong>. Every rule method composes its own threshold with the floor via
 * {@code Math.max}, so a misconfigured (too-low) per-rule threshold can never fire below the
 * floor -- see {@code OpportunityEngineTest#globalFloorOverridesMisconfiguredPerRuleThreshold}.</p>
 *
 * @param agentGapMinOccurrences          rule 1 minimum support: occurrences in 28 days (default 2)
 * @param agentGapMinDistinctSessions     rule 1 minimum support: distinct calls (default 2)
 * @param engineDivergenceMinImpressions  rule 2 minimum support: impressions on the stronger
 *                                        engine in 28 days (default 100)
 * @param engineDivergenceMinSharedQueries rule 2 minimum support: shared queries (default 10)
 * @param engineDivergenceMinPositionGap  rule 2 minimum support: position gap (default 10.0)
 * @param vocabularyGapMinClicks          rule 3 minimum support: clicks to the page in 28 days
 *                                        (default 5)
 * @param staleHighTrafficMinImpressions  rule 4 minimum support: page-level impressions in 28
 *                                        days (default 200)
 * @param staleDays                       rule 4 minimum support: age past which
 *                                        {@code verified_at} counts as stale (default 180)
 * @param globalFloorMinImpressions       cross-rule floor on impressions (default 10)
 * @param globalFloorMinOccurrences       cross-rule floor on occurrences (default 2)
 * @param cooldownDays                    per-page cooldown between automatic optimizations
 *                                        (default 60)
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
        int cooldownDays ) {

    /**
     * @return the design doc's default thresholds
     */
    public static OpportunityEngineConfig defaults() {
        return new OpportunityEngineConfig( 2, 2, 100, 10, 10.0, 5, 200, 180, 10, 2, 60 );
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
