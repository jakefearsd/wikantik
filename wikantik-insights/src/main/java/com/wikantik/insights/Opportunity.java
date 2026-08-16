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
import java.util.Map;

/**
 * One entry in the content-opportunity backlog (content-intelligence design §7.3/§7.5.1).
 *
 * <p>Both native rules ({@link OpportunityEngine}) and the five detectors imported unchanged from
 * jakemon ({@code striking_distance}, {@code ctr_gap}, {@code content_gap},
 * {@code cannibalization}, {@code decay}) share this one shape, so the backlog sorts and reads as
 * a single list end to end -- see §7.3 "Ranking across types".</p>
 *
 * @param type            the rule that produced this opportunity, lowercase snake_case
 *                         (e.g. {@code "agent_gap"}, {@code "engine_divergence"}, or one of the
 *                         imported jakemon types such as {@code "ctr_gap"})
 * @param target           the page path or query text the opportunity concerns, depending on
 *                          {@code type}
 * @param priority          expected incremental clicks -- the same unit jakemon's five imported
 *                          detectors already use, so the merged backlog is comparable end to end
 * @param evidence          the actual numbers that fired the rule, so a caller can sanity-check a
 *                          suggestion instead of trusting a bare score
 * @param suggestedAction   what a curator or agent should do about it
 * @param firstSeen         the date this opportunity was first observed
 * @param calibrated        whether this type's priority weight has been calibrated against
 *                          realized effect (§7.4.4) -- always {@code false} until that mechanism
 *                          exists; an agent should weigh an uncalibrated suggestion less
 */
public record Opportunity( String type, String target, double priority,
                           Map<String, Object> evidence, String suggestedAction,
                           LocalDate firstSeen, boolean calibrated ) {
}
