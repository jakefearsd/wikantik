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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the three snapshot-reading rules treat rows that carry no page, no query, no position or
 * no page facts, and how engine-divergence picks its strong and weak engine. Rows come from
 * partner payloads, so every one of these shapes occurs in production.
 */
class RuleInputRobustnessTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 16 );
    private static final String PAGE = "/wiki/PhilosophyOfMind";
    private static final String SITE = "wiki.wikantik.com";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();
    private final ExpectedCtrCurve curve = ExpectedCtrCurve.defaultCurve();

    private static VisibilityRow row( final String engineName, final String page, final String query,
                                      final int impressions, final int clicks, final Double position ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, engineName, SITE, page, query,
                impressions, clicks, position );
    }

    private static VisibilityRow rollup( final String engineName, final int impressions, final double position ) {
        return row( engineName, PAGE, "", impressions, 1, position );
    }

    private List<Opportunity> divergence( final List<VisibilityRow> rows ) {
        return engine.evaluateEngineDivergence( rows, curve, TODAY, defaults );
    }

    // --- engine divergence: choosing the extremes ---------------------------------------------

    @Test
    void theStrongEngineNeedNotBeTheFirstEngineSeen() {
        final List<Opportunity> out = divergence( List.of( rollup( "bing", 50, 30.0 ), rollup( "google", 50, 5.0 ) ) );

        assertEquals( 1, out.size() );
        assertEquals( "google", out.get( 0 ).evidence().get( "strongEngine" ) );
        assertEquals( "bing", out.get( 0 ).evidence().get( OpportunityEngine.EVIDENCE_WEAK_ENGINE ) );
        assertEquals( 5.0, out.get( 0 ).evidence().get( "strongPosition" ) );
        assertEquals( 30.0, out.get( 0 ).evidence().get( "weakPosition" ) );
    }

    @Test
    void aMiddleEngineIsNeitherStrongNorWeak() {
        final List<Opportunity> out = divergence( List.of(
                rollup( "google", 50, 5.0 ), rollup( "bing", 50, 15.0 ), rollup( "yandex", 70, 30.0 ) ) );

        assertEquals( 1, out.size(), "one candidate per page, not one per engine pair" );
        assertEquals( "google", out.get( 0 ).evidence().get( "strongEngine" ) );
        assertEquals( "yandex", out.get( 0 ).evidence().get( OpportunityEngine.EVIDENCE_WEAK_ENGINE ) );
        assertEquals( 70L, out.get( 0 ).evidence().get( "weakImpressions" ) );
        assertEquals( 50L, out.get( 0 ).evidence().get( "strongImpressions" ) );
    }

    @Test
    void whenTwoEnginesTieForStrongTheFirstSeenWins() {
        final List<Opportunity> out = divergence( List.of(
                rollup( "google", 50, 5.0 ), rollup( "bing", 99, 5.0 ), rollup( "yandex", 50, 30.0 ) ) );

        assertEquals( "google", out.get( 0 ).evidence().get( "strongEngine" ) );
        assertEquals( 50L, out.get( 0 ).evidence().get( "strongImpressions" ) );
    }

    @Test
    void whenTwoEnginesTieForWeakTheFirstSeenWins() {
        final List<Opportunity> out = divergence( List.of(
                rollup( "google", 50, 30.0 ), rollup( "bing", 99, 30.0 ), rollup( "yandex", 50, 5.0 ) ) );

        assertEquals( "google", out.get( 0 ).evidence().get( OpportunityEngine.EVIDENCE_WEAK_ENGINE ) );
        assertEquals( 50L, out.get( 0 ).evidence().get( "weakImpressions" ) );
    }

    @Test
    void anEnginesPositionIsTheMeanOfItsRowsAndItsImpressionsAreSummed() {
        final List<Opportunity> out = divergence( List.of(
                rollup( "google", 50, 5.0 ),
                row( "bing", PAGE, "", 10, 1, 10.0 ), row( "bing", PAGE, "", 15, 1, 30.0 ) ) );

        assertEquals( 1, out.size() );
        assertEquals( 20.0, out.get( 0 ).evidence().get( "weakPosition" ) );
        assertEquals( 25L, out.get( 0 ).evidence().get( "weakImpressions" ) );
        assertEquals( 15.0, out.get( 0 ).evidence().get( "positionGap" ) );
    }

    @Test
    void rowsWithoutAPositionAreIgnoredAndCannotSupplyAnEngine() {
        final List<Opportunity> out = divergence( List.of(
                rollup( "google", 50, 5.0 ),
                row( "bing", PAGE, "", 500, 1, null ) ) );

        assertTrue( out.isEmpty(), "a lone usable engine has nothing to diverge from" );
    }

    @Test
    void aPageWhereNoRowHasAPositionProducesNothingAndDoesNotThrow() {
        assertTrue( divergence( List.of( row( "google", PAGE, "", 50, 1, null ),
                                         row( "bing", PAGE, "", 50, 1, null ) ) ).isEmpty() );
    }

    @Test
    void rowsWithoutAUsablePageOrWithAQueryAreNotPageRollups() {
        final List<Opportunity> out = divergence( List.of(
                row( "google", null, "", 50, 1, 5.0 ), row( "bing", null, "", 50, 1, 30.0 ),
                row( "google", "  ", "", 50, 1, 5.0 ), row( "bing", "  ", "", 50, 1, 30.0 ),
                row( "google", PAGE, "some query", 50, 1, 5.0 ), row( "bing", PAGE, "some query", 50, 1, 30.0 ),
                row( "google", PAGE, null, 50, 1, 5.0 ), row( "bing", PAGE, null, 50, 1, 30.0 ) ) );

        assertEquals( 1, out.size(), "only the null-query page rollup pair counts" );
        assertEquals( PAGE, out.get( 0 ).target() );
        assertEquals( 50L, out.get( 0 ).evidence().get( "strongImpressions" ) );
    }

    // --- stale high traffic: summing page rollups ----------------------------------------------

    private static PageFacts neverVerified() {
        return new MapPageFacts( Map.of( PAGE,
                new PageFacts.PageFact( PAGE, "t", "s", List.of(), "c", null, "authoritative" ) ) );
    }

    @Test
    void staleTrafficSumsPageRollupsAcrossEnginesAndSkipsQueryAndPagelessRows() {
        final List<Opportunity> out = engine.evaluateStaleHighTraffic( List.of(
                row( "google", PAGE, "", 300, 1, 5.0 ),
                row( "bing", PAGE, null, 200, 1, 5.0 ),
                row( "google", PAGE, "a query", 9_000, 1, 5.0 ),
                row( "google", null, "", 9_000, 1, 5.0 ),
                row( "google", "", "", 9_000, 1, 5.0 ),
                row( "google", " ", "", 9_000, 1, 5.0 ) ), neverVerified(), TODAY, defaults );

        assertEquals( 1, out.size() );
        assertEquals( PAGE, out.get( 0 ).target() );
        assertEquals( 500L, out.get( 0 ).evidence().get( "impressions" ) );
    }

    // --- vocabulary gap ------------------------------------------------------------------------

    @Test
    void aPageWithNoTitleSummaryOrTagsHasNoVocabularyAndEveryContentWordIsAGap() {
        final PageFacts bare = new MapPageFacts( Map.of( PAGE,
                new PageFacts.PageFact( PAGE, null, null, null, null, Instant.EPOCH, "authoritative" ) ) );
        final List<Opportunity> out = engine.evaluateVocabularyGap(
                List.of( row( "google", PAGE, "epistemology", 100, 6, 5.0 ) ), bare, TODAY, defaults );

        assertEquals( 1, out.size() );
        assertEquals( List.of( "epistemology" ), out.get( 0 ).evidence().get( "missingContentWords" ) );
    }

    @Test
    void blankTitleAndSummaryContributeNoWords() {
        final PageFacts blank = new MapPageFacts( Map.of( PAGE,
                new PageFacts.PageFact( PAGE, "   ", "", List.of( " " ), null, Instant.EPOCH, "authoritative" ) ) );

        assertEquals( 1, engine.evaluateVocabularyGap(
                List.of( row( "google", PAGE, "epistemology", 100, 6, 5.0 ) ), blank, TODAY, defaults ).size() );
    }

    @Test
    void vocabularyGapIgnoresRowsWithoutAPageOrWithoutAQuery() {
        final PageFacts none = new MapPageFacts( Map.of( PAGE,
                new PageFacts.PageFact( PAGE, "Other", "Other", List.of(), null, Instant.EPOCH, "authoritative" ) ) );

        final List<Opportunity> out = engine.evaluateVocabularyGap( List.of(
                row( "google", null, "epistemology", 100, 6, 5.0 ),
                row( "google", " ", "epistemology", 100, 6, 5.0 ),
                row( "google", PAGE, null, 100, 6, 5.0 ),
                row( "google", PAGE, "  ", 100, 6, 5.0 ),
                row( "google", PAGE, "epistemology", 100, 6, 5.0 ) ), none, TODAY, defaults );

        assertEquals( 1, out.size() );
        assertEquals( "epistemology", out.get( 0 ).evidence().get( "queryText" ) );
    }
}
