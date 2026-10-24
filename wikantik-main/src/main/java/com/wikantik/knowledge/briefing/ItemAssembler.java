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
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.util.TokenEstimator;

import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns requested pins and the {@link BriefingScope} tiers (cluster members, then the pages they
 * cite) into {@link BriefingItem}s, spending against the shared {@link SectionBudget} ledger —
 * including the pin-supersedes-its-own-sections rule (a page body is a superset of its own
 * retrieved sections, so a kept pin refunds and drops them).
 *
 * <p>Which pages are in scope, and in what order, is {@link BriefingScope}'s decision; this class
 * only decides, item by item, whether a page's body still fits (full item) or degrades to a
 * title/summary pointer. Cluster and cited pointers are capped at {@code pointersMax}; pins never
 * are. Out-of-scope retrieval hits become {@code elsewhere} pointers with their own cap. It owns only
 * the pin/scope/elsewhere → {@link BriefingItem} concern, split out of
 * {@code DefaultBriefingAssemblyService.Assembly} (God Class burn-down).</p>
 */
final class ItemAssembler {

    // The origin vocabulary of BriefingItem; package-visible so the renderer filters on the same literals.
    static final String ORIGIN_PIN = "pin";
    static final String ORIGIN_CLUSTER = "cluster";
    static final String ORIGIN_CITED = "cited";
    static final String ORIGIN_ELSEWHERE = "elsewhere";

    private final Logger log;
    private final PageManager pageManager;
    private final StructuralIndexService structuralIndex; // nullable
    private final SectionBudget ledger;
    private final List< String > cappedPins;
    private final BriefingScope scope;
    private final int pointersMax;
    private final List< String > warnings;

    final List< BriefingItem > items = new ArrayList<>();
    private final Set< String > includedSlugs = new LinkedHashSet<>(); // every item (full or pointer)
    private int scopedPointers;          // cluster + cited pointers emitted, counted against pointersMax
    private boolean pointerCapWarned;

    ItemAssembler( final Logger log, final PageManager pageManager, final StructuralIndexService structuralIndex,
                   final SectionBudget ledger, final List< String > cappedPins, final BriefingScope scope,
                   final int pointersMax, final List< String > warnings ) {
        this.log = log;
        this.pageManager = pageManager;
        this.structuralIndex = structuralIndex;
        this.ledger = ledger;
        this.cappedPins = cappedPins;
        this.scope = scope;
        this.pointersMax = pointersMax;
        this.warnings = warnings;
    }

    // ------------------------------------------------------------- pins

    void assemblePins() {
        for ( final String pin : cappedPins ) {
            final String slug = resolvePinSlug( pin );
            // Unresolvable/skipped pins are NOT warned here: pin "unknown pin: ..." warnings are
            // owned solely by BriefingAclGate, which attributes them per requested pin AFTER the
            // ACL gate so that nonexistent, restricted, and cap-dropped pins are indistinguishable.
            if ( slug == null ) continue;
            if ( includedSlugs.contains( slug ) ) continue;
            addPin( slug );
        }
    }

    private String resolvePinSlug( final String pin ) {
        if ( pageManager.getPage( pin ) != null ) return pin;
        if ( structuralIndex != null ) {
            final Optional< String > resolved = structuralIndex.resolveSlugFromCanonicalId( pin );
            if ( resolved.isPresent() && pageManager.getPage( resolved.get() ) != null ) {
                return resolved.get();
            }
        }
        return null;
    }

    /**
     * Include the pin. Supersede rule: a page body is a superset of its own retrieved sections,
     * so if those sections were kept and their refund makes room for the full body, drop them
     * (refund tokens, recount coverage) and include the body. Otherwise fall through to the
     * shared include-or-pointer logic.
     */
    private void addPin( final String slug ) {
        final String body = bodyOf( slug );
        final List< BundleSection > own = ledger.sections.stream()
            .filter( s -> slug.equals( s.slug() ) ).toList();
        if ( !own.isEmpty() ) {
            final int est = TokenEstimator.estimate( body );
            final int refund = own.stream().mapToInt( s -> TokenEstimator.estimate( s.text() ) ).sum();
            if ( ledger.used - refund + est <= ledger.budget ) {
                ledger.sections.removeIf( s -> slug.equals( s.slug() ) );
                ledger.used -= refund;
                ledger.coverage = BundleCoverage.recount( ledger.bundleCoverage, ledger.sections );
                final Map< String, Object > meta =
                    FrontmatterParser.parse( body == null ? "" : body ).metadata();
                items.add( new BriefingItem( slug, canonicalIdOf( slug ),
                    DefaultBriefingAssemblyService.str( meta.get( "title" ), slug ),
                    DefaultBriefingAssemblyService.str( meta.get( "summary" ), "" ),
                    ORIGIN_PIN, true, body ) );
                includedSlugs.add( slug );
                ledger.used += est;
                return;
            }
        }
        loadItem( slug, ORIGIN_PIN, body, null );
    }

