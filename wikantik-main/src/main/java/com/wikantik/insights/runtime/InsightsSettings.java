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
package com.wikantik.insights.runtime;

import com.wikantik.insights.OpportunityEngineConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Typed, validated view over every {@code wikantik.insights.*} configuration key that the
 * content-opportunity engine's wiki-side runtime consumes (content-intelligence design §10).
 *
 * <p>Only the keys this repository's runtime actually reads are represented here -- the ingest
 * and telemetry keys in §10 belong to other parts of the design (jakemon ingestion, the Phase 3
 * browser collector) and are read elsewhere, not by this record.</p>
 *
 * <p><strong>Never throws on bad input.</strong> A malformed numeric value logs a
 * {@code LOG.warn} with the offending key and value, then falls back to that key's documented
 * default -- config parsing must not be able to take a wiki startup down.</p>
 *
 * @param enabled                                  kill switch for the whole subsystem
 *                                                  ({@code wikantik.insights.enabled}, default
 *                                                  {@code true})
 * @param rulesGateImpressions28d                  §7.3.0 site-level traffic gate
 *                                                  ({@code wikantik.insights.rules.gate.impressions28d},
 *                                                  default {@code 5000})
 * @param ruleSites                                sites the rule engine acts on, in configured
 *                                                  order -- the first entry is the default site
 *                                                  ({@code wikantik.insights.rules.sites}, default
 *                                                  {@code wiki.wikantik.com,wikantik.com})
 * @param opportunityMinImpressions                {@code ENGINE_DIVERGENCE} (stronger engine) and
 *                                                  {@code STALE_HIGH_TRAFFIC} minimum impressions
 *                                                  ({@code wikantik.insights.opportunity.min_impressions},
 *                                                  default {@code 20})
 * @param opportunityDivergenceMinImpressionsWeak  {@code ENGINE_DIVERGENCE} weaker-engine minimum
 *                                                  impressions
 *                                                  ({@code wikantik.insights.opportunity.divergence.min_impressions_weak},
 *                                                  default {@code 5})
 * @param opportunityMinOccurrences                {@code AGENT_GAP} minimum occurrences
 *                                                  ({@code wikantik.insights.opportunity.min_occurrences},
 *                                                  default {@code 2})
 * @param opportunityDivergenceMinGap              {@code ENGINE_DIVERGENCE} minimum position gap
 *                                                  ({@code wikantik.insights.opportunity.divergence.min_gap},
 *                                                  default {@code 10})
 * @param opportunityStaleDays                     {@code STALE_HIGH_TRAFFIC} staleness age
 *                                                  ({@code wikantik.insights.opportunity.stale.days},
 *                                                  default {@code 180})
 * @param weightAgentGap                           rule 1 priority weight
 *                                                  ({@code wikantik.insights.opportunity.weight.agent_gap},
 *                                                  default {@code 2.0})
 * @param weightEngineDivergence                   rule 2 priority weight
 *                                                  ({@code wikantik.insights.opportunity.weight.engine_divergence},
 *                                                  default {@code 1.0})
 * @param weightVocabularyGap                      rule 3 priority weight
 *                                                  ({@code wikantik.insights.opportunity.weight.vocabulary_gap},
 *                                                  default {@code 0.05})
 * @param weightStaleHighTraffic                   rule 4 priority weight
 *                                                  ({@code wikantik.insights.opportunity.weight.stale_high_traffic},
 *                                                  default {@code 0.02})
 * @param effectWindowDays                         before/after effect-measurement window
 *                                                  ({@code wikantik.insights.effect.window.days},
 *                                                  default {@code 28})
 * @param effectMinBaselineImpressions              below this, a verdict is
 *                                                  {@code insufficient_data}
 *                                                  ({@code wikantik.insights.effect.min_baseline_impressions},
 *                                                  default {@code 100})
 * @param changeCooldownDays                       max one automatic optimization per page per
 *                                                  window ({@code wikantik.insights.change.cooldown.days},
 *                                                  default {@code 60})
 * @param calibrationMinVerdicts                   evaluated changes required before a type reports
 *                                                  {@code calibrated: true}
 *                                                  ({@code wikantik.insights.calibration.min_verdicts},
 *                                                  default {@code 20})
 * @param calibrationDamping                       fraction of the way a weight moves toward the
 *                                                  observed ratio
 *                                                  ({@code wikantik.insights.calibration.damping},
 *                                                  default {@code 0.5})
 */
