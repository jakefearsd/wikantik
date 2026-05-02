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

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultStructuralIndexServiceTest {

    private PageManager pageManager;
    private PageCanonicalIdsDao dao;
    private DefaultStructuralIndexService svc;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        dao = mock( PageCanonicalIdsDao.class );
        svc = new DefaultStructuralIndexService( pageManager, dao );
    }

    private Page fakePage( final String name, final String frontmatter, final String body ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );
        when( pageManager.getPureText( p ) ).thenReturn( "---\n" + frontmatter + "\n---\n" + body );
        return p;
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_indexes_every_page_returned_by_pageManager() throws Exception {
        final Page a = fakePage( "HybridRetrieval",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN\n" +
                "title: Hybrid Retrieval\n" +
                "type: article\n" +
                "cluster: wikantik-development\n" +
                "tags: [retrieval, bm25]\n" +
                "summary: Hybrid retrieval reference.", "body" );
        final Page b = fakePage( "WikantikDevelopment",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A0\n" +
                "title: Wikantik Development\n" +
                "type: hub\n" +
                "cluster: wikantik-development\n" +
                "tags: [wikantik]\n" +
                "summary: Dev hub.", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );

        svc.rebuild();

        final var clusters = svc.listClusters();
        assertEquals( 1, clusters.size() );
        assertEquals( "wikantik-development", clusters.get( 0 ).name() );
        assertEquals( 2, clusters.get( 0 ).articleCount() );

        assertTrue( svc.getByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).isPresent() );
        verify( dao, times( 2 ) ).upsert( any(), any(), any(), any(), any() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_synthesises_canonical_id_for_pages_missing_frontmatter_field() throws Exception {
        final Page a = fakePage( "RawPage", "title: Raw Page\ntype: article", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );

        svc.rebuild();

        final var health = svc.health();
        assertEquals( 1, health.unclaimedCanonicalIds() );
        assertEquals( 1, svc.snapshot().pageCount() );
        // Synthesised IDs live in memory only — they MUST NOT be written to the DB,
        // otherwise every restart would churn new rows into page_canonical_ids.
        verifyNoInteractions( dao );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void listPagesByFilter_round_trip_after_rebuild() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: A\ntype: article\ncluster: x\ntags: [t1]", "" );
        final Page b = fakePage( "B",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: B\ntype: hub\ncluster: x\ntags: [t1]", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );

        svc.rebuild();

        final var articles = svc.listPagesByFilter( new StructuralFilter(
                Optional.of( PageType.ARTICLE ), null, null, null, 10, null ) );
        assertEquals( 1, articles.size() );
        assertEquals( "A", articles.get( 0 ).slug() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_persists_verification_with_computed_confidence() throws Exception {
        final java.time.Instant nowish = java.time.Instant.now().minus( java.time.Duration.ofDays( 1 ) );
        final String iso = nowish.toString();
        final Page auth = fakePage( "Auth",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: Auth\ntype: article\n" +
                "verified_at: " + iso + "\n" +
                "verified_by: alice\n", "" );
        final Page prov = fakePage( "Prov",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: Prov\ntype: article\n" +
                "verified_at: " + iso + "\n" +
                "verified_by: bob\n", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( auth, prov ) );

        final PageVerificationDao verificationDao = mock( PageVerificationDao.class );
        final ConfidenceComputer computer = new ConfidenceComputer( "alice"::equals );
        final var withVerification = new DefaultStructuralIndexService(
                pageManager, dao, verificationDao, computer,
                new StructuralIndexMetrics() );

        withVerification.rebuild();

        final org.mockito.ArgumentCaptor< com.wikantik.api.structure.Verification > cap =
                org.mockito.ArgumentCaptor.forClass( com.wikantik.api.structure.Verification.class );
        verify( verificationDao, times( 2 ) ).upsert( anyString(), cap.capture() );

        final var values = cap.getAllValues();
        assertTrue( values.stream().anyMatch(
                v -> v.confidence() == com.wikantik.api.structure.Confidence.AUTHORITATIVE ),
                "alice is trusted → AUTHORITATIVE" );
        assertTrue( values.stream().anyMatch(
                v -> v.confidence() == com.wikantik.api.structure.Confidence.PROVISIONAL ),
                "bob is not trusted → PROVISIONAL" );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageSaved_does_not_re_enumerate_every_page() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        final Page b = fakePage( "B",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: B\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );
        svc.rebuild();
        clearInvocations( pageManager );

        final Page bUpdated = fakePage( "B",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: B (updated)\ntype: article", "" );
        when( pageManager.getPage( "B" ) ).thenReturn( bUpdated );

        svc.onPageSaved( "B" );

        verify( pageManager, never() ).getAllPages();
        verify( pageManager, atLeastOnce() ).getPage( "B" );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageSaved_refreshes_just_the_named_page_descriptor() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article\ntags: [old]", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();

        final Page aUpdated = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A v2\ntype: article\ntags: [fresh]", "" );
        when( pageManager.getPage( "A" ) ).thenReturn( aUpdated );

        svc.onPageSaved( "A" );

        final var d = svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow();
        assertEquals( "A v2", d.title() );
        assertEquals( List.of( "fresh" ), d.tags() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageSaved_inserts_a_brand_new_page_without_full_rebuild() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();
        clearInvocations( pageManager );

        final Page newPage = fakePage( "Brand",
                "canonical_id: 01CCCCCCCCCCCCCCCCCCCCCCCC\ntitle: Brand\ntype: article", "" );
        when( pageManager.getPage( "Brand" ) ).thenReturn( newPage );

        svc.onPageSaved( "Brand" );

        verify( pageManager, never() ).getAllPages();
        assertEquals( 2, svc.snapshot().pageCount() );
        assertTrue( svc.getByCanonicalId( "01CCCCCCCCCCCCCCCCCCCCCCCC" ).isPresent() );
        assertTrue( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isPresent() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageSaved_updates_unclaimed_count_when_canonical_id_is_added() throws Exception {
        final Page noId = fakePage( "Drafty", "title: Drafty\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( noId ) );
        svc.rebuild();
        assertEquals( 1, svc.health().unclaimedCanonicalIds() );

        final Page authored = fakePage( "Drafty",
                "canonical_id: 01DDDDDDDDDDDDDDDDDDDDDDDD\ntitle: Drafty\ntype: article", "" );
        when( pageManager.getPage( "Drafty" ) ).thenReturn( authored );

        svc.onPageSaved( "Drafty" );

        assertEquals( 0, svc.health().unclaimedCanonicalIds() );
        assertTrue( svc.getByCanonicalId( "01DDDDDDDDDDDDDDDDDDDDDDDD" ).isPresent() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void conflicts_track_missing_canonical_id() throws Exception {
        final Page noId = fakePage( "NoId", "title: NoId\ntype: article", "" );
        final Page withId = fakePage( "WithId",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: WithId\ntype: article\n", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( noId, withId ) );

        svc.rebuild();

        final var conflicts = svc.conflicts();
        assertEquals( 1, conflicts.size() );

        final var missing = conflicts.stream()
                .filter( c -> c.kind() == com.wikantik.api.structure.StructuralConflict.Kind.MISSING_CANONICAL_ID )
                .findFirst().orElseThrow();
        assertEquals( "NoId", missing.slug() );
    }
}