    // ------------------------------------------------ cluster members, cited

    /** Tier 1 (origin {@code cluster}) in scope order, then tier 2 (origin {@code cited}, naming its cluster). */
    void assembleClusterMembers() {
        for ( final PageDescriptor member : scope.members() ) {
            addScoped( member, ORIGIN_CLUSTER, null );
        }
        for ( final BriefingScope.Cited cited : scope.cited() ) {
            addScoped( cited.page(), ORIGIN_CITED, cited.page().cluster() );
        }
    }

    private void addScoped( final PageDescriptor page, final String origin, final String cluster ) {
        final String slug = page.slug();
        if ( includedSlugs.contains( slug ) ) return;
        if ( pageManager.getPage( slug ) == null ) {
            log.debug( "Briefing page {} has no live page; skipping", slug );
            return;
        }
        // Pointer fast-path: once the budget is all but spent, nothing more can fit, so
        // emit a pointer straight from the descriptor (title/summary already loaded)
        // rather than reading the full body off disk — read-amplification defence.
        if ( ledger.budget - ledger.used < BriefingConfig.POINTER_ONLY_FLOOR_TOKENS ) {
            addPointer( page, origin, cluster );
        } else {
            loadItem( slug, origin, bodyOf( slug ), cluster );
        }
    }

    /** Pointer item built purely from a structural descriptor — no page-body read. */
    private void addPointer( final PageDescriptor d, final String origin, final String cluster ) {
        if ( !admitPointer( origin ) ) return;
        final String slug = d.slug();
        items.add( new BriefingItem( slug, d.canonicalId(),
            DefaultBriefingAssemblyService.str( d.title(), slug ),
            DefaultBriefingAssemblyService.str( d.summary(), "" ), origin, false, null, cluster ) );
        includedSlugs.add( slug );
    }

    /**
     * Whether another pointer of {@code origin} may be emitted. Cluster and cited pointers share
     * {@code pointersMax}; the first one refused adds the single {@code pointer list capped} warning.
     * A pin pointer is always admitted and never counted.
     */
    private boolean admitPointer( final String origin ) {
        if ( ORIGIN_PIN.equals( origin ) ) return true;
        if ( scopedPointers >= pointersMax ) {
            if ( !pointerCapWarned ) {
                warnings.add( "pointer list capped at " + pointersMax );
                pointerCapWarned = true;
            }
            return false;
        }
        scopedPointers++;
        return true;
    }

    // ------------------------------------------------------- elsewhere

    /**
     * One pointer per out-of-scope page (first hit wins), at most {@code elsewhereMax}, appended after
     * the scoped items. They spend no budget and have their own cap, so they never count toward
     * {@code pointersMax}; a page that is already an item (a pin, a member) is not repeated.
     */
    void assembleElsewhere( final List< BundleSection > outOfScope, final int elsewhereMax ) {
        int added = 0;
        for ( final BundleSection s : outOfScope ) {
            if ( added >= elsewhereMax ) break;
            if ( !includedSlugs.add( s.slug() ) ) continue;
            items.add( new BriefingItem( s.slug(), s.canonicalId(), s.slug(),
                String.join( " › ", s.headingPath() ), ORIGIN_ELSEWHERE, false, null, clusterOf( s.canonicalId() ) ) );
            added++;
        }
    }

    /** The page's primary cluster, or null when the index is absent or does not know the page. */
    private String clusterOf( final String canonicalId ) {
        if ( structuralIndex == null || canonicalId == null ) return null;
        return structuralIndex.getByCanonicalId( canonicalId ).map( PageDescriptor::cluster ).orElse( null );
    }

    // --------------------------------------------------------- shared

    /** Include the full body if it fits the remaining budget, else a title/summary pointer. */
    private void loadItem( final String slug, final String origin, final String body, final String cluster ) {
        final Map< String, Object > meta = FrontmatterParser.parse( body == null ? "" : body ).metadata();
        final String title = DefaultBriefingAssemblyService.str( meta.get( "title" ), slug );
        final String summary = DefaultBriefingAssemblyService.str( meta.get( "summary" ), "" );
        final String canonicalId = canonicalIdOf( slug );
        final int est = TokenEstimator.estimate( body );
        final boolean fits = ledger.used + est <= ledger.budget;
        if ( !fits && !admitPointer( origin ) ) return;
        items.add( new BriefingItem( slug, canonicalId, title, summary, origin, fits, fits ? body : null, cluster ) );
        includedSlugs.add( slug );
        if ( fits ) ledger.used += est;
    }

    /** Page text for injection into an agent's context — access-rule directives (people, by login) are left out. */
    private String bodyOf( final String slug ) {
        return AclDirectiveText.strip( pageManager.getPureText( slug, PageProvider.LATEST_VERSION ) );
    }

    private String canonicalIdOf( final String slug ) {
        return structuralIndex == null ? null
            : structuralIndex.resolveCanonicalIdFromSlug( slug ).orElse( null );
    }
}
