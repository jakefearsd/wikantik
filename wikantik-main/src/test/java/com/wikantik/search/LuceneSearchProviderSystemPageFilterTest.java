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
package com.wikantik.search;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.test.StubSystemPageRegistry;
import org.apache.lucene.analysis.classic.ClassicAnalyzer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Unit tests verifying that {@link LuceneSearchProvider} excludes system pages
 * (CSS themes, navigation fragments, layout templates) from the Lucene index
 * at every entry point. System pages pollute search results and downstream
 * RAG retrieval.
 *
 * <p>Covers the three filter points:
 * <ol>
 *   <li>{@link LuceneSearchProvider#reindexPage(Page)} — short-circuits without enqueuing</li>
 *   <li>{@link LuceneSearchProvider#updateLuceneIndex(Page, String)} — defense-in-depth
 *       filter on the drain path; returns {@code false} without writing to the index</li>
 *   <li>{@link LuceneSearchProvider#isSystemPageExcluded(String)} used by
 *       {@link LuceneSearchProvider#doFullLuceneReindex()} iteration</li>
 * </ol>
 */
class LuceneSearchProviderSystemPageFilterTest {

    private static LuceneSearchProvider newProviderWithSystemPages( final PageManager pm,
                                                                    final String... systemPages ) {
        final StubSystemPageRegistry spr = new StubSystemPageRegistry();
        for ( final String name : systemPages ) {
            spr.addSystemPage( name );
        }
        return new LuceneSearchProvider( pm, Mockito.mock( AttachmentManager.class ),
                null, null, spr );
    }

    private static void setField( final Object target, final String fieldName, final Object value ) {
        try {
            final Field field = target.getClass().getDeclaredField( fieldName );
            field.setAccessible( true );
            field.set( target, value );
        } catch( final Exception e ) {
            throw new RuntimeException( "Failed to set field " + fieldName, e );
        }
    }

    // =========================================================================
    // reindexPage(): must not enqueue system pages
    // =========================================================================

    @Test
    void testReindexPageSkipsSystemPage() {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = newProviderWithSystemPages( pm, "LeftMenu" );

        final Page sysPage = Mockito.mock( Page.class );
        Mockito.when( sysPage.getName() ).thenReturn( "LeftMenu" );
        Mockito.when( pm.getPureText( sysPage ) ).thenReturn( "* [Main]" );

        provider.reindexPage( sysPage );

        Assertions.assertEquals( 0, provider.getReindexQueueDepth(),
                "System pages must not be enqueued for reindexing" );
    }

    @Test
    void testReindexPageStillEnqueuesRegularPages() {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = newProviderWithSystemPages( pm, "LeftMenu" );

        final Page regularPage = Mockito.mock( Page.class );
        Mockito.when( regularPage.getName() ).thenReturn( "RegularArticle" );
        Mockito.when( pm.getPureText( regularPage ) ).thenReturn( "body" );

        provider.reindexPage( regularPage );

        Assertions.assertEquals( 1, provider.getReindexQueueDepth(),
                "Non-system pages must still be enqueued" );
    }

    // =========================================================================
    // updateLuceneIndex(): must return false for system pages without writing
    // =========================================================================

    @Test
    void testUpdateLuceneIndexReturnsFalseForSystemPage() {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = newProviderWithSystemPages( pm, "MoreMenu" );

        final Page sysPage = Mockito.mock( Page.class );
        Mockito.when( sysPage.getName() ).thenReturn( "MoreMenu" );

        // Should return false (skipped) without touching the Lucene index.
        // luceneDirectory is null because initialize() was not called; the
        // skip-path must run before any Directory is opened.
        final boolean written = provider.updateLuceneIndex( sysPage, "irrelevant body" );

        Assertions.assertFalse( written,
                "updateLuceneIndex must return false without writing when the page is a system page" );
    }

    // =========================================================================
    // doFullLuceneReindex() iteration filter: verified indirectly via a helper
    // predicate that the production code uses at the iteration site.
    // =========================================================================

    @Test
    void testIsSystemPageExcludedReflectsRegistry() {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = newProviderWithSystemPages( pm,
                "LeftMenu", "PageHeader" );

        Assertions.assertTrue( provider.isSystemPageExcluded( "LeftMenu" ),
                "Registered system page must be excluded from full reindex iteration" );
        Assertions.assertTrue( provider.isSystemPageExcluded( "PageHeader" ),
                "Registered system page must be excluded from full reindex iteration" );
        Assertions.assertFalse( provider.isSystemPageExcluded( "RegularArticle" ),
                "Non-system page must not be excluded from full reindex iteration" );
    }

    // =========================================================================
    // indexMissingPages(): must not re-index system pages on the periodic sweep
    // =========================================================================

    @Test
    void testIndexMissingPagesSkipsSystemPages( @TempDir final File tempDir ) throws Exception {
        final File luceneDir = new File( tempDir, "lucene" );
        Assertions.assertTrue( luceneDir.mkdirs() );

        final PageManager pm = Mockito.mock( PageManager.class );
        final AttachmentManager am = Mockito.mock( AttachmentManager.class );
        final StubSystemPageRegistry spr = new StubSystemPageRegistry();
        spr.addSystemPage( "LeftMenu" );
        final LuceneSearchProvider provider = new LuceneSearchProvider( pm, am, null, null, spr );
        setField( provider, "luceneDirectory", luceneDir.getAbsolutePath() );
        setField( provider, "analyzer", new ClassicAnalyzer() );
        setField( provider, "searchExecutor", Executors.newCachedThreadPool() );

        // Seed the Lucene index with one unrelated page so dir.list() is non-empty
        // (otherwise indexMissingPages short-circuits before checking pages).
        final Page seed = Mockito.mock( Page.class );
        Mockito.when( seed.getName() ).thenReturn( "SeedPage" );
        Mockito.when( am.listAttachments( Mockito.any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        provider.updateLuceneIndex( seed, "seed content" );

        // One regular page + one system page, both "missing" from the index.
        final Page regular = Mockito.mock( Page.class );
        Mockito.when( regular.getName() ).thenReturn( "RegularMissing" );
        final Page system = Mockito.mock( Page.class );
        Mockito.when( system.getName() ).thenReturn( "LeftMenu" );

        Mockito.when( pm.getAllPages() ).thenReturn( List.of( regular, system ) );
        Mockito.when( pm.getPageText( Mockito.eq( "RegularMissing" ), Mockito.anyInt() ) )
                .thenReturn( "regular body" );
        // If the filter fails, this stub would be consulted for the system page
        // too — we leave it unstubbed so the test would fail with a NPE / absent
        // content assertion if that ever happens.
        Mockito.when( am.getAllAttachments() ).thenReturn( Collections.emptyList() );

        final int indexed = provider.indexMissingPages();

        Assertions.assertEquals( 1, indexed,
                "Only the regular page should be indexed; the system page must be skipped" );
        Assertions.assertTrue( provider.getIndexedPageNames().contains( "RegularMissing" ),
                "Regular page should end up in the Lucene index" );
        Assertions.assertFalse( provider.getIndexedPageNames().contains( "LeftMenu" ),
                "System page must NOT be indexed by the missing-page sweep" );
    }

    // =========================================================================
    // Drain-loop counter: skips must not be counted as failures
    // =========================================================================

    @Test
    @SuppressWarnings( "unchecked" )
    void testDrainUpdateQueueDoesNotCountSystemPageSkipAsFailure() throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        final LuceneSearchProvider provider = newProviderWithSystemPages( pm, "LeftMenu" );

        // Enqueue a system page directly, bypassing reindexPage's filter, to
        // simulate the "enqueued via a non-filtered route" scenario the drain
        // loop must handle without counting the skip as a failure.
        final Page sysPage = Mockito.mock( Page.class );
        Mockito.when( sysPage.getName() ).thenReturn( "LeftMenu" );
        final Field updatesField = LuceneSearchProvider.class.getDeclaredField( "updates" );
        updatesField.setAccessible( true );
        final List< Object[] > updates = ( List< Object[] > ) updatesField.get( provider );
        updates.add( new Object[] { sysPage, "fragment body" } );

        final LuceneSearchProvider.DrainStats stats = provider.drainUpdateQueue();

        Assertions.assertEquals( 1, stats.totalQueued(), "Queue had one item" );
        Assertions.assertEquals( 0, stats.indexed(),
                "System page must not be indexed" );
        Assertions.assertEquals( 0, stats.failed(),
                "System-page skips MUST NOT be counted as failures — this was the bug" );
        Assertions.assertEquals( 1, stats.skipped(),
                "System page should be counted as skipped, not failed" );
    }

    @Test
    void testIsSystemPageExcludedIsFalseWhenNoRegistry() {
        // Defensive: if no SystemPageRegistry is wired (e.g. old callers of the
        // 4-arg test constructor), the filter must be a no-op rather than blowing up.
        final LuceneSearchProvider provider = new LuceneSearchProvider(
                Mockito.mock( PageManager.class ),
                Mockito.mock( AttachmentManager.class ),
                null, null );

        Assertions.assertFalse( provider.isSystemPageExcluded( "AnyPage" ),
                "With no registry wired, nothing should be treated as a system page" );
    }
}
