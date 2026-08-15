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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.TagSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of the structural projection. Built by {@link StructuralProjectionBuilder}
 * during a full rebuild; exposed through {@link DefaultStructuralIndexService} as the query
 * substrate. All list results are defensively copied at construction time.
 *
 * <p>Cluster, tag, page-descriptor, and canonical-id state are maintained here.
 * Typed-relation edges were removed in Tasks 7–9 (2026-05-02) when the upstream
 * consumers were deleted.
 */
public final class StructuralProjection {

    private final Map< String, PageDescriptor > byCanonicalId;
    private final Map< String, String >         slugToCanonicalId;
    private final Map< String, List< PageDescriptor > > byCluster;
    private final Map< String, PageDescriptor > hubByCluster;
    private final Map< String, List< PageDescriptor > > byTag;
    private final Map< PageType, List< PageDescriptor > > byType;
    private final List< StructuralConflict > duplicateDeclarations;
    private final Instant generatedAt;

    StructuralProjection( final Map< String, PageDescriptor > byCanonicalId,
                          final Map< String, String > slugToCanonicalId,
                          final Map< String, List< PageDescriptor > > byCluster,
                          final Map< String, PageDescriptor > hubByCluster,
                          final Map< String, List< PageDescriptor > > byTag,
                          final Map< PageType, List< PageDescriptor > > byType,
                          final List< StructuralConflict > duplicateDeclarations,
                          final Instant generatedAt ) {
        this.duplicateDeclarations = List.copyOf( duplicateDeclarations );
        this.byCanonicalId     = Map.copyOf( byCanonicalId );
        this.slugToCanonicalId = Map.copyOf( slugToCanonicalId );
        this.byCluster         = deepCopy( byCluster );
        this.hubByCluster      = Map.copyOf( hubByCluster );
        this.byTag             = deepCopy( byTag );
        this.byType            = deepCopyEnum( byType );
        this.generatedAt       = generatedAt;
    }

    public Instant generatedAt() { return generatedAt; }

    public int pageCount()    { return byCanonicalId.size(); }
    public int clusterCount() { return byCluster.size(); }
    public int tagCount()     { return byTag.size(); }

    /**
     *  All pages belonging to {@code cluster}, including those in its sub-clusters.
     *
     *  <p>Membership is transitive: a page in {@code machine-learning/mlops} is also a
     *  member of {@code machine-learning}. This is resolved here, at query time, rather
     *  than by indexing each page under every ancestor — the index stays a faithful
     *  record of what each page declares, and re-parenting needs no reindex.</p>
     *
     *  <p>Matching goes through {@link ClusterPath}, so it is segment-aware:
     *  {@code machine-learning-ops} is never a member of {@code machine-learning}.</p>
     */
    private List< PageDescriptor > membersOf( final String cluster ) {
        final List< PageDescriptor > out = new ArrayList<>( byCluster.getOrDefault( cluster, List.of() ) );
        byCluster.forEach( ( name, pages ) -> {
            if ( !name.equals( cluster ) && ClusterPath.isSelfOrDescendant( name, cluster ) ) {
                out.addAll( pages );
            }
        } );
        return out;
    }

    private static Instant lastUpdated( final List< PageDescriptor > pages ) {
        return pages.stream().map( PageDescriptor::updated ).filter( Objects::nonNull )
                    .max( Instant::compareTo ).orElse( null );
    }

    /**
     *  Every taxonomy defect visible in this snapshot, as defined by ClusterDeclarationDesign.
     *
     *  <p>Computed on demand rather than stored, so it always reflects the current snapshot.
     *  Duplicate declarations are the exception — they are captured during the build, because
     *  the projection keeps only the winning hub and cannot recover the loser afterwards.</p>
     *
     *  <p>An empty list means the taxonomy satisfies every invariant: exactly one hub per
     *  cluster, no cluster without a hub, no hub without a cluster, no orphaned sub-cluster.</p>
     */
    public List< StructuralConflict > structuralConflicts() {
        final List< StructuralConflict > out = new ArrayList<>( duplicateDeclarations );

        byType.getOrDefault( PageType.HUB, List.of() ).stream()
              .filter( p -> p.cluster() == null || p.cluster().isBlank() )
              .forEach( p -> out.add( new StructuralConflict(
                      p.slug(), p.canonicalId(), StructuralConflict.Kind.CLUSTERLESS_HUB,
                      "hub page declares no cluster, so it declares nothing" ) ) );

        byCluster.keySet().forEach( cluster -> {
            if ( !hubByCluster.containsKey( cluster ) ) {
                out.add( new StructuralConflict(
                        cluster, null, StructuralConflict.Kind.HEADLESS_CLUSTER,
                        "cluster '" + cluster + "' has member pages but no hub declares it" ) );
            }
            final String parent = ClusterPath.parent( cluster );
            if ( parent != null && !hubByCluster.containsKey( parent ) ) {
                out.add( new StructuralConflict(
                        cluster, null, StructuralConflict.Kind.UNDECLARED_CLUSTER,
                        "sub-cluster '" + cluster + "' names parent '" + parent
                                + "', which no hub declares" ) );
            }
        } );

        out.sort( Comparator.comparing( StructuralConflict::slug )
                            .thenComparing( c -> c.kind().name() ) );
        return List.copyOf( out );
    }

