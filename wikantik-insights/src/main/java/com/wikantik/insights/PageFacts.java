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
import java.util.List;
import java.util.Optional;

/**
 * The seam that keeps {@link OpportunityEngine} testable without a wiki engine.
 *
 * <p>Content-intelligence design §7.3/§7.4 (rules 3 and 4, and effect-measurement bookkeeping)
 * need page-level facts -- title, summary, tags, cluster, verification age -- that only the wiki
 * itself holds. Rather than let the rule engine reach into {@code WikiEngine} directly (which
 * would make it untestable without standing up a full wiki, and would violate the module's
 * declared no-dependency-on-wikantik-main boundary; see {@code wikantik-insights/pom.xml}), every
 * such lookup goes through this port.</p>
 *
 * <p>This is load-bearing for spec D10: the rule engine is meant to run over data from
 * <em>any</em> site jakemon polls, not only Wikantik's own. A rule that needs page facts and
 * finds none (a non-Wikantik site, or a page the wiki doesn't know about) must degrade gracefully
 * -- hence {@link #lookup} returns {@link Optional} rather than throwing.</p>
 *
 * <p>The wiki-side adapter (backed by {@code WikiEngine}/{@code PageManager}) is deliberately
 * <strong>not</strong> implemented here -- it lands later, in {@code wikantik-main}, once a rule
 * that actually needs it (rule 3 {@code VOCABULARY_GAP} or rule 4 {@code STALE_HIGH_TRAFFIC}) is
 * built. This module ships only the port and an in-memory test double.</p>
 */
public interface PageFacts {

    /**
     * Facts about one page, or empty if the page is unknown to this {@code PageFacts}.
     *
     * @param pagePath   the page's normalised path, matching {@code VisibilityRow.pagePath()}
     * @param title      the page title
     * @param summary    the page's {@code summary} frontmatter field
     * @param tags       the page's {@code tags} frontmatter field
     * @param cluster    the page's primary cluster (see {@code ClusterPath} in wikantik-api)
     * @param verifiedAt when the page was last human-verified, or {@code null} if never
     * @param confidence {@code authoritative} | {@code provisional} | {@code stale}
     */
    record PageFact( String pagePath, String title, String summary, List<String> tags,
                     String cluster, Instant verifiedAt, String confidence ) {}

    /**
     * Looks up facts for one page.
     *
     * @param pagePath the page path to look up
     * @return the page's facts, or empty if the page is unknown
     */
    Optional<PageFact> lookup( String pagePath );
}
