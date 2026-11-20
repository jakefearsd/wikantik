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
import java.util.Set;

/**
 * Everything one {@link OpportunityEngine#evaluateGated} run reads, gathered so each
 * {@link OpportunityRule} takes a single argument and picks the fields it needs. Pure data; the
 * field meanings are those documented on {@code evaluateGated}'s parameters.
 *
 * @param demandRows          retrieval demand rows (input for {@code AGENT_GAP})
 * @param visibilityRows      search-visibility rows (input for the three visibility-driven rules)
 * @param imported            opportunities imported unchanged from jakemon; never gated
 * @param pageFacts           page-state port for the rules that need page metadata
 * @param snoozes             active/inactive snooze rows
 * @param lastChangeByTarget  most recent change date per target, for the cooldown
 * @param siteImpressions28d  total site-wide impressions in the last 28 days, tested against the gate
 * @param calibratedTypes     opportunity types whose priority weight has been calibrated
 * @param curve               expected click-through-rate-by-position model
 * @param today               the date this evaluation runs
 * @param config              thresholds
 */
record EvaluationInput( List<DemandRow> demandRows,
                        List<VisibilityRow> visibilityRows,
                        List<Opportunity> imported,
                        PageFacts pageFacts,
                        List<OpportunitySnooze> snoozes,
                        Map<String, LocalDate> lastChangeByTarget,
                        int siteImpressions28d,
                        Set<String> calibratedTypes,
                        ExpectedCtrCurve curve,
                        LocalDate today,
                        OpportunityEngineConfig config ) {
}
