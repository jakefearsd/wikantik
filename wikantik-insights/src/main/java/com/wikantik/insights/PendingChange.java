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

/**
 * A {@code content_change_log} row not yet evaluated ({@code evaluated_at IS NULL}), as read by
 * {@link InsightsStore#unevaluatedChanges}. The eventual nightly evaluator (content-intelligence
 * design §7.4.2 -- not built by this deliverable) picks these up once 28 days have passed since
 * {@code appliedAt} and compares the 28 days after against the recorded baseline.
 *
 * @param id                   the row's generated id
 * @param pagePath             the page this change applies to
 * @param changeType           see {@link ContentChange#changeType()}
 * @param opportunityType      the rule that motivated this change, or {@code null} if manual
 * @param appliedAt            when the change was applied
 * @param appliedBy            login or agent identifier that applied the change
 * @param note                 free-text context, or {@code null}
 * @param baselineStart        first day of the pre-change measurement window
 * @param baselineEnd          last day of the pre-change measurement window
 * @param baselineImpressions  total impressions in the baseline window
 * @param baselineClicks       total clicks in the baseline window
 * @param baselineCtr          clicks / impressions for the baseline window, or {@code null}
 * @param baselinePosition     average position in the baseline window, or {@code null}
 */
public record PendingChange( long id, String pagePath, String changeType, String opportunityType,
                             Instant appliedAt, String appliedBy, String note,
                             LocalDate baselineStart, LocalDate baselineEnd,
                             int baselineImpressions, int baselineClicks,
                             Double baselineCtr, Double baselinePosition ) {
}
