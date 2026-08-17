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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpportunityEngine#evaluateStaleHighTraffic} (rule 4, content-intelligence
 * design section 7.3).
 */
class OpportunityEngineStaleHighTrafficTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 16 );
    private static final Instant TODAY_INSTANT = TODAY.atStartOfDay( ZoneOffset.UTC ).toInstant();
    private static final String PAGE = "/wiki/PhilosophyOfMind";
    private static final String SITE = "wiki.wikantik.com";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();

    /** A page-level rollup row: real pagePath, blank queryText -- see D3. */
    private static VisibilityRow rollup( final String engineName, final int impressions ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, engineName, SITE, PAGE, "",
                impressions, 0, null );
    }

    private static PageFacts factsWith( final Instant verifiedAt, final String confidence ) {
        return new MapPageFacts( Map.of( PAGE,
                new PageFacts.PageFact( PAGE, "Philosophy of Mind", "summary", List.of(),
                        "philosophy", verifiedAt, confidence ) ) );
    }

    private static final PageFacts NEVER_VERIFIED = factsWith( null, null );

    // --- impressions threshold (default min 200, floor 10) -------------------------------------

    @Test
    void justBelowImpressionThresholdStaysSilent() {
        final List<VisibilityRow> rows = List.of( rollup( "google", 199 ) );
        assertTrue( engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, defaults ).isEmpty() );
    }

    @Test
    void atImpressionThresholdFires() {
        final List<VisibilityRow> rows = List.of( rollup( "google", 200 ) );
        final List<Opportunity> result = engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 4.0, result.get( 0 ).priority(), 0.0001, "priority = impressions x 0.02" );
    }

    @Test
    void aboveImpressionThresholdFiresWithScaledPriority() {
        final List<VisibilityRow> rows = List.of( rollup( "google", 201 ) );
        final List<Opportunity> result = engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 4.02, result.get( 0 ).priority(), 0.0001 );
    }

    // --- the required null-verified-at-with-low-impressions silence case -----------------------

    @Test
    void nullVerifiedAtWithLowImpressionsStaysSilent() {
        final List<VisibilityRow> rows = List.of( rollup( "google", 50 ) );
        assertTrue( engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, defaults ).isEmpty(),
                "never-verified counts as stale, but only once the impressions floor also clears" );
    }

    // --- global floor backstop -------------------------------------------------------------------

    @Test
    void globalFloorOverridesADeliberatelyMisconfiguredPerRuleThreshold() {
        final OpportunityEngineConfig misconfigured = new OpportunityEngineConfig(
                defaults.agentGapMinOccurrences(), defaults.agentGapMinDistinctSessions(),
                defaults.engineDivergenceMinImpressions(), defaults.engineDivergenceMinSharedQueries(),
                defaults.engineDivergenceMinPositionGap(),
                defaults.vocabularyGapMinClicks(), 5, defaults.staleDays(),
                defaults.globalFloorMinImpressions(), defaults.globalFloorMinOccurrences(),
                defaults.cooldownDays() );

        final List<VisibilityRow> rows = List.of( rollup( "google", 7 ) );
        assertTrue( engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, misconfigured ).isEmpty(),
                "7 impressions satisfies the misconfigured per-rule minimum of 5, but the global "
                + "floor of 10 must still block it" );
    }

    // --- staleness: age boundary (default 180 days, ">" not ">=") ------------------------------

    @Test
    void atStaleDaysBoundaryStaysSilent() {
        final PageFacts facts = factsWith( TODAY_INSTANT.minus( 180, ChronoUnit.DAYS ),
                "authoritative" );
        final List<VisibilityRow> rows = List.of( rollup( "google", 300 ) );
        assertTrue( engine.evaluateStaleHighTraffic( rows, facts, TODAY, defaults ).isEmpty(),
                "exactly 180 days is not yet \"older than\" 180 days" );
    }

    @Test
    void justAboveStaleDaysBoundaryFires() {
        final PageFacts facts = factsWith( TODAY_INSTANT.minus( 181, ChronoUnit.DAYS ),
                "authoritative" );
        final List<VisibilityRow> rows = List.of( rollup( "google", 300 ) );
        assertEquals( 1, engine.evaluateStaleHighTraffic( rows, facts, TODAY, defaults ).size() );
    }

    // --- staleness: confidence -------------------------------------------------------------------

    @Test
    void freshAndAuthoritativeStaysSilent() {
        final PageFacts facts = factsWith( TODAY_INSTANT.minus( 1, ChronoUnit.DAYS ),
                "authoritative" );
        final List<VisibilityRow> rows = List.of( rollup( "google", 300 ) );
        assertTrue( engine.evaluateStaleHighTraffic( rows, facts, TODAY, defaults ).isEmpty() );
    }

    @Test
    void freshButNotAuthoritativeStillFires() {
        final PageFacts facts = factsWith( TODAY_INSTANT.minus( 1, ChronoUnit.DAYS ),
                "provisional" );
        final List<VisibilityRow> rows = List.of( rollup( "google", 300 ) );
        assertEquals( 1, engine.evaluateStaleHighTraffic( rows, facts, TODAY, defaults ).size(),
                "confidence pinned below authoritative is stale regardless of verification age" );
    }

    // --- unknown page: skipped ---------------------------------------------------------------------

    @Test
    void unknownPageIsSkipped() {
        final PageFacts empty = new MapPageFacts( Map.of() );
        final List<VisibilityRow> rows = List.of( rollup( "google", 300 ) );
        assertTrue( engine.evaluateStaleHighTraffic( rows, empty, TODAY, defaults ).isEmpty() );
    }

    // --- only the page-level rollup row is authoritative for totals (D3) -----------------------

    @Test
    void perQueryRowsAreNotUsedForTotals() {
        final List<VisibilityRow> rows = List.of(
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "query a", 150, 5, 3.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "query b", 150, 5, 3.0 ) );
        assertTrue( engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, defaults ).isEmpty(),
                "300 impressions summed across per-query rows must not substitute for the rollup row" );
    }

    @Test
    void rollupImpressionsSumAcrossEnginesAndSnapshots() {
        final List<VisibilityRow> rows = List.of(
                rollup( "google", 120 ),
                rollup( "bing", 90 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 8 ), 28, "google", SITE, PAGE, "", 30, 0, null ) );
        final List<Opportunity> result = engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 240L, result.get( 0 ).evidence().get( "impressions" ) );
    }

    // --- silence / empty input ----------------------------------------------------------------------

    @Test
    void emptyInputIsSilent() {
        assertTrue( engine.evaluateStaleHighTraffic( List.of(), NEVER_VERIFIED, TODAY, defaults ).isEmpty() );
    }

    // --- shape / evidence -----------------------------------------------------------------------------

    @Test
    void firingOpportunityCarriesExactEvidenceAndMetadata() {
        final List<VisibilityRow> rows = List.of( rollup( "google", 500 ) );
        final Opportunity result = engine.evaluateStaleHighTraffic( rows, NEVER_VERIFIED, TODAY, defaults ).get( 0 );

        assertEquals( OpportunityEngine.STALE_HIGH_TRAFFIC, result.type() );
        assertEquals( PAGE, result.target() );
        assertEquals( 10.0, result.priority(), 0.0001 );
        assertEquals( TODAY, result.firstSeen() );
        assertTrue( !result.calibrated() );
        assertEquals( "Re-verify or refresh the page.", result.suggestedAction() );

        final Map<String, Object> evidence = result.evidence();
        assertEquals( 500L, evidence.get( "impressions" ) );
        assertEquals( null, evidence.get( "verifiedAt" ) );
        assertEquals( null, evidence.get( "daysSinceVerified" ) );
        assertEquals( null, evidence.get( "confidence" ) );
    }
}
