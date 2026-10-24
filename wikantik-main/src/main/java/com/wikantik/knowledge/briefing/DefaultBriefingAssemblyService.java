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
import com.wikantik.api.bundle.BundleOptions;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.knowledge.briefing.BriefingConfig.BriefingLimits;
import com.wikantik.util.TokenEstimator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles a session-start context briefing from explicit pins/clusters plus an optional
 * retrieval-driven section widening. Never synthesizes answers (ADR-0001) and is ACL-agnostic
 * like {@code DefaultBundleAssemblyService} — the REST/MCP surfaces (Tasks 6-7) gate visibility.
 *
 * <p>{@code bundleService}, {@code structuralIndex} and {@code referenceManager} are nullable: the
 * assembler degrades with a warning rather than failing. {@code pageManager} is required.</p>
 */
public final class DefaultBriefingAssemblyService implements BriefingAssemblyService {

    private static final Logger LOG = LogManager.getLogger( DefaultBriefingAssemblyService.class );

    private static final int BUDGET_FLOOR = 200;
    private static final String ORIGIN_ELSEWHERE = "elsewhere";

    private final BundleAssemblyService bundleService;   // nullable
    private final StructuralIndexService structuralIndex; // nullable
    private final ReferenceManager referenceManager;      // nullable: no cited tier, with a warning
    private final PageManager pageManager;
    private final BriefingLimits limits;

    public DefaultBriefingAssemblyService( final BundleAssemblyService bundleService,
                                           final StructuralIndexService structuralIndex,
                                           final ReferenceManager referenceManager,
                                           final PageManager pageManager,
                                           final BriefingLimits limits ) {
        this.bundleService = bundleService;
        this.structuralIndex = structuralIndex;
        this.referenceManager = referenceManager;
        this.pageManager = Objects.requireNonNull( pageManager, "pageManager" );
        this.limits = Objects.requireNonNull( limits, "limits" );
    }

    /** Without reference data: no cited tier. The remaining limits are {@link BriefingLimits#defaults()}. */
    public DefaultBriefingAssemblyService( final BundleAssemblyService bundleService,
                                           final StructuralIndexService structuralIndex,
                                           final PageManager pageManager,
                                           final int defaultBudget,
                                           final int maxBudget ) {
        this( bundleService, structuralIndex, null, pageManager, withBudgets( defaultBudget, maxBudget ) );
    }

