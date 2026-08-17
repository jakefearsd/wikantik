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

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpportunityEngine#evaluateAgentGap} (rule 1, content-intelligence design
 * §7.3). Every threshold dimension is tested just below, at, and just above its boundary, and
 * silence on insufficient support is asserted explicitly -- an empty result is a valid, checked
 * output here, not a gap in coverage.
 */
class OpportunityEngineAgentGapTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 16 );

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();

    // --- occurrences threshold (default min 2, floor 2) ------------------------------------

    @Test
    void justBelowOccurrenceThresholdStaysSilent() {
        final DemandRow row = new DemandRow( "philosophy of mind", null, 0, 1, 2 );
        assertTrue( engine.evaluateAgentGap( List.of( row ), TODAY, defaults ).isEmpty(),
                "1 occurrence is below the minimum of 2 and must not fire" );
    }

    @Test
    void atOccurrenceThresholdFires() {
        final DemandRow row = new DemandRow( "philosophy of mind", null, 0, 2, 2 );
        final List<Opportunity> result = engine.evaluateAgentGap( List.of( row ), TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 4.0, result.get( 0 ).priority(), 0.0001, "priority = occurrences x 2" );
    }

    @Test
    void aboveOccurrenceThresholdFiresWithScaledPriority() {
        final DemandRow row = new DemandRow( "philosophy of mind", null, 0, 3, 3 );
        final List<Opportunity> result = engine.evaluateAgentGap( List.of( row ), TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 6.0, result.get( 0 ).priority(), 0.0001 );
    }

    // --- distinct-sessions threshold (default min 2) ---------------------------------------

    @Test
    void justBelowDistinctSessionThresholdStaysSilentEvenWithManyOccurrences() {
        final DemandRow row = new DemandRow( "philosophy of mind", null, 0, 5, 1 );
        assertTrue( engine.evaluateAgentGap( List.of( row ), TODAY, defaults ).isEmpty(),
                "5 occurrences from a single call must not fire -- support requires >=2 distinct calls" );
    }

    @Test
    void atDistinctSessionThresholdFires() {
        final DemandRow row = new DemandRow( "philosophy of mind", null, 0, 2, 2 );
        assertEquals( 1, engine.evaluateAgentGap( List.of( row ), TODAY, defaults ).size() );
    }

    @Test
    void aboveDistinctSessionThresholdFires() {
        final DemandRow row = new DemandRow( "philosophy of mind", null, 0, 3, 3 );
        assertEquals( 1, engine.evaluateAgentGap( List.of( row ), TODAY, defaults ).size() );
    }

    // --- "every occurrence weak" -------------------------------------------------------------

    @Test
    void staysSilentWhenNotEveryOccurrenceForTheQueryWasWeak() {
        final DemandRow weak = new DemandRow( "philosophy of mind", null, 0, 2, 2 );
        final DemandRow strong = new DemandRow( "philosophy of mind", "strong", 8, 1, 1 );
        final List<Opportunity> result = engine.evaluateAgentGap( List.of( weak, strong ), TODAY, defaults );
        assertTrue( result.isEmpty(),
                "a single non-weak occurrence means the gap is not consistent for this query" );
    }

    @Test
    void staysSilentWhenCoverageIsStrongOrPartialRegardlessOfOccurrenceCount() {
        final DemandRow row = new DemandRow( "philosophy of mind", "partial", 4, 10, 10 );
        assertTrue( engine.evaluateAgentGap( List.of( row ), TODAY, defaults ).isEmpty() );
    }

    @Test
    void zeroResultCountIsWeakRegardlessOfReportedCoverage() {
        final DemandRow row = new DemandRow( "philosophy of mind", "strong", 0, 2, 2 );
        final List<Opportunity> result = engine.evaluateAgentGap( List.of( row ), TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 2L, result.get( 0 ).evidence().get( "zeroResultOccurrences" ) );
        assertEquals( 0L, result.get( 0 ).evidence().get( "weakCoverageOccurrences" ) );
    }

    @Test
    void weakOrUnknownCoverageIsWeakEvenWithNonzeroResults() {
        final DemandRow weak = new DemandRow( "philosophy of mind", "weak", 5, 1, 1 );
        final DemandRow unknown = new DemandRow( "philosophy of mind", "unknown", 3, 1, 1 );
        final List<Opportunity> result = engine.evaluateAgentGap( List.of( weak, unknown ), TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 0L, result.get( 0 ).evidence().get( "zeroResultOccurrences" ) );
        assertEquals( 2L, result.get( 0 ).evidence().get( "weakCoverageOccurrences" ) );
    }

    // --- global floor backstop ---------------------------------------------------------------

    @Test
    void globalFloorOverridesADeliberatelyMisconfiguredPerRuleThreshold() {
        // Misconfigure the rule's own threshold down to 1 -- below the global floor of 2.
        final OpportunityEngineConfig misconfigured = new OpportunityEngineConfig(
                1, defaults.agentGapMinDistinctSessions(),
                defaults.engineDivergenceMinImpressions(), defaults.engineDivergenceMinSharedQueries(),
                defaults.engineDivergenceMinPositionGap(),
                defaults.vocabularyGapMinClicks(), defaults.staleHighTrafficMinImpressions(),
                defaults.staleDays(),
                defaults.globalFloorMinImpressions(), defaults.globalFloorMinOccurrences(),
                defaults.cooldownDays(),
                defaults.gateImpressions28d(), defaults.divergenceMinImpressionsWeak(),
                defaults.weightAgentGap(), defaults.weightEngineDivergence(),
                defaults.weightVocabularyGap(), defaults.weightStaleHighTraffic() );

        final DemandRow row = new DemandRow( "philosophy of mind", null, 0, 1, 2 );
        assertTrue( engine.evaluateAgentGap( List.of( row ), TODAY, misconfigured ).isEmpty(),
                "1 occurrence satisfies the misconfigured per-rule minimum of 1, but the global "
                + "floor of 2 must still block it" );
    }

    // --- shape / evidence ----------------------------------------------------------------------

    @Test
    void firingOpportunityCarriesExactEvidenceAndMetadata() {
        final DemandRow row = new DemandRow( "philosophy of mind", "weak", 0, 4, 3 );
        final Opportunity result = engine.evaluateAgentGap( List.of( row ), TODAY, defaults ).get( 0 );

        assertEquals( OpportunityEngine.AGENT_GAP, result.type() );
        assertEquals( "philosophy of mind", result.target() );
        assertEquals( 8.0, result.priority(), 0.0001 );
        assertEquals( TODAY, result.firstSeen() );
        assertTrue( !result.calibrated(), "no calibration mechanism exists yet -- must default to uncalibrated" );
        assertEquals( "Curate the Knowledge Graph relations, or write the missing section.",
                result.suggestedAction() );

        final Map<String, Object> evidence = result.evidence();
        assertEquals( "philosophy of mind", evidence.get( "queryText" ) );
        assertEquals( 4, evidence.get( "occurrences" ) );
        assertEquals( 3, evidence.get( "distinctSessions" ) );
        assertEquals( 4L, evidence.get( "zeroResultOccurrences" ) );
        assertEquals( 0L, evidence.get( "weakCoverageOccurrences" ) );
    }

    @Test
    void emptyInputIsSilent() {
        assertTrue( engine.evaluateAgentGap( List.of(), TODAY, defaults ).isEmpty() );
    }

    @Test
    void twoDistinctQueriesProduceTwoIndependentOpportunities() {
        final DemandRow a = new DemandRow( "a", null, 0, 2, 2 );
        final DemandRow b = new DemandRow( "b", null, 0, 3, 3 );
        final List<Opportunity> result = engine.evaluateAgentGap( List.of( a, b ), TODAY, defaults );
        assertEquals( 2, result.size() );
    }
}
