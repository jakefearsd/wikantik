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
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.util.TokenEstimator;

import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns requested pins and cluster members into {@link BriefingItem}s, spending against the
 * shared {@link SectionBudget} ledger — including the pin-supersedes-its-own-sections rule (a
 * page body is a superset of its own retrieved sections, so a kept pin refunds and drops them).
 *
 * <p>Extracted verbatim (method bodies unchanged, only field access re-targeted to this class'
 * own fields / the shared {@link SectionBudget}) from {@code DefaultBriefingAssemblyService
 * .Assembly}, which had grown into a God Class covering prompt-driven section retrieval,
 * budgeting, pin resolution, and cluster-member fan-out all at once. This class owns only the
 * pin/cluster-member → {@link BriefingItem} concern.</p>
 */
final class ItemAssembler {

    private final Logger log;
    private final PageManager pageManager;
    private final StructuralIndexService structuralIndex; // nullable
    private final SectionBudget ledger;
    private final List< String > cappedPins;
    private final List< String > cappedClusters;
    private final List< String > warnings;
    private final Set< String > warnedClusters;

    final List< BriefingItem > items = new ArrayList<>();
    private final Set< String > includedSlugs = new LinkedHashSet<>(); // every item (full or pointer)

    ItemAssembler( final Logger log, final PageManager pageManager, final StructuralIndexService structuralIndex,
                   final SectionBudget ledger, final List< String > cappedPins, final List< String > cappedClusters,
                   final List< String > warnings, final Set< String > warnedClusters ) {
        this.log = log;
        this.pageManager = pageManager;
        this.structuralIndex = structuralIndex;
        this.ledger = ledger;
        this.cappedPins = cappedPins;
        this.cappedClusters = cappedClusters;
        this.warnings = warnings;
        this.warnedClusters = warnedClusters;
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
                    "pin", true, body ) );
                includedSlugs.add( slug );
                ledger.used += est;
                return;
            }
        }
        loadItem( slug, "pin", body );
    }

    // -------------------------------------------------- cluster members

    void assembleClusterMembers() {
        if ( cappedClusters.isEmpty() ) return;
        if ( structuralIndex == null ) {
            warnings.add( "structural index unavailable; clusters skipped" );
            return;
        }
        for ( final String name : cappedClusters ) {
            for ( final PageDescriptor member : clusterMembers( name ) ) {
                final String slug = member.slug();
                if ( includedSlugs.contains( slug ) ) continue;
                if ( pageManager.getPage( slug ) == null ) {
                    log.debug( "Cluster member {} has no live page; skipping", slug );
                    continue;
                }
                // Pointer fast-path: once the budget is all but spent, nothing more can fit, so
                // emit a pointer straight from the descriptor (title/summary already loaded)
                // rather than reading the full body off disk — read-amplification defence.
                if ( ledger.budget - ledger.used < BriefingConfig.POINTER_ONLY_FLOOR_TOKENS ) {
                    addPointer( member );
                } else {
                    loadItem( slug, "cluster", bodyOf( slug ) );
                }
            }
        }
    }

    /** Hub first (if any), then articles by {@code updated} descending (nulls last), capped. */
    private List< PageDescriptor > clusterMembers( final String name ) {
        final Optional< ClusterDetails > cd = structuralIndex.getCluster( name );
        if ( cd.isEmpty() ) {
            if ( warnedClusters.add( name ) ) warnings.add( "unknown cluster: " + name );
            return List.of();
        }
        final ClusterDetails c = cd.get();
        final List< PageDescriptor > members = new ArrayList<>();
        if ( c.hubPage() != null ) members.add( c.hubPage() );
        c.articles().stream()
            .sorted( Comparator.comparing( PageDescriptor::updated,
                Comparator.nullsLast( Comparator.reverseOrder() ) ) )
            .forEach( members::add );
        if ( members.size() > BriefingConfig.MAX_CLUSTER_MEMBERS ) {
            warnings.add( "too many members in cluster " + name + "; first "
                + BriefingConfig.MAX_CLUSTER_MEMBERS + " included" );
            return members.subList( 0, BriefingConfig.MAX_CLUSTER_MEMBERS );
        }
        return members;
    }

    /** Pointer item built purely from a structural descriptor — no page-body read. */
    private void addPointer( final PageDescriptor d ) {
        final String slug = d.slug();
        items.add( new BriefingItem( slug, d.canonicalId(),
            DefaultBriefingAssemblyService.str( d.title(), slug ),
            DefaultBriefingAssemblyService.str( d.summary(), "" ), "cluster", false, null ) );
        includedSlugs.add( slug );
    }

    // --------------------------------------------------------- shared

    /** Include the full body if it fits the remaining budget, else a title/summary pointer. */
    private void loadItem( final String slug, final String origin, final String body ) {
        final Map< String, Object > meta = FrontmatterParser.parse( body == null ? "" : body ).metadata();
        final String title = DefaultBriefingAssemblyService.str( meta.get( "title" ), slug );
        final String summary = DefaultBriefingAssemblyService.str( meta.get( "summary" ), "" );
        final String canonicalId = canonicalIdOf( slug );
        final int est = TokenEstimator.estimate( body );
        final boolean fits = ledger.used + est <= ledger.budget;
        items.add( new BriefingItem( slug, canonicalId, title, summary, origin, fits, fits ? body : null ) );
        includedSlugs.add( slug );
        if ( fits ) ledger.used += est;
    }

    private String bodyOf( final String slug ) {
        return pageManager.getPureText( slug, PageProvider.LATEST_VERSION );
    }

    private String canonicalIdOf( final String slug ) {
        return structuralIndex == null ? null
            : structuralIndex.resolveCanonicalIdFromSlug( slug ).orElse( null );
    }
}
