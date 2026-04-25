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
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.ClusterDetails;
import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.Relation;
import com.wikantik.api.structure.RelationDirection;
import com.wikantik.api.structure.RelationEdge;
import com.wikantik.api.structure.RelationType;
import com.wikantik.api.structure.Sitemap;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.TagSummary;
import com.wikantik.api.structure.TraversalSpec;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of the structural projection. Built by {@link StructuralProjectionBuilder}
 * during a full rebuild; exposed through {@link DefaultStructuralIndexService} as the query
 * substrate. All list results are defensively copied at construction time.
 */
public final class StructuralProjection {

    private final Map< String, PageDescriptor > byCanonicalId;
    private final Map< String, String >         slugToCanonicalId;
    private final Map< String, List< PageDescriptor > > byCluster;
    private final Map< String, PageDescriptor > hubByCluster;
    private final Map< String, List< PageDescriptor > > byTag;
    private final Map< PageType, List< PageDescriptor > > byType;
    private final Map< String, List< Relation > > outgoingBySource;
    private final Map< String, List< Relation > > incomingByTarget;
    private final Instant generatedAt;

    StructuralProjection( final Map< String, PageDescriptor > byCanonicalId,
                          final Map< String, String > slugToCanonicalId,
                          final Map< String, List< PageDescriptor > > byCluster,
                          final Map< String, PageDescriptor > hubByCluster,
                          final Map< String, List< PageDescriptor > > byTag,
                          final Map< PageType, List< PageDescriptor > > byType,
                          final Map< String, List< Relation > > outgoingBySource,
                          final Map< String, List< Relation > > incomingByTarget,
                          final Instant generatedAt ) {
        this.byCanonicalId     = Map.copyOf( byCanonicalId );
        this.slugToCanonicalId = Map.copyOf( slugToCanonicalId );
        this.byCluster         = deepCopy( byCluster );
        this.hubByCluster      = Map.copyOf( hubByCluster );
        this.byTag             = deepCopy( byTag );
        this.byType            = deepCopyEnum( byType );
        this.outgoingBySource  = deepCopyRelations( outgoingBySource );
        this.incomingByTarget  = deepCopyRelations( incomingByTarget );
        this.generatedAt       = generatedAt;
    }

    public Instant generatedAt() { return generatedAt; }

    public int pageCount()    { return byCanonicalId.size(); }
    public int clusterCount() { return byCluster.size(); }
    public int tagCount()     { return byTag.size(); }

    public List< ClusterSummary > listClusters() {
        final List< ClusterSummary > out = new ArrayList<>( byCluster.size() );
        byCluster.forEach( ( name, pages ) -> out.add( new ClusterSummary(
                name,
                hubByCluster.get( name ),
                pages.size(),
                pages.stream().map( PageDescriptor::updated ).filter( Objects::nonNull )
                     .max( Instant::compareTo ).orElse( null ) ) ) );
        out.sort( Comparator.comparing( ClusterSummary::name ) );
        return out;
    }

    public Optional< ClusterDetails > getCluster( final String name ) {
        final List< PageDescriptor > pages = byCluster.get( name );
        if ( pages == null ) {
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
                .filter( p -> filter.cluster().map( c -> c.equals( p.cluster() ) ).orElse( true ) )
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

    public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
        return Optional.ofNullable( byCanonicalId.get( canonicalId ) ).map( PageDescriptor::slug );
    }

    public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
        return Optional.ofNullable( slugToCanonicalId.get( slug ) );
    }

    /* --------------------------------------------------- Relation graph */

    public List< RelationEdge > outgoingRelations( final String canonicalId,
                                                    final Optional< RelationType > typeFilter ) {
        return edgesOf( outgoingBySource.get( canonicalId ), typeFilter, /* outgoing */ true, 1 );
    }

    public List< RelationEdge > incomingRelations( final String canonicalId,
                                                    final Optional< RelationType > typeFilter ) {
        return edgesOf( incomingByTarget.get( canonicalId ), typeFilter, /* outgoing */ false, 1 );
    }

