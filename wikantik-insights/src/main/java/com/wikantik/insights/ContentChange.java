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
 * A content change to record in {@code content_change_log} (V052), with its pre-change baseline.
 *
 * <p>The baseline is captured <strong>at write time</strong>, not reconstructed later --
 * reconstructing it later works only until retention or a GSC restatement moves the ground under
 * it (content-intelligence design §6.3). Callers read the baseline from
 * {@link InsightsStore#trend}/{@link InsightsStore#engineTotals} over the 28 days before
 * {@code appliedAt} and pass the aggregate here.</p>
 *
 * @param pagePath        the page this change applies to
 * @param changeType       {@code title}|{@code summary}|{@code tags}|{@code body}|
 *                         {@code new_page}|{@code internal_links}
 * @param opportunityType  the rule that motivated this change, or {@code null} if applied manually
 * @param appliedBy        login or agent identifier that applied the change
 * @param note             free-text context, or {@code null}
 * @param baselineStart    first day of the pre-change measurement window
 * @param baselineEnd      last day of the pre-change measurement window
 * @param baselineImpressions total impressions in the baseline window
 * @param baselineClicks      total clicks in the baseline window
 * @param baselineCtr         clicks / impressions for the baseline window, or {@code null} if
 *                            impressions was {@code 0}
 * @param baselinePosition    average position in the baseline window, or {@code null} if unreported
 * @param predictedPriority   the priority the engine assigned when this change was proposed
 *                            (V055, design §7.4.4), or {@code null} for a manually-applied change
 *                            with no motivating rule. Recorded at write time because it is
 *                            otherwise unrecoverable once thresholds or weights move -- see
 *                            {@link InsightsStore#calibrationSamples}.
 */
public record ContentChange( String pagePath, String changeType, String opportunityType,
                             String appliedBy, String note,
                             LocalDate baselineStart, LocalDate baselineEnd,
                             int baselineImpressions, int baselineClicks,
                             Double baselineCtr, Double baselinePosition,
                             Double predictedPriority ) {
}
