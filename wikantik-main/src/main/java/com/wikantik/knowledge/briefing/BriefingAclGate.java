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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.util.TokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared post-assembly ACL gate for the REST and MCP briefing surfaces, and the <b>single source of
 * pin warnings</b>. The assembler is ACL-agnostic (like {@code DefaultBundleAssemblyService}) and no
 * longer emits {@code "unknown pin: ..."} warnings; this gate owns them entirely, applied AFTER the
 * view filter so restricted pages are indistinguishable from nonexistent ones.
 *
 * <p><b>Existence-oracle defence.</b> Without this gate an anonymous caller could distinguish three
 * outcomes for a guessed page name — viewable / nonexistent / exists-but-restricted — from the
 * warnings, the {@code usedTokens} figure, and warning order/count. This gate collapses them:
 * <ul>
 *   <li><b>Warnings.</b> For each requested pin (in request order, deduped by the caller's raw input
 *       string), emit exactly one {@code "unknown pin: <input>"} unless the pin is <em>satisfied</em>
 *       — i.e. some KEPT (viewable) item matches it by slug or canonical_id. Attribution is by
 *       requested-pin, NOT by dropped-item, so a null-canonicalId index inconsistency on a dropped
 *       item cannot leak (a dropped restricted pin simply has no kept match → warned like any other
 *       unsatisfied pin). Nonexistent, ACL-dropped, and cap-dropped pins are byte-identical here.</li>
 *   <li><b>usedTokens.</b> Recomputed from KEPT content only (kept section texts + kept included-item
 *       bodies; pointer items contribute 0, matching the assembler). The assembler charged a
 *       restricted pin its full body estimate; leaving that in would leak the restricted page's
 *       size.</li>
 * </ul>
 *
 * <p><b>Not masked (conscious accept):</b> cluster-name existence — the assembler's
 * {@code "unknown cluster: ..."} warnings pass through unchanged. Cluster names are lower-sensitivity
 * than page slugs; this is a recorded decision, not a silent gap.</p>
 */
public final class BriefingAclGate {

    private BriefingAclGate() {}

    /** Warning prefix for an unsatisfied pin. */
    static final String UNKNOWN_PIN_PREFIX = "unknown pin: ";

    /**
     * @param original       the ACL-agnostic briefing from the assembler
     * @param requestedPins  the caller's raw pin inputs (slug or canonical_id), in request order
     * @param canView        predicate: may the caller view this page slug?
     * @return a new briefing with non-viewable sections/items dropped, coverage + usedTokens
     *         recomputed over kept content, and one {@code "unknown pin: <input>"} warning per
     *         unsatisfied requested pin
     */
    public static ContextBriefing gate( final ContextBriefing original,
                                        final List< String > requestedPins,
                                        final Predicate< String > canView ) {
        final List< BundleSection > keptSections = original.sections().stream()
                .filter( s -> canView.test( s.slug() ) ).toList();
        final List< BriefingItem > keptItems = original.items().stream()
                .filter( i -> canView.test( i.slug() ) ).toList();
        final BundleCoverage coverage = BundleCoverage.recount( original.coverage(), keptSections );

        int usedTokens = 0;
        for ( final BundleSection s : keptSections ) {
            usedTokens += TokenEstimator.estimate( s.text() );
        }
        for ( final BriefingItem i : keptItems ) {
            if ( i.content() != null ) {
                usedTokens += TokenEstimator.estimate( i.content() );
            }
        }

        // Non-pin warnings (cluster, cap, degraded, ...) pass through unchanged; the assembler no
        // longer emits any pin warnings, so pin warnings are appended here in request order.
        final List< String > warnings = new ArrayList<>( original.warnings() );
        final Set< String > seen = new LinkedHashSet<>();
        if ( requestedPins != null ) {
            for ( final String pin : requestedPins ) {
                if ( pin == null || !seen.add( pin ) ) continue;   // dedupe by raw input, keep order
                final boolean satisfied = keptItems.stream().anyMatch(
                        i -> pin.equals( i.slug() ) || pin.equals( i.canonicalId() ) );
                if ( !satisfied ) {
                    warnings.add( UNKNOWN_PIN_PREFIX + pin );
                }
            }
        }

        return new ContextBriefing( original.prompt(), keptSections, coverage,
                keptItems, warnings, original.budgetTokens(), usedTokens );
    }
}