    /**
     * Bounded BFS. Visits each canonical_id at most once at its first discovered
     * depth; the resulting edge list reflects the traversal order. Edges into
     * targets that no longer exist as descriptors are still emitted (their
     * targetSlug/targetTitle land as null) — a broken-link signal callers can
     * surface.
     */
    public List< RelationEdge > traverse( final String rootCanonicalId, final TraversalSpec spec ) {
        if ( rootCanonicalId == null || !byCanonicalId.containsKey( rootCanonicalId ) ) {
            return List.of();
        }
        final List< RelationEdge > out = new ArrayList<>();
        final Set< String > visited = new HashSet<>();
        visited.add( rootCanonicalId );
        final Deque< String > frontier = new ArrayDeque<>();
        frontier.add( rootCanonicalId );

        for ( int depth = 1; depth <= spec.depthCap() && !frontier.isEmpty(); depth++ ) {
            final int frontierSize = frontier.size();
            for ( int i = 0; i < frontierSize; i++ ) {
                final String node = frontier.poll();
                if ( spec.direction() == RelationDirection.OUT || spec.direction() == RelationDirection.BOTH ) {
                    walkAdjacency( out, frontier, visited, outgoingBySource.get( node ), spec.typeFilter(),
                                    /* outgoing */ true, depth );
                }
                if ( spec.direction() == RelationDirection.IN || spec.direction() == RelationDirection.BOTH ) {
                    walkAdjacency( out, frontier, visited, incomingByTarget.get( node ), spec.typeFilter(),
                                    /* outgoing */ false, depth );
                }
            }
        }
        return out;
    }

    private void walkAdjacency( final List< RelationEdge > out,
                                 final Deque< String > frontier,
                                 final Set< String > visited,
                                 final List< Relation > adjacency,
                                 final Optional< RelationType > typeFilter,
                                 final boolean outgoing,
                                 final int depth ) {
        if ( adjacency == null ) {
            return;
        }
        for ( final Relation r : adjacency ) {
            if ( typeFilter.isPresent() && r.type() != typeFilter.get() ) {
                continue;
            }
            final String otherEnd = outgoing ? r.targetId() : r.sourceId();
            out.add( edgeOf( r, outgoing, depth ) );
            if ( visited.add( otherEnd ) ) {
                frontier.add( otherEnd );
            }
        }
    }

    private List< RelationEdge > edgesOf( final List< Relation > rels,
                                           final Optional< RelationType > typeFilter,
                                           final boolean outgoing,
                                           final int depth ) {
        if ( rels == null || rels.isEmpty() ) {
            return List.of();
        }
        final List< RelationEdge > out = new ArrayList<>( rels.size() );
        for ( final Relation r : rels ) {
            if ( typeFilter.isPresent() && r.type() != typeFilter.get() ) {
                continue;
            }
            out.add( edgeOf( r, outgoing, depth ) );
        }
        return out;
    }

    private RelationEdge edgeOf( final Relation r, final boolean outgoing, final int depth ) {
        final PageDescriptor sourceDesc = byCanonicalId.get( r.sourceId() );
        final PageDescriptor targetDesc = byCanonicalId.get( r.targetId() );
        final String sourceSlug  = sourceDesc != null ? sourceDesc.slug()  : null;
        final String targetSlug  = targetDesc != null ? targetDesc.slug()  : null;
        final String targetTitle = targetDesc != null ? targetDesc.title() : null;
        return new RelationEdge( r.sourceId(), sourceSlug, r.targetId(),
                                  targetSlug, targetTitle, r.type(), depth );
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

    private static Map< String, List< Relation > > deepCopyRelations( final Map< String, List< Relation > > m ) {
        final Map< String, List< Relation > > out = new HashMap<>( m.size() );
        m.forEach( ( k, v ) -> out.put( k, List.copyOf( v ) ) );
        return Collections.unmodifiableMap( out );
    }
}
