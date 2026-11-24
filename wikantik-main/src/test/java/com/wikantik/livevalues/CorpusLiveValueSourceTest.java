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
package com.wikantik.livevalues;

import com.wikantik.api.Release;
import com.wikantik.api.livevalues.LiveValueExpander;
import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorpusLiveValueSourceTest {

    private CorpusLiveValueSource source;

    private static PageDescriptor page( final String slug, final PageType type, final List< String > clusters,
                                        final boolean derived ) {
        return new PageDescriptor( "id-" + slug, slug, slug, type, null, clusters, List.of(), null, null, derived );
    }

    @BeforeEach
    void setUp() {
        final List< PageDescriptor > pages = List.of(
            page( "A1", PageType.ARTICLE, List.of( "a" ), false ),
            page( "A2", PageType.ARTICLE, List.of( "a", "b" ), false ),
            page( "B1", PageType.ARTICLE, List.of( "b/sub" ), false ),
            page( "AHub", PageType.HUB, List.of( "a" ), false ),
            page( "SysPage", PageType.ARTICLE, List.of(), false ),
            page( "Hidden", PageType.ARTICLE, List.of( "secret" ), false ),
            page( "BHub", PageType.HUB, List.of( "b" ), false ),
            page( "BSubHub", PageType.HUB, List.of( "b/sub" ), false ),
            page( "Ghost", PageType.ARTICLE, List.of( "ghost" ), false ),
            page( "Derived", PageType.ARTICLE, List.of( "a" ), true ) );
        final StructuralIndexService index = mock( StructuralIndexService.class );
        when( index.sitemap() ).thenReturn( new Sitemap( pages, pages.size(), Instant.EPOCH ) );
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "SysPage" ) ).thenReturn( true );
        source = new CorpusLiveValueSource( index, sys, name -> !"Hidden".equals( name ) );
    }

    private Resolution resolve( final String name ) {
        final Map< String, Resolution > v = source.values();
        return v.getOrDefault( name, new Resolution.Unknown() );
    }

    @Test
    void articlesAndPagesExcludePrivateAndSystemAndHubsAsSpecified() {
        assertEquals( new Resolution.Value( 5 ), resolve( "articles" ) );   // A1 A2 B1 Ghost Derived
        assertEquals( new Resolution.Value( 9 ), resolve( "pages" ) );      // + 3 hubs + SysPage, no Hidden
    }

    @Test
    void clusterCountsUseMembershipAndExcludeHubs() {
        assertEquals( new Resolution.Value( 3 ), resolve( "cluster.a.pages" ) );     // A1 A2 Derived, not AHub
        assertEquals( new Resolution.Value( 1 ), resolve( "cluster.b.pages" ) );     // A2
        assertEquals( new Resolution.Value( 1 ), resolve( "cluster.b/sub.pages" ) );
        assertEquals( new Resolution.Value( 2 ), resolve( "clusters" ) );            // a, b
        assertEquals( new Resolution.Value( 1 ), resolve( "subclusters" ) );         // b/sub
    }

    @Test
    void undeclaredClusterPathIsNeitherCountedNorKnown() {
        assertEquals( new Resolution.Unknown(), resolve( "cluster.ghost.pages" ) );
        assertEquals( new Resolution.Value( 2 ), resolve( "clusters" ) );   // a, b -- not ghost
    }

    @Test
    void privateClusterIsIndistinguishableFromUnknownCluster() {
        assertEquals( resolve( "cluster.nosuch.pages" ), resolve( "cluster.secret.pages" ) );
        assertEquals( new Resolution.Unknown(), resolve( "cluster.secret.pages" ) );
        assertTrue( source.values().keySet().stream().noneMatch( k -> k.contains( "secret" ) ) );
    }

    @Test
    void versionIsTheReleaseString() {
        assertEquals( new Resolution.Text( Release.getVersionString() ), resolve( "version" ) );
    }

    @Test
    void notReadyUntilTheFirstRebuildHasFinished() {
        final StructuralIndexService booting = mock( StructuralIndexService.class );
        when( booting.sitemap() ).thenReturn( new Sitemap( List.of(), 0, Instant.EPOCH ) );
        when( booting.health() ).thenReturn( new com.wikantik.api.pagegraph.IndexHealth(
                com.wikantik.api.pagegraph.IndexHealth.Status.DOWN, 0, 0, null, null, 0L, 0L ) );
        final CorpusLiveValueSource early = new CorpusLiveValueSource( booting, mock( SystemPageRegistry.class ), n -> true );
        org.junit.jupiter.api.Assertions.assertThrows( com.wikantik.api.livevalues.LiveValueSource.NotReadyException.class,
                early::values );
        when( booting.health() ).thenReturn( new com.wikantik.api.pagegraph.IndexHealth(
                com.wikantik.api.pagegraph.IndexHealth.Status.UP, 0, 0, Instant.EPOCH, Instant.EPOCH, 0L, 0L ) );
        assertEquals( new Resolution.Value( 0 ), early.values().get( "articles" ) );
    }

    @Test
    void declaresItsNames() {
        final Set< String > names = source.declaredNames();
        assertTrue( names.containsAll( Set.of( "articles", "pages", "clusters", "subclusters", "version",
                LiveValueExpander.CLUSTER_PREFIX ) ) );
    }
}
