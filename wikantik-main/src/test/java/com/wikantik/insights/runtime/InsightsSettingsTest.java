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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InsightsSettings} (content-intelligence design §10).
 */
class InsightsSettingsTest {

    @Test
    void emptyPropertiesYieldsAllDefaults() {
        final InsightsSettings settings = InsightsSettings.from( new Properties() );

        assertTrue( settings.enabled() );
        assertEquals( 5000, settings.rulesGateImpressions28d() );
        assertEquals( List.of( "wiki.wikantik.com", "wikantik.com" ), settings.ruleSites() );
        assertEquals( 20, settings.opportunityMinImpressions() );
        assertEquals( 5, settings.opportunityDivergenceMinImpressionsWeak() );
        assertEquals( 2, settings.opportunityMinOccurrences() );
        assertEquals( 10.0, settings.opportunityDivergenceMinGap() );
        assertEquals( 180, settings.opportunityStaleDays() );
        assertEquals( 2.0, settings.weightAgentGap() );
        assertEquals( 1.0, settings.weightEngineDivergence() );
        assertEquals( 0.05, settings.weightVocabularyGap() );
        assertEquals( 0.02, settings.weightStaleHighTraffic() );
        assertEquals( 28, settings.effectWindowDays() );
        assertEquals( 100, settings.effectMinBaselineImpressions() );
        assertEquals( 60, settings.changeCooldownDays() );
        assertEquals( 20, settings.calibrationMinVerdicts() );
        assertEquals( 0.5, settings.calibrationDamping() );
        assertEquals( 7, settings.importedMaxAgeDays() );
        assertEquals( 0.008, settings.ctrDeep() );
        assertEquals( 20, settings.ctrDeepMaxPosition() );
    }

    @Test
    void nullPropertiesYieldsAllDefaults() {
        final InsightsSettings settings = InsightsSettings.from( null );

        assertTrue( settings.enabled() );
        assertEquals( 5000, settings.rulesGateImpressions28d() );
    }