    private static BriefingLimits withBudgets( final int defaultBudget, final int maxBudget ) {
        final BriefingLimits d = BriefingLimits.defaults();
        return new BriefingLimits( defaultBudget, maxBudget, d.sectionsPerPage(), d.candidatePool(),
            d.citedEnabled(), d.citedMax(), d.elsewhereMax(), d.pointersMax() );
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
     * returns. Delegates pin/cluster-member/cited-page handling to {@link ItemAssembler} and shares the
     * token-budget ledger with it via {@link SectionBudget} (God Class burn-down: this class now
     * owns only orchestration + the prompt-driven section-retrieval step and its out-of-scope
     * {@code elsewhere} pointers).
     */
    private final class Assembly {

        private final BriefingRequest req;
        private final SectionBudget ledger;
        private final List< String > warnings = new ArrayList<>();
        private final List< String > cappedPins;      // req.pins() truncated to MAX_PINS
        private final List< String > cappedClusters;  // req.clusters() truncated to MAX_CLUSTERS
        private final Set< String > warnedClusters = new LinkedHashSet<>();
        private final BriefingScope scope;
        private final ItemAssembler itemAssembler;

        Assembly( final BriefingRequest req ) {
            this.req = req;
            final int requested = req.budgetTokens() == null ? limits.defaultBudget() : req.budgetTokens();
            this.ledger = new SectionBudget( clamp( requested, BUDGET_FLOOR, limits.maxBudget() ) );
            this.cappedPins = cap( req.pins(), BriefingConfig.MAX_PINS,
                "too many pins; first " + BriefingConfig.MAX_PINS + " included" );
            this.cappedClusters = cap( req.clusters(), BriefingConfig.MAX_CLUSTERS,
                "too many clusters; first " + BriefingConfig.MAX_CLUSTERS + " included" );
            this.scope = BriefingScope.compute( cappedClusters, structuralIndex, referenceManager,
                limits.citedEnabled(), limits.citedMax(), warnings, warnedClusters );
            this.itemAssembler = new ItemAssembler( LOG, pageManager, structuralIndex, ledger,
                cappedPins, scope, limits.pointersMax(), warnings );
        }

        /** Truncate a caller-supplied list to {@code max}, warning once when it actually trims. */
        private List< String > cap( final List< String > in, final int max, final String warning ) {
            if ( in.size() <= max ) return in;
            warnings.add( warning );
            return in.subList( 0, max );
        }

        ContextBriefing run() {
            final List< BundleSection > outOfScope = assembleSections();
            itemAssembler.assemblePins();
            itemAssembler.assembleClusterMembers();
            addElsewhere( outOfScope );
            return new ContextBriefing( req.prompt(), ledger.sections, ledger.coverage,
                itemAssembler.items, warnings, ledger.budget, ledger.used );
        }

        // --------------------------------------------------------- sections

        /**
         * Fills {@code ledger.sections} from the prompt's candidate pool and returns the pool's
         * out-of-scope sections (in rank order) for {@link #addElsewhere}; empty when the scope is
         * {@code STRICT}, when nothing is out of scope, or when there was no retrieval pass.
         */
        private List< BundleSection > assembleSections() {
            final String prompt = req.prompt();
            if ( prompt == null || prompt.isBlank() ) return List.of();
            if ( bundleService == null ) {
                warnings.add( "bundle service unavailable; prompt refinement skipped" );
                return List.of();
            }
            final ContextBundle bundle;
            try {
                bundle = bundleService.assemble( prompt, new BundleOptions( null, limits.candidatePool() ) );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Bundle assembly failed for briefing prompt; degrading to pins/clusters", e );
                warnings.add( "bundle assembly failed; briefing degraded to pins/clusters" );
                return List.of();
            }
            ledger.bundleCoverage = bundle.coverage();
            final Partition partition = partition( bundle.sections() );
            for ( final BundleSection s : capPerPage( partition.inScope(), limits.sectionsPerPage() ) ) {
                final int est = TokenEstimator.estimate( s.text() );
                if ( ledger.used + est > ledger.budget ) break;
                ledger.sections.add( s );
                ledger.used += est;
            }
            ledger.coverage = BundleCoverage.recount( ledger.bundleCoverage, ledger.sections );
            return partition.outOfScope();
        }

        /** The candidate pool split by {@link BriefingScope} tier. */
        private record Partition( List< BundleSection > inScope, List< BundleSection > outOfScope ) {}

        /**
         * Tier 1 (cluster members), then tier 2 (cited pages), each in rank order, make up
         * {@code inScope}; everything else is {@code outOfScope} — kept only under {@code PREFER}, where
         * it becomes {@code elsewhere} pointers. Without a scope every section is in scope.
         */
        private Partition partition( final List< BundleSection > pool ) {
            if ( scope.isNone() ) return new Partition( pool, List.of() );
            final List< BundleSection > inScope = new ArrayList<>();
            final List< BundleSection > cited = new ArrayList<>();
            final List< BundleSection > out = new ArrayList<>();
            for ( final BundleSection s : pool ) {
                switch ( scope.tierOf( s.slug() ) ) {
                    case 1 -> inScope.add( s );
                    case 2 -> cited.add( s );
                    default -> out.add( s );
                }
            }
            inScope.addAll( cited );
            return new Partition( inScope, req.scopeMode() == ScopeMode.STRICT ? List.of() : out );
        }

        /** The first {@code perPage} sections of each page, in rank order; later ones are dropped, not moved. */
        private List< BundleSection > capPerPage( final List< BundleSection > sections, final int perPage ) {
            final Map< String, Integer > taken = new HashMap<>();
            final List< BundleSection > kept = new ArrayList<>();
            for ( final BundleSection s : sections ) {
                if ( taken.merge( s.slug(), 1, Integer::sum ) <= perPage ) kept.add( s );
            }
            return kept;
        }

        /**
         * One pointer per out-of-scope page (first hit wins), at most {@code elsewhereMax}, appended after
         * the scoped items. They spend no budget and have their own cap, so they never count toward
         * {@code pointersMax}; a page that is already an item (a pin) is not repeated.
         */
        private void addElsewhere( final List< BundleSection > outOfScope ) {
            final Set< String > seen = new LinkedHashSet<>();
            itemAssembler.items.forEach( i -> seen.add( i.slug() ) );
            int added = 0;
            for ( final BundleSection s : outOfScope ) {
                if ( added >= limits.elsewhereMax() ) break;
                if ( !seen.add( s.slug() ) ) continue;
                itemAssembler.items.add( new BriefingItem( s.slug(), s.canonicalId(), s.slug(),
                    String.join( " › ", s.headingPath() ), ORIGIN_ELSEWHERE, false, null, clusterOf( s.slug() ) ) );
                added++;
            }
        }

        /** The page's primary cluster, or null when the index is absent or does not know the page. */
        private String clusterOf( final String slug ) {
            if ( structuralIndex == null ) return null;
            return structuralIndex.resolveCanonicalIdFromSlug( slug )
                .flatMap( structuralIndex::getByCanonicalId )
                .map( PageDescriptor::cluster )
                .orElse( null );
        }
    }
}