public record InsightsSettings(
        boolean enabled,
        int rulesGateImpressions28d,
        List<String> ruleSites,
        int opportunityMinImpressions,
        int opportunityDivergenceMinImpressionsWeak,
        int opportunityMinOccurrences,
        double opportunityDivergenceMinGap,
        int opportunityStaleDays,
        double weightAgentGap,
        double weightEngineDivergence,
        double weightVocabularyGap,
        double weightStaleHighTraffic,
        int effectWindowDays,
        int effectMinBaselineImpressions,
        int changeCooldownDays,
        int calibrationMinVerdicts,
        double calibrationDamping ) {

    private static final Logger LOG = LogManager.getLogger( InsightsSettings.class );

    public static final String KEY_ENABLED = "wikantik.insights.enabled";
    public static final String KEY_RULES_GATE_IMPRESSIONS_28D = "wikantik.insights.rules.gate.impressions28d";
    public static final String KEY_RULES_SITES = "wikantik.insights.rules.sites";
    public static final String KEY_OPPORTUNITY_MIN_IMPRESSIONS = "wikantik.insights.opportunity.min_impressions";
    public static final String KEY_OPPORTUNITY_DIVERGENCE_MIN_IMPRESSIONS_WEAK =
            "wikantik.insights.opportunity.divergence.min_impressions_weak";
    public static final String KEY_OPPORTUNITY_MIN_OCCURRENCES = "wikantik.insights.opportunity.min_occurrences";
    public static final String KEY_OPPORTUNITY_DIVERGENCE_MIN_GAP = "wikantik.insights.opportunity.divergence.min_gap";
    public static final String KEY_OPPORTUNITY_STALE_DAYS = "wikantik.insights.opportunity.stale.days";
    public static final String KEY_WEIGHT_AGENT_GAP = "wikantik.insights.opportunity.weight.agent_gap";
    public static final String KEY_WEIGHT_ENGINE_DIVERGENCE = "wikantik.insights.opportunity.weight.engine_divergence";
    public static final String KEY_WEIGHT_VOCABULARY_GAP = "wikantik.insights.opportunity.weight.vocabulary_gap";
    public static final String KEY_WEIGHT_STALE_HIGH_TRAFFIC = "wikantik.insights.opportunity.weight.stale_high_traffic";
    public static final String KEY_EFFECT_WINDOW_DAYS = "wikantik.insights.effect.window.days";
    public static final String KEY_EFFECT_MIN_BASELINE_IMPRESSIONS = "wikantik.insights.effect.min_baseline_impressions";
    public static final String KEY_CHANGE_COOLDOWN_DAYS = "wikantik.insights.change.cooldown.days";
    public static final String KEY_CALIBRATION_MIN_VERDICTS = "wikantik.insights.calibration.min_verdicts";
    public static final String KEY_CALIBRATION_DAMPING = "wikantik.insights.calibration.damping";

    private static final String DEFAULT_RULES_SITES = "wiki.wikantik.com,wikantik.com";

    /**
     * @param props the wiki's merged configuration properties; {@code null} is treated as empty
     *              (every key falls back to its default)
     * @return the typed settings, with every malformed numeric value replaced by its default and
     *         logged
     */
    public static InsightsSettings from( final Properties props ) {
        final Properties effective = props == null ? new Properties() : props;
        return new InsightsSettings(
                parseBoolean( effective, KEY_ENABLED, true ),
                parseInt( effective, KEY_RULES_GATE_IMPRESSIONS_28D, 5000 ),
                parseSites( effective ),
                parseInt( effective, KEY_OPPORTUNITY_MIN_IMPRESSIONS, 20 ),
                parseInt( effective, KEY_OPPORTUNITY_DIVERGENCE_MIN_IMPRESSIONS_WEAK, 5 ),
                parseInt( effective, KEY_OPPORTUNITY_MIN_OCCURRENCES, 2 ),
                parseDouble( effective, KEY_OPPORTUNITY_DIVERGENCE_MIN_GAP, 10.0 ),
                parseInt( effective, KEY_OPPORTUNITY_STALE_DAYS, 180 ),
                parseDouble( effective, KEY_WEIGHT_AGENT_GAP, 2.0 ),
                parseDouble( effective, KEY_WEIGHT_ENGINE_DIVERGENCE, 1.0 ),
                parseDouble( effective, KEY_WEIGHT_VOCABULARY_GAP, 0.05 ),
                parseDouble( effective, KEY_WEIGHT_STALE_HIGH_TRAFFIC, 0.02 ),
                parseInt( effective, KEY_EFFECT_WINDOW_DAYS, 28 ),
                parseInt( effective, KEY_EFFECT_MIN_BASELINE_IMPRESSIONS, 100 ),
                parseInt( effective, KEY_CHANGE_COOLDOWN_DAYS, 60 ),
                parseInt( effective, KEY_CALIBRATION_MIN_VERDICTS, 20 ),
                parseDouble( effective, KEY_CALIBRATION_DAMPING, 0.5 ) );
    }

    /**
     * Builds the {@link OpportunityEngineConfig} this engine should run with, before calibration
     * substitutes any per-type weight. Fields this record does not carry a configuration key for
     * ({@code agentGapMinDistinctSessions}, {@code engineDivergenceMinSharedQueries}, the two
     * global-floor fields, and {@code vocabularyGapMinClicks} -- none of which §10 exposes a
     * property for) are taken from {@link OpportunityEngineConfig#defaults()} unchanged.
     *
     * @return the effective engine configuration for this settings snapshot
     */
    public OpportunityEngineConfig toEngineConfig() {
        final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();
        return new OpportunityEngineConfig(
                opportunityMinOccurrences,
                defaults.agentGapMinDistinctSessions(),
                opportunityMinImpressions,
                defaults.engineDivergenceMinSharedQueries(),
                opportunityDivergenceMinGap,
                defaults.vocabularyGapMinClicks(),
                opportunityMinImpressions,
                opportunityStaleDays,
                defaults.globalFloorMinImpressions(),
                defaults.globalFloorMinOccurrences(),
                changeCooldownDays,
                rulesGateImpressions28d,
                opportunityDivergenceMinImpressionsWeak,
                weightAgentGap,
                weightEngineDivergence,
                weightVocabularyGap,
                weightStaleHighTraffic );
    }

    private static boolean parseBoolean( final Properties props, final String key, final boolean defaultValue ) {
        final String raw = props.getProperty( key );
        if ( raw == null || raw.isBlank() ) {
            return defaultValue;
        }
        return Boolean.parseBoolean( raw.trim() );
    }

    private static int parseInt( final Properties props, final String key, final int defaultValue ) {
        final String raw = props.getProperty( key );
        if ( raw == null || raw.isBlank() ) {
            return defaultValue;
        }
        try {
            return Integer.parseInt( raw.trim() );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Malformed integer for '{}': '{}' -- falling back to default {}", key, raw, defaultValue );
            return defaultValue;
        }
    }

    private static double parseDouble( final Properties props, final String key, final double defaultValue ) {
        final String raw = props.getProperty( key );
        if ( raw == null || raw.isBlank() ) {
            return defaultValue;
        }
        try {
            return Double.parseDouble( raw.trim() );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Malformed number for '{}': '{}' -- falling back to default {}", key, raw, defaultValue );
            return defaultValue;
        }
    }

    private static List<String> parseSites( final Properties props ) {
        final String raw = props.getProperty( KEY_RULES_SITES );
        final String effective = ( raw == null || raw.isBlank() ) ? DEFAULT_RULES_SITES : raw;
        final List<String> sites = splitSites( effective );
        // A configured value that splits down to nothing (e.g. ",,,") falls back to the default
        // list rather than leaving ruleSites() empty, which would make every site request
        // unresolvable.
        return sites.isEmpty() ? splitSites( DEFAULT_RULES_SITES ) : sites;
    }

    private static List<String> splitSites( final String value ) {
        return Arrays.stream( value.split( "," ) )
                .map( String::trim )
                .filter( s -> !s.isEmpty() )
                .collect( Collectors.toUnmodifiableList() );
    }
}
