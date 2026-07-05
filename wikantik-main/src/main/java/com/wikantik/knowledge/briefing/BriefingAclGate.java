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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared post-assembly ACL gate for the REST and MCP briefing surfaces. The assembler is
 * ACL-agnostic (like {@code DefaultBundleAssemblyService}); each surface applies this gate to drop
 * anything the caller cannot view before serializing.
 *
 * <p><b>Existence-oracle defence.</b> A nonexistent pin already surfaces an {@code "unknown pin:
 * <input>"} warning from the assembler. Without this gate, an existing-but-restricted pin would be
 * silently ACL-dropped with <em>no</em> warning — a 3-way existence oracle (exists+viewable /
 * nonexistent / exists-but-restricted) for any guessed page name. To match the repo's 404-hiding
 * posture, every requested pin whose item was ACL-dropped gets the <em>same</em> warning string a
 * nonexistent pin would, so dropped-restricted and nonexistent are indistinguishable.</p>
 */
public final class BriefingAclGate {

    private BriefingAclGate() {}

    /** Warning prefix; MUST match {@code DefaultBriefingAssemblyService} for the parity to hold. */
    static final String UNKNOWN_PIN_PREFIX = "unknown pin: ";

    /**
     * @param original       the ACL-agnostic briefing from the assembler
     * @param requestedPins  the caller's raw pin inputs (slug or canonical_id), for warning parity
     * @param canView        predicate: may the caller view this page slug?
     * @return a new briefing with non-viewable sections/items dropped, coverage recounted, and a
     *         parity {@code "unknown pin: <input>"} warning appended for every dropped-restricted pin
     */
    public static ContextBriefing gate( final ContextBriefing original,
                                        final List< String > requestedPins,
                                        final Predicate< String > canView ) {
        final List< BundleSection > keptSections = original.sections().stream()
                .filter( s -> canView.test( s.slug() ) ).toList();
        final List< BriefingItem > keptItems = original.items().stream()
                .filter( i -> canView.test( i.slug() ) ).toList();
        final BundleCoverage coverage = BundleCoverage.recount( original.coverage(), keptSections );

        final List< String > warnings = new ArrayList<>( original.warnings() );
        final List< BriefingItem > dropped = original.items().stream()
                .filter( i -> !canView.test( i.slug() ) ).toList();
        if ( requestedPins != null ) {
            for ( final String pin : requestedPins ) {
                if ( pin == null ) continue;
                final boolean pinDropped = dropped.stream().anyMatch(
                        i -> pin.equals( i.slug() ) || pin.equals( i.canonicalId() ) );
                final String warning = UNKNOWN_PIN_PREFIX + pin;
                if ( pinDropped && !warnings.contains( warning ) ) {
                    warnings.add( warning );
                }
            }
        }

        return new ContextBriefing( original.prompt(), keptSections, coverage,
                keptItems, warnings, original.budgetTokens(), original.usedTokens() );
    }
}
