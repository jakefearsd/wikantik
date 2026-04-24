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
import com.wikantik.api.structure.Sitemap;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.TagSummary;

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
 */
public final class StructuralProjection {

    private final Map< String, PageDescriptor > byCanonicalId;
    private final Map< String, String >         slugToCanonicalId;
    private final Map< String, List< PageDescriptor > > byCluster;
    private final Map< String, PageDescriptor > hubByCluster;
    private final Map< String, List< PageDescriptor > > byTag;
    private final Map< PageType, List< PageDescriptor > > byType;
    private final Instant generatedAt;

    StructuralProjection( final Map< String, PageDescriptor > byCanonicalId,
                          final Map< String, String > slugToCanonicalId,
                          final Map< String, List< PageDescriptor > > byCluster,
                          final Map< String, PageDescriptor > hubByCluster,
                          final Map< String, List< PageDescriptor > > byTag,
                          final Map< PageType, List< PageDescriptor > > byType,
                          final Instant generatedAt ) {
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
