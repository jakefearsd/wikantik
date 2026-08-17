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

/**
 * One resolved {@code search_visibility_snapshot} window for the effect evaluator (design
 * §7.4.2/§7.4.3) -- either one page's rollup or the whole site's rollup total, for a single
 * {@code snapshot_date}, summed across engines.
 *
 * <p><strong>{@code snapshotDate} is the single snapshot the caller matched against, never an
 * aggregation period.</strong> Every {@code search_visibility_snapshot} row is already a trailing
 * 28-day aggregate stamped with its window's <em>end</em> date -- see
 * {@link InsightsStore#pageWindowNear} for why summing across dates would double-count. Recording
 * the actual date used (not just the target the caller asked for) lets a verdict be re-derived
 * later, which is why {@link InsightsStore#pageWindowNear}/{@link InsightsStore#siteWindowNear}
 * return it rather than requiring the caller to already know it.</p>
 *
 * @param snapshotDate the actual {@code snapshot_date} matched, which may differ from the
 *                      caller's target date by up to the requested tolerance
 * @param impressions   impressions summed across every engine reporting for this date
 * @param clicks        clicks summed across every engine reporting for this date
 * @param position      impression-weighted mean position across engines that reported one, or
 *                      {@code null} if no engine reported a position for this date
 */
public record PageWindow( LocalDate snapshotDate, int impressions, int clicks, Double position ) {
}
