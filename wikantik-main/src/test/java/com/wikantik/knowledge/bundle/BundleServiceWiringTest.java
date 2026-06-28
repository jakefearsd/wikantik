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
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.managers.PageManager;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BundleServiceWiringTest {

    private static CandidateSection sec( final String head ) {
        return new CandidateSection( "p", List.of( head ), head + " text", 0.5 );
    }

    @Test
    void rerankerDisabledByDefault_isIdentity_noNetwork() {
        // No 'enabled' key -> default OFF: identity reranker that returns the input untouched
        // (the 2026-06-13 live measurement showed the reranker is ordering-only, +1.5s, no recall).
        final SectionReranker r = BundleServiceWiring.rerankerFor( new Properties() );
        final List< CandidateSection > in = List.of( sec( "A" ), sec( "B" ) );
        assertSame( in, r.rerank( "q", in ), "disabled reranker must return the input list unchanged" );
    }

    @Test
    void rerankerEnabled_isLlmReranker() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "true" );
        assertInstanceOf( LlmSectionReranker.class, BundleServiceWiring.rerankerFor( p ),
            "enabled=true must select the LLM reranker" );
    }

    @Test
    void rerankerExplicitlyFalse_isIdentity() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "false" );
        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertEquals( in, BundleServiceWiring.rerankerFor( p ).rerank( "q", in ) );
    }

    @Test
    void sectionsPerPage_defaultsTo20WhenAbsentOrInvalid() {
        assertEquals( 20, BundleServiceWiring.sectionsPerPageFrom( null ) );
        assertEquals( 20, BundleServiceWiring.sectionsPerPageFrom( new Properties() ) );
        final Properties bad = new Properties();
        bad.setProperty( "wikantik.bundle.sections_per_page", "notanumber" );
        assertEquals( 20, BundleServiceWiring.sectionsPerPageFrom( bad ) );
        final Properties zero = new Properties();
        zero.setProperty( "wikantik.bundle.sections_per_page", "0" );
        assertEquals( 20, BundleServiceWiring.sectionsPerPageFrom( zero ), "non-positive falls back" );
    }

    @Test
    void sectionsPerPage_readsValidOverride() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.sections_per_page", "8" );
        assertEquals( 8, BundleServiceWiring.sectionsPerPageFrom( p ) );
    }

    /* ---------- build() ---------- */

    private static SectionCandidateSource denseWith( final String slug ) {
        return q -> List.of( new CandidateSection( slug, List.of( "H" ), "text", 0.9 ) );
    }

    private static ContextRetrievalService stubRetrieval() {
        final ContextRetrievalService r = mock( ContextRetrievalService.class );
        when( r.retrieve( any() ) ).thenReturn( new RetrievalResult( "q", List.of(), 0 ) );
        return r;
    }

    private static PageCanonicalIdsDao.Row row( final String canonical, final String slug ) {
        return new PageCanonicalIdsDao.Row( canonical, slug, "title", "article", "cluster", null, null );
    }

    @Test
    void build_nullRetrieval_returnsNull() {
        assertNull( BundleServiceWiring.build( null,
            Map.of( RetrievalMode.HYBRID, denseWith( "PageX" ), RetrievalMode.DENSE, denseWith( "PageX" ) ),
            null, null, new Properties() ),
            "no retrieval service → no bundle service" );
    }

    @Test
    void build_densePath_usesDenseSource_resolvesCanonicalAndVersion() {
        final ContextRetrievalService retrieval = mock( ContextRetrievalService.class );  // never touched on dense path
        final PageCanonicalIdsDao dao = mock( PageCanonicalIdsDao.class );
        when( dao.findBySlug( "PageX" ) ).thenReturn( Optional.of( row( "01X", "PageX" ) ) );
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getVersion() ).thenReturn( 3 );
        when( pm.getPage( "PageX" ) ).thenReturn( page );

        final BundleAssemblyService svc = BundleServiceWiring.build( retrieval,
            Map.of( RetrievalMode.HYBRID, denseWith( "PageX" ), RetrievalMode.DENSE, denseWith( "PageX" ) ),
            dao, pm, new Properties() );
        final ContextBundle b = svc.assemble( "q" );

        assertEquals( 1, b.sections().size() );
        assertEquals( "01X", b.sections().get( 0 ).canonicalId() );
        assertEquals( 3, b.sections().get( 0 ).citation().version() );
        verifyNoInteractions( retrieval );   // dense path must not fall through to page-gated retrieval
    }

    @Test
    void build_emptySourceMap_usesPageGated() {
        // Empty source map → page-gated fallback (RetrievalSectionSource wired with stubbed retrieval).
        final ContextRetrievalService retrieval = mock( ContextRetrievalService.class );
        when( retrieval.retrieve( any() ) ).thenReturn( new RetrievalResult( "q", List.of(), 0 ) );

        final ContextBundle b = BundleServiceWiring.build( retrieval, Map.of(), null, null, new Properties() ).assemble( "q" );

        assertTrue( b.sections().isEmpty() );
        verify( retrieval ).retrieve( any() );   // page-gated path exercised
    }

    @Test
    void build_nullSourceMap_usesPageGated() {
        // Null source map → page-gated fallback.
        final ContextRetrievalService retrieval = mock( ContextRetrievalService.class );
        when( retrieval.retrieve( any() ) ).thenReturn( new RetrievalResult( "q", List.of(), 0 ) );

        final BundleAssemblyService svc = BundleServiceWiring.build( retrieval, null, null, null, new Properties() );

        assertTrue( svc.assemble( "q" ).sections().isEmpty() );
        verify( retrieval ).retrieve( any() );
    }

    @Test
    void build_nullDao_skipsSectionLackingCanonical() {
        final ContextRetrievalService retrieval = mock( ContextRetrievalService.class );
        final BundleAssemblyService svc = BundleServiceWiring.build( retrieval,
            Map.of( RetrievalMode.HYBRID, denseWith( "PageX" ), RetrievalMode.DENSE, denseWith( "PageX" ) ),
            null, null, new Properties() );
        assertTrue( svc.assemble( "q" ).sections().isEmpty(),
            "null dao → canonicalIdOf empty → un-citable section skipped" );
    }

    @Test
    void build_nullPageManager_versionZero() {
        final ContextRetrievalService retrieval = mock( ContextRetrievalService.class );
        final PageCanonicalIdsDao dao = mock( PageCanonicalIdsDao.class );
        when( dao.findBySlug( "PageX" ) ).thenReturn( Optional.of( row( "01X", "PageX" ) ) );

        final ContextBundle b = BundleServiceWiring.build( retrieval,
            Map.of( RetrievalMode.HYBRID, denseWith( "PageX" ), RetrievalMode.DENSE, denseWith( "PageX" ) ),
            dao, null, new Properties() ).assemble( "q" );

        assertEquals( 1, b.sections().size() );
        assertEquals( 0, b.sections().get( 0 ).citation().version(), "null pageManager → version 0" );
    }

    @Test
    void build_pageManagerReturnsNull_versionZero() {
        final ContextRetrievalService retrieval = mock( ContextRetrievalService.class );
        final PageCanonicalIdsDao dao = mock( PageCanonicalIdsDao.class );
        when( dao.findBySlug( "PageX" ) ).thenReturn( Optional.of( row( "01X", "PageX" ) ) );
        final PageManager pm = mock( PageManager.class );
        when( pm.getPage( "PageX" ) ).thenReturn( null );

        final ContextBundle b = BundleServiceWiring.build( retrieval,
            Map.of( RetrievalMode.HYBRID, denseWith( "PageX" ), RetrievalMode.DENSE, denseWith( "PageX" ) ),
            dao, pm, new Properties() ).assemble( "q" );

        assertEquals( 0, b.sections().get( 0 ).citation().version(), "missing page → version 0" );
    }

    @Test
    void build_with_mode_map_routes_each_mode() {
        final SectionCandidateSource dense   = q -> List.of();
        final SectionCandidateSource lexical = q -> List.of();
        final var map = java.util.Map.of( RetrievalMode.HYBRID, dense, RetrievalMode.DENSE, dense,
                                          RetrievalMode.LEXICAL, lexical );
        final BundleAssemblyService svc = BundleServiceWiring.build(
            stubRetrieval(), map, null, null, new java.util.Properties() );
        assertNotNull( svc );
        // null/empty map → page-gated fallback, still non-null
        assertNotNull( BundleServiceWiring.build( stubRetrieval(), java.util.Map.of(), null, null, new java.util.Properties() ) );
    }
}
