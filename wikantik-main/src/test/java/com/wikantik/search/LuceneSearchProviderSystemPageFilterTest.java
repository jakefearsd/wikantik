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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
