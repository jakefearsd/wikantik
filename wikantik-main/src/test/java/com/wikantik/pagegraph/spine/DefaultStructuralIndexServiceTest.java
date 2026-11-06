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
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralFilter;
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

    /** Taxonomy defects must reach the same conflicts() surface as missing canonical_ids. */
    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_reports_taxonomy_conflicts_through_the_conflicts_surface() throws Exception {
        final Page orphan = fakePage( "Orphan",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN\n" +
                "title: Orphan\n" +
                "type: article\n" +
                "cluster: van-life", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( orphan ) );

        svc.rebuild();

        assertTrue( svc.conflicts().stream().anyMatch(
                c -> c.kind() == com.wikantik.api.pagegraph.StructuralConflict.Kind.HEADLESS_CLUSTER ),
                "expected a HEADLESS_CLUSTER conflict for van-life, got: " + svc.conflicts() );
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

        final org.mockito.ArgumentCaptor< com.wikantik.api.pagegraph.Verification > cap =
                org.mockito.ArgumentCaptor.forClass( com.wikantik.api.pagegraph.Verification.class );
        verify( verificationDao, times( 2 ) ).upsert( anyString(), cap.capture() );

        final var values = cap.getAllValues();
        assertTrue( values.stream().anyMatch(
                v -> v.confidence() == com.wikantik.api.pagegraph.Confidence.AUTHORITATIVE ),
                "alice is trusted → AUTHORITATIVE" );
        assertTrue( values.stream().anyMatch(
                v -> v.confidence() == com.wikantik.api.pagegraph.Confidence.PROVISIONAL ),
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

    /**
     * The index counts the tags the page draws. In this body (a differential-corpus input) the old line scan finds #t, but
     * the render draws no tag there; in the second body the render draws #t and the old scan does not.
     */
    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void inlineTagsAreTheRenderedOnes() throws Exception {
        final Page h = fakePage( "H", "canonical_id: 01CCCCCCCCCCCCCCCCCCCCCCCC\ntitle: H\ntype: article", "<span>#x</span> #y\n" );
        final Page a = fakePage( "A", "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article",
                "  -     #t  #t \n%%a<b t=\"`" );
        final Page b = fakePage( "B", "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: B\ntype: article",
                "a\\<b t=\"1.  | %%- \n\n#t $$$\n\\a\">" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b, h ) );

        svc.rebuild();

        assertEquals( List.of( "y" ), svc.getByCanonicalId( "01CCCCCCCCCCCCCCCCCCCCCCCC" ).orElseThrow().inlineTags(), "readable case" );
        assertEquals( List.of(), svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow().inlineTags() );
        assertEquals( List.of( "t" ), svc.getByCanonicalId( "01BBBBBBBBBBBBBBBBBBBBBBBB" ).orElseThrow().inlineTags() );
    }

    /** Obsidian phase 4: a page's tags for finding are its frontmatter tags and its inline #tags, one normalised set. */
    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void inline_tags_join_frontmatter_tags_in_one_normalised_set() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article\ntags: [Foo]",
                "Prose #foo and #bar/baz.\n%% #hidden %%\n`#code`\n" );
        final Page b = fakePage( "B",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: B\ntype: article", "No tag here." );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );

        svc.rebuild();

        final var desc = svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow();
        assertEquals( List.of( "Foo" ), desc.tags(), "the descriptor's tags stay as written (display, ontology, hints, chips)" );
        assertEquals( List.of( "foo", "bar/baz" ), desc.inlineTags() );
        assertEquals( List.of( "foo", "bar-baz" ), List.copyOf( desc.findableTags() ) );
        final var foo = svc.listTags( 1 ).stream().filter( t -> t.tag().equalsIgnoreCase( "foo" ) ).toList();
        assertEquals( 1, foo.size(), "frontmatter Foo and inline #foo are one tag" );
        assertEquals( 1, foo.get( 0 ).count(), "and the page counts once" );
        assertEquals( "Foo", foo.get( 0 ).tag(), "shown as the page wrote it in its frontmatter" );
        assertTrue( svc.listTags( 1 ).stream().noneMatch( t -> t.tag().equals( "hidden" ) || t.tag().equals( "code" ) ) );
        assertEquals( List.of( "A" ), slugs( "FOO" ) );
        assertEquals( List.of( "A" ), slugs( "Bar/Baz" ) );
    }

    /** Counts (and sample ids) cover only pages the caller may view: a restricted page's tags must never leak. */
    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void tag_counts_cover_only_the_viewable_pages() throws Exception {
        final Page open = fakePage( "Open",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article\ntags: [shared, fmonly]", "#shared" );
        final Page secret = fakePage( "Secret",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: B\ntype: article\ntags: [shared, fmsecret]", "#inlinesecret #shared" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( open, secret ) );
        svc.rebuild();

        final var all = svc.listTags( 1 );
        assertTrue( all.stream().anyMatch( t -> t.tag().equals( "inlinesecret" ) ), "the unfiltered dictionary has it" );

        final var visible = svc.listTags( 1, d -> d.slug().equals( "Open" ) );
        assertEquals( java.util.Set.of( "shared", "fmonly" ),
                visible.stream().map( com.wikantik.api.pagegraph.TagSummary::tag ).collect( java.util.stream.Collectors.toSet() ) );
        assertTrue( visible.stream().noneMatch( t -> t.tag().equals( "inlinesecret" ) || t.tag().equals( "fmsecret" ) ),
                "tags of a page the caller cannot view are absent, inline and frontmatter alike" );
        assertEquals( 1, visible.stream().filter( t -> t.tag().equals( "shared" ) ).findFirst().orElseThrow().count() );
        assertEquals( List.of( "01AAAAAAAAAAAAAAAAAAAAAAAA" ),
                visible.stream().filter( t -> t.tag().equals( "shared" ) ).findFirst().orElseThrow().topPageIds() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void frontmatter_tags_written_with_a_slash_or_capitals_stay_as_written_on_the_descriptor() throws Exception {
        final Page a = fakePage( "A", "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article\ntags: [\"a/b\", Foo]", "x" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();
        assertEquals( List.of( "a/b", "Foo" ), svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow().tags() );
        assertEquals( List.of( "A" ), slugs( "a/b" ), "yet the filter finds it by the normalised form" );
        assertEquals( List.of( "A" ), slugs( "FOO" ) );
    }

    private List< String > slugs( final String tag ) {
        return svc.listPagesByFilter( new StructuralFilter( Optional.empty(), Optional.empty(), List.of( tag ),
                        Optional.empty(), 10, Optional.empty() ) )
                .stream().map( com.wikantik.api.pagegraph.PageDescriptor::slug ).toList();
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void inline_tags_are_refreshed_on_an_incremental_save() throws Exception {
        final Page a = fakePage( "A", "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "#old" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();
        final Page updated = fakePage( "A", "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "#fresh" );
        when( pageManager.getPage( "A" ) ).thenReturn( updated );

        svc.onPageSaved( "A" );

        assertEquals( List.of( "fresh" ), List.copyOf( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow().findableTags() ) );
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
                .filter( c -> c.kind() == com.wikantik.api.pagegraph.StructuralConflict.Kind.MISSING_CANONICAL_ID )
                .findFirst().orElseThrow();
        assertEquals( "NoId", missing.slug() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_marks_descriptor_derived_when_frontmatter_has_derived_from() throws Exception {
        final Page derivedPage = fakePage( "Ingested",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: Ingested\ntype: article\nderived_from: source.pdf", "" );
        final Page authoredPage = fakePage( "HandWritten",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: HandWritten\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( derivedPage, authoredPage ) );

        svc.rebuild();

        assertTrue( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow().derived() );
        assertFalse( svc.getByCanonicalId( "01BBBBBBBBBBBBBBBBBBBBBBBB" ).orElseThrow().derived() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageSaved_marks_descriptor_derived_when_frontmatter_has_derived_from() throws Exception {
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();

        final Page ingested = fakePage( "Ingested",
                "canonical_id: 01CCCCCCCCCCCCCCCCCCCCCCCC\n" +
                "title: Ingested\ntype: article\nderived_from: source.pdf", "" );
        when( pageManager.getPage( "Ingested" ) ).thenReturn( ingested );

        svc.onPageSaved( "Ingested" );

        assertTrue( svc.getByCanonicalId( "01CCCCCCCCCCCCCCCCCCCCCCCC" ).orElseThrow().derived() );
        assertFalse( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow().derived() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void titleLookup_isEmptyUntilTheFirstRebuild_thenServesTitlesAndAliases() throws Exception {
        assertTrue( svc.titleLookup().isEmpty(), "warming: no lookup before the first rebuild" );
        final Page k = fakePage( "Kubernetes",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8K8\ntitle: Kubernetes\naliases: [k8s, kube]", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( k ) );
        svc.rebuild();
        assertEquals( List.of( "Kubernetes" ),
                svc.titleLookup().orElseThrow().rank( List.of( "Kubernetes", "Other" ), "kube" ) );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void titleLookup_followsSavesAndDeletes() throws Exception {
        final Page a = fakePage( "AlphaPage", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A1\ntitle: Alpha", "b" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();
        assertTrue( svc.titleLookup().orElseThrow().rank( List.of( "AlphaPage" ), "zeta" ).isEmpty() );

        final Page a2 = fakePage( "AlphaPage",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A1\ntitle: Alpha\naliases: [zeta]", "b" );
        when( pageManager.getPage( "AlphaPage" ) ).thenReturn( a2 );
        svc.onPageSaved( "AlphaPage" );
        assertEquals( List.of( "AlphaPage" ), svc.titleLookup().orElseThrow().rank( List.of( "AlphaPage" ), "zeta" ) );

        svc.onPageDeleted( "AlphaPage" );
        assertTrue( svc.titleLookup().orElseThrow().entries().stream().noneMatch( e -> e.slug().equals( "AlphaPage" ) ) );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void titleLookup_aBodyOnlySaveKeepsTheSameIndexWhileATitleChangeReplacesIt() throws Exception {
        final Page a = fakePage( "AlphaPage", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A1\ntitle: Alpha\naliases: [zeta]", "b" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();
        final var before = svc.titleLookup().orElseThrow();

        final Page edited = fakePage( "AlphaPage", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A1\ntitle: Alpha\naliases: [zeta]", "new body" );
        when( pageManager.getPage( "AlphaPage" ) ).thenReturn( edited );
        svc.onPageSaved( "AlphaPage" );
        assertSame( before, svc.titleLookup().orElseThrow(),
                "titles and aliases unchanged: the same index, so the wikilink resolver keeps its folded maps" );

        final Page retitled = fakePage( "AlphaPage", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A1\ntitle: Omega\naliases: [zeta]", "b" );
        when( pageManager.getPage( "AlphaPage" ) ).thenReturn( retitled );
        svc.onPageSaved( "AlphaPage" );
        final var after = svc.titleLookup().orElseThrow();
        assertNotSame( before, after );
        assertEquals( List.of( "AlphaPage" ), after.rank( List.of( "AlphaPage" ), "omega" ) );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void titleLookup_renameUnderTheSameCanonicalIdDropsTheOldSlug() throws Exception {
        final Page oldPage = fakePage( "OldName", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8R1\naliases: [legacy]", "b" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( oldPage ) );
        svc.rebuild();
        final Page renamed = fakePage( "NewName", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8R1\naliases: [legacy]", "b" );
        when( pageManager.getPage( "NewName" ) ).thenReturn( renamed );
        svc.onPageSaved( "NewName" );
        final var slugs = svc.titleLookup().orElseThrow().entries().stream()
                .map( com.wikantik.api.pagegraph.PageTitleLookup.TitleEntry::slug ).toList();
        assertTrue( slugs.contains( "NewName" ), slugs.toString() );
        assertFalse( slugs.contains( "OldName" ), slugs.toString() );
    }
}
