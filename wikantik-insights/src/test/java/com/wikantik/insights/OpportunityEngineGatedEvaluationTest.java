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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpportunityEngine#evaluateGated} -- the §7.3.0 site-level traffic gate applied
 * to the three visibility-driven rules, and the {@link Backlog#suppressed()} reporting that keeps
 * the gate's silence legible.
 */
class OpportunityEngineGatedEvaluationTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 17 );
    private static final String SITE = "wiki.wikantik.com";
    private static final String PAGE_DIVERGENCE = "/wiki/DivergencePage";
    private static final String PAGE_VOCAB = "/wiki/VocabPage";
    private static final String PAGE_STALE = "/wiki/StalePage";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();
    private final ExpectedCtrCurve curve = ExpectedCtrCurve.defaultCurve();

    /**
     * Rows that, on an open gate, make all three visibility-driven rules fire on three distinct
     * pages -- distinct so no cross-rule suppression (divergence vs. vocabulary-gap) muddies which
     * absence is caused by the gate versus by a cross-rule constraint.
     */
    private static List<VisibilityRow> visibilityRowsForAllThreeRules() {
        return List.of(
                // ENGINE_DIVERGENCE: page rollup rows, gap = 10.
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE_DIVERGENCE, "",
                        20, 5, 2.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, PAGE_DIVERGENCE, "",
                        5, 1, 12.0 ),
                // VOCABULARY_GAP: a per-query row with no vocabulary overlap.
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE_VOCAB,
                        "epistemology", 100, 5, 5.0 ),
                // STALE_HIGH_TRAFFIC: a page rollup row clearing the impressions floor.
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE_STALE, "",
                        300, 0, null ) );
    }

    private static PageFacts pageFactsForAllThreeRules() {
        return new MapPageFacts( Map.of(
                PAGE_VOCAB, new PageFacts.PageFact( PAGE_VOCAB, "Other Topic", null, List.of(),
                        "philosophy", null, "authoritative" ),
                PAGE_STALE, new PageFacts.PageFact( PAGE_STALE, "Some Page", null, List.of(),
                        "philosophy", null, null ) ) );
    }

    private static List<DemandRow> agentGapDemandRow() {
        return List.of( new DemandRow( "some query", null, 0, 5, 5 ) );
    }

    // --- gate closed: the three visibility-driven rules are absent and reported suppressed -------

    @Test
    void gateClosedSuppressesTheThreeVisibilityDrivenRulesButNotAgentGapOrImports() {
        final Opportunity imported = new Opportunity( "ctr_gap", "/wiki/ImportedPage", 12.0, Map.of(),
                "jakemon-supplied action", TODAY, false );

        final int siteImpressions28d = 2000; // below the default gate of 5000
        final Backlog backlog = engine.evaluateGated( agentGapDemandRow(), visibilityRowsForAllThreeRules(),
                List.of( imported ), pageFactsForAllThreeRules(), List.of(), Map.of(),
                siteImpressions28d, Set.of(), curve, TODAY, defaults );

        final List<String> firedTypes = backlog.opportunities().stream().map( Opportunity::type ).toList();
        assertTrue( firedTypes.contains( OpportunityEngine.AGENT_GAP ), "AGENT_GAP is never gated" );
        assertTrue( firedTypes.contains( "ctr_gap" ), "imported opportunities are never gated" );
        assertFalse( firedTypes.contains( OpportunityEngine.ENGINE_DIVERGENCE ) );
        assertFalse( firedTypes.contains( OpportunityEngine.VOCABULARY_GAP ) );
        assertFalse( firedTypes.contains( OpportunityEngine.STALE_HIGH_TRAFFIC ) );

        final Map<String, SuppressedRule> suppressedByType = new LinkedHashMap<>();
        for ( final SuppressedRule rule : backlog.suppressed() ) {
            suppressedByType.put( rule.type(), rule );
        }
        assertEquals( 3, backlog.suppressed().size() );
        assertTrue( suppressedByType.containsKey( OpportunityEngine.ENGINE_DIVERGENCE ) );
        assertTrue( suppressedByType.containsKey( OpportunityEngine.VOCABULARY_GAP ) );
        assertTrue( suppressedByType.containsKey( OpportunityEngine.STALE_HIGH_TRAFFIC ) );

        for ( final SuppressedRule rule : backlog.suppressed() ) {
            assertEquals( "traffic_gate", rule.reason() );
            assertEquals( 2000.0, rule.measured(), 0.0001 );
            assertEquals( 5000.0, rule.required(), 0.0001 );
        }
    }

    // --- gate open: all rules run, nothing is reported suppressed ---------------------------------

    @Test
    void gateOpenRunsAllRulesAndReportsNothingSuppressed() {
        final int siteImpressions28d = 5000; // exactly at the default gate -- open (>=)
        final Backlog backlog = engine.evaluateGated( agentGapDemandRow(), visibilityRowsForAllThreeRules(),
                List.of(), pageFactsForAllThreeRules(), List.of(), Map.of(),
                siteImpressions28d, Set.of(), curve, TODAY, defaults );

        final List<String> firedTypes = backlog.opportunities().stream().map( Opportunity::type ).toList();
        assertTrue( firedTypes.contains( OpportunityEngine.AGENT_GAP ) );
        assertTrue( firedTypes.contains( OpportunityEngine.ENGINE_DIVERGENCE ) );
        assertTrue( firedTypes.contains( OpportunityEngine.VOCABULARY_GAP ) );
        assertTrue( firedTypes.contains( OpportunityEngine.STALE_HIGH_TRAFFIC ) );
        assertTrue( backlog.suppressed().isEmpty() );
    }

    @Test
    void wellAboveTheGateAlsoRunsAllRules() {
        final Backlog backlog = engine.evaluateGated( agentGapDemandRow(), visibilityRowsForAllThreeRules(),
                List.of(), pageFactsForAllThreeRules(), List.of(), Map.of(),
                50_000, Set.of(), curve, TODAY, defaults );
        assertEquals( 4, backlog.opportunities().stream().map( Opportunity::type ).distinct().count() );
        assertTrue( backlog.suppressed().isEmpty() );
    }

    // --- calibratedTypes controls the calibrated flag on every returned opportunity ----------------

    @Test
    void calibratedTypesControlsTheCalibratedFlag() {
        final Opportunity ctrGap = new Opportunity( "ctr_gap", "/wiki/ImportedPage", 12.0, Map.of(),
                "jakemon-supplied action", TODAY, false );
        final Opportunity contentGap = new Opportunity( "content_gap", "/wiki/OtherImportedPage", 8.0,
                Map.of(), "jakemon-supplied action", TODAY, false );

        final Backlog backlog = engine.evaluateGated( agentGapDemandRow(), List.of(),
                List.of( ctrGap, contentGap ), new MapPageFacts( Map.of() ), List.of(), Map.of(),
                50_000, Set.of( OpportunityEngine.AGENT_GAP, "ctr_gap" ), curve, TODAY, defaults );

        final Map<String, Boolean> calibratedByType = new LinkedHashMap<>();
        for ( final Opportunity opportunity : backlog.opportunities() ) {
            calibratedByType.put( opportunity.type(), opportunity.calibrated() );
        }

        assertTrue( calibratedByType.get( OpportunityEngine.AGENT_GAP ),
                "agent_gap is in calibratedTypes -- must report calibrated: true" );
        assertTrue( calibratedByType.get( "ctr_gap" ),
                "calibration applies to imported opportunities too" );
        assertFalse( calibratedByType.get( "content_gap" ),
                "content_gap is not in calibratedTypes -- must report calibrated: false" );
    }

    @Test
    void emptyCalibratedTypesLeavesEveryOpportunityUncalibrated() {
        final Backlog backlog = engine.evaluateGated( agentGapDemandRow(), List.of(), List.of(),
                new MapPageFacts( Map.of() ), List.of(), Map.of(), 50_000, Set.of(), curve, TODAY, defaults );

        assertFalse( backlog.opportunities().isEmpty() );
        assertTrue( backlog.opportunities().stream().noneMatch( Opportunity::calibrated ) );
    }
}
