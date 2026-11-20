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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The native rules of the content-opportunity engine (content-intelligence design §7.3).
 *
 * <p><strong>Pure function.</strong> No I/O and no clock reads happen anywhere in this class --
 * every method takes its inputs (rows, config, {@code today}) as parameters and returns a new
 * list. This is deliberate: it is what makes the rule engine testable without a wiki engine or a
 * database, and it is why {@link OpportunityEngineConfig} and {@code today} are threaded through
 * every entry point rather than read from a clock or a properties file.</p>
 *
 * <p>Wikantik does not recompute what jakemon already detects (§7.3). This class holds only the
 * four rules that need data jakemon cannot see: {@link #evaluateAgentGap} (rule 1, needs MCP
 * traffic), {@link #evaluateEngineDivergence} (rule 2, needs retained multi-engine history),
 * {@link #evaluateVocabularyGap} (rule 3, needs page frontmatter), and
 * {@link #evaluateStaleHighTraffic} (rule 4, needs verification age). The five jakemon-imported
 * types ({@code striking_distance}, {@code ctr_gap}, {@code content_gap}, {@code cannibalization},
 * {@code decay}) arrive pre-built as {@link Opportunity} instances and only pass through
 * {@link #suppressDivergenceAffected}.</p>
 *
 * <p><strong>The §7.3.0 traffic gate.</strong> {@link #evaluate} always runs all four native
 * rules (an always-open gate), matching its original contract. {@link #evaluateGated} is the
 * gate-aware entry point: below {@code config.gateImpressions28d()} site-wide impressions, the
 * three visibility-driven rules ({@code ENGINE_DIVERGENCE}, {@code VOCABULARY_GAP},
 * {@code STALE_HIGH_TRAFFIC}) do not run at all and are reported in the returned
 * {@link Backlog#suppressed()} instead -- see §7.3.0. {@code AGENT_GAP} is never gated.</p>
 */
public final class OpportunityEngine {

    /** Rule 1 type identifier. */
    public static final String AGENT_GAP = "agent_gap";

    /** Rule 2 type identifier. */
    public static final String ENGINE_DIVERGENCE = "engine_divergence";

    /** Rule 3 type identifier. */
    public static final String VOCABULARY_GAP = "vocabulary_gap";

    /** Rule 4 type identifier. */
    public static final String STALE_HIGH_TRAFFIC = "stale_high_traffic";

    /**
     * Imported jakemon types that {@link #evaluateEngineDivergence} can explain away. A page
     * ranking well on one engine and badly on another looks, in Google's data alone, exactly like
     * a title/snippet failure -- but rewriting the page will not fix a domain-authority gap. See
     * §7.3 rule 2 and the class-level note on why this suppression exists.
     */
    private static final Set<String> SUPPRESSIBLE_BY_DIVERGENCE = Set.of( "ctr_gap", "striking_distance" );

    private static final String EVIDENCE_ENGINE = "engine";
    static final String EVIDENCE_WEAK_ENGINE = "weakEngine";

    /** {@link SuppressedRule#reason()} for a rule suppressed by the §7.3.0 traffic gate. */
    private static final String TRAFFIC_GATE_REASON = "traffic_gate";

    /**
     * The native rules in evaluation order; the gated ones are skipped while the traffic gate is closed. The merge in
     * {@link #evaluateGated} names each rule's type, so a rule added here must be merged there too (the gate test
     * pins the set). Package-private for that test.
     */
    static final List<OpportunityRule> RULES = List.of(
            new AgentGapRule(), new EngineDivergenceRule(), new VocabularyGapRule(), new StaleHighTrafficRule() );

    /**
     * Rule 1 -- {@code AGENT_GAP}. Delegates to {@link AgentGapRule#detect}, which documents the
     * rule.
     *
     * @param rows   demand rows for the evaluation window
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds
     * @return opportunities for queries meeting the minimum support, in no particular order
     */
    public List<Opportunity> evaluateAgentGap( final List<DemandRow> rows, final LocalDate today,
                                                final OpportunityEngineConfig config ) {
        return AgentGapRule.detect( rows, today, config );
    }

    /**
     * Rule 2 -- {@code ENGINE_DIVERGENCE}, diagnostic rather than corrective (see
     * {@link #suppressDivergenceAffected}). Delegates to {@link EngineDivergenceRule#detect}, which
     * documents the rule.
     *
     * @param rows   visibility rows for the evaluation window
     * @param curve  the expected click-through-rate-by-position model driving the priority formula
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds
     * @return diagnostic opportunities for pages meeting the minimum support
     */
    public List<Opportunity> evaluateEngineDivergence( final List<VisibilityRow> rows,
                                                        final ExpectedCtrCurve curve, final LocalDate today,
                                                        final OpportunityEngineConfig config ) {
        return EngineDivergenceRule.detect( rows, curve, today, config );
    }

    /**
     * Rule 3 -- {@code VOCABULARY_GAP}. Delegates to {@link VocabularyGapRule#detect}, which
     * documents the rule.
     *
     * @param rows      visibility rows for the evaluation window
     * @param pageFacts the page-state port (title/summary/tags), backed by the live wiki
     * @param today     the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config    thresholds
     * @return opportunities for page/query pairs meeting the minimum support
     */
    public List<Opportunity> evaluateVocabularyGap( final List<VisibilityRow> rows, final PageFacts pageFacts,
                                                     final LocalDate today, final OpportunityEngineConfig config ) {
        return VocabularyGapRule.detect( rows, pageFacts, today, config );
    }

    /**
     * Rule 4 -- {@code STALE_HIGH_TRAFFIC}. Delegates to {@link StaleHighTrafficRule#detect}, which
     * documents the rule.
     *
     * @param rows      visibility rows for the evaluation window
     * @param pageFacts the page-state port ({@code verified_at}, confidence)
     * @param today     the date this evaluation runs, also the staleness clock
     * @param config    thresholds
     * @return opportunities for pages meeting the minimum support
     */
    public List<Opportunity> evaluateStaleHighTraffic( final List<VisibilityRow> rows, final PageFacts pageFacts,
                                                        final LocalDate today, final OpportunityEngineConfig config ) {
        return StaleHighTrafficRule.detect( rows, pageFacts, today, config );
    }

    /**
     * Cross-rule constraint: divergence suppression (§7.3). Where {@link #evaluateEngineDivergence}
     * fires for a {@code (page, weakEngine)} pair, the imported {@code ctr_gap} and
     * {@code striking_distance} opportunities for that same pair are dropped -- the weakness is
     * already explained by domain authority, not by anything on the page, so surfacing both would
     * train an agent to rewrite a page that doesn't need it.
     *
     * <p>An imported opportunity can only be matched and suppressed if it carries an
     * {@code "engine"} evidence entry identifying which engine's data triggered it; one without
     * that entry always passes through unsuppressed.</p>
     *
     * @param divergenceOpportunities  the result of {@link #evaluateEngineDivergence}
     * @param importedOpportunities    opportunities imported unchanged from jakemon
     * @return {@code divergenceOpportunities} followed by every {@code importedOpportunities}
     *         entry that was not suppressed
     */
    public List<Opportunity> suppressDivergenceAffected( final List<Opportunity> divergenceOpportunities,
                                                          final List<Opportunity> importedOpportunities ) {
        final Set<String> suppressedPairs = new HashSet<>();
        for ( final Opportunity divergence : divergenceOpportunities ) {
            final Object weakEngine = divergence.evidence().get( EVIDENCE_WEAK_ENGINE );
            if ( weakEngine != null ) {
                suppressedPairs.add( pairKey( divergence.target(), weakEngine.toString() ) );
            }
        }

        final List<Opportunity> out = new ArrayList<>( divergenceOpportunities );
        for ( final Opportunity imported : importedOpportunities ) {
            if ( SUPPRESSIBLE_BY_DIVERGENCE.contains( imported.type() ) ) {
                final Object engine = imported.evidence().get( EVIDENCE_ENGINE );
                if ( engine != null && suppressedPairs.contains( pairKey( imported.target(), engine.toString() ) ) ) {
                    continue; // suppressed: divergence already explains this page/engine pair
                }
            }
            out.add( imported );
        }
        return out;
    }

    private static String pairKey( final String target, final String engine ) {
        return target + ' ' + engine;
    }

    /**
     * Cross-rule constraint: divergence suppression, asymmetric case (design doc section 7.3).
     * Unlike {@link #suppressDivergenceAffected}, this is keyed on <strong>page only</strong>, not
     * {@code (page, engine)} -- {@link #evaluateVocabularyGap} aggregates clicks/impressions
     * across engines per query, so no single engine attribution survives to match against.
     *
     * <p><strong>Why {@code VOCABULARY_GAP} is suppressed here but {@code STALE_HIGH_TRAFFIC} is
     * not</strong> (this asymmetry is deliberate, not an oversight -- see
     * {@code OpportunityEngineCrossRuleTest#suppressionIsAsymmetricAcrossTheTwoNativeRules}):</p>
     * <ul>
     *   <li>{@code VOCABULARY_GAP} is a CTR-shaped fix -- its theory of change is "the reader's
     *       words aren't on the page, so they bounce off the snippet." On a page ENGINE_DIVERGENCE
     *       has already diagnosed as an authority problem (ranking ~60th, effectively unseen),
     *       nobody reaches the snippet to bounce off in the first place; adding a tag cannot move
     *       a page that isn't being shown. It belongs in the same suppressible family as the
     *       imported {@code ctr_gap} and {@code striking_distance} types.</li>
     *   <li>{@code STALE_HIGH_TRAFFIC} is not about rank at all -- its trigger is real traffic
     *       plus verification age, and a divergence diagnosis says nothing about whether the
     *       content is still accurate. A stale page stays stale (and worth re-verifying)
     *       regardless of which engine is delivering its clicks.</li>
     * </ul>
     *
     * @param divergenceOpportunities    the result of {@link #evaluateEngineDivergence}
     * @param vocabularyGapOpportunities the result of {@link #evaluateVocabularyGap}
     * @return {@code vocabularyGapOpportunities} with any entry targeting a page that
     *         {@code divergenceOpportunities} also targets removed
     */
    public List<Opportunity> suppressVocabularyGapForDivergentPages(
            final List<Opportunity> divergenceOpportunities,
            final List<Opportunity> vocabularyGapOpportunities ) {
        final Set<String> divergentPages = new HashSet<>();
        for ( final Opportunity divergence : divergenceOpportunities ) {
            divergentPages.add( divergence.target() );
        }

        final List<Opportunity> out = new ArrayList<>();
        for ( final Opportunity candidate : vocabularyGapOpportunities ) {
            if ( !divergentPages.contains( candidate.target() ) ) {
                out.add( candidate );
            }
        }
        return out;
    }

    /**
     * Cross-rule constraint: 60-day per-page cooldown (§7.3). No target may generate an automatic
     * optimization more than once per {@code cooldownDays} -- two changes inside one measurement
     * window make the effect unattributable.
     *
     * @param opportunities        candidates to filter
     * @param lastChangeByTarget   the most recent {@code content_change_log.applied_at} date
     *                             recorded for each target, keyed by {@link Opportunity#target()}
     * @param today                the date this evaluation runs
     * @param cooldownDays         the cooldown window in days
     * @return {@code opportunities} with any target still inside its cooldown removed
     */
    public List<Opportunity> applyCooldown( final List<Opportunity> opportunities,
                                            final Map<String, LocalDate> lastChangeByTarget,
                                            final LocalDate today, final int cooldownDays ) {
        final List<Opportunity> out = new ArrayList<>();
        for ( final Opportunity opportunity : opportunities ) {
            final LocalDate lastChange = lastChangeByTarget.get( opportunity.target() );
            if ( lastChange == null || ChronoUnit.DAYS.between( lastChange, today ) >= cooldownDays ) {
                out.add( opportunity );
            }
        }
        return out;
    }

    /**
     * Cross-rule constraint: snooze filtering (§7.3). A {@code (type, target)} pair under an
     * unexpired snooze is removed before scoring.
     *
     * @param opportunities candidates to filter
     * @param snoozes       snooze rows to check against
     * @param today         the date this evaluation runs; a snooze is active while
     *                      {@code today <= snoozedUntil}
     * @return {@code opportunities} with any snoozed {@code (type, target)} removed
     */
    public List<Opportunity> filterSnoozed( final List<Opportunity> opportunities,
                                            final List<OpportunitySnooze> snoozes, final LocalDate today ) {
        final Set<String> active = new HashSet<>();
        for ( final OpportunitySnooze snooze : snoozes ) {
            if ( !today.isAfter( snooze.snoozedUntil() ) ) {
                active.add( pairKey( snooze.target(), snooze.opportunityType() ) );
            }
        }

        final List<Opportunity> out = new ArrayList<>();
        for ( final Opportunity opportunity : opportunities ) {
            if ( !active.contains( pairKey( opportunity.target(), opportunity.type() ) ) ) {
                out.add( opportunity );
            }
        }
        return out;
    }

    /**
     * Runs the full Phase 2 pipeline with an always-open traffic gate: all four native rules,
     * divergence suppression of the imported backlog and (separately -- see
     * {@link #suppressVocabularyGapForDivergentPages}) of {@code VOCABULARY_GAP}, cooldown,
     * snooze filtering, then a descending-priority sort of the merged result. Each step is also
     * exposed individually above for isolated testing.
     *
     * <p>Delegates to {@link #evaluateGated} with {@code siteImpressions28d} set to
     * {@link Integer#MAX_VALUE} (so the §7.3.0 gate never closes), an empty
     * {@code calibratedTypes} (so every {@link Opportunity#calibrated()} stays {@code false}, this
     * method's original contract), and {@link ExpectedCtrCurve#defaultCurve()} -- preserving this
     * method's original signature and behavior for existing callers.</p>
     *
     * @param demandRows            input for {@link #evaluateAgentGap}
     * @param visibilityRows        input for {@link #evaluateEngineDivergence},
     *                              {@link #evaluateVocabularyGap}, and
     *                              {@link #evaluateStaleHighTraffic}
     * @param importedOpportunities opportunities imported unchanged from jakemon
     * @param pageFacts             page-state port for {@link #evaluateVocabularyGap} and
     *                              {@link #evaluateStaleHighTraffic}
     * @param snoozes               active/inactive snooze rows; expiry is checked against {@code today}
     * @param lastChangeByTarget    most recent change date per target, for the cooldown
     * @param today                 the date this evaluation runs
     * @param config                thresholds
     * @return the merged, filtered, priority-sorted backlog
     */
    public List<Opportunity> evaluate( final List<DemandRow> demandRows,
                                       final List<VisibilityRow> visibilityRows,
                                       final List<Opportunity> importedOpportunities,
                                       final PageFacts pageFacts,
                                       final List<OpportunitySnooze> snoozes,
                                       final Map<String, LocalDate> lastChangeByTarget,
                                       final LocalDate today,
                                       final OpportunityEngineConfig config ) {
        return evaluateGated( demandRows, visibilityRows, importedOpportunities, pageFacts, snoozes,
                lastChangeByTarget, Integer.MAX_VALUE, Set.of(), ExpectedCtrCurve.defaultCurve(),
                today, config ).opportunities();
    }

    /**
     * Runs the full Phase 2 pipeline with the §7.3.0 site-level traffic gate applied: below
     * {@code config.gateImpressions28d()} site-wide impressions in 28 days, the three
     * visibility-driven rules ({@code ENGINE_DIVERGENCE}, {@code VOCABULARY_GAP},
     * {@code STALE_HIGH_TRAFFIC}) do not run at all -- each is reported in
     * {@link Backlog#suppressed()} with reason {@code "traffic_gate"} rather than silently
     * returning nothing. {@code AGENT_GAP} always runs; it is never gated, since its denominator
     * is MCP retrieval traffic, not search impressions. Imported opportunities are never gated
     * either.
     *
     * <p>When the gate is open, behavior matches {@link #evaluate}: divergence suppression of the
     * imported backlog and of {@code VOCABULARY_GAP}, cooldown, snooze filtering, then a
     * descending-priority sort. Finally, every returned {@link Opportunity#calibrated()} is set
     * to {@code calibratedTypes.contains(opportunity.type())} -- see design §7.4.4.</p>
     *
     * @param demandRows            input for {@link #evaluateAgentGap}
     * @param visibilityRows        input for {@link #evaluateEngineDivergence},
     *                              {@link #evaluateVocabularyGap}, and
     *                              {@link #evaluateStaleHighTraffic}
     * @param importedOpportunities opportunities imported unchanged from jakemon; never gated
     * @param pageFacts             page-state port for {@link #evaluateVocabularyGap} and
     *                              {@link #evaluateStaleHighTraffic}
     * @param snoozes               active/inactive snooze rows; expiry is checked against {@code today}
     * @param lastChangeByTarget    most recent change date per target, for the cooldown
     * @param siteImpressions28d    total site-wide search-visibility impressions in the last 28
     *                              days, compared against {@code config.gateImpressions28d()}
     * @param calibratedTypes       opportunity types whose priority weight has been calibrated
     *                              against realized effect (design §7.4.4); drives
     *                              {@link Opportunity#calibrated()} on every returned opportunity
     * @param curve                 the expected click-through-rate-by-position model for
     *                              {@link #evaluateEngineDivergence}
     * @param today                 the date this evaluation runs
     * @param config                thresholds
     * @return the merged, filtered, priority-sorted backlog, plus any rules the traffic gate
     *         suppressed
     */
    public Backlog evaluateGated( final List<DemandRow> demandRows,
                                  final List<VisibilityRow> visibilityRows,
                                  final List<Opportunity> importedOpportunities,
                                  final PageFacts pageFacts,
                                  final List<OpportunitySnooze> snoozes,
                                  final Map<String, LocalDate> lastChangeByTarget,
                                  final int siteImpressions28d,
                                  final Set<String> calibratedTypes,
                                  final ExpectedCtrCurve curve,
                                  final LocalDate today,
                                  final OpportunityEngineConfig config ) {
        final EvaluationInput in = new EvaluationInput( demandRows, visibilityRows, importedOpportunities,
                pageFacts, snoozes, lastChangeByTarget, siteImpressions28d, calibratedTypes, curve, today,
                config );

        final Map<String, List<Opportunity>> byRule = new LinkedHashMap<>();
        final List<SuppressedRule> suppressed = new ArrayList<>();
        final boolean gateOpen = siteImpressions28d >= config.gateImpressions28d();
        for ( final OpportunityRule rule : RULES ) {
            if ( rule.gated() && !gateOpen ) {
                suppressed.add( new SuppressedRule( rule.type(), TRAFFIC_GATE_REASON,
                        siteImpressions28d, config.gateImpressions28d() ) );
                byRule.put( rule.type(), List.of() );
            } else {
                byRule.put( rule.type(), rule.evaluate( in ) );
            }
        }

        // Note the second-order effect of a closed gate: with no divergence opportunities, the
        // divergence SUPPRESSION below has nothing to match, so imported ctr_gap /
        // striking_distance opportunities flow through unchecked. That is deliberate. Below the
        // gate, divergence cannot fire reliably, so it cannot suppress reliably either, and a
        // suppression decision made from a single-impression position estimate would be worth
        // less than no decision. The imported types keep their own upstream floor
        // (jakemon's MIN_IMPRESSIONS = 50, stricter than anything here), which on this corpus
        // admits only the handful of pages above the 99th percentile of traffic -- precisely the
        // pages where acting on an imported suggestion is most defensible.
        final List<Opportunity> divergence = byRule.get( ENGINE_DIVERGENCE );
        final List<Opportunity> mergedImported = suppressDivergenceAffected( divergence, importedOpportunities );
        final List<Opportunity> survivingVocabularyGap =
                suppressVocabularyGapForDivergentPages( divergence, byRule.get( VOCABULARY_GAP ) );

        final List<Opportunity> all = new ArrayList<>( byRule.get( AGENT_GAP ) );
        all.addAll( mergedImported );
        all.addAll( survivingVocabularyGap );
        all.addAll( byRule.get( STALE_HIGH_TRAFFIC ) );

        final List<Opportunity> afterCooldown = applyCooldown( all, lastChangeByTarget, today, config.cooldownDays() );
        final List<Opportunity> afterSnooze = filterSnoozed( afterCooldown, snoozes, today );

        final List<Opportunity> calibrated = new ArrayList<>();
        for ( final Opportunity opportunity : afterSnooze ) {
            calibrated.add( new Opportunity( opportunity.type(), opportunity.target(), opportunity.priority(),
                    opportunity.evidence(), opportunity.suggestedAction(), opportunity.firstSeen(),
                    calibratedTypes.contains( opportunity.type() ) ) );
        }
        calibrated.sort( Comparator.comparingDouble( Opportunity::priority ).reversed() );

        return new Backlog( calibrated, List.copyOf( suppressed ) );
    }
}
