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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rule 4 -- {@code STALE_HIGH_TRAFFIC}: a page with real traffic whose verification has aged out. Traffic-gated (§7.3.0).
 *
 * <p>Package-private: callers reach it through {@link OpportunityEngine}, which delegates its
 * public {@code evaluate*} method here and runs it as part of the rule list.</p>
 */
final class StaleHighTrafficRule implements OpportunityRule {

    private static final String CONFIDENCE_AUTHORITATIVE = "authoritative";


    @Override
    public String type() {
        return OpportunityEngine.STALE_HIGH_TRAFFIC;
    }

    @Override
    public boolean gated() {
        return true;
    }

    @Override
    public List<Opportunity> evaluate( final EvaluationInput in ) {
        return detect( in.visibilityRows(), in.pageFacts(), in.today(), in.config() );
    }

    /**
     * Rule 4 -- {@code OpportunityEngine.STALE_HIGH_TRAFFIC}: a page with real traffic whose verification has aged
     * out (or never happened, or was never marked authoritative).
     *
     * <p>Impressions are read from the <strong>page-level rollup row</strong> only (real
     * {@code pagePath}, blank {@code queryText}) -- per-query rows are a lower bound once an
     * engine's low-volume-query privacy floor is applied, so summing them would undercount a
     * page's true traffic (see the design doc's "Known limitations" note in §3 and
     * {@code SnapshotPayloadParser}). A page can appear more than once across engines/snapshots;
     * rollup impressions are summed across all of them.</p>
     *
     * <p>A page unknown to {@code pageFacts} is skipped, not flagged -- there is no verification
     * state to judge staleness against. Otherwise the three trigger conditions are independent
     * ORs, matching §7.3 rule 4's literal wording: never verified, verified more than
     * {@link OpportunityEngineConfig#staleDays()} days ago, or confidence is anything other than
     * {@code authoritative} (which also catches an explicit non-authoritative override even on an
     * otherwise-fresh {@code verified_at}).</p>
     *
     * @param rows       visibility rows for the evaluation window
     * @param pageFacts  the page-state port ({@code verified_at}, confidence)
     * @param today      the date this evaluation runs, used both as
     *                   {@link Opportunity#firstSeen()} and as the staleness clock
     * @param config     thresholds; the global impression floor always applies even if
     *                   {@link OpportunityEngineConfig#staleHighTrafficMinImpressions()} is
     *                   configured lower
     * @return opportunities for pages meeting the minimum support
     */
    static List<Opportunity> detect( final List<VisibilityRow> rows, final PageFacts pageFacts,
                                     final LocalDate today, final OpportunityEngineConfig config ) {
        final int minImpressions = config.effectiveStaleHighTrafficMinImpressions();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, Long> entry : pageRollupImpressions( rows ).entrySet() ) {
            if ( entry.getValue() < minImpressions ) {
                continue;
            }
            final Optional<PageFacts.PageFact> fact = pageFacts.lookup( entry.getKey() );
            if ( fact.isEmpty() ) {
                continue; // unknown page: no verification state to judge staleness against
            }
            staleOpportunity( entry.getKey(), entry.getValue(), fact.get(), today, config ).ifPresent( out::add );
        }
        return out;
    }

    /** Impressions per page from page-level rollup rows only, summed across engines and snapshots. */
    private static Map<String, Long> pageRollupImpressions( final List<VisibilityRow> rows ) {
        final Map<String, Long> impressionsByPage = new LinkedHashMap<>();
        for ( final VisibilityRow row : rows ) {
            if ( row.pagePath() == null || row.pagePath().isBlank() ) {
                continue;
            }
            if ( row.queryText() != null && !row.queryText().isBlank() ) {
                continue; // not the page-level rollup row -- per-query rows undercount (D3)
            }
            impressionsByPage.merge( row.pagePath(), ( long ) row.impressions(), Long::sum );
        }
        return impressionsByPage;
    }

    /** The opportunity for a page, or empty when it is verified, fresh and authoritative. */
    private static Optional<Opportunity> staleOpportunity( final String pagePath, final long impressions,
                                                           final PageFacts.PageFact pf, final LocalDate today,
                                                           final OpportunityEngineConfig config ) {
        final Instant todayInstant = today.atStartOfDay( ZoneOffset.UTC ).toInstant();
        final Instant verifiedAt = pf.verifiedAt();
        final boolean neverVerified = verifiedAt == null;
        final Long daysSinceVerified = neverVerified ? null : ChronoUnit.DAYS.between( verifiedAt, todayInstant );
        final boolean agedOut = daysSinceVerified != null && daysSinceVerified > config.staleDays();
        final boolean notAuthoritative = !CONFIDENCE_AUTHORITATIVE.equalsIgnoreCase( pf.confidence() );

        if ( !( neverVerified || agedOut || notAuthoritative ) ) {
            return Optional.empty();
        }

        final Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put( "impressions", impressions );
        evidence.put( "verifiedAt", verifiedAt == null ? null : verifiedAt.toString() );
        evidence.put( "daysSinceVerified", daysSinceVerified );
        evidence.put( "confidence", pf.confidence() );

        return Optional.of( new Opportunity( OpportunityEngine.STALE_HIGH_TRAFFIC, pagePath,
                impressions * config.weightStaleHighTraffic(), evidence,
                "Re-verify or refresh the page.", today, false ) );
    }
}
