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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for upserting and querying search visibility snapshots.
 */
public interface InsightsStore {

    /**
     * Upserts visibility rows, keyed by (snapshotDate, engine, siteHost, pagePath, queryText).
     * Re-sending a window converges instead of duplicating — backfill and the nightly run
     * are the same code path, so duplicates would double-count every history load.
     *
     * @param rows the visibility rows to upsert
     * @return the number of rows written; 0 if the list is empty or an error occurred
     */
    int upsert( List<VisibilityRow> rows );

    /**
     * The most recent {@code snapshot_date} with page-rollup rows ({@code query_text = ''})
     * for the given site.
     *
     * @param siteHost the site host
     * @return the latest snapshot date, or empty if the site has no page-rollup rows
     */
    Optional<LocalDate> latestSnapshotDate( String siteHost );

    /**
     * Per-engine totals from page-rollup rows ({@code query_text = ''}) for exactly one
     * {@code snapshot_date}. An engine that emits no page-rollup rows for that date (e.g.
     * Yandex) is simply absent from the result — never synthesized as a zero row.
     *
     * @param siteHost     the site host
     * @param snapshotDate the snapshot date to total
     * @return per-engine totals, ordered by engine name
     */
    List<EngineTotal> engineTotals( String siteHost, LocalDate snapshotDate );

    /**
     * One point per (snapshot_date, engine) with {@code snapshot_date >= since}, aggregated
     * from page-rollup rows ({@code query_text = ''}) only, oldest first.
     *
     * @param siteHost the site host
     * @param since    the inclusive earliest snapshot date to include
     * @return trend points ordered by snapshot date then engine
     */
    List<TrendPoint> trend( String siteHost, LocalDate since );

    /**
     * Records an applied content change with its pre-change baseline in
     * {@code content_change_log} (V052). The baseline is captured at write time -- see
     * {@link ContentChange}'s javadoc.
     *
     * @param change the change and its baseline
     * @return the generated row id, or empty if the write failed
     */
    Optional<Long> recordChange( ContentChange change );

    /**
     * Reads {@code content_change_log} rows not yet evaluated ({@code evaluated_at IS NULL})
     * whose {@code applied_at} is at or before {@code cutoff} -- i.e. rows the nightly evaluator
     * (content-intelligence design §7.4.2) is ready to score. Passing {@code cutoff = today}
     * (rather than filtering on age here) keeps the 28-day rule a caller-side concern.
     *
     * @param cutoff the latest {@code applied_at} to include
     * @return matching rows, oldest {@code applied_at} first
     */
    List<PendingChange> unevaluatedChanges( LocalDate cutoff );

    /**
     * Upserts one snooze into {@code content_opportunity_snooze} (V053), keyed by
     * {@code (opportunityType, target)}. A repeat call for the same pair replaces the prior
     * snooze rather than erroring.
     *
     * @param snooze the snooze to write
     * @return {@code true} if the write succeeded
     */
    boolean snooze( OpportunitySnooze snooze );

    /**
     * Reads every snooze active as of {@code today} (i.e. {@code snoozed_until >= today}).
     *
     * @param today the date to check snoozes against
     * @return active snoozes
     */
    List<OpportunitySnooze> activeSnoozes( LocalDate today );

    /**
     * Every row from the most recent {@code snapshot_date} <strong>per engine</strong>, for the
     * given site -- both rollup and query rows. Engines poll on independent cadences, so this
     * deliberately does not require one shared date across engines (that would silently empty a
     * cross-engine comparison whenever one engine's latest snapshot lags another's).
     *
     * <p>An engine whose latest snapshot is older than {@code withinDays} before the newest
     * snapshot date across all engines is excluded entirely -- a stale engine is worse than an
     * absent one in a comparison view.</p>
     *
     * @param siteHost   the site host
     * @param withinDays how many days before the newest snapshot date an engine's own latest
     *                   snapshot may lag before it is dropped
     * @return the latest rows per engine, ordered by engine then page path then query text
     */
    List<VisibilityRow> latestRowsPerEngine( String siteHost, int withinDays );