    @Test
    void everyKeyCanBeOverridden() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_ENABLED, "false" );
        props.setProperty( InsightsSettings.KEY_RULES_GATE_IMPRESSIONS_28D, "9999" );
        props.setProperty( InsightsSettings.KEY_RULES_SITES, " example.com , other.example.com " );
        props.setProperty( InsightsSettings.KEY_OPPORTUNITY_MIN_IMPRESSIONS, "42" );
        props.setProperty( InsightsSettings.KEY_OPPORTUNITY_DIVERGENCE_MIN_IMPRESSIONS_WEAK, "7" );
        props.setProperty( InsightsSettings.KEY_OPPORTUNITY_MIN_OCCURRENCES, "3" );
        props.setProperty( InsightsSettings.KEY_OPPORTUNITY_DIVERGENCE_MIN_GAP, "15.5" );
        props.setProperty( InsightsSettings.KEY_OPPORTUNITY_STALE_DAYS, "90" );
        props.setProperty( InsightsSettings.KEY_WEIGHT_AGENT_GAP, "3.5" );
        props.setProperty( InsightsSettings.KEY_WEIGHT_ENGINE_DIVERGENCE, "2.5" );
        props.setProperty( InsightsSettings.KEY_WEIGHT_VOCABULARY_GAP, "0.1" );
        props.setProperty( InsightsSettings.KEY_WEIGHT_STALE_HIGH_TRAFFIC, "0.04" );
        props.setProperty( InsightsSettings.KEY_EFFECT_WINDOW_DAYS, "14" );
        props.setProperty( InsightsSettings.KEY_EFFECT_MIN_BASELINE_IMPRESSIONS, "200" );
        props.setProperty( InsightsSettings.KEY_CHANGE_COOLDOWN_DAYS, "30" );
        props.setProperty( InsightsSettings.KEY_CALIBRATION_MIN_VERDICTS, "10" );
        props.setProperty( InsightsSettings.KEY_CALIBRATION_DAMPING, "0.75" );
        props.setProperty( InsightsSettings.KEY_IMPORTED_MAX_AGE_DAYS, "3" );
        props.setProperty( InsightsSettings.KEY_CTR_DEEP, "0.01" );
        props.setProperty( InsightsSettings.KEY_CTR_DEEP_MAX_POSITION, "25" );

        final InsightsSettings settings = InsightsSettings.from( props );

        assertFalse( settings.enabled() );
        assertEquals( 9999, settings.rulesGateImpressions28d() );
        assertEquals( List.of( "example.com", "other.example.com" ), settings.ruleSites() );
        assertEquals( 42, settings.opportunityMinImpressions() );
        assertEquals( 7, settings.opportunityDivergenceMinImpressionsWeak() );
        assertEquals( 3, settings.opportunityMinOccurrences() );
        assertEquals( 15.5, settings.opportunityDivergenceMinGap() );
        assertEquals( 90, settings.opportunityStaleDays() );
        assertEquals( 3.5, settings.weightAgentGap() );
        assertEquals( 2.5, settings.weightEngineDivergence() );
        assertEquals( 0.1, settings.weightVocabularyGap() );
        assertEquals( 0.04, settings.weightStaleHighTraffic() );
        assertEquals( 14, settings.effectWindowDays() );
        assertEquals( 200, settings.effectMinBaselineImpressions() );
        assertEquals( 30, settings.changeCooldownDays() );
        assertEquals( 10, settings.calibrationMinVerdicts() );
        assertEquals( 0.75, settings.calibrationDamping() );
        assertEquals( 3, settings.importedMaxAgeDays() );
        assertEquals( 0.01, settings.ctrDeep() );
        assertEquals( 25, settings.ctrDeepMaxPosition() );
    }

    @Test
    void malformedIntegerFallsBackToDefaultWithoutThrowing() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_OPPORTUNITY_MIN_IMPRESSIONS, "not-a-number" );

        final InsightsSettings settings = assertDoesNotThrow( () -> InsightsSettings.from( props ) );

        assertEquals( 20, settings.opportunityMinImpressions() );
    }

    @Test
    void malformedDoubleFallsBackToDefaultWithoutThrowing() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_CALIBRATION_DAMPING, "half" );

        final InsightsSettings settings = assertDoesNotThrow( () -> InsightsSettings.from( props ) );

        assertEquals( 0.5, settings.calibrationDamping() );
    }

    @Test
    void malformedCtrDeepFallsBackToDefaultWithoutThrowing() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_CTR_DEEP, "not-a-number" );

        final InsightsSettings settings = assertDoesNotThrow( () -> InsightsSettings.from( props ) );

        assertEquals( 0.008, settings.ctrDeep() );
    }

    @Test
    void malformedCtrDeepMaxPositionFallsBackToDefaultWithoutThrowing() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_CTR_DEEP_MAX_POSITION, "not-a-number" );

        final InsightsSettings settings = assertDoesNotThrow( () -> InsightsSettings.from( props ) );

        assertEquals( 20, settings.ctrDeepMaxPosition() );
    }

    @Test
    void blankRulesSitesFallsBackToDefaultSites() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_RULES_SITES, "   " );

        final InsightsSettings settings = InsightsSettings.from( props );

        assertEquals( List.of( "wiki.wikantik.com", "wikantik.com" ), settings.ruleSites() );
    }

    @Test
    void toEngineConfigCarriesConfiguredValuesAndFillsUnconfiguredFieldsFromDefaults() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_OPPORTUNITY_MIN_IMPRESSIONS, "50" );
        props.setProperty( InsightsSettings.KEY_WEIGHT_AGENT_GAP, "9.0" );
        final InsightsSettings settings = InsightsSettings.from( props );

        final OpportunityEngineConfig config = settings.toEngineConfig();
        final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();

        // configured
        assertEquals( 50, config.engineDivergenceMinImpressions() );
        assertEquals( 50, config.staleHighTrafficMinImpressions() );
        assertEquals( 9.0, config.weightAgentGap() );

        // not exposed via any wikantik.insights.* key -- taken from OpportunityEngineConfig.defaults()
        assertEquals( defaults.agentGapMinDistinctSessions(), config.agentGapMinDistinctSessions() );
        assertEquals( defaults.engineDivergenceMinSharedQueries(), config.engineDivergenceMinSharedQueries() );
        assertEquals( defaults.vocabularyGapMinClicks(), config.vocabularyGapMinClicks() );
        assertEquals( defaults.globalFloorMinImpressions(), config.globalFloorMinImpressions() );
        assertEquals( defaults.globalFloorMinOccurrences(), config.globalFloorMinOccurrences() );
    }
}