    public List< ClusterSummary > listClusters() {
        final List< ClusterSummary > out = new ArrayList<>( byCluster.size() );
        byCluster.keySet().forEach( name -> {
            final List< PageDescriptor > members = membersOf( name );
            out.add( new ClusterSummary( name, hubByCluster.get( name ), members.size(),
                                          lastUpdated( members ) ) );
        } );
        out.sort( Comparator.comparing( ClusterSummary::name ) );
        return out;
    }

    public Optional< ClusterDetails > getCluster( final String name ) {
        final List< PageDescriptor > pages = membersOf( name );
        if ( pages.isEmpty() ) {
            return Optional.empty();
        }
        final Map< String, Integer > tagDist = new TreeMap<>();
        pages.forEach( p -> p.tags().forEach( t -> tagDist.merge( t, 1, Integer::sum ) ) );
        return Optional.of( new ClusterDetails(
                name,
                hubByCluster.get( name ),
                pages,
                tagDist,
                pages.stream().map( PageDescriptor::updated ).filter( Objects::nonNull )
                     .max( Instant::compareTo ).orElse( null ) ) );
    }

    public List< TagSummary > listTags( final int minPages ) {
        final int threshold = Math.max( 1, minPages );
        return byTag.entrySet().stream()
                .filter( e -> e.getValue().size() >= threshold )
                .map( e -> new TagSummary(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().limit( 10 ).map( PageDescriptor::canonicalId ).toList() ) )
                .sorted( Comparator.comparingInt( TagSummary::count ).reversed()
                        .thenComparing( TagSummary::tag ) )
                .collect( Collectors.toList() );
    }

    public List< PageDescriptor > listPagesByType( final PageType type ) {
        return byType.getOrDefault( type, List.of() );
    }

    public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) {
        return byCanonicalId.values().stream()
                .filter( p -> filter.type().map( t -> t == p.type() ).orElse( true ) )
                .filter( p -> filter.cluster()
                        .map( c -> ClusterPath.isSelfOrDescendant( p.cluster(), c ) ).orElse( true ) )
                .filter( p -> filter.tags().isEmpty() || p.tags().containsAll( filter.tags() ) )
                .filter( p -> filter.updatedSince()
                        .map( since -> p.updated() != null && !p.updated().isBefore( since ) )
                        .orElse( true ) )
                .sorted( Comparator.comparing( PageDescriptor::canonicalId ) )
                .limit( filter.limit() )
                .toList();
    }

    public Sitemap sitemap() {
        final List< PageDescriptor > all = byCanonicalId.values().stream()
                .sorted( Comparator.comparing( PageDescriptor::slug ) ).toList();
        return new Sitemap( all, all.size(), Instant.now() );
    }

    public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) {
        return Optional.ofNullable( byCanonicalId.get( canonicalId ) );
    }

    /**
     * Snapshot of every {@link PageDescriptor} known to this projection. Used by callers
     * that need to splice an updated page into a fresh projection (e.g. the
     * post-save patch path in {@code DefaultStructuralIndexService}).
     */
    public java.util.Collection< PageDescriptor > allPages() {
        return byCanonicalId.values();
    }

    public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
        return Optional.ofNullable( byCanonicalId.get( canonicalId ) ).map( PageDescriptor::slug );
    }

    public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
        return Optional.ofNullable( slugToCanonicalId.get( slug ) );
    }

    private static < K > Map< K, List< PageDescriptor > > deepCopy( final Map< K, List< PageDescriptor > > m ) {
        final Map< K, List< PageDescriptor > > out = new HashMap<>( m.size() );
        m.forEach( ( k, v ) -> out.put( k, List.copyOf( v ) ) );
        return Collections.unmodifiableMap( out );
    }

    private static Map< PageType, List< PageDescriptor > > deepCopyEnum( final Map< PageType, List< PageDescriptor > > m ) {
        final EnumMap< PageType, List< PageDescriptor > > out = new EnumMap<>( PageType.class );
        m.forEach( ( k, v ) -> out.put( k, List.copyOf( v ) ) );
        return Collections.unmodifiableMap( out );
    }
}