    /**
     * Sum of {@code impressions} over page-rollup rows only ({@code query_text = ''}), from the
     * latest {@code snapshot_date} per engine, across every engine. Feeds a traffic gate, so query
     * rows -- which would double count against the page total -- are never included.
     *
     * @param siteHost the site host
     * @return total rollup impressions across engines' latest snapshots
     */
    int siteImpressions28d( String siteHost );

    /**
     * Demand signals from {@code retrieval_query_log} within the last {@code sinceDays} days,
     * grouped by exact {@code query_text}.
     *
     * @param sinceDays how many days back to look, inclusive of today
     * @return one {@link DemandRow} per distinct query text observed in the window
     */
    List<DemandRow> demandRows( int sinceDays );

    /**
     * Most recent {@code applied_at::date} per {@code page_path} from {@code content_change_log}.
     *
     * @return page path to last-change date
     */
    Map<String, LocalDate> lastChangeByTarget();

    /**
     * Records the realised outcome of a previously-applied change (design §7.4.2/§7.4.4).
     *
     * @param changeId       the {@code content_change_log} row id to update
     * @param verdict        the effect verdict (e.g. {@code positive}, {@code no_effect},
     *                       {@code insufficient_data})
     * @param ctrDelta       realised CTR delta, or {@code null}
     * @param positionDelta  realised position delta, or {@code null}
     * @param clickDelta     realised, site-adjusted click delta, or {@code null}
     * @param method         which comparison the evaluator ran ({@code query_intersection} or
     *                       {@code page_rollup}), or {@code null}
     * @param detailJson     free-form JSON detail, stored as {@code jsonb}, or {@code null}
     * @return {@code true} if a row was updated
     */
    boolean recordEffect( long changeId, String verdict, Double ctrDelta, Double positionDelta,
                          Double clickDelta, String method, String detailJson );

    /**
     * Count of evaluated, non-{@code insufficient_data} {@code content_change_log} rows per
     * {@code opportunity_type} -- used to decide which rule types have enough evaluated history
     * to be calibrated.
     *
     * @return opportunity type to evaluated-row count
     */
    Map<String, Integer> verdictCountsByType();

    /**
     * Evaluated rows with both a recorded prediction and a recorded outcome -- the raw material
     * for self-calibration (design §7.4.4).
     *
     * @return calibration samples
     */
    List<CalibrationSample> calibrationSamples();

    /**
     * Upserts one {@code content_opportunity_seen} (V054) row per {@code (type, target)} pair,
     * advancing {@code last_seen} to {@code today} while preserving {@code first_seen} across
     * repeat calls. Fail-soft: on failure this logs a warning and returns an empty map rather than
     * throwing, so callers fall back to treating every opportunity as first-seen today.
     *
     * @param typeTargetPairs each element a {@code {opportunityType, target}} pair
     * @param today           the date to stamp as {@code last_seen} (and {@code first_seen} for a
     *                        new pair)
     * @return {@code "<type> <target>"} to the row's {@code first_seen} after the upsert; empty on
     *         failure
     */
    Map<String, LocalDate> upsertSeen( List<String[]> typeTargetPairs, LocalDate today );

