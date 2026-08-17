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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpportunityEngine#evaluateEngineDivergence} (rule 2, content-intelligence
 * design §7.3). Each of the three minimum-support dimensions -- strong-engine impressions, shared
 * queries, and position gap -- is tested just below, at, and just above its threshold while the
 * other two are held comfortably clear.
 */
class OpportunityEngineDivergenceTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 16 );
    private static final String PAGE = "/wiki/PhilosophyOfMind";
    private static final String SITE = "wiki.wikantik.com";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();

    private static VisibilityRow row( final String engineName, final String query,
                                      final int impressions, final int clicks, final double position ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, engineName, SITE, PAGE, query,
                impressions, clicks, position );
    }

    /** Builds {@code sharedQueries} matching (google, bing) query-pair rows for {@link #PAGE}. */
    private static List<VisibilityRow> sharedRows( final int sharedQueries, final int strongImpressionsEach,
                                                    final double strongPosition, final double weakPosition ) {
        final List<VisibilityRow> rows = new ArrayList<>();
        for ( int i = 0; i < sharedQueries; i++ ) {
            final String query = "query " + i;
            rows.add( row( "google", query, strongImpressionsEach, 5, strongPosition ) );
            rows.add( row( "bing", query, 3, 1, weakPosition ) );
        }
        return rows;
    }

    // --- strong-engine impressions threshold (default min 100, floor 10) -------------------

    @Test
    void justBelowImpressionThresholdStaysSilent() {
        final List<VisibilityRow> rows = sharedRows( 10, 9, 3.0, 20.0 ); // strong total = 90
        assertTrue( engine.evaluateEngineDivergence( rows, TODAY, defaults ).isEmpty() );
    }

    @Test
    void atImpressionThresholdFires() {
        final List<VisibilityRow> rows = sharedRows( 10, 10, 3.0, 20.0 ); // strong total = 100
        final List<Opportunity> result = engine.evaluateEngineDivergence( rows, TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 25.0, result.get( 0 ).priority(), 0.0001, "priority = strong_engine_clicks x 0.5" );
    }

    @Test
    void aboveImpressionThresholdFires() {
        final List<VisibilityRow> rows = sharedRows( 10, 11, 3.0, 20.0 ); // strong total = 110
        assertEquals( 1, engine.evaluateEngineDivergence( rows, TODAY, defaults ).size() );
    }

    // --- shared-queries threshold (default min 10) -------------------------------------------

    @Test
    void justBelowSharedQueryThresholdStaysSilentDespiteHighImpressions() {
        final List<VisibilityRow> rows = sharedRows( 9, 20, 3.0, 20.0 ); // 9 shared queries, 180 impressions
        assertTrue( engine.evaluateEngineDivergence( rows, TODAY, defaults ).isEmpty() );
    }

    @Test
    void atSharedQueryThresholdFires() {
        final List<VisibilityRow> rows = sharedRows( 10, 20, 3.0, 20.0 );
        final Opportunity result = engine.evaluateEngineDivergence( rows, TODAY, defaults ).get( 0 );
        assertEquals( 10, result.evidence().get( "sharedQueries" ) );
    }

    @Test
    void aboveSharedQueryThresholdFires() {
        final List<VisibilityRow> rows = sharedRows( 11, 20, 3.0, 20.0 );
        assertEquals( 1, engine.evaluateEngineDivergence( rows, TODAY, defaults ).size() );
    }

    @Test
    void queriesUniqueToOneEngineDoNotCountAsShared() {
        final List<VisibilityRow> rows = new ArrayList<>( sharedRows( 9, 20, 3.0, 20.0 ) );
        rows.add( row( "google", "google only query", 50, 5, 3.0 ) ); // not answered by bing
        assertTrue( engine.evaluateEngineDivergence( rows, TODAY, defaults ).isEmpty(),
                "an engine-exclusive query must not inflate the shared-query count" );
    }

    // --- position-gap threshold (default min 10.0) --------------------------------------------

    @Test
    void justBelowPositionGapThresholdStaysSilent() {
        final List<VisibilityRow> rows = sharedRows( 10, 20, 10.0, 19.0 ); // gap = 9
        assertTrue( engine.evaluateEngineDivergence( rows, TODAY, defaults ).isEmpty() );
    }

    @Test
    void atPositionGapThresholdFires() {
        final List<VisibilityRow> rows = sharedRows( 10, 20, 10.0, 20.0 ); // gap = 10
        final Opportunity result = engine.evaluateEngineDivergence( rows, TODAY, defaults ).get( 0 );
        assertEquals( 10.0, result.evidence().get( "positionGap" ) );
    }

    @Test
    void abovePositionGapThresholdFires() {
        final List<VisibilityRow> rows = sharedRows( 10, 20, 10.0, 21.0 ); // gap = 11
        assertEquals( 1, engine.evaluateEngineDivergence( rows, TODAY, defaults ).size() );
    }

    // --- global floor backstop ------------------------------------------------------------------

    @Test
    void globalFloorOverridesADeliberatelyMisconfiguredImpressionThreshold() {
        // Misconfigure the rule's own minimum down to 5 -- below the global floor of 10.
        final OpportunityEngineConfig misconfigured = new OpportunityEngineConfig(
                defaults.agentGapMinOccurrences(), defaults.agentGapMinDistinctSessions(),
                5, defaults.engineDivergenceMinSharedQueries(), defaults.engineDivergenceMinPositionGap(),
                defaults.vocabularyGapMinClicks(), defaults.staleHighTrafficMinImpressions(),
                defaults.staleDays(),
                defaults.globalFloorMinImpressions(), defaults.globalFloorMinOccurrences(),
                defaults.cooldownDays() );

        // 10 shared queries, 0 or 1 impression each -> strong total = 7: satisfies the
        // misconfigured minimum of 5 but not the global floor of 10.
        final List<VisibilityRow> rows = new ArrayList<>();
        for ( int i = 0; i < 10; i++ ) {
            final int impressions = i < 7 ? 1 : 0;
            rows.add( row( "google", "query " + i, impressions, 5, 3.0 ) );
            rows.add( row( "bing", "query " + i, 3, 1, 20.0 ) );
        }

        assertTrue( engine.evaluateEngineDivergence( rows, TODAY, misconfigured ).isEmpty(),
                "7 impressions satisfies the misconfigured per-rule minimum of 5, but the global "
                + "floor of 10 must still block it" );
    }

    // --- silence / no-signal cases ---------------------------------------------------------------

    @Test
    void emptyInputIsSilent() {
        assertTrue( engine.evaluateEngineDivergence( List.of(), TODAY, defaults ).isEmpty() );
    }

    @Test
    void pageRollupAndQueryRollupRowsAreIgnored() {
        final List<VisibilityRow> rows = List.of(
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "", 500, 50, 3.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, "", "some query", 500, 50, 40.0 ) );
        assertTrue( engine.evaluateEngineDivergence( rows, TODAY, defaults ).isEmpty(),
                "rollup rows carry no per-engine query set and must not be compared" );
    }

    @Test
    void singleEngineOnAPageProducesNoDivergence() {
        final List<VisibilityRow> rows = sharedRows( 10, 20, 3.0, 20.0 ).stream()
                .filter( r -> "google".equals( r.engine() ) ).toList();
        assertTrue( engine.evaluateEngineDivergence( rows, TODAY, defaults ).isEmpty() );
    }

    // --- shape / evidence -------------------------------------------------------------------------

    @Test
    void firingOpportunityCarriesExactEvidenceAndMetadata() {
        final List<VisibilityRow> rows = sharedRows( 10, 20, 3.0, 20.0 );
        final Opportunity result = engine.evaluateEngineDivergence( rows, TODAY, defaults ).get( 0 );

        assertEquals( OpportunityEngine.ENGINE_DIVERGENCE, result.type() );
        assertEquals( PAGE, result.target() );
        assertEquals( TODAY, result.firstSeen() );
        assertTrue( !result.calibrated() );
        assertEquals( "Diagnostic, usually \"change nothing on this page.\"", result.suggestedAction() );

        final Map<String, Object> evidence = result.evidence();
        assertEquals( "google", evidence.get( "strongEngine" ) );
        assertEquals( "bing", evidence.get( "weakEngine" ) );
        assertEquals( 200L, evidence.get( "strongEngineImpressions" ) );
        assertEquals( 30L, evidence.get( "weakEngineImpressions" ) );
        assertEquals( 50L, evidence.get( "strongEngineClicks" ) );
        assertEquals( 3.0, ( Double ) evidence.get( "strongEnginePosition" ), 0.0001 );
        assertEquals( 20.0, ( Double ) evidence.get( "weakEnginePosition" ), 0.0001 );
        assertEquals( 17.0, ( Double ) evidence.get( "positionGap" ), 0.0001 );
        assertEquals( 10, evidence.get( "sharedQueries" ) );
    }
}
