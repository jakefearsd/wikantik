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
package org.apache.wiki.search;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.wiki.HttpMockFactory;
import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.search.SearchResult;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.pages.PageManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;


/**
 * Unit tests for LuceneSearchProvider, particularly focused on the missing pages functionality
 * and empty query handling.
 */
class LuceneSearchProviderTest {

    private TestEngine m_engine;
    private SearchManager m_mgr;
    private LuceneSearchProvider m_provider;

    @BeforeEach
    void setUp() {
        final Properties props = TestEngine.getTestProperties();
        final String workDir = props.getProperty( "jspwiki.workDir" );
        final String workRepo = props.getProperty( "jspwiki.fileSystemProvider.pageDir" );

        // Use unique directories for each test run
        final long timestamp = System.currentTimeMillis();
        props.setProperty( SearchManager.PROP_SEARCHPROVIDER, "LuceneSearchProvider" );
        props.setProperty( "jspwiki.lucene.indexdelay", "0" );
        props.setProperty( "jspwiki.lucene.initialdelay", "0" );
        props.setProperty( "jspwiki.workDir", workDir + timestamp );
        props.setProperty( "jspwiki.fileSystemProvider.pageDir", workRepo + timestamp );

        m_engine = TestEngine.build( props );
        m_mgr = m_engine.getManager( SearchManager.class );
        m_provider = (LuceneSearchProvider) m_mgr.getSearchEngine();
    }

    @AfterEach
    void tearDown() {
        m_engine.stop();
    }

    /**
     * Helper to wait for search results.
     */
    private Callable<Boolean> findsResultsFor( final Collection<SearchResult> res, final String text ) {
        return () -> {
            final HttpServletRequest request = HttpMockFactory.createHttpRequest();
            final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
            final Collection<SearchResult> search = m_mgr.findPages( text, ctx );
            if( search != null && search.size() > 0 ) {
                res.addAll( search );
                return true;
            }
            return false;
        };
    }

    // =========================================================================
    // Tests for empty/blank query handling
    // =========================================================================

