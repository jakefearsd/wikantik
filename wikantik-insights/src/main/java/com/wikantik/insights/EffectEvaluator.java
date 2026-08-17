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

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns recorded content changes into effect verdicts (content-intelligence design section 7.4).
 *
 * <p>Runs over {@link InsightsStore#unevaluatedChanges} and writes a verdict per row. The
 * comparison is a <strong>weak quasi-experiment</strong> -- one site, no control group, no
 * randomisation -- so a single verdict is suggestive, not conclusive. Value accrues in aggregate:
 * fifty verdicts across one change type say whether that class of edit works even though any one
 * of them may be noise. {@code insufficient_data} is an expected and honest majority verdict on a
 * low-traffic corpus, not a failure of the mechanism.</p>
 *
 * <p><strong>Two things here are easy to get wrong and expensive to get wrong.</strong></p>
 *
 * <p>1. <em>Select a snapshot; never sum snapshots.</em> Rows in {@code
 * search_visibility_snapshot} are already trailing 28-day aggregates stamped with the window's END
 * date, so the "28 days after a change applied on D" is the single snapshot nearest {@code D + 28}.
 * Summing rows across snapshot dates would count the same impressions once per overlapping window
 * and inflate the after-figure several-fold. That selection lives in
 * {@link InsightsStore#pageWindowNear} / {@link InsightsStore#siteWindowNear}; this class must
 * never aggregate across dates itself.</p>
 *
 * <p>2. <em>A zero baseline CTR has no relative change.</em> The +/-15% thresholds are relative to
 * baseline CTR, which is undefined when the page drew no clicks -- the common case on a corpus
 * whose whole site draws ten clicks per 28 days. That case is resolved explicitly rather than by
 * dividing by zero.</p>
 *
 * <p>Every verdict records which comparison was actually available in {@code effect_method}. Today
 * that is always {@link #METHOD_PAGE_ROLLUP}: {@link #METHOD_QUERY_INTERSECTION} needs queries
 * attributed to pages, which the store does not have until jakemon ships {@code query_page} (work
 * item J1, design section 12.1). A page-rollup verdict is a weaker claim than a query-intersection
 * one because query-mix drift is uncontrolled, and anything reading verdicts in aggregate --
 * {@link WeightCalibrator}-style calibration especially -- must be able to tell them apart.</p>
 */
public final class EffectEvaluator {

    private static final Logger LOG = LogManager.getLogger( EffectEvaluator.class );
    private static final Gson GSON = new Gson();

    /** Query-mix drift is uncontrolled; page totals only. The only reachable mode today. */
    public static final String METHOD_PAGE_ROLLUP = "page_rollup";

    /** Restricted to queries present in both windows. Unreachable until jakemon ships query_page. */
    public static final String METHOD_QUERY_INTERSECTION = "query_intersection";

    public static final String IMPROVED = "improved";
    public static final String NO_EFFECT = "no_effect";
    public static final String REGRESSED = "regressed";
    public static final String INSUFFICIENT_DATA = "insufficient_data";

    /** Relative adjusted-CTR movement that counts as a real effect either way. */
    private static final double EFFECT_THRESHOLD = 0.15;

    private final InsightsStore store;
    private final String siteHost;
    private final int windowDays;
    private final int minBaselineImpressions;
    private final int toleranceDays;

    /**
     * @param store                  the fact store
     * @param siteHost               the site whose snapshots the verdicts are drawn from
     * @param windowDays             before/after window, normally 28
     * @param minBaselineImpressions below this the verdict is {@link #INSUFFICIENT_DATA}
     * @param toleranceDays          how far the nearest snapshot may sit from the ideal date
     */
    public EffectEvaluator( final InsightsStore store, final String siteHost, final int windowDays,
                            final int minBaselineImpressions, final int toleranceDays ) {
        this.store = store;
        this.siteHost = siteHost;
        this.windowDays = windowDays;
        this.minBaselineImpressions = minBaselineImpressions;
        this.toleranceDays = toleranceDays;
    }

    /**
     * Evaluates every change old enough to have a full after-window.
     *
     * <p>Fail-soft per row: one change that cannot be evaluated is logged and skipped, never
     * allowed to abort the batch -- a single malformed row must not stop every other verdict from
     * being recorded.</p>
     *
     * @param today the date this run happens
     * @return how many changes were given a verdict
     */
    public int evaluatePending( final LocalDate today ) {
        final LocalDate cutoff = today.minusDays( windowDays );
        final List< PendingChange > pending;
        try {
            pending = store.unevaluatedChanges( cutoff );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Effect evaluation could not read pending changes: {}", e.getMessage(), e );
            return 0;
        }

        int evaluated = 0;
        for ( final PendingChange change : pending ) {
            try {
                if ( evaluateOne( change ) ) {
                    evaluated++;
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "Effect evaluation failed for change id={} page={}: {}",
                        change.id(), change.pagePath(), e.getMessage(), e );
            }
        }
        if ( evaluated > 0 ) {
            LOG.info( "Effect evaluation recorded {} verdict(s) of {} pending", evaluated, pending.size() );
        }
        return evaluated;
    }

    private boolean evaluateOne( final PendingChange change ) {
        final LocalDate appliedDate = change.appliedAt().atZone( ZoneOffset.UTC ).toLocalDate();
        final LocalDate afterTarget = appliedDate.plusDays( windowDays );

        final Map< String, Object > detail = new LinkedHashMap<>();
        detail.put( "method", METHOD_PAGE_ROLLUP );
        detail.put( "appliedDate", appliedDate.toString() );
        detail.put( "afterTargetDate", afterTarget.toString() );

        // Below the support floor there is nothing to say, and saying it anyway would feed
        // calibration a verdict built on noise.
        if ( change.baselineImpressions() < minBaselineImpressions ) {
            detail.put( "reason", "baseline impressions " + change.baselineImpressions()
                    + " below minimum " + minBaselineImpressions );
            return record( change, INSUFFICIENT_DATA, null, null, null, detail );
        }

        final Optional< PageWindow > pageAfter =
                store.pageWindowNear( siteHost, change.pagePath(), afterTarget, toleranceDays );
        if ( pageAfter.isEmpty() ) {
            detail.put( "reason", "no snapshot within " + toleranceDays + " days of the after-window date" );
            return record( change, INSUFFICIENT_DATA, null, null, null, detail );
        }
        final PageWindow after = pageAfter.get();
        detail.put( "afterSnapshotDate", after.snapshotDate().toString() );
        detail.put( "afterImpressions", after.impressions() );
        detail.put( "afterClicks", after.clicks() );

        final double baselineCtr = change.baselineCtr() != null ? change.baselineCtr()
                : ratio( change.baselineClicks(), change.baselineImpressions() );
        final double afterCtr = ratio( after.clicks(), after.impressions() );

        // Difference-in-differences against the site's own trend. Seasonality and ranking updates
        // move a page's CTR without anyone touching it; subtracting the site-wide movement removes
        // the part of the change that was going to happen anyway. When either site window is
        // missing the adjustment is skipped and that fact is recorded -- an unadjusted verdict is
        // a weaker claim and must not masquerade as an adjusted one.
        final Optional< PageWindow > siteBefore =
                store.siteWindowNear( siteHost, change.baselineEnd(), toleranceDays );
        final Optional< PageWindow > siteAfter =
                store.siteWindowNear( siteHost, afterTarget, toleranceDays );

        double siteCtrDelta = 0.0;
        final boolean adjusted = siteBefore.isPresent() && siteAfter.isPresent();
        if ( adjusted ) {
            final double siteCtrBefore = ratio( siteBefore.get().clicks(), siteBefore.get().impressions() );
            final double siteCtrAfter = ratio( siteAfter.get().clicks(), siteAfter.get().impressions() );
            siteCtrDelta = siteCtrAfter - siteCtrBefore;
            detail.put( "siteCtrBefore", siteCtrBefore );
            detail.put( "siteCtrAfter", siteCtrAfter );
            detail.put( "siteSnapshotBefore", siteBefore.get().snapshotDate().toString() );
            detail.put( "siteSnapshotAfter", siteAfter.get().snapshotDate().toString() );
        }
        detail.put( "differenceInDifferences", adjusted );

        final double adjustedCtrDelta = ( afterCtr - baselineCtr ) - siteCtrDelta;
        detail.put( "baselineCtr", baselineCtr );
        detail.put( "afterCtr", afterCtr );

        // Realised click delta in the same unit the priorities are expressed in (expected
        // incremental clicks), so calibration can divide one by the other. The site-trend term is
        // converted back into clicks over the baseline's own impression volume.
        final double expectedFromSiteTrend = adjusted ? change.baselineImpressions() * siteCtrDelta : 0.0;
        final double clickDelta = after.clicks() - change.baselineClicks() - expectedFromSiteTrend;

        final Double positionDelta = after.position() == null || change.baselinePosition() == null
                ? null : after.position() - change.baselinePosition();

        final String verdict = verdictFor( baselineCtr, after.clicks(), adjustedCtrDelta, detail );
        return record( change, verdict, adjustedCtrDelta, positionDelta, clickDelta, detail );
    }

    /**
     * The verdict table from design section 7.4.2/7.4.3, with the zero-baseline case resolved
     * explicitly rather than by dividing by zero.
     */
    private static String verdictFor( final double baselineCtr, final int afterClicks,
                                      final double adjustedCtrDelta, final Map< String, Object > detail ) {
        if ( baselineCtr <= 0.0 ) {
            // No relative change exists against a zero rate. Going from no clicks to some clicks
            // is the only improvement this case can express.
            detail.put( "zeroBaselineCtr", true );
            return afterClicks > 0 ? IMPROVED : NO_EFFECT;
        }
        final double relative = adjustedCtrDelta / baselineCtr;
        detail.put( "relativeCtrChange", relative );
        if ( relative >= EFFECT_THRESHOLD ) {
            return IMPROVED;
        }
        if ( relative <= -EFFECT_THRESHOLD ) {
            return REGRESSED;
        }
        return NO_EFFECT;
    }

    private boolean record( final PendingChange change, final String verdict, final Double ctrDelta,
                            final Double positionDelta, final Double clickDelta,
                            final Map< String, Object > detail ) {
        return store.recordEffect( change.id(), verdict, ctrDelta, positionDelta, clickDelta,
                METHOD_PAGE_ROLLUP, GSON.toJson( detail ) );
    }

    private static double ratio( final int numerator, final int denominator ) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }
}
