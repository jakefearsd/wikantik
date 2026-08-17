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
package com.wikantik.insights.runtime;

import com.wikantik.insights.Backlog;
import com.wikantik.insights.CalibrationSample;
import com.wikantik.insights.CtrCurveSnapshot;
import com.wikantik.insights.DemandRow;
import com.wikantik.insights.ExpectedCtrCurve;
import com.wikantik.insights.InsightsStore;
import com.wikantik.insights.Opportunity;
import com.wikantik.insights.OpportunityEngine;
import com.wikantik.insights.OpportunityEngineConfig;
import com.wikantik.insights.OpportunitySnooze;
import com.wikantik.insights.PageFacts;
import com.wikantik.insights.SuppressedRule;
import com.wikantik.insights.VisibilityRow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wiki-side facade over {@link OpportunityEngine} (content-intelligence design §7.5.1) -- the one
 * class the {@code list_content_opportunities} / {@code snooze_opportunity} MCP tools and the
 * {@code /admin/insights} backlog panel are meant to call. Everything the pure rule engine needs
 * (rows, page facts, config, {@code today}) is gathered here from {@link InsightsStore} and
 * {@link PageFacts}, and the calibration loop (§7.4.4) is run before the engine sees the config.
 *
 * <p><strong>Fail-open is mandatory.</strong> {@link InsightsStore}'s read methods throw
 * {@link IllegalStateException} on a SQL failure (see its javadoc) -- this facade is the one place
 * that has to catch it. A database hiccup degrades {@link #backlog} to an empty
 * {@link BacklogView} rather than propagating and 500ing an MCP tool call.</p>
 */
public final class ContentOpportunityService {

    private static final Logger LOG = LogManager.getLogger( ContentOpportunityService.class );

    private static final Set<String> NATIVE_TYPES = Set.of(
            OpportunityEngine.AGENT_GAP, OpportunityEngine.ENGINE_DIVERGENCE,
            OpportunityEngine.VOCABULARY_GAP, OpportunityEngine.STALE_HIGH_TRAFFIC );

    private static final int DEFAULT_LIMIT = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 200;
    private static final int MIN_SNOOZE_DAYS = 1;
    private static final int MAX_SNOOZE_DAYS = 365;
    private static final int DEMAND_WINDOW_DAYS = 28;
    private static final int VISIBILITY_WITHIN_DAYS = 14;

    private final InsightsStore store;
    private final PageFacts pageFacts;
    private final OpportunityEngine engine;
    private final InsightsSettings settings;
    private final WeightCalibrator calibrator;

    public ContentOpportunityService( final InsightsStore store, final PageFacts pageFacts,
                                      final OpportunityEngine engine, final InsightsSettings settings ) {
        if ( store == null || pageFacts == null || engine == null || settings == null ) {
            throw new IllegalArgumentException( "store, pageFacts, engine and settings are all required" );
        }
        this.store = store;
        this.pageFacts = pageFacts;
        this.engine = engine;
        this.settings = settings;
        this.calibrator = new WeightCalibrator();
    }

    /**
     * The read side of {@code list_content_opportunities} (design §7.5.1).
     *
     * @param opportunities     the merged, filtered, priority-sorted backlog, after the type/
     *                          priority/limit filters below
     * @param suppressed        rules the §7.3.0 traffic gate suppressed for this evaluation
     * @param uncalibratedTypes the native opportunity types whose weight is still the configured
     *                          default -- an agent should weigh their suggestions less
     * @param generatedAt       the date this view was assembled
     * @param site              the site this view was assembled for
     * @param ctrCurveSource    which {@link ExpectedCtrCurve} priced {@code ENGINE_DIVERGENCE} for
     *                          this evaluation -- {@code "imported:<as_of>"} when jakemon's real
     *                          curve was fresh enough to use, or {@code "builtin"} when it fell
     *                          back to {@link ExpectedCtrCurve#defaultCurve()} (no shipment yet, or
     *                          the latest one has gone stale). Deliberately explicit rather than
     *                          silent: a jakemon shipment quietly going stale must be visible to
     *                          whoever reads the backlog, not just inferable from the numbers
     *                          looking different
     */
    public record BacklogView( List<Opportunity> opportunities, List<SuppressedRule> suppressed,
                               Set<String> uncalibratedTypes, LocalDate generatedAt, String site,
                               String ctrCurveSource ) {
    }

    /**
     * The result of {@code snooze_opportunity} (design §7.5.1).
     *
     * @param snoozedUntil       the last day the new snooze is active (inclusive)
     * @param previouslySnoozed  whether an unexpired snooze for this {@code (type, target)} pair
     *                           already existed before this call
     */
    public record SnoozeResult( LocalDate snoozedUntil, boolean previouslySnoozed ) {
    }

    /**
     * Assembles the content-opportunity backlog for one site.
     *
     * @param site          the site to evaluate, or {@code null}/blank for the configured default
     *                      ({@link InsightsSettings#ruleSites()}'s first entry); a site not in
     *                      that list falls back to the default rather than querying an
     *                      unconfigured site
     * @param typeFilter    exact-match opportunity type filter, ignored when {@code null}/blank
     * @param minPriority   drop opportunities below this priority, ignored when {@code null}
     * @param limit         max opportunities to return; {@code <= 0} means the default (20),
     *                      otherwise clamped to {@code [1, 200]}
     * @param includeSnoozed when {@code true}, snoozed opportunities are not filtered out
     * @return the assembled view, or an empty view if the subsystem is disabled or a data-layer
     *         failure occurred (fail-open -- see the class javadoc)
     */
    public BacklogView backlog( final String site, final String typeFilter, final Double minPriority,
                                final int limit, final boolean includeSnoozed ) {
        final LocalDate today = LocalDate.now();
        final String resolvedSite = resolveSite( site );

        if ( !settings.enabled() ) {
            return emptyView( resolvedSite, today );
        }

        try {
            return backlogUnsafe( resolvedSite, typeFilter, minPriority, limit, includeSnoozed, today );
        } catch ( final RuntimeException e ) {
            LOG.warn( "ContentOpportunityService.backlog failed for site '{}': {}",
                    resolvedSite, e.getMessage(), e );
            return emptyView( resolvedSite, today );
        }
    }

    private BacklogView backlogUnsafe( final String site, final String typeFilter, final Double minPriority,
                                       final int limit, final boolean includeSnoozed, final LocalDate today ) {
        final int siteImpressions28d = store.siteImpressions28d( site );
        final List<VisibilityRow> visibilityRows = store.latestRowsPerEngine( site, VISIBILITY_WITHIN_DAYS );
        final List<DemandRow> demandRows = store.demandRows( DEMAND_WINDOW_DAYS );
        final Map<String, LocalDate> lastChangeByTarget = store.lastChangeByTarget();
        final List<OpportunitySnooze> snoozes = includeSnoozed ? List.of() : store.activeSnoozes( today );
        // The single most recent as_of row set for this site, or empty if it is older than
        // importedMaxAgeDays (content-intelligence design §7.3 "Imported from jakemon", §12.1 item
        // J3) -- a store failure here is caught by backlog()'s outer try/catch like every other
        // gather-block call, degrading the whole view to empty rather than partially populated.
        final List<Opportunity> importedOpportunities = store.latestImported( site, settings.importedMaxAgeDays() );
        // Calibration eligibility comes from calibrationSamples()'s own per-type group size, which
        // WeightCalibrator compares against minVerdicts directly (design §7.4.4). Deliberately NOT
        // store.verdictCountsByType(): that counts every evaluated change, including the
        // insufficient_data verdicts that carry no click delta and so cannot contribute to a
        // predicted-vs-realised ratio. Counting them would let a type cross the calibration
        // threshold on rows that teach it nothing.
        final List<CalibrationSample> calibrationSamples = store.calibrationSamples();

        final WeightCalibrator.CalibrationResult calibration = calibrator.calibrate(
                calibrationSamples, settings.calibrationMinVerdicts(), settings.calibrationDamping(),
                defaultWeightsByType() );

        final OpportunityEngineConfig engineConfig =
                withCalibratedWeights( settings.toEngineConfig(), calibration.calibratedWeights() );

        // jakemon's real curve (content-intelligence design §7.3 rule 2, V057) when a fresh
        // shipment exists, else the invented step table -- same staleness window as imported
        // opportunities (importedMaxAgeDays): both are "how stale can what jakemon shipped be
        // before we stop trusting it". A store failure here surfaces via the outer try/catch in
        // backlog(), degrading the whole view to empty like every other gather-block call.
        final Optional<CtrCurveSnapshot> ctrCurveSnapshot = store.latestCtrCurve( settings.importedMaxAgeDays() );
        final ExpectedCtrCurve ctrCurve = ctrCurveSnapshot
                .<ExpectedCtrCurve>map( s -> ExpectedCtrCurve.fromTable( s.points(), settings.ctrDeep(),
                        settings.ctrDeepMaxPosition() ) )
                .orElseGet( ExpectedCtrCurve::defaultCurve );
        final String ctrCurveSource = ctrCurveSnapshot
                .map( s -> "imported:" + s.asOf() )
                .orElse( "builtin" );

        final Backlog raw = engine.evaluateGated( demandRows, visibilityRows, importedOpportunities, pageFacts,
                snoozes, lastChangeByTarget, siteImpressions28d, calibration.calibratedTypes(),
                ctrCurve, today, engineConfig );

        final List<Opportunity> withProvenance = applyFirstSeenLedger( raw.opportunities(), today );

        final List<Opportunity> filtered = withProvenance.stream()
                .filter( o -> typeFilter == null || typeFilter.isBlank() || typeFilter.equals( o.type() ) )
                .filter( o -> minPriority == null || o.priority() >= minPriority )
                .limit( effectiveLimit( limit ) )
                .toList();

        final Set<String> uncalibratedTypes = new LinkedHashSet<>( NATIVE_TYPES );
        uncalibratedTypes.removeAll( calibration.calibratedTypes() );

        return new BacklogView( filtered, raw.suppressed(), uncalibratedTypes, today, site, ctrCurveSource );
    }

    /**
     * §7.5.1's provenance step -- this is the whole reason V054 ({@code content_opportunity_seen})
     * exists: without it, every opportunity would claim {@code firstSeen == today} on every call.
     */
    private List<Opportunity> applyFirstSeenLedger( final List<Opportunity> opportunities, final LocalDate today ) {
        final List<String[]> pairs = opportunities.stream()
                .map( o -> new String[] { o.type(), o.target() } )
                .toList();
        final Map<String, LocalDate> firstSeenByPair = store.upsertSeen( pairs, today );

        return opportunities.stream()
                .map( o -> new Opportunity( o.type(), o.target(), o.priority(), o.evidence(),
                        o.suggestedAction(), firstSeenByPair.getOrDefault( ledgerKey( o.type(), o.target() ), today ),
                        o.calibrated() ) )
                .toList();
    }

    /** Matches {@code InsightsStore#upsertSeen}'s {@code "<type> <target>"} key format exactly. */
    private static String ledgerKey( final String type, final String target ) {
        return type + " " + target;
    }

    private Map<String, Double> defaultWeightsByType() {
        final Map<String, Double> defaults = new LinkedHashMap<>();
        defaults.put( OpportunityEngine.AGENT_GAP, settings.weightAgentGap() );
        defaults.put( OpportunityEngine.ENGINE_DIVERGENCE, settings.weightEngineDivergence() );
        defaults.put( OpportunityEngine.VOCABULARY_GAP, settings.weightVocabularyGap() );
        defaults.put( OpportunityEngine.STALE_HIGH_TRAFFIC, settings.weightStaleHighTraffic() );
        return defaults;
    }

    private static OpportunityEngineConfig withCalibratedWeights( final OpportunityEngineConfig base,
                                                                   final Map<String, Double> calibratedWeights ) {
        if ( calibratedWeights.isEmpty() ) {
            return base;
        }
        return new OpportunityEngineConfig(
                base.agentGapMinOccurrences(),
                base.agentGapMinDistinctSessions(),
                base.engineDivergenceMinImpressions(),
                base.engineDivergenceMinSharedQueries(),
                base.engineDivergenceMinPositionGap(),
                base.vocabularyGapMinClicks(),
                base.staleHighTrafficMinImpressions(),
                base.staleDays(),
                base.globalFloorMinImpressions(),
                base.globalFloorMinOccurrences(),
                base.cooldownDays(),
                base.gateImpressions28d(),
                base.divergenceMinImpressionsWeak(),
                calibratedWeights.getOrDefault( OpportunityEngine.AGENT_GAP, base.weightAgentGap() ),
                calibratedWeights.getOrDefault( OpportunityEngine.ENGINE_DIVERGENCE, base.weightEngineDivergence() ),
                calibratedWeights.getOrDefault( OpportunityEngine.VOCABULARY_GAP, base.weightVocabularyGap() ),
                calibratedWeights.getOrDefault( OpportunityEngine.STALE_HIGH_TRAFFIC, base.weightStaleHighTraffic() ) );
    }

    private String resolveSite( final String site ) {
        final List<String> configured = settings.ruleSites();
        final String defaultSite = configured.isEmpty() ? null : configured.get( 0 );
        if ( site == null || site.isBlank() ) {
            return defaultSite;
        }
        if ( !configured.contains( site ) ) {
            LOG.warn( "Requested insights site '{}' is not among the configured rule sites {} -- "
                    + "falling back to default '{}'", site, configured, defaultSite );
            return defaultSite;
        }
        return site;
    }

    private static int effectiveLimit( final int limit ) {
        final int requested = limit <= 0 ? DEFAULT_LIMIT : limit;
        return Math.max( MIN_LIMIT, Math.min( MAX_LIMIT, requested ) );
    }

    private static BacklogView emptyView( final String site, final LocalDate today ) {
        return new BacklogView( List.of(), List.of(), Set.of(), today, site, "builtin" );
    }

    /**
     * The write side of {@code snooze_opportunity} (design §7.5.1).
     *
     * @param type   the opportunity type to snooze
     * @param target the page path or query text to snooze
     * @param days   how long to snooze for; clamped to {@code [1, 365]}
     * @param reason why the suggestion was declined; required -- a snooze with no reason is
     *               indistinguishable from a bug (matches {@link OpportunitySnooze}'s {@code NOT
     *               NULL} column)
     * @param by     login or agent identifier creating the snooze
     * @return the resulting snooze's expiry, and whether it replaced an already-active one
     * @throws IllegalArgumentException if {@code reason} is {@code null} or blank
     */
    public SnoozeResult snooze( final String type, final String target, final int days, final String reason,
                                final String by ) {
        if ( reason == null || reason.isBlank() ) {
            throw new IllegalArgumentException( "reason is required to snooze an opportunity" );
        }

        final LocalDate today = LocalDate.now();
        final int clampedDays = Math.max( MIN_SNOOZE_DAYS, Math.min( MAX_SNOOZE_DAYS, days ) );
        final LocalDate snoozedUntil = today.plusDays( clampedDays );

        final boolean previouslySnoozed = store.activeSnoozes( today ).stream()
                .anyMatch( s -> s.opportunityType().equals( type ) && s.target().equals( target )
                        && !today.isAfter( s.snoozedUntil() ) );

        store.snooze( new OpportunitySnooze( type, target, snoozedUntil, reason, by ) );

        return new SnoozeResult( snoozedUntil, previouslySnoozed );
    }
}
