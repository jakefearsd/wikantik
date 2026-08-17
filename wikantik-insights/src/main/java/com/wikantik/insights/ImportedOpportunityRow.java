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
 * One row parsed from jakemon's imported-opportunity payload (content-intelligence design §7.3
 * "Imported from jakemon", §12.1 item J3) -- the storage shape {@link ImportedOpportunityParser}
 * produces and {@link InsightsStore#upsertImported} writes to {@code imported_opportunity} (V056).
 *
 * <p><strong>{@code target} is a query for four of the five detector types.</strong> jakemon's
 * detectors are keyed on query: {@code target_page} is the empty string for
 * {@code striking_distance}, {@code ctr_gap}, {@code content_gap} and {@code decay}, and only
 * {@code cannibalization} populates {@code target_pages} (carried in {@code evidenceJson}, not a
 * dedicated column). {@code target} is therefore whichever of {@code query} / {@code target_page}
 * is non-blank, matching the migration's own column comment.</p>
 *
 * @param asOf           the detector run date this opportunity was reported for
 * @param engine         the search engine the opportunity concerns (e.g. {@code google})
 * @param siteHost       the site the opportunity concerns
 * @param opportunityType one of {@code striking_distance}, {@code ctr_gap}, {@code content_gap},
 *                        {@code cannibalization}, {@code decay}
 * @param target         the query text, or the target page path when no query is present
 * @param expectedUplift jakemon's own expected-incremental-clicks estimate, carried through
 *                        unchanged (design §7.3 "Ranking across types")
 * @param confidence     jakemon's per-type detector confidence, or {@code null} if absent
 * @param evidenceJson   every payload key not consumed by the fields above, serialised as a JSON
 *                        object -- detector-specific extras (e.g. {@code target_pages},
 *                        {@code impressions}, {@code position}) captured generically rather than
 *                        enumerated
 */
public record ImportedOpportunityRow( LocalDate asOf, String engine, String siteHost,
                                      String opportunityType, String target, double expectedUplift,
                                      Double confidence, String evidenceJson ) {
}
