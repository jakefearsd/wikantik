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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule 2 -- {@code ENGINE_DIVERGENCE}: a page ranking materially better on one engine than another. Traffic-gated (§7.3.0).
 *
 * <p>Package-private: callers reach it through {@link OpportunityEngine}, which delegates its
 * public {@code evaluate*} method here and runs it as part of the rule list.</p>
 */
final class EngineDivergenceRule implements OpportunityRule {


    @Override
    public String type() {
        return OpportunityEngine.ENGINE_DIVERGENCE;
    }

    @Override
    public boolean gated() {
        return true;
    }

    @Override
    public List<Opportunity> evaluate( final EvaluationInput in ) {
        return detect( in.visibilityRows(), in.curve(), in.today(), in.config() );
    }

    /**
     * Rule 2 -- {@code OpportunityEngine.ENGINE_DIVERGENCE}: a page ranking materially better on one engine than
     * another. This rule is diagnostic, not corrective -- see the class comment and
     * {@link OpportunityEngine#suppressDivergenceAffected} for why it exists.
     *
     * <p><strong>Grain correction (2026-08-17, design §7.3 rule 2).</strong> This rule was
     * originally specified over a shared query set per page, but the ingest payload's
     * {@code by_page} and {@code by_query} rows are disjoint projections -- every query row lands
     * with {@code pagePath = ""}, so no query is attributed to a page (design §12.1 item J1). This
     * method therefore operates on <strong>page rollup rows only</strong> ({@code pagePath}
     * non-blank, {@code queryText} blank), comparing each page's per-engine average position
     * directly. Rows failing either condition -- including per-query rows, which carry a
     * non-blank {@code queryText} -- are ignored. A row with a {@code null} position is also
     * skipped: its impressions cannot be attributed to a position bucket, so it contributes
     * nothing to either side of the comparison.</p>
     *
     * <p>For each page present with a usable position on two or more distinct engines, the engine
     * with the best (lowest) average position is STRONG and the engine with the worst (highest)
     * is WEAK -- one candidate per page, not one per engine pair. Both floors are required:
     * {@code strong.impressions} against {@link OpportunityEngineConfig#effectiveEngineDivergenceMinImpressions()}
     * and {@code weak.impressions} against {@link OpportunityEngineConfig#divergenceMinImpressionsWeak()}
     * (deliberately <em>not</em> composed with the global floor -- see that field's doc), plus the
     * position gap against {@link OpportunityEngineConfig#engineDivergenceMinPositionGap()}. A
     * one-sided floor would compare a well-sampled position against one estimated from a single
     * impression.</p>
     *
     * <p>Priority is the expected-CTR uplift from moving the weak engine's traffic to the strong
     * engine's position, using {@code curve}: {@code weak.impressions x (curve.ctrAt(strong.position)
     * - curve.ctrAt(weak.position)) x config.weightEngineDivergence()}, floored at zero.</p>
     *
     * @param rows   visibility rows for the evaluation window
     * @param curve  the expected click-through-rate-by-position model driving the priority
     *               formula -- see {@link ExpectedCtrCurve#defaultCurve()}
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds
     * @return diagnostic opportunities for pages meeting the minimum support
     */
    static List<Opportunity> detect( final List<VisibilityRow> rows, final ExpectedCtrCurve curve,
                                     final LocalDate today, final OpportunityEngineConfig config ) {
        final int minStrongImpressions = config.effectiveEngineDivergenceMinImpressions();
        final int minWeakImpressions = config.divergenceMinImpressionsWeak();
        final double minPositionGap = config.engineDivergenceMinPositionGap();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, Map<String, RollupAccumulator>> pageEntry : rollupsByPageThenEngine( rows ).entrySet() ) {
            final Extremes extremes = Extremes.of( pageEntry.getValue() );
            if ( extremes == null ) {
                continue; // fewer than two engines with a usable position -- nothing to compare
            }
            if ( extremes.strongAcc.impressions < minStrongImpressions
                    || extremes.weakAcc.impressions < minWeakImpressions
                    || extremes.positionGap() < minPositionGap ) {
                continue;
            }
            out.add( opportunityFor( pageEntry.getKey(), extremes, curve, today, config ) );
        }
        return out;
    }

    /** Page-rollup rows only (real page path, blank query), grouped by page then engine. */
    private static Map<String, Map<String, RollupAccumulator>> rollupsByPageThenEngine( final List<VisibilityRow> rows ) {
        final Map<String, Map<String, RollupAccumulator>> byPageThenEngine = new LinkedHashMap<>();
        for ( final VisibilityRow row : rows ) {
            if ( row.pagePath() == null || row.pagePath().isBlank() ) {
                continue; // no page to target -- this is a query-rollup row (D3)
            }
            if ( row.queryText() != null && !row.queryText().isBlank() ) {
                continue; // not a page-rollup row -- see the grain-correction note above
            }
            byPageThenEngine
                    .computeIfAbsent( row.pagePath(), key -> new LinkedHashMap<>() )
                    .computeIfAbsent( row.engine(), key -> new RollupAccumulator() )
                    .add( row );
        }
        return byPageThenEngine;
    }

    private static Opportunity opportunityFor( final String pagePath, final Extremes extremes,
                                               final ExpectedCtrCurve curve, final LocalDate today,
                                               final OpportunityEngineConfig config ) {
        final double ctrUplift = curve.ctrAt( extremes.strongPosition ) - curve.ctrAt( extremes.weakPosition );
        final double estimatedRecoverableClicks = extremes.weakAcc.impressions * ctrUplift;
        final double priority = Math.max( 0.0, estimatedRecoverableClicks * config.weightEngineDivergence() );

        final Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put( "strongEngine", extremes.strongEngine );
        evidence.put( OpportunityEngine.EVIDENCE_WEAK_ENGINE, extremes.weakEngine );
        evidence.put( "strongPosition", extremes.strongPosition );
        evidence.put( "weakPosition", extremes.weakPosition );
        evidence.put( "positionGap", extremes.positionGap() );
        evidence.put( "strongImpressions", extremes.strongAcc.impressions );
        evidence.put( "weakImpressions", extremes.weakAcc.impressions );
        evidence.put( "estimatedRecoverableClicks", estimatedRecoverableClicks );

        return new Opportunity( OpportunityEngine.ENGINE_DIVERGENCE, pagePath, priority, evidence,
                "Diagnostic, usually \"change nothing on this page.\"", today, false );
    }

    /** One page's best-ranked (strong) and worst-ranked (weak) engines, by average position. */
    private static final class Extremes {
        private String strongEngine;
        private String weakEngine;
        private double strongPosition;
        private double weakPosition;
        private RollupAccumulator strongAcc;
        private RollupAccumulator weakAcc;

        /**
         * @return the extremes among engines with a usable position, or {@code null} when fewer
         *         than two distinct engines qualify
         */
        static Extremes of( final Map<String, RollupAccumulator> byEngine ) {
            final Extremes extremes = new Extremes();
            for ( final Map.Entry<String, RollupAccumulator> engineEntry : byEngine.entrySet() ) {
                final Double position = engineEntry.getValue().averagePosition();
                if ( position == null ) {
                    continue; // no usable position for this engine -- can't rank it
                }
                if ( extremes.strongEngine == null || position < extremes.strongPosition ) {
                    extremes.strongPosition = position;
                    extremes.strongEngine = engineEntry.getKey();
                    extremes.strongAcc = engineEntry.getValue();
                }
                if ( extremes.weakEngine == null || position > extremes.weakPosition ) {
                    extremes.weakPosition = position;
                    extremes.weakEngine = engineEntry.getKey();
                    extremes.weakAcc = engineEntry.getValue();
                }
            }
            return extremes.strongEngine == null || extremes.strongEngine.equals( extremes.weakEngine )
                    ? null : extremes;
        }

        double positionGap() {
            return weakPosition - strongPosition;
        }
    }

    /** Per-(page, engine) running totals accumulated while scanning page-rollup rows. */
    private static final class RollupAccumulator {
        private long impressions;
        private long clicks;
        private double positionSum;
        private int positionCount;

        void add( final VisibilityRow row ) {
            if ( row.position() == null ) {
                return; // a row with no position can't be attributed to a position bucket
            }
            impressions += row.impressions();
            clicks += row.clicks();
            positionSum += row.position();
            positionCount++;
        }

        Double averagePosition() {
            return positionCount == 0 ? null : positionSum / positionCount;
        }
    }
}
