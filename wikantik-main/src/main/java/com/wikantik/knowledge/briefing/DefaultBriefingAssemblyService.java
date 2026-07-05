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

import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.util.TokenEstimator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles a session-start context briefing from explicit pins/clusters plus an optional
 * retrieval-driven section widening. Never synthesizes answers (ADR-0001) and is ACL-agnostic
 * like {@code DefaultBundleAssemblyService} — the REST/MCP surfaces (Tasks 6-7) gate visibility.
 *
 * <p>{@code bundleService} and {@code structuralIndex} are nullable: the assembler degrades with a
 * warning rather than failing. {@code pageManager} is required.</p>
 */
public final class DefaultBriefingAssemblyService implements BriefingAssemblyService {

    private static final Logger LOG = LogManager.getLogger( DefaultBriefingAssemblyService.class );

    private static final int BUDGET_FLOOR = 200;

    private final BundleAssemblyService bundleService;   // nullable
    private final StructuralIndexService structuralIndex; // nullable
    private final PageManager pageManager;
    private final int defaultBudget;
    private final int maxBudget;

    public DefaultBriefingAssemblyService( final BundleAssemblyService bundleService,
                                           final StructuralIndexService structuralIndex,
                                           final PageManager pageManager,
                                           final int defaultBudget,
                                           final int maxBudget ) {
        this.bundleService = bundleService;
        this.structuralIndex = structuralIndex;
        this.pageManager = Objects.requireNonNull( pageManager, "pageManager" );
        this.defaultBudget = defaultBudget;
        this.maxBudget = maxBudget;
    }

    @Override
    public ContextBriefing assemble( final BriefingRequest request ) {
        return new Assembly( request ).run();
    }

    private static int clamp( final int v, final int lo, final int hi ) {
        return Math.max( lo, Math.min( v, hi ) );
    }

    private static String str( final Object value, final String fallback ) {
        if ( value == null ) return fallback;
        final String s = String.valueOf( value );
        return s.isBlank() ? fallback : s;
    }

    /** Per-request mutable working state — keeps the assembly steps small and free of tuple returns. */
    private final class Assembly {

        private final BriefingRequest req;
        private final int budget;
        private final List< String > warnings = new ArrayList<>();
        private final List< BundleSection > sections = new ArrayList<>();  // kept, budget-trimmed
        private final List< BriefingItem > items = new ArrayList<>();
        private final Set< String > includedSlugs = new LinkedHashSet<>(); // every item (full or pointer)
        private final Set< String > warnedClusters = new LinkedHashSet<>();
        private BundleCoverage bundleCoverage;             // original retrieval coverage, for recount
        private BundleCoverage coverage = BundleCoverage.empty();
        private int used;

        Assembly( final BriefingRequest req ) {
            this.req = req;
            final int requested = req.budgetTokens() == null ? defaultBudget : req.budgetTokens();
            this.budget = clamp( requested, BUDGET_FLOOR, maxBudget );
        }

        ContextBriefing run() {
            assembleSections();
            assemblePins();
            assembleClusterMembers();
            return new ContextBriefing( req.prompt(), sections, coverage, items, warnings, budget, used );
        }

        // --------------------------------------------------------- sections

        private void assembleSections() {
            final String prompt = req.prompt();
            if ( prompt == null || prompt.isBlank() ) return;
            if ( bundleService == null ) {
                warnings.add( "bundle service unavailable; prompt refinement skipped" );
                return;
            }
            final ContextBundle bundle;
            try {
                bundle = bundleService.assemble( prompt );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Bundle assembly failed for briefing prompt; degrading to pins/clusters", e );
                warnings.add( "bundle assembly failed; briefing degraded to pins/clusters" );
                return;
            }
            bundleCoverage = bundle.coverage();
            final Set< String > inScope = computeScope();
            for ( final BundleSection s : partitionByScope( bundle.sections(), inScope, req.scopeMode() ) ) {
                final int est = TokenEstimator.estimate( s.text() );
                if ( used + est > budget ) break;
                sections.add( s );
                used += est;
            }
            coverage = BundleCoverage.recount( bundleCoverage, sections );
        }

        /** Slugs in the requested clusters, or {@code null} when no scoping applies. */
        private Set< String > computeScope() {
            if ( req.clusters().isEmpty() || structuralIndex == null ) return null;
            final Set< String > inScope = new LinkedHashSet<>();
            for ( final String name : req.clusters() ) {
                final Optional< ClusterDetails > cd = structuralIndex.getCluster( name );
                if ( cd.isEmpty() ) {
                    if ( warnedClusters.add( name ) ) warnings.add( "unknown cluster: " + name );
                    continue;
                }
                final ClusterDetails c = cd.get();
                if ( c.hubPage() != null ) inScope.add( c.hubPage().slug() );
                for ( final PageDescriptor a : c.articles() ) inScope.add( a.slug() );
            }
            return inScope;
        }

