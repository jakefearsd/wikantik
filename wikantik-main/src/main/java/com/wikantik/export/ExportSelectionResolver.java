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
package com.wikantik.export;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.PageDescriptor;

/**
 * Turns an {@link ExportSelection} into a concrete, ACL-filtered, sorted page list.
 *
 * <p>Resolution runs in three stages: (1) seed the set from {@code allPages()} by
 * cluster/tag/type/status filters, (2) expand the seed by following outbound links
 * up to {@code selection.hops()} hops (never crossing into pages the catalog doesn't
 * know about), then (3) drop anything the caller may not view. The ACL check is
 * applied to the seed <em>and</em> to each hop's newly discovered slugs <em>before</em>
 * they are traversed further — a restricted page's outbound links are never followed,
 * so a page reachable only through a restricted page can never leak into the export
 * (that would reveal the restricted page links to it, which a dropped page must be
 * indistinguishable from a nonexistent one to avoid).</p>
 */
public final class ExportSelectionResolver {
    public ResolvedSelection resolve( final ExportSelection selection, final ExportCatalog catalog ) {
        final List< PageDescriptor > all = catalog.allPages();
        final Map< String, PageDescriptor > bySlug = all.stream()
                .collect( Collectors.toMap( PageDescriptor::slug, p -> p, ( a, b ) -> a, LinkedHashMap::new ) );

        final LinkedHashSet< String > seedAll = new LinkedHashSet<>();
        for ( final PageDescriptor p : all ) {
            if ( matches( p, selection, catalog ) ) {
                seedAll.add( p.slug() );
            }
        }
        final int seedCount = seedAll.size();

        final Set< String > viewableSeed = catalog.viewable( seedAll );
        final LinkedHashSet< String > included = new LinkedHashSet<>( viewableSeed );
        int aclDropped = seedAll.size() - viewableSeed.size();

        Set< String > frontier = included;
        int hopAdded = 0;
        for ( int h = 0; h < selection.hops(); h++ ) {
            final LinkedHashSet< String > candidates = new LinkedHashSet<>();
            for ( final String slug : frontier ) {
                for ( final String out : catalog.outboundPages( slug ) ) {
                    if ( bySlug.containsKey( out ) && !included.contains( out ) ) {
                        candidates.add( out );
                    }
                }
            }
            final Set< String > viewableCandidates = catalog.viewable( candidates );
            aclDropped += candidates.size() - viewableCandidates.size();
            included.addAll( viewableCandidates );
            hopAdded += viewableCandidates.size();
            frontier = viewableCandidates;
        }

        final List< PageDescriptor > pages = included.stream()
                .map( bySlug::get )
                .sorted( Comparator.comparing( PageDescriptor::slug ) )
                .toList();

        return new ResolvedSelection( pages, seedCount, hopAdded, aclDropped );
    }

    private boolean matches( final PageDescriptor page, final ExportSelection selection, final ExportCatalog catalog ) {
        if ( !selection.clusters().isEmpty() && !clusterMatches( page, selection ) ) {
            return false;
        }
        if ( !selection.tags().isEmpty() && !page.tags().containsAll( selection.tags() ) ) {
            return false;
        }
        if ( selection.type().isPresent() && page.type() != selection.type().get() ) {
            return false;
        }
        if ( selection.status().isPresent() && !statusMatches( page, selection.status().get(), catalog ) ) {
            return false;
        }
        return true;
    }

    private boolean clusterMatches( final PageDescriptor page, final ExportSelection selection ) {
        return page.clusters().stream().anyMatch( membership ->
                selection.clusters().stream().anyMatch( selected ->
                        selection.includeSubClusters()
                                ? ClusterPath.isSelfOrDescendant( membership, selected )
                                : membership.equals( selected ) ) );
    }

    private boolean statusMatches( final PageDescriptor page, final String wanted, final ExportCatalog catalog ) {
        final Optional< String > actual = catalog.status( page.slug() );
        return actual.isPresent() && actual.get().equalsIgnoreCase( wanted );
    }
}
