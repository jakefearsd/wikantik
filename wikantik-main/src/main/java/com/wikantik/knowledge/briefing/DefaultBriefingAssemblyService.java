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
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.util.TokenEstimator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** Package-visible so {@link ItemAssembler} (extracted from {@code Assembly}) can share it. */
    static String str( final Object value, final String fallback ) {
        if ( value == null ) return fallback;
        final String s = String.valueOf( value );
        return s.isBlank() ? fallback : s;
    }

    /**
     * Per-request mutable working state — keeps the assembly steps small and free of tuple
     * returns. Delegates pin/cluster-member handling to {@link ItemAssembler} and shares the
     * token-budget ledger with it via {@link SectionBudget} (God Class burn-down: this class now
     * owns only orchestration + the prompt-driven section-retrieval step).
     */
    private final class Assembly {

        private final BriefingRequest req;
        private final SectionBudget ledger;
        private final List< String > warnings = new ArrayList<>();
        private final List< String > cappedPins;      // req.pins() truncated to MAX_PINS
        private final List< String > cappedClusters;  // req.clusters() truncated to MAX_CLUSTERS
        private final Set< String > warnedClusters = new LinkedHashSet<>();
        private final ItemAssembler itemAssembler;

        Assembly( final BriefingRequest req ) {
            this.req = req;
            final int requested = req.budgetTokens() == null ? defaultBudget : req.budgetTokens();
            this.ledger = new SectionBudget( clamp( requested, BUDGET_FLOOR, maxBudget ) );
            this.cappedPins = cap( req.pins(), BriefingConfig.MAX_PINS,
                "too many pins; first " + BriefingConfig.MAX_PINS + " included" );
            this.cappedClusters = cap( req.clusters(), BriefingConfig.MAX_CLUSTERS,
                "too many clusters; first " + BriefingConfig.MAX_CLUSTERS + " included" );
            this.itemAssembler = new ItemAssembler( LOG, pageManager, structuralIndex, ledger,
                cappedPins, cappedClusters, warnings, warnedClusters );
        }

        /** Truncate a caller-supplied list to {@code max}, warning once when it actually trims. */
        private List< String > cap( final List< String > in, final int max, final String warning ) {
            if ( in.size() <= max ) return in;
            warnings.add( warning );
            return in.subList( 0, max );
        }

        ContextBriefing run() {
            assembleSections();
            itemAssembler.assemblePins();
            itemAssembler.assembleClusterMembers();
            return new ContextBriefing( req.prompt(), ledger.sections, ledger.coverage,
                itemAssembler.items, warnings, ledger.budget, ledger.used );
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
            ledger.bundleCoverage = bundle.coverage();
            final Set< String > inScope = computeScope();
            for ( final BundleSection s : partitionByScope( bundle.sections(), inScope, req.scopeMode() ) ) {
                final int est = TokenEstimator.estimate( s.text() );
                if ( ledger.used + est > ledger.budget ) break;
                ledger.sections.add( s );
                ledger.used += est;
            }
            ledger.coverage = BundleCoverage.recount( ledger.bundleCoverage, ledger.sections );
        }

        /** Slugs in the requested clusters, or {@code null} when no scoping applies. */
        private Set< String > computeScope() {
            if ( cappedClusters.isEmpty() || structuralIndex == null ) return null;
            final Set< String > inScope = new LinkedHashSet<>();
            for ( final String name : cappedClusters ) {
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
    }
}