        private List< BundleSection > partitionByScope( final List< BundleSection > secs,
                                                        final Set< String > inScope, final ScopeMode mode ) {
            if ( inScope == null ) return secs;
            if ( mode == ScopeMode.STRICT ) {
                return secs.stream().filter( s -> inScope.contains( s.slug() ) ).toList();
            }
            final List< BundleSection > in = new ArrayList<>();  // PREFER: stable in-scope-first partition
            final List< BundleSection > out = new ArrayList<>();
            for ( final BundleSection s : secs ) {
                ( inScope.contains( s.slug() ) ? in : out ).add( s );
            }
            in.addAll( out );
            return in;
        }

        // ------------------------------------------------------------- pins

        private void assemblePins() {
            for ( final String pin : req.pins() ) {
                final String slug = resolvePinSlug( pin );
                if ( slug == null ) {
                    warnings.add( "unknown pin: " + pin );
                    continue;
                }
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
            final List< BundleSection > own = sections.stream()
                .filter( s -> slug.equals( s.slug() ) ).toList();
            if ( !own.isEmpty() ) {
                final int est = TokenEstimator.estimate( body );
                final int refund = own.stream().mapToInt( s -> TokenEstimator.estimate( s.text() ) ).sum();
                if ( used - refund + est <= budget ) {
                    sections.removeIf( s -> slug.equals( s.slug() ) );
                    used -= refund;
                    coverage = BundleCoverage.recount( bundleCoverage, sections );
                    final Map< String, Object > meta =
                        FrontmatterParser.parse( body == null ? "" : body ).metadata();
                    items.add( new BriefingItem( slug, canonicalIdOf( slug ),
                        str( meta.get( "title" ), slug ), str( meta.get( "summary" ), "" ),
                        "pin", true, body ) );
                    includedSlugs.add( slug );
                    used += est;
                    return;
                }
            }
            loadItem( slug, "pin", body );
        }

        // -------------------------------------------------- cluster members

        private void assembleClusterMembers() {
            if ( req.clusters().isEmpty() ) return;
            if ( structuralIndex == null ) {
                warnings.add( "structural index unavailable; clusters skipped" );
                return;
            }
            for ( final String name : req.clusters() ) {
                for ( final String slug : clusterMemberSlugs( name ) ) {
                    if ( includedSlugs.contains( slug ) ) continue;
                    if ( pageManager.getPage( slug ) == null ) {
                        LOG.debug( "Cluster member {} has no live page; skipping", slug );
                        continue;
                    }
                    loadItem( slug, "cluster", bodyOf( slug ) );
                }
            }
        }

        /** Hub first (if any), then articles by {@code updated} descending (nulls last). */
        private List< String > clusterMemberSlugs( final String name ) {
            final Optional< ClusterDetails > cd = structuralIndex.getCluster( name );
            if ( cd.isEmpty() ) {
                if ( warnedClusters.add( name ) ) warnings.add( "unknown cluster: " + name );
                return List.of();
            }
            final ClusterDetails c = cd.get();
            final List< String > slugs = new ArrayList<>();
            if ( c.hubPage() != null ) slugs.add( c.hubPage().slug() );
            c.articles().stream()
                .sorted( Comparator.comparing( PageDescriptor::updated,
                    Comparator.nullsLast( Comparator.reverseOrder() ) ) )
                .forEach( a -> slugs.add( a.slug() ) );
            return slugs;
        }

        // --------------------------------------------------------- shared

        /** Include the full body if it fits the remaining budget, else a title/summary pointer. */
        private void loadItem( final String slug, final String origin, final String body ) {
            final Map< String, Object > meta = FrontmatterParser.parse( body == null ? "" : body ).metadata();
            final String title = str( meta.get( "title" ), slug );
            final String summary = str( meta.get( "summary" ), "" );
            final String canonicalId = canonicalIdOf( slug );
            final int est = TokenEstimator.estimate( body );
            final boolean fits = used + est <= budget;
            items.add( new BriefingItem( slug, canonicalId, title, summary, origin, fits, fits ? body : null ) );
            includedSlugs.add( slug );
            if ( fits ) used += est;
        }

        private String bodyOf( final String slug ) {
            return pageManager.getPureText( slug, PageProvider.LATEST_VERSION );
        }

        private String canonicalIdOf( final String slug ) {
            return structuralIndex == null ? null
                : structuralIndex.resolveCanonicalIdFromSlug( slug ).orElse( null );
        }
    }
}
