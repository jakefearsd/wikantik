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
}
