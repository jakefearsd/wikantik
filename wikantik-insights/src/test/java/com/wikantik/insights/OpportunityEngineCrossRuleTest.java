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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the cross-rule constraints applied to the merged backlog (content-intelligence
 * design §7.3 "Cross-rule constraints"): divergence suppression, the 60-day cooldown, snooze
 * filtering, and the full {@link OpportunityEngine#evaluate} pipeline.
 */
class OpportunityEngineCrossRuleTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 16 );
    private static final String PAGE = "/wiki/PhilosophyOfMind";
    private static final String SITE = "wiki.wikantik.com";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();

    private static Opportunity divergence( final String target, final String weakEngine ) {
        return new Opportunity( OpportunityEngine.ENGINE_DIVERGENCE, target, 25.0,
                Map.of( "strongEngine", "google", "weakEngine", weakEngine ),
                "Diagnostic, usually \"change nothing on this page.\"", TODAY, false );
    }

    private static Opportunity imported( final String type, final String target, final String engine ) {
        return new Opportunity( type, target, 12.0, Map.of( "engine", engine ),
                "some jakemon-supplied action", TODAY, false );
    }

    // --- divergence suppression ---------------------------------------------------------------

    @Test
    void suppressedCtrGapDoesNotAppearInTheMergedOutput() {
        final List<Opportunity> divergences = List.of( divergence( PAGE, "bing" ) );
        final List<Opportunity> imported = List.of( imported( "ctr_gap", PAGE, "bing" ) );

        final List<Opportunity> merged = engine.suppressDivergenceAffected( divergences, imported );

        assertEquals( 1, merged.size(), "the suppressed ctr_gap must be dropped entirely" );
        assertEquals( OpportunityEngine.ENGINE_DIVERGENCE, merged.get( 0 ).type() );
        assertTrue( merged.stream().noneMatch( o -> "ctr_gap".equals( o.type() ) ) );
    }

    @Test
    void suppressedStrikingDistanceDoesNotAppearInTheMergedOutput() {
        final List<Opportunity> divergences = List.of( divergence( PAGE, "bing" ) );
        final List<Opportunity> imported = List.of( imported( "striking_distance", PAGE, "bing" ) );

        final List<Opportunity> merged = engine.suppressDivergenceAffected( divergences, imported );

        assertFalse( merged.stream().anyMatch( o -> "striking_distance".equals( o.type() ) ) );
    }

    @Test
    void ctrGapOnADifferentPageIsNotSuppressed() {
        final List<Opportunity> divergences = List.of( divergence( PAGE, "bing" ) );
        final List<Opportunity> imported = List.of( imported( "ctr_gap", "/wiki/OtherPage", "bing" ) );

        final List<Opportunity> merged = engine.suppressDivergenceAffected( divergences, imported );

        assertEquals( 2, merged.size(), "a ctr_gap for a different page is unrelated and must pass through" );
    }

    @Test
    void ctrGapOnTheStrongEngineIsNotSuppressed() {
        final List<Opportunity> divergences = List.of( divergence( PAGE, "bing" ) );
        final List<Opportunity> imported = List.of( imported( "ctr_gap", PAGE, "google" ) );

        final List<Opportunity> merged = engine.suppressDivergenceAffected( divergences, imported );

        assertEquals( 2, merged.size(),
                "divergence explains the weak engine only -- a ctr_gap on the strong engine is unrelated" );
    }

    @Test
    void nonSuppressibleImportedTypesAlwaysPassThrough() {
        final List<Opportunity> divergences = List.of( divergence( PAGE, "bing" ) );
        final List<Opportunity> imported = List.of( imported( "content_gap", PAGE, "bing" ) );

        final List<Opportunity> merged = engine.suppressDivergenceAffected( divergences, imported );

        assertEquals( 2, merged.size(), "content_gap is not in the suppressible set and must survive" );
    }

    @Test
    void withNoDivergenceNothingIsSuppressed() {
        final List<Opportunity> imported = List.of( imported( "ctr_gap", PAGE, "bing" ) );
        final List<Opportunity> merged = engine.suppressDivergenceAffected( List.of(), imported );
        assertEquals( 1, merged.size() );
    }

    // --- vocabulary-gap suppression (the divergence-asymmetric case) ---------------------------

    private static Opportunity vocabularyGap( final String target ) {
        return new Opportunity( OpportunityEngine.VOCABULARY_GAP, target, 5.0,
                Map.of( "queryText", "epistemology" ), "add the term as a tag", TODAY, false );
    }

    @Test
    void vocabularyGapOnADivergentPageIsSuppressed() {
        final List<Opportunity> divergences = List.of( divergence( PAGE, "bing" ) );
        final List<Opportunity> vocabularyGaps = List.of( vocabularyGap( PAGE ) );

        final List<Opportunity> surviving =
                engine.suppressVocabularyGapForDivergentPages( divergences, vocabularyGaps );

        assertTrue( surviving.isEmpty(),
                "a vocabulary gap on a page divergence already diagnosed as a rank problem must be dropped" );
    }

    @Test
    void vocabularyGapOnANonDivergentPageIsNotSuppressed() {
        final List<Opportunity> divergences = List.of( divergence( PAGE, "bing" ) );
        final List<Opportunity> vocabularyGaps = List.of( vocabularyGap( "/wiki/OtherPage" ) );

        final List<Opportunity> surviving =
                engine.suppressVocabularyGapForDivergentPages( divergences, vocabularyGaps );

        assertEquals( 1, surviving.size(), "a different page's vocabulary gap is unrelated and must pass through" );
    }

    @Test
    void withNoDivergenceVocabularyGapIsNeverSuppressed() {
        final List<Opportunity> surviving =
                engine.suppressVocabularyGapForDivergentPages( List.of(), List.of( vocabularyGap( PAGE ) ) );
        assertEquals( 1, surviving.size() );
    }

    // --- suppression asymmetry: VOCABULARY_GAP vs. STALE_HIGH_TRAFFIC, through evaluate() ------

    /** Ten shared (google, bing) query rows that make {@code evaluateEngineDivergence} fire for PAGE. */
    private static List<VisibilityRow> divergenceRows() {
        final List<VisibilityRow> rows = new ArrayList<>();
        for ( int i = 0; i < 10; i++ ) {
            final String query = "query " + i;
            rows.add( new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, query,
                    20, 5, 3.0 ) );
            rows.add( new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, PAGE, query,
                    3, 1, 20.0 ) );
        }
        return rows;
    }

    @Test
    void suppressionIsAsymmetricAcrossTheTwoNativeRules() {
        final List<VisibilityRow> rows = new ArrayList<>( divergenceRows() );
        // A vocabulary-gap-eligible query on the same page and engine as the weak (bing) side --
        // the extra row's position matches the other bing rows exactly (20.0) so it does not shift
        // bing's average position or create a new shared query, leaving the divergence math above
        // untouched while still giving evaluateVocabularyGap something to fire on.
        rows.add( new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, PAGE, "mind uploading",
                50, 6, 20.0 ) );
        // A page-level rollup row clearing the STALE_HIGH_TRAFFIC impressions floor.
        rows.add( new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "",
                250, 0, null ) );

        // Page facts: vocabulary that matches neither "mind" nor "uploading" (a real gap), and
        // never verified (a real staleness signal too) -- one PageFact drives both native rules.
        final PageFacts facts = new MapPageFacts( Map.of( PAGE,
                new PageFacts.PageFact( PAGE, "Other Topic", null, List.of(), "philosophy", null, null ) ) );

        final List<Opportunity> result = engine.evaluate( List.of(), rows, List.of(), facts,
                List.of(), Map.of(), TODAY, defaults );

        assertTrue( result.stream().anyMatch( o -> OpportunityEngine.ENGINE_DIVERGENCE.equals( o.type() )
                        && PAGE.equals( o.target() ) ),
                "engine divergence itself must still fire" );
        assertTrue( result.stream().anyMatch( o -> OpportunityEngine.STALE_HIGH_TRAFFIC.equals( o.type() )
                        && PAGE.equals( o.target() ) ),
                "staleness is real regardless of rank -- STALE_HIGH_TRAFFIC must survive divergence suppression" );
        assertFalse( result.stream().anyMatch( o -> OpportunityEngine.VOCABULARY_GAP.equals( o.type() )
                        && PAGE.equals( o.target() ) ),
                "a tag will not rescue a page the divergence rule already diagnosed as a rank problem -- "
                + "VOCABULARY_GAP must be suppressed" );
    }

    // --- 60-day cooldown ------------------------------------------------------------------------

    private static Opportunity opportunityFor( final String target ) {
        return new Opportunity( "agent_gap", target, 4.0, Map.of(), "action", TODAY, false );
    }

    @Test
    void justBelowCooldownWindowIsBlocked() {
        final Map<String, LocalDate> lastChange = Map.of( PAGE, TODAY.minusDays( 59 ) );
        final List<Opportunity> result = engine.applyCooldown(
                List.of( opportunityFor( PAGE ) ), lastChange, TODAY, defaults.cooldownDays() );
        assertTrue( result.isEmpty(), "59 days since the last change is inside the 60-day cooldown" );
    }

    @Test
    void atCooldownWindowIsAllowed() {
        final Map<String, LocalDate> lastChange = Map.of( PAGE, TODAY.minusDays( 60 ) );
        final List<Opportunity> result = engine.applyCooldown(
                List.of( opportunityFor( PAGE ) ), lastChange, TODAY, defaults.cooldownDays() );
        assertEquals( 1, result.size() );
    }

    @Test
    void aboveCooldownWindowIsAllowed() {
        final Map<String, LocalDate> lastChange = Map.of( PAGE, TODAY.minusDays( 61 ) );
        final List<Opportunity> result = engine.applyCooldown(
                List.of( opportunityFor( PAGE ) ), lastChange, TODAY, defaults.cooldownDays() );
        assertEquals( 1, result.size() );
    }

    @Test
    void aTargetWithNoRecordedChangeIsAlwaysAllowed() {
        final List<Opportunity> result = engine.applyCooldown(
                List.of( opportunityFor( PAGE ) ), Map.of(), TODAY, defaults.cooldownDays() );
        assertEquals( 1, result.size() );
    }

    // --- snooze filtering -----------------------------------------------------------------------

    @Test
    void snoozeActiveThroughItsLastDayIsFiltered() {
        final OpportunitySnooze snooze = new OpportunitySnooze( "agent_gap", "some query", TODAY,
                "not worth chasing", "jake" );
        final List<Opportunity> result = engine.filterSnoozed(
                List.of( opportunityFor( "some query" ) ), List.of( snooze ), TODAY );
        assertTrue( result.isEmpty(), "a snooze is active through and including its snoozed_until date" );
    }

    @Test
    void snoozeExpiredYesterdayNoLongerFilters() {
        final OpportunitySnooze snooze = new OpportunitySnooze( "agent_gap", "some query",
                TODAY.minusDays( 1 ), "not worth chasing", "jake" );
        final List<Opportunity> result = engine.filterSnoozed(
                List.of( opportunityFor( "some query" ) ), List.of( snooze ), TODAY );
        assertEquals( 1, result.size() );
    }

    @Test
    void snoozeExpiringTomorrowStillFilters() {
        final OpportunitySnooze snooze = new OpportunitySnooze( "agent_gap", "some query",
                TODAY.plusDays( 1 ), "not worth chasing", "jake" );
        final List<Opportunity> result = engine.filterSnoozed(
                List.of( opportunityFor( "some query" ) ), List.of( snooze ), TODAY );
        assertTrue( result.isEmpty() );
    }

    @Test
    void snoozeOnADifferentTypeDoesNotFilterTheSameTarget() {
        final OpportunitySnooze snooze = new OpportunitySnooze( "engine_divergence", "some query",
                TODAY, "not worth chasing", "jake" );
        final List<Opportunity> result = engine.filterSnoozed(
                List.of( opportunityFor( "some query" ) ), List.of( snooze ), TODAY );
        assertEquals( 1, result.size(), "a snooze is keyed by (type, target) -- a different type must not match" );
    }

    // --- full pipeline --------------------------------------------------------------------------

    @Test
    void evaluateComposesEveryStepAndSortsByPriorityDescending() {
        final DemandRow gap = new DemandRow( "some query", null, 0, 5, 5 ); // priority 10.0
        final List<VisibilityRow> visibility = List.of(); // no divergence input
        final Opportunity ctrGap = imported( "ctr_gap", PAGE, "bing" ); // priority 12.0, unaffected

        final List<Opportunity> result = engine.evaluate( List.of( gap ), visibility, List.of( ctrGap ),
                new MapPageFacts( Map.of() ), List.of(), Map.of(), TODAY, defaults );

        assertEquals( 2, result.size() );
        assertEquals( "ctr_gap", result.get( 0 ).type(), "higher priority (12.0) sorts first" );
        assertEquals( OpportunityEngine.AGENT_GAP, result.get( 1 ).type() );
    }

    @Test
    void evaluateAppliesCooldownAndSnoozeToNativeRuleOutputToo() {
        final DemandRow gap = new DemandRow( "some query", null, 0, 5, 5 );
        final OpportunitySnooze snooze = new OpportunitySnooze( OpportunityEngine.AGENT_GAP, "some query",
                TODAY, "declined", "jake" );

        final List<Opportunity> result = engine.evaluate( List.of( gap ), List.of(), List.of(),
                new MapPageFacts( Map.of() ), List.of( snooze ), Map.of(), TODAY, defaults );

        assertTrue( result.isEmpty(), "a snoozed native-rule opportunity must not survive the full pipeline" );
    }
}
