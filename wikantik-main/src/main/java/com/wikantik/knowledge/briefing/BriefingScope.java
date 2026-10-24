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

import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The two tiers of a cluster-scoped briefing, derived from the Page Graph.
 *
 * <ul>
 *   <li><b>Tier 1</b> — members of the requested clusters, hub(s) first, then ranked by
 *       <i>in-degree</i> (how many other members link to the page), then summary present, then
 *       {@code updated} newest first.</li>
 *   <li><b>Tier 2</b> — pages <i>outside</i> the requested clusters that at least one member links
 *       to, ranked by <i>citers</i> (how many members link to it), then <i>reach</i> (how many
 *       distinct clusters have a page linking to it), then {@code updated}; capped.</li>
 * </ul>
 *
 * <p>A citation from a project page to a shared page is that project's adoption of it, so tier 2 is
 * the set of shared pages the requested clusters have adopted. Every edge {@link ReferenceManager}
 * holds counts (body wikilinks and frontmatter {@code related:} alike). Cluster membership comes
 * from {@link StructuralIndexService#getCluster}, whose articles are already transitive over
 * sub-clusters, so no path comparison happens here.</p>
 *
 * <p>Pure with respect to its inputs: one {@code findRefersTo} and one {@code findReferrers} per
 * member, one {@code findReferrers} per cited candidate; no caching and nothing retained.</p>
 */
final class BriefingScope {

    private static final Logger LOG = LogManager.getLogger( BriefingScope.class );

    /** Tier-2 entry: a page outside the requested clusters that a member links to. */
    record Cited( PageDescriptor page, int citers, int reach ) {}

    /** No clusters requested, or no structural index: nothing is in scope and nothing is ranked. */
    static final BriefingScope NONE = new BriefingScope( List.of(), List.of() );

    private static final Comparator< Instant > NEWEST_FIRST_NULLS_LAST =
        Comparator.nullsLast( Comparator.reverseOrder() );

    private final List< PageDescriptor > members;
    private final List< Cited > cited;
    private final Map< String, Integer > tiers = new HashMap<>();

    private BriefingScope( final List< PageDescriptor > members, final List< Cited > cited ) {
        this.members = List.copyOf( members );
        this.cited = List.copyOf( cited );
        this.cited.forEach( c -> tiers.put( c.page().slug(), 2 ) );
        this.members.forEach( m -> tiers.put( m.slug(), 1 ) );
    }

    /** Tier 1, ordered: hub(s) first, then in-degree desc, summary present, {@code updated} desc (nulls last). */
    List< PageDescriptor > members() {
        return members;
    }

    /** Tier 2, ordered: citers desc, reach desc, {@code updated} desc; at most the requested cap. */
    List< Cited > cited() {
        return cited;
    }

    /** {@code 1} for a member, {@code 2} for a cited page, {@code 0} for anything out of scope. */
    int tierOf( final String slug ) {
        return tiers.getOrDefault( slug, 0 );
    }

    /** True for {@link #NONE}: scoping does not apply at all (distinct from "requested but empty"). */
    boolean isNone() {
        return this == NONE;
    }

    /**
     * True when no page is in scope at all &mdash; no member and no cited page. This is {@link #NONE}
     * and also a scope that was requested but matched nothing (every cluster unknown, or only empty
     * ones); the latter is not {@link #isNone()}.
     */
    boolean isEmpty() {
        return members.isEmpty() && cited.isEmpty();
    }

    /**
     * Computes the tiers for {@code clusters}.
     *
     * @param clusters       requested cluster names; null or empty yields {@link #NONE}
     * @param index          structural index; null with clusters requested yields {@link #NONE} plus a warning
     * @param refs           link source; null yields no cited pages plus a warning (when cited is enabled)
     * @param citedEnabled   false skips tier 2 entirely, silently
     * @param citedMax       cap on tier-2 entries
     * @param warnings       receives user-visible warnings
     * @param warnedClusters unknown-cluster names already warned about, so each warns once per request
     */
    static BriefingScope compute( final List< String > clusters, final StructuralIndexService index,
                                  final ReferenceManager refs, final boolean citedEnabled, final int citedMax,
                                  final List< String > warnings, final Set< String > warnedClusters ) {
        if ( clusters == null || clusters.isEmpty() ) return NONE;
        if ( index == null ) {
            warnings.add( "structural index unavailable; clusters skipped" );
            return NONE;
        }
        final Descriptors descriptors = new Descriptors( index );
        final Map< String, PageDescriptor > hubs = new LinkedHashMap<>();
        final Map< String, PageDescriptor > articles = new LinkedHashMap<>();
        for ( final String name : clusters ) {
            collect( name, index, warnings, warnedClusters, hubs, articles );
        }
        final List< PageDescriptor > candidates = new ArrayList<>( hubs.values() );
        candidates.addAll( articles.values() );
        candidates.forEach( descriptors::remember );
        final Set< String > memberSlugs = new LinkedHashSet<>();
        candidates.forEach( m -> memberSlugs.add( m.slug() ) );

        final Map< String, Integer > inDegree = inDegrees( candidates, memberSlugs, refs );
        final List< PageDescriptor > ranked = new ArrayList<>( hubs.values() );
        ranked.addAll( articles.values().stream().sorted( byRank( inDegree ) ).toList() );

        final List< Cited > cited = new ArrayList<>();
        if ( citedEnabled && citedMax > 0 ) {
            if ( refs == null ) {
                warnings.add( "reference data unavailable; cited pages skipped" );
            } else {
                cited.addAll( citedPages( candidates, memberSlugs, refs, descriptors, citedMax ) );
            }
        }
        return new BriefingScope( ranked, cited );
    }

    // ------------------------------------------------------------ tier 1

    /** Adds one cluster's hub and capped, recency-ordered articles; warns once if the cluster is unknown. */
    private static void collect( final String name, final StructuralIndexService index, final List< String > warnings,
                                 final Set< String > warnedClusters, final Map< String, PageDescriptor > hubs,
                                 final Map< String, PageDescriptor > articles ) {
        final Optional< ClusterDetails > found = index.getCluster( name );
        if ( found.isEmpty() ) {
            if ( warnedClusters.add( name ) ) warnings.add( "unknown cluster: " + name );
            return;
        }
        final ClusterDetails cluster = found.get();
        final PageDescriptor hub = cluster.hubPage();
        if ( hub != null ) {
            hubs.putIfAbsent( hub.slug(), hub );
            articles.remove( hub.slug() );
        }
        final List< PageDescriptor > byRecency = cluster.articles().stream()
            .sorted( Comparator.comparing( PageDescriptor::updated, NEWEST_FIRST_NULLS_LAST ) )
            .toList();
        final int room = BriefingConfig.MAX_CLUSTER_MEMBERS - ( hub == null ? 0 : 1 );
        if ( byRecency.size() > room ) {
            warnings.add( "too many members in cluster " + name + "; first "
                + BriefingConfig.MAX_CLUSTER_MEMBERS + " included" );
        }
        byRecency.stream().limit( room )
            .filter( a -> !hubs.containsKey( a.slug() ) )
            .forEach( a -> articles.putIfAbsent( a.slug(), a ) );
    }

    /** Per member, how many <i>other</i> members link to it. */
    private static Map< String, Integer > inDegrees( final List< PageDescriptor > candidates,
                                                    final Set< String > memberSlugs, final ReferenceManager refs ) {
        final Map< String, Integer > result = new HashMap<>();
        for ( final PageDescriptor m : candidates ) {
            int count = 0;
            for ( final String referrer : referrers( refs, m.slug() ) ) {
                if ( !referrer.equals( m.slug() ) && memberSlugs.contains( referrer ) ) count++;
            }
            result.put( m.slug(), count );
        }
        return result;
    }

    private static Comparator< PageDescriptor > byRank( final Map< String, Integer > inDegree ) {
        return Comparator
            .comparing( ( PageDescriptor d ) -> inDegree.getOrDefault( d.slug(), 0 ), Comparator.reverseOrder() )
            .thenComparing( d -> hasSummary( d ) ? 0 : 1 )
            .thenComparing( PageDescriptor::updated, NEWEST_FIRST_NULLS_LAST )
            .thenComparing( PageDescriptor::slug );
    }

    private static boolean hasSummary( final PageDescriptor d ) {
        return d.summary() != null && !d.summary().isBlank();
    }

    // ------------------------------------------------------------ tier 2

    private static List< Cited > citedPages( final List< PageDescriptor > members, final Set< String > memberSlugs,
                                            final ReferenceManager refs, final Descriptors descriptors,
                                            final int citedMax ) {
        final Map< String, Integer > citers = new LinkedHashMap<>();
        for ( final PageDescriptor m : members ) {
            for ( final String target : new LinkedHashSet<>( outbound( refs, m.slug() ) ) ) {
                if ( !memberSlugs.contains( target ) ) citers.merge( target, 1, Integer::sum );
            }
        }
        final List< Cited > result = new ArrayList<>();
        citers.forEach( ( slug, count ) -> {
            final Optional< PageDescriptor > page = descriptors.of( slug );
            if ( page.isEmpty() ) {
                LOG.debug( "Cited page {} has no descriptor; skipping", slug );
                return;
            }
            result.add( new Cited( page.get(), count, reach( page.get(), refs, descriptors ) ) );
        } );
        result.sort( Comparator
            .comparingInt( Cited::citers ).reversed()
            .thenComparing( Comparator.comparingInt( Cited::reach ).reversed() )
            .thenComparing( c -> c.page().updated(), NEWEST_FIRST_NULLS_LAST )
            .thenComparing( c -> c.page().slug() ) );
        return result.size() > citedMax ? result.subList( 0, citedMax ) : result;
    }

    /** Distinct clusters across every referrer's memberships, plus the page's own. */
    private static int reach( final PageDescriptor page, final ReferenceManager refs, final Descriptors descriptors ) {
        final Set< String > clusters = new LinkedHashSet<>( page.clusters() );
        for ( final String referrer : referrers( refs, page.slug() ) ) {
            descriptors.of( referrer ).ifPresent( d -> clusters.addAll( d.clusters() ) );
        }
        return clusters.size();
    }

    // ----------------------------------------------------------- helpers

    /** Outbound links of {@code slug}; {@link ReferenceManager} may return null. */
    private static Collection< String > outbound( final ReferenceManager refs, final String slug ) {
        if ( refs == null ) return List.of();
        final Collection< String > result = refs.findRefersTo( slug );
        return result == null ? List.of() : result;
    }

    /** Pages linking to {@code slug}; {@link ReferenceManager} may return null. */
    private static Collection< String > referrers( final ReferenceManager refs, final String slug ) {
        if ( refs == null ) return List.of();
        final Collection< String > result = refs.findReferrers( slug );
        return result == null ? List.of() : result;
    }

    /** Per-compute slug to descriptor resolver (slug, canonical id, descriptor), memoised. */
    private static final class Descriptors {
        private final StructuralIndexService index;
        private final Map< String, Optional< PageDescriptor > > memo = new HashMap<>();

        Descriptors( final StructuralIndexService index ) {
            this.index = index;
        }

        void remember( final PageDescriptor d ) {
            memo.put( d.slug(), Optional.of( d ) );
        }

        Optional< PageDescriptor > of( final String slug ) {
            return memo.computeIfAbsent( slug,
                s -> index.resolveCanonicalIdFromSlug( s ).flatMap( index::getByCanonicalId ) );
        }
    }
}
