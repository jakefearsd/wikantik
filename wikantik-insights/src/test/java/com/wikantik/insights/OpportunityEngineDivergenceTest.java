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
 * Tests for {@link OpportunityEngine#evaluateEngineDivergence} (rule 2, content-intelligence
 * design §7.3, grain-corrected 2026-08-17 to operate on page-rollup rows -- see the method's
 * javadoc). Each of the three minimum-support dimensions -- strong-engine impressions,
 * weak-engine impressions, and position gap -- is tested just below, at, and just above its
 * threshold while the other two are held comfortably clear.
 */
class OpportunityEngineDivergenceTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 16 );
    private static final String PAGE = "/wiki/PhilosophyOfMind";
    private static final String SITE = "wiki.wikantik.com";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();
    private final ExpectedCtrCurve curve = ExpectedCtrCurve.defaultCurve();

    /** A page-level rollup row: real pagePath, blank queryText -- the only grain this rule reads. */
    private static VisibilityRow rollup( final String engineName, final int impressions, final int clicks,
                                         final double position ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, engineName, SITE, PAGE, "",
                impressions, clicks, position );
    }

    /** A strong (google, position 2.0) / weak (bing, position 12.0) rollup pair -- gap = 10.0. */
    private static List<VisibilityRow> pair( final int strongImpressions, final int weakImpressions ) {
        return List.of(
                rollup( "google", strongImpressions, 5, 2.0 ),
                rollup( "bing", weakImpressions, 1, 12.0 ) );
    }

    private List<Opportunity> evaluate( final List<VisibilityRow> rows, final OpportunityEngineConfig config ) {
        return engine.evaluateEngineDivergence( rows, curve, TODAY, config );
    }

    // --- strong-engine impressions threshold (default min 20, floor 10) ------------------------

    @Test
    void justBelowStrongImpressionThresholdStaysSilent() {
        assertTrue( evaluate( pair( 19, 10 ), defaults ).isEmpty() );
    }

    @Test
    void atStrongImpressionThresholdFires() {
        assertEquals( 1, evaluate( pair( 20, 10 ), defaults ).size() );
    }

    @Test
    void aboveStrongImpressionThresholdFires() {
        assertEquals( 1, evaluate( pair( 21, 10 ), defaults ).size() );
    }

    // --- weak-engine impressions threshold (default min 5, deliberately below the global floor) -

    @Test
    void justBelowWeakImpressionThresholdStaysSilent() {
        assertTrue( evaluate( pair( 20, 4 ), defaults ).isEmpty() );
    }

    @Test
    void atWeakImpressionThresholdFires() {
        // 5 is below globalFloorMinImpressions (10) -- proves the weak floor is used as-is,
        // never composed with the global floor (OpportunityEngineConfig#divergenceMinImpressionsWeak).
        final List<Opportunity> result = evaluate( pair( 20, 5 ), defaults );
        assertEquals( 1, result.size(),
                "weak-engine impressions of 5 must fire even though it is below the global floor of 10" );
    }

    @Test
    void aboveWeakImpressionThresholdFires() {
        assertEquals( 1, evaluate( pair( 20, 6 ), defaults ).size() );
    }

    // --- position-gap threshold (default min 10.0) ------------------------------------------------

    @Test
    void justBelowPositionGapThresholdStaysSilent() {
        final List<VisibilityRow> rows = List.of(
                rollup( "google", 20, 5, 10.0 ), rollup( "bing", 10, 1, 18.9 ) ); // gap = 8.9
        assertTrue( evaluate( rows, defaults ).isEmpty() );
    }

    @Test
    void atPositionGapThresholdFires() {
        final List<VisibilityRow> rows = List.of(
                rollup( "google", 20, 5, 10.0 ), rollup( "bing", 10, 1, 20.0 ) ); // gap = 10.0
        final Opportunity result = evaluate( rows, defaults ).get( 0 );
        assertEquals( 10.0, ( Double ) result.evidence().get( "positionGap" ), 0.0001 );
    }

    @Test
    void abovePositionGapThresholdFires() {
        final List<VisibilityRow> rows = List.of(
                rollup( "google", 20, 5, 10.0 ), rollup( "bing", 10, 1, 21.0 ) ); // gap = 11.0
        assertEquals( 1, evaluate( rows, defaults ).size() );
    }

    // --- global floor backstop on the strong side only --------------------------------------------

    @Test
    void globalFloorOverridesADeliberatelyMisconfiguredStrongImpressionThreshold() {
        final OpportunityEngineConfig misconfigured = new OpportunityEngineConfig(
                defaults.agentGapMinOccurrences(), defaults.agentGapMinDistinctSessions(),
                5, defaults.engineDivergenceMinSharedQueries(), defaults.engineDivergenceMinPositionGap(),
                defaults.vocabularyGapMinClicks(), defaults.staleHighTrafficMinImpressions(),
                defaults.staleDays(),
                defaults.globalFloorMinImpressions(), defaults.globalFloorMinOccurrences(),
                defaults.cooldownDays(),
                defaults.gateImpressions28d(), defaults.divergenceMinImpressionsWeak(),
                defaults.weightAgentGap(), defaults.weightEngineDivergence(),
                defaults.weightVocabularyGap(), defaults.weightStaleHighTraffic() );

        // 7 strong-engine impressions satisfies the misconfigured minimum of 5, but not the
        // global floor of 10.
        assertTrue( evaluate( pair( 7, 10 ), misconfigured ).isEmpty(),
                "7 impressions satisfies the misconfigured per-rule minimum of 5, but the global "
                + "floor of 10 must still block it" );
    }

    // --- silence / no-signal cases -----------------------------------------------------------------

    @Test
    void emptyInputIsSilent() {
        assertTrue( evaluate( List.of(), defaults ).isEmpty() );
    }

    @Test
    void queryRowsAreIgnored() {
        // The same numbers as a firing pair, but as per-query rows (non-blank queryText) -- this
        // rule's grain is page-rollup rows only (§7.3 rule 2 grain correction).
        final List<VisibilityRow> rows = List.of(
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "some query",
                        20, 5, 2.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, PAGE, "some query",
                        10, 1, 12.0 ) );
        assertTrue( evaluate( rows, defaults ).isEmpty(), "per-query rows must not be compared here" );
    }

    @Test
    void queryRollupRowsWithBlankPagePathAreIgnored() {
        final List<VisibilityRow> rows = List.of(
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, "", "", 500, 50, 2.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, null, "", 500, 50, 12.0 ) );
        assertTrue( evaluate( rows, defaults ).isEmpty(),
                "rows with no attributed page path must never produce an opportunity targeting "
                + "the empty string" );
    }

    @Test
    void singleEngineOnAPageProducesNoDivergence() {
        final List<VisibilityRow> rows = List.of( rollup( "google", 100, 20, 2.0 ) );
        assertTrue( evaluate( rows, defaults ).isEmpty() );
    }

    @Test
    void rowsWithANullPositionAreSkipped() {
        // bing's only row has no position -- it must not be treated as usable evidence for the
        // weak side, even though its impressions alone would clear the weak-engine floor.
        final List<VisibilityRow> rows = List.of(
                rollup( "google", 20, 5, 2.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, PAGE, "", 50, 5, null ) );
        assertTrue( evaluate( rows, defaults ).isEmpty(),
                "a row with no position contributes nothing -- effectively a single-engine page" );
    }

    @Test
    void withThreeEnginesOnlyTheBestAndWorstAreCompared() {
        // yandex sits in the middle -- neither strong nor weak -- and must not itself appear.
        final List<VisibilityRow> rows = List.of(
                rollup( "google", 20, 5, 2.0 ),
                rollup( "yandex", 20, 5, 7.0 ),
                rollup( "bing", 10, 1, 12.0 ) );
        final Opportunity result = evaluate( rows, defaults ).get( 0 );
        assertEquals( "google", result.evidence().get( "strongEngine" ) );
        assertEquals( "bing", result.evidence().get( "weakEngine" ) );
    }

    // --- priority arithmetic (the expected-CTR uplift, assert the actual number) -----------------

    @Test
    void priorityEqualsTheExpectedCtrUpliftArithmetic() {
        // strong = google @ position 2 -> ctrAt(2) = 0.15; weak = bing @ position 12 -> ctrAt(12)
        // falls in the 11-20 bucket = 0.012. uplift = 0.138. weak impressions = 5.
        // pre-weight uplift = 5 x 0.138 = 0.69; weightEngineDivergence default = 1.0.
        final List<VisibilityRow> rows = pair( 20, 5 );
        final Opportunity result = evaluate( rows, defaults ).get( 0 );

        assertEquals( 0.69, result.priority(), 0.0000001, "priority = weak_impressions x CTR uplift x weight" );
        assertEquals( 0.69, ( Double ) result.evidence().get( "estimatedRecoverableClicks" ), 0.0000001 );
    }

    @Test
    void priorityScalesWithTheConfiguredWeight() {
        final OpportunityEngineConfig doubled = new OpportunityEngineConfig(
                defaults.agentGapMinOccurrences(), defaults.agentGapMinDistinctSessions(),
                defaults.engineDivergenceMinImpressions(), defaults.engineDivergenceMinSharedQueries(),
                defaults.engineDivergenceMinPositionGap(),
                defaults.vocabularyGapMinClicks(), defaults.staleHighTrafficMinImpressions(),
                defaults.staleDays(),
                defaults.globalFloorMinImpressions(), defaults.globalFloorMinOccurrences(),
                defaults.cooldownDays(),
                defaults.gateImpressions28d(), defaults.divergenceMinImpressionsWeak(),
                defaults.weightAgentGap(), 2.0,
                defaults.weightVocabularyGap(), defaults.weightStaleHighTraffic() );

        final Opportunity result = evaluate( pair( 20, 5 ), doubled ).get( 0 );
        assertEquals( 1.38, result.priority(), 0.0000001 );
    }

    @Test
    void negativeUpliftIsFlooredAtZero() {
        // A pathological curve where the "weak" position has a higher CTR than the "strong"
        // position -- the priority formula must still floor at zero, never go negative.
        final ExpectedCtrCurve invertedCurve = position -> position < 5 ? 0.01 : 0.5;
        final List<VisibilityRow> rows = List.of(
                rollup( "google", 20, 5, 2.0 ), rollup( "bing", 10, 1, 20.0 ) );
        final Opportunity result = engine.evaluateEngineDivergence( rows, invertedCurve, TODAY, defaults ).get( 0 );
        assertEquals( 0.0, result.priority(), 0.0001 );
    }

    // --- shape / evidence -------------------------------------------------------------------------

    @Test
    void firingOpportunityCarriesExactEvidenceAndMetadata() {
        final List<VisibilityRow> rows = pair( 20, 5 );
        final Opportunity result = evaluate( rows, defaults ).get( 0 );

        assertEquals( OpportunityEngine.ENGINE_DIVERGENCE, result.type() );
        assertEquals( PAGE, result.target() );
        assertEquals( TODAY, result.firstSeen() );
        assertTrue( !result.calibrated() );
        assertEquals( "Diagnostic, usually \"change nothing on this page.\"", result.suggestedAction() );

        final Map<String, Object> evidence = result.evidence();
        assertEquals( "google", evidence.get( "strongEngine" ) );
        assertEquals( "bing", evidence.get( "weakEngine" ) );
        assertEquals( 2.0, ( Double ) evidence.get( "strongPosition" ), 0.0001 );
        assertEquals( 12.0, ( Double ) evidence.get( "weakPosition" ), 0.0001 );
        assertEquals( 10.0, ( Double ) evidence.get( "positionGap" ), 0.0001 );
        assertEquals( 20L, evidence.get( "strongImpressions" ) );
        assertEquals( 5L, evidence.get( "weakImpressions" ) );
        assertEquals( 0.69, ( Double ) evidence.get( "estimatedRecoverableClicks" ), 0.0000001 );
    }
}
