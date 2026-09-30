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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;

import static org.junit.jupiter.api.Assertions.*;

class ExportSelectionResolverTest {
    private static PageDescriptor page( final String slug, final List< String > clusters, final List< String > tags, final PageType type ) {
        return new PageDescriptor( "ID-" + slug, slug, slug, type, clusters.isEmpty() ? null : clusters.get( 0 ),
                clusters, tags, "", Instant.EPOCH, Optional.empty(), false );
    }
    private final List< PageDescriptor > all = List.of(
            page( "FinHub", List.of( "finance" ), List.of(), PageType.HUB ),
            page( "Roth", List.of( "finance/retirement" ), List.of( "tax" ), PageType.ARTICLE ),
            page( "Stocks", List.of( "finance" ), List.of( "equity" ), PageType.ARTICLE ),
            page( "Financial", List.of( "financials" ), List.of(), PageType.ARTICLE ),   // startsWith trap
            page( "Secret", List.of( "finance" ), List.of(), PageType.ARTICLE ),
            page( "Math", List.of( "math" ), List.of(), PageType.ARTICLE ),
            page( "Deep", List.of( "math" ), List.of(), PageType.ARTICLE ) );

    private final ExportCatalog catalog = new ExportCatalog() {
        public List< PageDescriptor > allPages() { return all; }
        public Optional< String > status( final String s ) { return "Stocks".equals( s ) ? Optional.of( "draft" ) : Optional.empty(); }
        public Collection< String > outboundPages( final String s ) {
            return switch ( s ) { case "Stocks" -> List.of( "Math" ); case "Math" -> List.of( "Deep" ); default -> List.of(); };
        }
        public Set< String > viewable( final Collection< String > s ) { return s.stream().filter( n -> !"Secret".equals( n ) ).collect( Collectors.toSet() ); }
    };
    private final ExportSelectionResolver resolver = new ExportSelectionResolver();

    private List< String > slugs( final ExportSelection sel ) { return resolver.resolve( sel, catalog ).pages().stream().map( PageDescriptor::slug ).toList(); }
    private ExportSelection sel( final List< String > clusters, final boolean sub, final List< String > tags, final PageType type, final String status, final int hops ) {
        return new ExportSelection( clusters, sub, tags, Optional.ofNullable( type ), Optional.ofNullable( status ), hops, UnresolvedLinkMode.KEEP );
    }

    @Test void clusterWithSubClustersIsSegmentAware() {
        assertEquals( List.of( "FinHub", "Roth", "Stocks" ), slugs( sel( List.of( "finance" ), true, List.of(), null, null, 0 ) ) );
    }
    @Test void clusterWithoutSubClusters() {
        assertEquals( List.of( "FinHub", "Stocks" ), slugs( sel( List.of( "finance" ), false, List.of(), null, null, 0 ) ) );
    }
    @Test void clustersAreOred() {
        assertEquals( List.of( "Deep", "FinHub", "Math", "Roth", "Stocks" ), slugs( sel( List.of( "finance", "math" ), true, List.of(), null, null, 0 ) ) );
    }
    @Test void tagsTypeStatusNarrow() {
        assertEquals( List.of( "Roth" ), slugs( sel( List.of( "finance" ), true, List.of( "tax" ), null, null, 0 ) ) );
        assertEquals( List.of( "FinHub" ), slugs( sel( List.of( "finance" ), true, List.of(), PageType.HUB, null, 0 ) ) );
        assertEquals( List.of( "Stocks" ), slugs( sel( List.of(), true, List.of(), null, "draft", 0 ) ) );
    }
    @Test void hopsExpandOutboundOnlyAndAreBounded() {
        assertEquals( List.of( "Math", "Stocks" ), slugs( sel( List.of(), true, List.of( "equity" ), null, null, 1 ) ) );
        assertEquals( List.of( "Deep", "Math", "Stocks" ), slugs( sel( List.of(), true, List.of( "equity" ), null, null, 2 ) ) );
    }
    @Test void noFiltersMeansEverythingViewable() {
        final ResolvedSelection r = resolver.resolve( sel( List.of(), true, List.of(), null, null, 0 ), catalog );
        assertEquals( 6, r.pages().size() );
        assertFalse( r.pages().stream().anyMatch( p -> p.slug().equals( "Secret" ) ) );
        assertEquals( 1, r.aclDropped() );
    }
    @Test void hopDoesNotReintroduceRestrictedPage() {
        // even if something links to Secret, it never appears
        assertFalse( slugs( sel( List.of( "finance" ), true, List.of(), null, null, 2 ) ).contains( "Secret" ) );
    }

    @Test void hopNeverTraversesARestrictedPage() {
        // Secret is restricted and links to OnlyViaSecret, which is reachable from nowhere else.
        // OnlyViaSecret must never surface — traversal must stop at the restricted page, not
        // merely filter its target out at the very end (which would leak that Secret links to it).
        final PageDescriptor secret = page( "Secret", List.of( "finance" ), List.of(), PageType.ARTICLE );
        final PageDescriptor onlyViaSecret = page( "OnlyViaSecret", List.of( "unrelated" ), List.of(), PageType.ARTICLE );
        final List< PageDescriptor > isolatedPages = List.of( secret, onlyViaSecret );
        final ExportCatalog leakyCatalog = new ExportCatalog() {
            public List< PageDescriptor > allPages() { return isolatedPages; }
            public Optional< String > status( final String s ) { return Optional.empty(); }
            public Collection< String > outboundPages( final String s ) {
                return "Secret".equals( s ) ? List.of( "OnlyViaSecret" ) : List.of();
            }
            public Set< String > viewable( final Collection< String > s ) { return s.stream().filter( n -> !"Secret".equals( n ) ).collect( Collectors.toSet() ); }
        };
        final ResolvedSelection r = resolver.resolve( sel( List.of( "finance" ), true, List.of(), null, null, 1 ), leakyCatalog );
        assertTrue( r.pages().isEmpty() );
        assertFalse( r.pages().stream().anyMatch( p -> p.slug().equals( "OnlyViaSecret" ) ) );
        assertEquals( 1, r.aclDropped() );
    }
}
