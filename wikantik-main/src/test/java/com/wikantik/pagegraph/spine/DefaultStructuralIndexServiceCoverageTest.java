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
import com.wikantik.api.pagegraph.Verification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the corners of {@link DefaultStructuralIndexService} that
 * {@code DefaultStructuralIndexServiceTest} doesn't reach: the simple read
 * delegates that are never exercised there, the various failure-resilience
 * catch blocks (rebuild-time page enumeration/parsing, DAO writes on save and
 * delete, verification persistence, the metrics sink itself), the two
 * secondary constructors, and the {@code parseInstant}/{@code stringList}/
 * {@code parseKgInclude} private helpers exercised end to end through
 * frontmatter values.
 */
class DefaultStructuralIndexServiceCoverageTest {

    private PageManager pageManager;
    private PageCanonicalIdsDao dao;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        dao = mock( PageCanonicalIdsDao.class );
    }

    private Page fakePage( final String name, final String frontmatter, final String body ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );
        when( pageManager.getPureText( p ) ).thenReturn( "---\n" + frontmatter + "\n---\n" + body );
        return p;
    }

    // -----------------------------------------------------------------------
    // Secondary constructors
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void threeArgConstructor_withExplicitMetrics_rebuildsNormally() throws Exception {
        final StructuralIndexMetrics metrics = new StructuralIndexMetrics();
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao, metrics );

        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );

        svc.rebuild();

        assertEquals( 1, svc.health().pages() );
    }

    // -----------------------------------------------------------------------
    // rebuild() — pageManager.getAllPages() throws -> DEGRADED, no exception escapes
    // -----------------------------------------------------------------------

    @Test
    void rebuild_pageManagerThrowsEnumeratingPages_marksDegraded() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        when( pageManager.getAllPages() ).thenThrow( new RuntimeException( "provider offline" ) );

        assertDoesNotThrow( svc::rebuild );

        assertEquals( com.wikantik.api.pagegraph.IndexHealth.Status.DEGRADED, svc.health().status() );
    }

    // -----------------------------------------------------------------------
    // rebuild() — a single page's parse/index step throws; the rest still index
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_onePageThrowsWhileIndexing_othersStillIndexed() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );

        final Page good = fakePage( "Good",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: Good\ntype: article", "" );
        final Page bad = mock( Page.class );
        when( bad.getName() ).thenReturn( "Bad" );
        when( pageManager.getPureText( bad ) ).thenThrow( new RuntimeException( "read failure" ) );

        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( good, bad ) );

        svc.rebuild();

        assertTrue( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isPresent() );
        assertEquals( com.wikantik.api.pagegraph.IndexHealth.Status.UP, svc.health().status() );
        assertEquals( 1, svc.snapshot().pageCount(), "the failing page must not be indexed" );
    }

    // -----------------------------------------------------------------------
    // Simple read delegates — never exercised in the main test class
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void simpleReadDelegates_forwardToTheCurrentProjection() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: A\ntype: article\ncluster: some-cluster\ntags: [t1]", "" );
        final Page hub = fakePage( "Hub",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\n" +
                "title: Hub\ntype: hub\ncluster: some-cluster", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, hub ) );

        svc.rebuild();

        assertTrue( svc.getCluster( "some-cluster" ).isPresent() );
        assertTrue( svc.getCluster( "no-such-cluster" ).isEmpty() );

        assertFalse( svc.listTags( 0 ).isEmpty() );

        final var articles = svc.listPagesByType( PageType.ARTICLE );
        assertEquals( 1, articles.size() );
        assertEquals( "A", articles.get( 0 ).slug() );

        assertNotNull( svc.sitemap() );

        assertTrue( svc.resolveSlugFromCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isPresent() );
        assertEquals( "A", svc.resolveSlugFromCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow() );
        assertTrue( svc.resolveSlugFromCanonicalId( "nonexistent" ).isEmpty() );

        assertNotNull( svc.snapshot().generatedAt() );

        assertTrue( svc.resolveCanonicalIdFromSlug( "A" ).isPresent() );
        assertEquals( "01AAAAAAAAAAAAAAAAAAAAAAAA", svc.resolveCanonicalIdFromSlug( "A" ).orElseThrow() );
        assertTrue( svc.resolveCanonicalIdFromSlug( "NoSuchSlug" ).isEmpty() );

        assertNotNull( svc.confidenceComputer() );
    }

    // -----------------------------------------------------------------------
    // applyIncrementalUpdate — onPageSaved for a page still missing
    // canonical_id must synthesise one and record a conflict, mirroring
    // rebuild()'s own missing-canonical_id handling.
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageSaved_pageStillMissingCanonicalId_synthesisesIdAndRecordsConflict() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();

        final Page untitled = fakePage( "Untitled", "title: Untitled\ntype: article", "" );
        when( pageManager.getPage( "Untitled" ) ).thenReturn( untitled );

        svc.onPageSaved( "Untitled" );

        assertEquals( 2, svc.snapshot().pageCount() );
        assertTrue( svc.conflicts().stream().anyMatch(
                c -> "Untitled".equals( c.slug() )
                     && c.kind() == com.wikantik.api.pagegraph.StructuralConflict.Kind.MISSING_CANONICAL_ID ) );
    }

    // -----------------------------------------------------------------------
    // parseInstant — the pass-through branch for an already-Instant value.
    // Frontmatter parsing never actually produces a raw Instant (SnakeYAML
    // gives a Date or a String), so this defensive branch is exercised
    // directly via reflection.
    // -----------------------------------------------------------------------

    @Test
    void parseInstant_passesThroughAnAlreadyParsedInstant() throws Exception {
        final java.lang.reflect.Method m = DefaultStructuralIndexService.class
                .getDeclaredMethod( "parseInstant", Object.class );
        m.setAccessible( true );
        final java.time.Instant now = java.time.Instant.now();
        assertEquals( now, m.invoke( null, now ) );
    }

    // -----------------------------------------------------------------------
    // verificationOf — all three branches
    // -----------------------------------------------------------------------

    @Test
    void verificationOf_noVerificationDaoWired_returnsEmpty() {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        assertTrue( svc.verificationOf( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isEmpty() );
    }

    @Test
    void verificationOf_blankCanonicalId_returnsEmpty() {
        final PageVerificationDao verificationDao = mock( PageVerificationDao.class );
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService(
                pageManager, dao, verificationDao, null, new StructuralIndexMetrics() );

        assertTrue( svc.verificationOf( "" ).isEmpty() );
        assertTrue( svc.verificationOf( null ).isEmpty() );
    }

    @Test
    void verificationOf_delegatesToVerificationDao() {
        final PageVerificationDao verificationDao = mock( PageVerificationDao.class );
        final Verification v = new Verification( java.time.Instant.now(), "alice",
                com.wikantik.api.pagegraph.Confidence.AUTHORITATIVE, com.wikantik.api.pagegraph.Audience.HUMANS );
        when( verificationDao.findByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ) ).thenReturn( Optional.of( v ) );
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService(
                pageManager, dao, verificationDao, null, new StructuralIndexMetrics() );

        assertEquals( Optional.of( v ), svc.verificationOf( "01AAAAAAAAAAAAAAAAAAAAAAAA" ) );
    }

    // -----------------------------------------------------------------------
    // onPageSaved — page vanished (null) / pageManager throws
    // -----------------------------------------------------------------------

    @Test
    void onPageSaved_pageNoLongerExists_isANoOp() {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        when( pageManager.getPage( "Ghost" ) ).thenReturn( null );

        assertDoesNotThrow( () -> svc.onPageSaved( "Ghost" ) );
        assertEquals( 0, svc.snapshot().pageCount() );
    }

    @Test
    void onPageSaved_pageManagerThrows_isCaughtAndLogged() {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        when( pageManager.getPage( "Boom" ) ).thenThrow( new RuntimeException( "provider offline" ) );

        assertDoesNotThrow( () -> svc.onPageSaved( "Boom" ) );
    }

    // -----------------------------------------------------------------------
    // persistCanonicalId — dao.upsert throws RuntimeException; in-memory
    // projection still carries the page (SKIPPED_STALE_SLUG_OWNER fallback).
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_daoUpsertThrows_pageStillIndexedInMemory() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        when( dao.upsert( any(), any(), any(), any(), any() ) )
                .thenThrow( new RuntimeException( "connection reset" ) );

        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );

        svc.rebuild();

        assertTrue( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isPresent(),
                "a DAO failure must not roll back the in-memory projection" );
    }

    // -----------------------------------------------------------------------
    // onPageDeleted — normal two-page delete (keeps the other page), a DAO
    // failure that's swallowed, and a metrics failure that reaches the
    // OUTER catch (proving applyIncrementalDelete's un-guarded tail is safe).
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageDeleted_removesOnlyTheTargetPage() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        final Page b = fakePage( "B",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: B\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );
        svc.rebuild();

        svc.onPageDeleted( "A" );

        assertTrue( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isEmpty() );
        assertTrue( svc.getByCanonicalId( "01BBBBBBBBBBBBBBBBBBBBBBBB" ).isPresent(),
                "the other page must survive the delete" );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageDeleted_unknownSlug_isANoOp() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        assertDoesNotThrow( () -> svc.onPageDeleted( "NeverExisted" ) );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageDeleted_daoDeleteThrows_isCaughtAndProjectionStillUpdated() throws Exception {
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService( pageManager, dao );
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();

        doThrow( new RuntimeException( "connection reset" ) ).when( dao ).delete( anyString() );

        assertDoesNotThrow( () -> svc.onPageDeleted( "A" ) );
        assertTrue( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isEmpty(),
                "in-memory projection must still drop the page even if the DAO write failed" );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageSaved_metricsUpdateThrows_isCaughtByOuterCatch() throws Exception {
        final StructuralIndexMetrics metrics = mock( StructuralIndexMetrics.class );
        // First call (inside rebuild()) succeeds; the second (inside the incremental
        // update triggered by onPageSaved) throws, exercising onPageSaved's own
        // try/catch around applyIncrementalUpdate's unguarded tail.
        doNothing().doThrow( new RuntimeException( "metrics sink down" ) )
                .when( metrics ).update( any(), any() );

        final DefaultStructuralIndexService svc =
                new DefaultStructuralIndexService( pageManager, dao, null, null, metrics );
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();

        final Page aUpdated = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A v2\ntype: article", "" );
        when( pageManager.getPage( "A" ) ).thenReturn( aUpdated );

        assertDoesNotThrow( () -> svc.onPageSaved( "A" ) );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void onPageDeleted_metricsUpdateThrows_isCaughtByOuterCatch() throws Exception {
        final StructuralIndexMetrics metrics = mock( StructuralIndexMetrics.class );
        doNothing().doThrow( new RuntimeException( "metrics sink down" ) )
                .when( metrics ).update( any(), any() );

        final DefaultStructuralIndexService svc =
                new DefaultStructuralIndexService( pageManager, dao, null, null, metrics );
        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: A\ntype: article", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();

        assertDoesNotThrow( () -> svc.onPageDeleted( "A" ) );
    }

    // -----------------------------------------------------------------------
    // persistVerification — verificationDao.upsert throws, caught and logged
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_verificationUpsertThrows_isCaughtAndLogged() throws Exception {
        final PageVerificationDao verificationDao = mock( PageVerificationDao.class );
        doThrow( new RuntimeException( "connection reset" ) )
                .when( verificationDao ).upsert( anyString(), any() );
        final ConfidenceComputer computer = new ConfidenceComputer( name -> false );
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService(
                pageManager, dao, verificationDao, computer, new StructuralIndexMetrics() );

        final Page a = fakePage( "A",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n" +
                "title: A\ntype: article\nverified_at: 2024-01-01T00:00:00Z\nverified_by: alice", "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );

        assertDoesNotThrow( svc::rebuild );
        assertTrue( svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).isPresent() );
    }

    // -----------------------------------------------------------------------
    // parseInstant / stringList / parseKgInclude — exercised end to end
    // through rebuild()'s frontmatter mapping.
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void rebuild_parsesVariousVerifiedAtAndFrontmatterShapes() throws Exception {
        final PageVerificationDao verificationDao = mock( PageVerificationDao.class );
        final ConfidenceComputer computer = new ConfidenceComputer( name -> false );
        final DefaultStructuralIndexService svc = new DefaultStructuralIndexService(
                pageManager, dao, verificationDao, computer, new StructuralIndexMetrics() );

        // No verified_at at all -> parseInstant(null).
        final Page noVerifiedAt = fakePage( "NoVerifiedAt",
                "canonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\ntitle: NoVerifiedAt\ntype: article\n" +
                "tags: solo-tag\nkg_include: true", "" );

        // Quoted ISO instant string (kept as a String by SnakeYAML) -> Instant.parse success.
        final Page isoString = fakePage( "IsoString",
                "canonical_id: 01BBBBBBBBBBBBBBBBBBBBBBBB\ntitle: IsoString\ntype: article\n" +
                "verified_at: \"2024-03-01T12:00:00Z\"\nverified_by: bob\nkg_include: \"maybe\"", "" );

        // Quoted local-date-time (no trailing Z) -> falls back to LocalDateTime parse.
        final Page localDateTime = fakePage( "LocalDateTime",
                "canonical_id: 01CCCCCCCCCCCCCCCCCCCCCCCC\ntitle: LocalDateTime\ntype: article\n" +
                "verified_at: \"2024-03-01T12:00:00\"\nverified_by: carol", "" );

        // Quoted garbage -> both parses fail, verifiedAt ends up null, no exception.
        final Page garbage = fakePage( "Garbage",
                "canonical_id: 01DDDDDDDDDDDDDDDDDDDDDDDD\ntitle: Garbage\ntype: article\n" +
                "verified_at: \"not-a-date\"\nverified_by: dave", "" );

        // Quoted empty string -> isEmpty() branch.
        final Page emptyVerifiedAt = fakePage( "EmptyVerifiedAt",
                "canonical_id: 01EEEEEEEEEEEEEEEEEEEEEEEE\ntitle: EmptyVerifiedAt\ntype: article\n" +
                "verified_at: \"\"\nverified_by: erin", "" );

        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of(
                noVerifiedAt, isoString, localDateTime, garbage, emptyVerifiedAt ) );

        assertDoesNotThrow( svc::rebuild );

        // stringList's scalar (non-list) branch: "tags: solo-tag" -> single-element list.
        assertEquals( List.of( "solo-tag" ),
                svc.getByCanonicalId( "01AAAAAAAAAAAAAAAAAAAAAAAA" ).orElseThrow().tags() );

        final var cap = org.mockito.ArgumentCaptor.forClass( Verification.class );
        verify( verificationDao, times( 5 ) ).upsert( anyString(), cap.capture() );
        final var byVerifiedBy = cap.getAllValues();

        // IsoString's verified_by=bob must have a non-null parsed Instant.
        assertTrue( byVerifiedBy.stream().anyMatch(
                v -> "bob".equals( v.verifiedBy() ) && v.verifiedAt() != null ) );
        // LocalDateTime's fallback parse must also succeed (non-null Instant).
        assertTrue( byVerifiedBy.stream().anyMatch(
                v -> "carol".equals( v.verifiedBy() ) && v.verifiedAt() != null ) );
        // Garbage and the empty string must both yield a null verifiedAt without throwing.
        assertTrue( byVerifiedBy.stream().anyMatch(
                v -> "dave".equals( v.verifiedBy() ) && v.verifiedAt() == null ) );
        assertTrue( byVerifiedBy.stream().anyMatch(
                v -> "erin".equals( v.verifiedBy() ) && v.verifiedAt() == null ) );
    }
}