    @Test
    void testEmptyQueryReturnsEmptyResults() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );

        // Empty string should return empty results, not throw exception
        Collection<SearchResult> results = m_mgr.findPages( "", ctx );
        Assertions.assertNotNull( results, "Results should not be null for empty query" );
        Assertions.assertTrue( results.isEmpty(), "Results should be empty for empty query" );

        // Whitespace-only should also return empty results
        results = m_mgr.findPages( "   ", ctx );
        Assertions.assertNotNull( results, "Results should not be null for whitespace query" );
        Assertions.assertTrue( results.isEmpty(), "Results should be empty for whitespace query" );

        // Null-like strings
        results = m_mgr.findPages( "\t\n", ctx );
        Assertions.assertNotNull( results, "Results should not be null for tab/newline query" );
        Assertions.assertTrue( results.isEmpty(), "Results should be empty for tab/newline query" );
    }

    // =========================================================================
    // Tests for getIndexedPageNames()
    // =========================================================================

    @Test
    void testGetIndexedPageNamesReturnsEmptySetWhenNoIndex() {
        // Before any pages are created, the index should be empty or not exist
        // The method should return an empty set, not throw
        final Set<String> indexedPages = m_provider.getIndexedPageNames();
        Assertions.assertNotNull( indexedPages, "Should return non-null set" );
        // May or may not be empty depending on timing, but should not throw
    }

    @Test
    void testGetIndexedPageNamesReturnsIndexedPages() throws Exception {
        // Create a page through the wiki engine
        final String pageName = "IndexedTestPage";
        final String content = "This is content for the indexed test page.";
        m_engine.saveText( pageName, content );

        // Wait for it to be searchable (which means it's indexed)
        final Collection<SearchResult> res = new ArrayList<>();
        Awaitility.await( "waiting for page to be indexed" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( findsResultsFor( res, "indexed" ) );

        // Now getIndexedPageNames should include our page
        final Set<String> indexedPages = m_provider.getIndexedPageNames();
        Assertions.assertTrue( indexedPages.contains( pageName ),
                "Indexed pages should contain '" + pageName + "'" );

        m_engine.deleteTestPage( pageName );
    }

    @Test
    void testGetIndexedPageNamesReturnsMultiplePages() throws Exception {
        // Create multiple pages
        m_engine.saveText( "PageOne", "Content for page one unique1234" );
        m_engine.saveText( "PageTwo", "Content for page two unique1234" );
        m_engine.saveText( "PageThree", "Content for page three unique1234" );

        // Wait for indexing - search for unique term to ensure all are indexed
        final Collection<SearchResult> res = new ArrayList<>();
        Awaitility.await( "waiting for pages to be indexed" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( () -> {
                    final HttpServletRequest request = HttpMockFactory.createHttpRequest();
                    final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
                    final Collection<SearchResult> search = m_mgr.findPages( "unique1234", ctx );
                    return search != null && search.size() >= 3;
                } );

        final Set<String> indexedPages = m_provider.getIndexedPageNames();
        Assertions.assertTrue( indexedPages.contains( "PageOne" ), "Should contain PageOne" );
        Assertions.assertTrue( indexedPages.contains( "PageTwo" ), "Should contain PageTwo" );
        Assertions.assertTrue( indexedPages.contains( "PageThree" ), "Should contain PageThree" );

        m_engine.deleteTestPage( "PageOne" );
        m_engine.deleteTestPage( "PageTwo" );
        m_engine.deleteTestPage( "PageThree" );
    }

    // =========================================================================
    // Tests for indexMissingPages()
    // =========================================================================

    @Test
    void testIndexMissingPagesReturnsZeroWhenNoIndex() {
        // When there's no index at all, indexMissingPages should return 0
        // (full reindex handles that case)
        final int indexed = m_provider.indexMissingPages();
        Assertions.assertEquals( 0, indexed, "Should return 0 when no index exists" );
    }

    @Test
    void testIndexMissingPagesReturnsZeroWhenAllPagesIndexed() throws Exception {
        // Create pages through the wiki - they will be automatically indexed
        m_engine.saveText( "WikiPage1", "Wiki page one content uniqueXYZ789" );
        m_engine.saveText( "WikiPage2", "Wiki page two content uniqueXYZ789" );

        // Wait for them to be indexed
        final Collection<SearchResult> res = new ArrayList<>();
        Awaitility.await( "waiting for wiki pages" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( () -> {
                    final HttpServletRequest request = HttpMockFactory.createHttpRequest();
                    final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
                    final Collection<SearchResult> search = m_mgr.findPages( "uniqueXYZ789", ctx );
                    return search != null && search.size() >= 2;
                } );

        // All pages already indexed, so indexMissingPages should find nothing
        final int pagesIndexed = m_provider.indexMissingPages();
        Assertions.assertEquals( 0, pagesIndexed, "Should not reindex already-indexed pages" );

        // Cleanup
        m_engine.deleteTestPage( "WikiPage1" );
        m_engine.deleteTestPage( "WikiPage2" );
    }

    @Test
    void testIndexMissingPagesIsIdempotent() throws Exception {
        // Create a page through the wiki
        m_engine.saveText( "SetupPage", "Setup content uniqueABC123" );

        // Wait for it to be indexed
        Awaitility.await( "waiting for setup page" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( findsResultsFor( new ArrayList<>(), "uniqueABC123" ) );

        // First call should find nothing (page already indexed)
        final int firstCall = m_provider.indexMissingPages();
        Assertions.assertEquals( 0, firstCall, "First call should index 0 pages (already indexed)" );

        // Second call should also find nothing
        final int secondCall = m_provider.indexMissingPages();
        Assertions.assertEquals( 0, secondCall, "Second call should also index 0 pages" );

        // Cleanup
        m_engine.deleteTestPage( "SetupPage" );
    }

    // =========================================================================
    // Tests for page removal from index
    // =========================================================================

    @Test
    void testDeletedPageRemovedFromIndex() throws Exception {
        // Create a page
        final String pageName = "PageToDelete";
        m_engine.saveText( pageName, "Content to be deleted uniqueDEL999" );

        // Wait for it to be indexed
        final Collection<SearchResult> res = new ArrayList<>();
        Awaitility.await( "waiting for page to be indexed" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( findsResultsFor( res, "uniqueDEL999" ) );

        // Verify it's in the index
        Set<String> indexedBefore = m_provider.getIndexedPageNames();
        Assertions.assertTrue( indexedBefore.contains( pageName ), "Page should be in index before deletion" );

        // Delete the page through the wiki engine
        m_engine.deleteTestPage( pageName );

        // Force removal from index by calling pageRemoved
        m_provider.pageRemoved( Wiki.contents().page( m_engine, pageName ) );

        // Now it should not be in the index
        Set<String> indexedAfter = m_provider.getIndexedPageNames();
        Assertions.assertFalse( indexedAfter.contains( pageName ), "Page should not be in index after deletion" );
    }

    // =========================================================================
    // Tests verifying the provider info
    // =========================================================================

    @Test
    void testProviderInfo() {
        Assertions.assertEquals( "LuceneSearchProvider", m_provider.getProviderInfo() );
    }

}