    /**
     * Resolves one page's {@code search_visibility_snapshot} rollup window nearest a target date
     * (design §7.4.2/§7.4.3) -- the primitive the nightly effect evaluator uses to read "the 28
     * days after a change applied on D".
     *
     * <p><strong>The critical semantic: select a snapshot, never sum snapshots.</strong> Every
     * row in {@code search_visibility_snapshot} is <em>already</em> a trailing 28-day aggregate,
     * stamped with its window's <em>end</em> date -- not a daily figure. "The 28 days after a
     * change applied on D" is therefore not a sum over rows between D and D+28; it is the
     * <strong>single</strong> snapshot whose {@code snapshot_date} lands nearest {@code D + 28}.
     * Summing multiple snapshot dates would sum overlapping 28-day windows and inflate the
     * after-figure several-fold, because the same underlying daily impressions would be counted
     * once for every snapshot whose trailing window includes that day. This method picks exactly
     * one {@code snapshot_date} (nearest {@code targetDate}, ties broken toward the earlier date)
     * and aggregates only across <em>engines</em> for that one date -- never across dates. Do not
     * "fix" this into a {@code SUM(...) WHERE snapshot_date BETWEEN ...} without re-reading design
     * §7.4.2.</p>
     *
     * <p>Within the matched date, impressions and clicks are summed across every engine that
     * reported a page-rollup row ({@code query_text = ''}); {@code position} is the
     * impression-weighted mean of the engines that reported one, skipping engines that reported
     * no position.</p>
     *
     * @param siteHost      the site host
     * @param pagePath      the page path
     * @param targetDate    the date to resolve a snapshot near (e.g. {@code appliedAt + 28 days})
     * @param toleranceDays how many days before/after {@code targetDate} a snapshot may fall and
     *                      still be selected
     * @return the resolved window, or empty if no page-rollup snapshot for this page falls within
     *         tolerance of {@code targetDate}
     */
    Optional<PageWindow> pageWindowNear( String siteHost, String pagePath, LocalDate targetDate,
                                         int toleranceDays );

    /**
     * The difference-in-differences control term for {@link #pageWindowNear} (design §7.4.3) --
     * the same nearest-single-snapshot resolution, but totalled across <strong>every page's</strong>
     * rollup row for the site rather than one page's. Read {@link #pageWindowNear}'s javadoc first:
     * the same "select one snapshot, never sum snapshots" rule applies here.
     *
     * <p>This method resolves its own nearest {@code snapshot_date} independently of any
     * {@link #pageWindowNear} call -- the site-wide rollup total for a date and one page's rollup
     * for that same target may legitimately land on different actual {@code snapshot_date}s, since
     * each resolution only considers the snapshot dates that have rollup data for what it is
     * totalling. Callers must not assume the two share a date; each returned {@link PageWindow}
     * carries its own {@code snapshotDate} for exactly this reason.</p>
     *
     * @param siteHost      the site host
     * @param targetDate    the date to resolve a snapshot near
     * @param toleranceDays how many days before/after {@code targetDate} a snapshot may fall and
     *                      still be selected
     * @return the resolved site-wide window, or empty if no page-rollup snapshot falls within
     *         tolerance of {@code targetDate}
     */
    Optional<PageWindow> siteWindowNear( String siteHost, LocalDate targetDate, int toleranceDays );

    /**
     * Batch-upserts imported jakemon detector opportunities into {@code imported_opportunity}
     * (V056), keyed by {@code (asOf, engine, siteHost, opportunityType, target)}. Re-sending a
     * day's detector run converges instead of duplicating -- the same idempotency contract as
     * {@link #upsert}.
     *
     * @param rows the imported opportunity rows to upsert
     * @return the number of rows written; 0 if the list is empty or an error occurred
     */
    int upsertImported( List<ImportedOpportunityRow> rows );

    /**
     * The imported opportunity backlog for one site (content-intelligence design §7.3 "Imported
     * from jakemon"): every row from the single most recent {@code as_of} for {@code siteHost},
     * mapped to {@link Opportunity}, but <strong>only</strong> if that {@code as_of} is within
     * {@code maxAgeDays} of today.
     *
     * <p>The staleness guard is deliberate: without it, a detector run jakemon shipped weeks ago
     * (because the collector stopped, or the shipper broke) would keep proposing the same work
     * forever. Past {@code maxAgeDays}, this returns empty rather than stale rows.</p>
     *
     * @param siteHost   the site to read imported opportunities for
     * @param maxAgeDays how many days old the most recent {@code as_of} may be before it is
     *                   treated as stale and this returns empty
     * @return the imported opportunities for the latest, non-stale {@code as_of}; empty if the
     *         site has no rows, or its latest {@code as_of} is older than {@code maxAgeDays}
     */
    List<Opportunity> latestImported( String siteHost, int maxAgeDays );
}
