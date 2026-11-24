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

import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.IndexHealth;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveValuesStructuralIndexServiceTest {

    private static final String TOKEN = "Over {{wiki:articles}} articles";
    private static final String EXPANDED = "Over 42 articles";

    private StructuralIndexService raw;
    private LiveValuesStructuralIndexService decorated;
    private final PageDescriptor live = descriptor( "A", TOKEN );
    private final PageDescriptor plain = descriptor( "B", "No tokens here" );

    private static PageDescriptor descriptor( final String slug, final String summary ) {
        return new PageDescriptor( "id-" + slug, slug, slug, PageType.ARTICLE, "c", List.of( "c" ),
            List.of( "t" ), summary, Instant.EPOCH, false, List.of( "inline" ) );
    }

    @BeforeEach
    void setUp() {
        raw = mock( StructuralIndexService.class );
        final LiveValues lv = mock( LiveValues.class );
        when( lv.expandText( TOKEN ) ).thenReturn( EXPANDED );
        when( lv.expandText( "No tokens here" ) ).thenAnswer( i -> i.getArgument( 0 ) );
        decorated = new LiveValuesStructuralIndexService( raw, () -> lv );
    }

    @Test
    void getByCanonicalIdExpandsSummaryAndKeepsOtherFields() {
        when( raw.getByCanonicalId( "id-A" ) ).thenReturn( Optional.of( live ) );
        final PageDescriptor out = decorated.getByCanonicalId( "id-A" ).orElseThrow();
        assertEquals( EXPANDED, out.summary() );
        assertEquals( live.canonicalId(), out.canonicalId() );
        assertEquals( live.tags(), out.tags() );
        assertEquals( live.inlineTags(), out.inlineTags() );
        assertEquals( live.clusters(), out.clusters() );
    }

    @Test
    void descriptorWithoutTokenIsTheSameInstance() {
        when( raw.getByCanonicalId( "id-B" ) ).thenReturn( Optional.of( plain ) );
        assertSame( plain, decorated.getByCanonicalId( "id-B" ).orElseThrow() );
    }

    @Test
    void listPagesByFilterAndTypeExpand() {
        when( raw.listPagesByFilter( StructuralFilter.none() ) ).thenReturn( List.of( live, plain ) );
        when( raw.listPagesByType( PageType.ARTICLE ) ).thenReturn( List.of( live ) );
        final List< PageDescriptor > byFilter = decorated.listPagesByFilter( StructuralFilter.none() );
        assertEquals( EXPANDED, byFilter.get( 0 ).summary() );
        assertSame( plain, byFilter.get( 1 ) );
        assertEquals( EXPANDED, decorated.listPagesByType( PageType.ARTICLE ).get( 0 ).summary() );
    }

    @Test
    void getClusterExpandsHubAndArticles() {
        final ClusterDetails details = new ClusterDetails( "c", live, List.of( live, plain ), java.util.Map.of(), Instant.EPOCH );
        when( raw.getCluster( "c" ) ).thenReturn( Optional.of( details ) );
        final ClusterDetails out = decorated.getCluster( "c" ).orElseThrow();
        assertEquals( EXPANDED, out.hubPage().summary() );
        assertEquals( EXPANDED, out.articles().get( 0 ).summary() );
        assertSame( plain, out.articles().get( 1 ) );
        assertEquals( "c", out.name() );
    }

    @Test
    void listClustersExpandsHub() {
        when( raw.listClusters() ).thenReturn( List.of( new ClusterSummary( "c", live, 3, Instant.EPOCH ) ) );
        final ClusterSummary out = decorated.listClusters().get( 0 );
        assertEquals( EXPANDED, out.hubPage().summary() );
        assertEquals( 3, out.articleCount() );
    }

    @Test
    void sitemapExpands() {
        final Sitemap sm = new Sitemap( List.of( live, plain ), 2, Instant.EPOCH );
        when( raw.sitemap() ).thenReturn( sm );
        final Sitemap out = decorated.sitemap();
        assertEquals( EXPANDED, out.pages().get( 0 ).summary() );
        assertEquals( 2, out.count() );
        assertEquals( Instant.EPOCH, out.generatedAt() );
    }

    @Test
    void untouchedSitemapIsTheSameInstance() {
        final Sitemap sm = new Sitemap( List.of( plain ), 1, Instant.EPOCH );
        when( raw.sitemap() ).thenReturn( sm );
        assertSame( sm, decorated.sitemap() );
    }

    @Test
    void nonDescriptorMethodsDelegateUntouched() {
        final StructuralIndexService.StructuralProjectionSnapshot snap = mock( StructuralIndexService.StructuralProjectionSnapshot.class );
        final IndexHealth health = mock( IndexHealth.class );
        when( raw.snapshot() ).thenReturn( snap );
        when( raw.health() ).thenReturn( health );
        when( raw.conflicts() ).thenReturn( List.of() );
        assertSame( snap, decorated.snapshot() );
        assertSame( health, decorated.health() );
        assertEquals( List.of(), decorated.conflicts() );
    }

    @Test
    void missingLiveValuesPassesThrough() {
        final Supplier< LiveValues > none = () -> null;
        final LiveValuesStructuralIndexService passthrough = new LiveValuesStructuralIndexService( raw, none );
        when( raw.getByCanonicalId( "id-A" ) ).thenReturn( Optional.of( live ) );
        assertSame( live, passthrough.getByCanonicalId( "id-A" ).orElseThrow() );
    }

    @Test
    void failingExpansionServesVerbatim() {
        final LiveValues boom = mock( LiveValues.class );
        when( boom.expandText( TOKEN ) ).thenThrow( new IllegalStateException( "boom" ) );
        final LiveValuesStructuralIndexService svc = new LiveValuesStructuralIndexService( raw, () -> boom );
        when( raw.getByCanonicalId( "id-A" ) ).thenReturn( Optional.of( live ) );
        assertSame( live, svc.getByCanonicalId( "id-A" ).orElseThrow() );
    }

    @Test
    void unwrapReturnsTheDelegate() {
        assertSame( raw, LiveValuesStructuralIndexService.unwrap( decorated ) );
        assertSame( raw, LiveValuesStructuralIndexService.unwrap( raw ) );
        assertNotSame( raw, decorated );
    }
}
