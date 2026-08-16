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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * two rules that need data jakemon cannot see: {@link #evaluateAgentGap} (rule 1, needs MCP
 * traffic) and {@link #evaluateEngineDivergence} (rule 2, needs retained multi-engine history).
 * The five jakemon-imported types ({@code striking_distance}, {@code ctr_gap},
 * {@code content_gap}, {@code cannibalization}, {@code decay}) arrive pre-built as
 * {@link Opportunity} instances and only pass through {@link #suppressDivergenceAffected}.</p>
 */
public final class OpportunityEngine {

    /** Rule 1 type identifier. */
    public static final String AGENT_GAP = "agent_gap";

    /** Rule 2 type identifier. */
    public static final String ENGINE_DIVERGENCE = "engine_divergence";

    private static final Set<String> WEAK_COVERAGE = Set.of( "weak", "unknown" );

    /**
     * Imported jakemon types that {@link #evaluateEngineDivergence} can explain away. A page
     * ranking well on one engine and badly on another looks, in Google's data alone, exactly like
     * a title/snippet failure -- but rewriting the page will not fix a domain-authority gap. See
     * §7.3 rule 2 and the class-level note on why this suppression exists.
     */
    private static final Set<String> SUPPRESSIBLE_BY_DIVERGENCE = Set.of( "ctr_gap", "striking_distance" );

    private static final String EVIDENCE_ENGINE = "engine";
    private static final String EVIDENCE_WEAK_ENGINE = "weakEngine";

    /**
     * Rule 1 -- {@code AGENT_GAP}: a retrieval query that consistently comes back empty or weak
     * on agent surfaces. See DemandRow's javadoc for the input shape and grouping contract.
     *
     * <p>Rows are grouped by {@link DemandRow#queryText()}. A query only fires when
     * <strong>every</strong> row in its group is weak (zero results, or
     * {@code coverage ∈ {weak, unknown}}) -- a single non-weak row for that query means the gap
     * is not consistent and the query is left silent, matching §7.3 rule 1's "every occurrence"
     * wording.</p>
     *
     * @param rows   demand rows for the evaluation window; not queried here, supplied by the caller
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds; the global occurrence floor always applies even if
     *               {@link OpportunityEngineConfig#agentGapMinOccurrences()} is configured lower
     * @return opportunities for queries meeting the minimum support, in no particular order
     */
    public List<Opportunity> evaluateAgentGap( final List<DemandRow> rows, final LocalDate today,
                                                final OpportunityEngineConfig config ) {
        final Map<String, List<DemandRow>> byQuery = new LinkedHashMap<>();
        for ( final DemandRow row : rows ) {
            byQuery.computeIfAbsent( row.queryText(), key -> new ArrayList<>() ).add( row );
        }

        final int minOccurrences = config.effectiveAgentGapMinOccurrences();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, List<DemandRow>> entry : byQuery.entrySet() ) {
            final List<DemandRow> group = entry.getValue();

            final boolean everyRowWeak = group.stream().allMatch( OpportunityEngine::isWeak );
            if ( !everyRowWeak ) {
                continue;
            }

            final int occurrences = group.stream().mapToInt( DemandRow::occurrences ).sum();
            final int distinctSessions = group.stream().mapToInt( DemandRow::distinctSessions ).sum();
            if ( occurrences < minOccurrences || distinctSessions < config.agentGapMinDistinctSessions() ) {
                continue;
            }

            final long zeroResultOccurrences = group.stream()
                    .filter( r -> r.resultCount() <= 0 ).mapToInt( DemandRow::occurrences ).sum();
            final long weakCoverageOccurrences = group.stream()
                    .filter( r -> r.resultCount() > 0 && isWeakCoverage( r.coverage() ) )
                    .mapToInt( DemandRow::occurrences ).sum();

            final Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put( "queryText", entry.getKey() );
            evidence.put( "occurrences", occurrences );
            evidence.put( "distinctSessions", distinctSessions );
            evidence.put( "zeroResultOccurrences", zeroResultOccurrences );
            evidence.put( "weakCoverageOccurrences", weakCoverageOccurrences );

            out.add( new Opportunity( AGENT_GAP, entry.getKey(), occurrences * 2.0, evidence,
                    "Curate the Knowledge Graph relations, or write the missing section.",
                    today, false ) );
        }
        return out;
    }

    private static boolean isWeak( final DemandRow row ) {
        return row.resultCount() <= 0 || isWeakCoverage( row.coverage() );
    }

    private static boolean isWeakCoverage( final String coverage ) {
        return coverage != null && WEAK_COVERAGE.contains( coverage.toLowerCase( Locale.ROOT ) );
    }

    /**
     * Rule 2 -- {@code ENGINE_DIVERGENCE}: a page ranking materially better on one engine than
     * another across a shared query set. This rule is diagnostic, not corrective -- see the class
     * comment and {@link #suppressDivergenceAffected} for why it exists.
     *
     * <p>Only rows carrying <em>both</em> a non-blank {@link VisibilityRow#pagePath()} and a
     * non-blank {@link VisibilityRow#queryText()} (the per-page-per-query grain) are considered;
     * page-rollup rows ({@code queryText = ""}) and query-rollup rows ({@code pagePath = ""})
     * carry no per-engine query set to compare and are ignored.</p>
     *
     * @param rows   visibility rows for the evaluation window
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds; the global impression floor always applies even if
     *               {@link OpportunityEngineConfig#engineDivergenceMinImpressions()} is
     *               configured lower
     * @return diagnostic opportunities for page/engine pairs meeting the minimum support
     */
    public List<Opportunity> evaluateEngineDivergence( final List<VisibilityRow> rows, final LocalDate today,
                                                        final OpportunityEngineConfig config ) {
        final Map<String, Map<String, EngineAccumulator>> byPageThenEngine = new LinkedHashMap<>();
        for ( final VisibilityRow row : rows ) {
            if ( row.pagePath() == null || row.pagePath().isBlank()
                    || row.queryText() == null || row.queryText().isBlank() ) {
                continue;
            }
            byPageThenEngine
                    .computeIfAbsent( row.pagePath(), key -> new LinkedHashMap<>() )
                    .computeIfAbsent( row.engine(), key -> new EngineAccumulator() )
                    .add( row );
        }

        final int minImpressions = config.effectiveEngineDivergenceMinImpressions();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, Map<String, EngineAccumulator>> pageEntry : byPageThenEngine.entrySet() ) {
            final String pagePath = pageEntry.getKey();
            final List<String> engines = new ArrayList<>( pageEntry.getValue().keySet() );

            for ( int i = 0; i < engines.size(); i++ ) {
                for ( int j = i + 1; j < engines.size(); j++ ) {
                    final EngineAccumulator a = pageEntry.getValue().get( engines.get( i ) );
                    final EngineAccumulator b = pageEntry.getValue().get( engines.get( j ) );

                    final Double posA = a.averagePosition();
                    final Double posB = b.averagePosition();
                    if ( posA == null || posB == null ) {
                        continue; // can't compare rank without a position on both sides
                    }

                    final boolean aStronger = posA < posB;
                    final EngineAccumulator strong = aStronger ? a : b;
                    final EngineAccumulator weak = aStronger ? b : a;
                    final String strongEngine = aStronger ? engines.get( i ) : engines.get( j );
                    final String weakEngine = aStronger ? engines.get( j ) : engines.get( i );

                    final double positionGap = weak.averagePosition() - strong.averagePosition();
                    final int sharedQueries = sharedQueryCount( strong.queries, weak.queries );

                    if ( strong.impressions < minImpressions
                            || sharedQueries < config.engineDivergenceMinSharedQueries()
                            || positionGap < config.engineDivergenceMinPositionGap() ) {
                        continue;
                    }

                    final Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put( "strongEngine", strongEngine );
                    evidence.put( EVIDENCE_WEAK_ENGINE, weakEngine );
                    evidence.put( "strongEngineImpressions", strong.impressions );
                    evidence.put( "weakEngineImpressions", weak.impressions );
                    evidence.put( "strongEngineClicks", strong.clicks );
                    evidence.put( "strongEnginePosition", strong.averagePosition() );
                    evidence.put( "weakEnginePosition", weak.averagePosition() );
                    evidence.put( "positionGap", positionGap );
                    evidence.put( "sharedQueries", sharedQueries );

                    out.add( new Opportunity( ENGINE_DIVERGENCE, pagePath, strong.clicks * 0.5, evidence,
                            "Diagnostic, usually \"change nothing on this page.\"", today, false ) );
                }
            }
        }
        return out;
    }

    private static int sharedQueryCount( final Set<String> a, final Set<String> b ) {
        final Set<String> shared = new HashSet<>( a );
        shared.retainAll( b );
        return shared.size();
    }

    /** Per-(page, engine) running totals accumulated while scanning {@link VisibilityRow}s. */
    private static final class EngineAccumulator {
        private final Set<String> queries = new LinkedHashSet<>();
        private long impressions;
        private long clicks;
        private double positionSum;
        private int positionCount;

        void add( final VisibilityRow row ) {
            queries.add( row.queryText() );
            impressions += row.impressions();
            clicks += row.clicks();
            if ( row.position() != null ) {
                positionSum += row.position();
                positionCount++;
            }
        }

        Double averagePosition() {
            return positionCount == 0 ? null : positionSum / positionCount;
        }
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
     * Runs the full Phase 2 pipeline: the two native rules, divergence suppression of the
     * imported backlog, cooldown, snooze filtering, then a descending-priority sort of the merged
     * result. Each step is also exposed individually above for isolated testing.
     *
     * @param demandRows            input for {@link #evaluateAgentGap}
     * @param visibilityRows        input for {@link #evaluateEngineDivergence}
     * @param importedOpportunities opportunities imported unchanged from jakemon
     * @param snoozes               active/inactive snooze rows; expiry is checked against {@code today}
     * @param lastChangeByTarget    most recent change date per target, for the cooldown
     * @param today                 the date this evaluation runs
     * @param config                thresholds
     * @return the merged, filtered, priority-sorted backlog
     */
    public List<Opportunity> evaluate( final List<DemandRow> demandRows,
                                       final List<VisibilityRow> visibilityRows,
                                       final List<Opportunity> importedOpportunities,
                                       final List<OpportunitySnooze> snoozes,
                                       final Map<String, LocalDate> lastChangeByTarget,
                                       final LocalDate today,
                                       final OpportunityEngineConfig config ) {
        final List<Opportunity> agentGap = evaluateAgentGap( demandRows, today, config );
        final List<Opportunity> divergence = evaluateEngineDivergence( visibilityRows, today, config );
        final List<Opportunity> merged = suppressDivergenceAffected( divergence, importedOpportunities );

        final List<Opportunity> all = new ArrayList<>( agentGap );
        all.addAll( merged );

        final List<Opportunity> afterCooldown = applyCooldown( all, lastChangeByTarget, today, config.cooldownDays() );
        final List<Opportunity> afterSnooze = filterSnoozed( afterCooldown, snoozes, today );

        final List<Opportunity> sorted = new ArrayList<>( afterSnooze );
        sorted.sort( Comparator.comparingDouble( Opportunity::priority ).reversed() );
        return sorted;
    }
}
