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
 * Tests for {@link OpportunityEngine#evaluateVocabularyGap} (rule 3 case (a), content-intelligence
 * design section 7.3). Only the Search Console click case is implemented -- the reformulation-pair
 * case needs session_hash pairing this engine does not read yet.
 */
class OpportunityEngineVocabularyGapTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 16 );
    private static final String PAGE = "/wiki/PhilosophyOfMind";
    private static final String SITE = "wiki.wikantik.com";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();

    private static VisibilityRow row( final String query, final int impressions, final int clicks ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, query,
                impressions, clicks, 5.0 );
    }

    private static PageFacts factsWith( final String title, final String summary, final List<String> tags ) {
        return new MapPageFacts( Map.of( PAGE,
                new PageFacts.PageFact( PAGE, title, summary, tags, "philosophy", null, "authoritative" ) ) );
    }

    private static final PageFacts NO_OVERLAP =
            factsWith( "Other Topic Entirely", "Nothing to do with the query.", List.of( "unrelated" ) );

    // --- clicks threshold (default min 5, floor 2) --------------------------------------------

    @Test
    void justBelowClickThresholdStaysSilent() {
        final List<VisibilityRow> rows = List.of( row( "epistemology", 100, 4 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults ).isEmpty() );
    }

    @Test
    void atClickThresholdFires() {
        final List<VisibilityRow> rows = List.of( row( "epistemology", 100, 5 ) );
        final List<Opportunity> result = engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 5.0, result.get( 0 ).priority(), 0.0001, "priority = impressions x 0.05" );
    }

    @Test
    void aboveClickThresholdFiresWithScaledPriority() {
        final List<VisibilityRow> rows = List.of( row( "epistemology", 120, 6 ) );
        final List<Opportunity> result = engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults );
        assertEquals( 1, result.size() );
        assertEquals( 6.0, result.get( 0 ).priority(), 0.0001 );
    }

    // --- global floor backstop -----------------------------------------------------------------

    @Test
    void globalFloorOverridesADeliberatelyMisconfiguredPerRuleThreshold() {
        final OpportunityEngineConfig misconfigured = new OpportunityEngineConfig(
                defaults.agentGapMinOccurrences(), defaults.agentGapMinDistinctSessions(),
                defaults.engineDivergenceMinImpressions(), defaults.engineDivergenceMinSharedQueries(),
                defaults.engineDivergenceMinPositionGap(),
                1, defaults.staleHighTrafficMinImpressions(), defaults.staleDays(),
                defaults.globalFloorMinImpressions(), defaults.globalFloorMinOccurrences(),
                defaults.cooldownDays() );

        final List<VisibilityRow> rows = List.of( row( "epistemology", 100, 1 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, misconfigured ).isEmpty(),
                "1 click satisfies the misconfigured per-rule minimum of 1, but the global floor "
                + "of 2 must still block it" );
    }

    // --- term-presence checks --------------------------------------------------------------------

    @Test
    void queryWordPresentInTitleStaysSilent() {
        final PageFacts facts = factsWith( "An Essay on Consciousness", null, List.of() );
        final List<VisibilityRow> rows = List.of( row( "consciousness debate", 100, 5 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, facts, TODAY, defaults ).isEmpty(),
                "the title already mentions \"consciousness\" -- not a gap" );
    }

    @Test
    void queryWordPresentInSummaryStaysSilent() {
        final PageFacts facts = factsWith( "Philosophy of Mind", "Covers dualism in depth.", List.of() );
        final List<VisibilityRow> rows = List.of( row( "dualism", 100, 5 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, facts, TODAY, defaults ).isEmpty() );
    }

    @Test
    void queryWordPresentInTagsStaysSilent() {
        final PageFacts facts = factsWith( "Philosophy of Mind", null, List.of( "qualia", "zombies" ) );
        final List<VisibilityRow> rows = List.of( row( "qualia", 100, 5 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, facts, TODAY, defaults ).isEmpty() );
    }

    @Test
    void noOverlapAtAllFires() {
        final List<VisibilityRow> rows = List.of( row( "epistemology", 100, 5 ) );
        final List<Opportunity> result = engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults );
        assertEquals( 1, result.size() );
    }

    // --- stopword handling (the vacuous-comparison guard) --------------------------------------

    @Test
    void stopwordOnlyOverlapDoesNotCountAsTermPresent() {
        // The title shares only the word "the" with the query -- a stopword. The real content
        // word, "epistemology", genuinely does not appear anywhere on the page, so this must
        // still fire: without stopword filtering, "the" would falsely look like coverage.
        final PageFacts facts = factsWith( "The Basics", "The introduction to the subject.", List.of() );
        final List<VisibilityRow> rows = List.of( row( "the epistemology", 100, 5 ) );
        final List<Opportunity> result = engine.evaluateVocabularyGap( rows, facts, TODAY, defaults );
        assertEquals( 1, result.size(), "a stopword-only overlap must not be treated as term presence" );
        assertEquals( List.of( "epistemology" ), result.get( 0 ).evidence().get( "missingContentWords" ) );
    }

    @Test
    void stopwordOnlyQueryStaysSilent() {
        final List<VisibilityRow> rows = List.of( row( "how to", 100, 5 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults ).isEmpty(),
                "a query with no content words has nothing actionable to add" );
    }

    // --- unknown page: skipped, not flagged -----------------------------------------------------

    @Test
    void unknownPageIsSkippedNotFlagged() {
        final PageFacts empty = new MapPageFacts( Map.of() );
        final List<VisibilityRow> rows = List.of( row( "epistemology", 100, 5 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, empty, TODAY, defaults ).isEmpty(),
                "a page absent from PageFacts must be skipped, never treated as \"term absent\"" );
    }

    // --- rollup rows are ignored -----------------------------------------------------------------

    @Test
    void pageRollupAndQueryRollupRowsAreIgnored() {
        final List<VisibilityRow> rows = List.of(
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "", 500, 50, 3.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, "", "epistemology", 500, 50, 3.0 ) );
        assertTrue( engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults ).isEmpty(),
                "rollup rows carry no page-plus-query pair to check vocabulary against" );
    }

    // --- aggregation across multiple rows for the same (page, query) ---------------------------

    @Test
    void clicksAndImpressionsAggregateAcrossEnginesForTheSameQuery() {
        final List<VisibilityRow> rows = List.of(
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "epistemology", 60, 3, 5.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, PAGE, "epistemology", 40, 2, 8.0 ) );
        final List<Opportunity> result = engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults );
        assertEquals( 1, result.size(), "3+2=5 clicks clears the threshold only once summed" );
        assertEquals( 5.0, result.get( 0 ).priority(), 0.0001, "priority uses the summed 100 impressions" );
    }

    // --- silence / empty input -------------------------------------------------------------------

    @Test
    void emptyInputIsSilent() {
        assertTrue( engine.evaluateVocabularyGap( List.of(), NO_OVERLAP, TODAY, defaults ).isEmpty() );
    }

    // --- shape / evidence --------------------------------------------------------------------------

    @Test
    void firingOpportunityCarriesExactEvidenceAndMetadata() {
        final List<VisibilityRow> rows = List.of( row( "epistemology theory", 200, 7 ) );
        final Opportunity result = engine.evaluateVocabularyGap( rows, NO_OVERLAP, TODAY, defaults ).get( 0 );

        assertEquals( OpportunityEngine.VOCABULARY_GAP, result.type() );
        assertEquals( PAGE, result.target() );
        assertEquals( 10.0, result.priority(), 0.0001 );
        assertEquals( TODAY, result.firstSeen() );
        assertTrue( !result.calibrated() );
        assertEquals( "Add the term as a tag or alias; tighten the summary to include the "
                + "reader's vocabulary.", result.suggestedAction() );

        final Map<String, Object> evidence = result.evidence();
        assertEquals( "epistemology theory", evidence.get( "queryText" ) );
        assertEquals( 7, evidence.get( "clicks" ) );
        assertEquals( 200, evidence.get( "impressions" ) );
        assertEquals( List.of( "epistemology", "theory" ), evidence.get( "missingContentWords" ) );
    }
}
