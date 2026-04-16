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

import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.PageManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
        final String workDir = props.getProperty( "wikantik.workDir" );
        final String workRepo = props.getProperty( "wikantik.fileSystemProvider.pageDir" );

        // Use unique directories for each test run
        final long timestamp = System.currentTimeMillis();
        props.setProperty( SearchManager.PROP_SEARCHPROVIDER, "LuceneSearchProvider" );
        props.setProperty( "wikantik.lucene.indexdelay", "0" );
        props.setProperty( "wikantik.lucene.initialdelay", "0" );
        props.setProperty( "wikantik.workDir", workDir + timestamp );
        props.setProperty( "wikantik.fileSystemProvider.pageDir", workRepo + timestamp );

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

        // Wait until ALL THREE pages appear in getIndexedPageNames().
        // Previously this test waited for findPages() to return 3 results, but that
        // uses a different IndexReader snapshot than getIndexedPageNames(), causing
        // a race condition where the assertion could fail even though the wait succeeded.
        Awaitility.await( "waiting for pages to be indexed" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( () -> {
                    final Set<String> indexed = m_provider.getIndexedPageNames();
                    return indexed.contains( "PageOne" )
                        && indexed.contains( "PageTwo" )
                        && indexed.contains( "PageThree" );
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

    // =========================================================================
    // Field boost tests: title (name) matches should rank above body-only matches
    // =========================================================================

    @Test
    void testTitleMatchRanksAboveBodyOnlyMatch() throws Exception {
        // "widgetquark" is a unique nonsense term. Page "Widgetquark" only
        // contains it in the title/name; page "BodyHolder" contains it three
        // times in body text. Without field boosts the body-repeat page often
        // wins on pure TF. With a title/name boost the title match wins.
        m_engine.saveText( "Widgetquark", "Generic lorem ipsum content with nothing unusual." );
        m_engine.saveText( "BodyHolder", "Body mentions widgetquark. Again widgetquark. Thrice widgetquark." );

        Awaitility.await( "both pages indexed" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( () -> {
                    final HttpServletRequest request = HttpMockFactory.createHttpRequest();
                    final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
                    final Collection<SearchResult> results = m_mgr.findPages( "widgetquark", ctx );
                    return results != null && results.size() >= 2;
                } );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
        final List<SearchResult> sorted = new ArrayList<>( m_mgr.findPages( "widgetquark", ctx ) );
        sorted.sort( Comparator.comparingInt( SearchResult::getScore ).reversed() );

        Assertions.assertEquals( "Widgetquark", sorted.get( 0 ).getPage().getName(),
                "Title-only match should outrank a body-only match with three TF hits when name field is boosted" );

        m_engine.deleteTestPage( "Widgetquark" );
        m_engine.deleteTestPage( "BodyHolder" );
    }

    // =========================================================================
    // Recency decay helper: pure function, unit-testable without TestEngine.
    // =========================================================================

    @Test
    void testRecencyFactorIsOneForJustModifiedPage() {
        final long now = 1_000_000_000_000L;
        Assertions.assertEquals( 1.0, LuceneSearchProvider.recencyFactor( now, now ), 1e-9,
                "Age zero should produce factor 1.0" );
    }

    @Test
    void testRecencyFactorClampsFuturePagesToOne() {
        final long now = 1_000_000_000_000L;
        // A page with lastModified in the future (clock skew, bad mtime) must
        // not be boosted above 1.0.
        Assertions.assertEquals( 1.0,
                LuceneSearchProvider.recencyFactor( now + 86_400_000L, now ),
                1e-9,
                "Future lastModified should clamp to 1.0" );
    }

    @Test
    void testRecencyFactorMonotonicallyDecreasesWithAge() {
        final long now = 1_000_000_000_000L;
        final long oneYearMs  = 365L * 24 * 60 * 60 * 1000;
        final long twoYearMs  = 2 * oneYearMs;
        final long tenYearMs  = 10 * oneYearMs;

        final double fresh = LuceneSearchProvider.recencyFactor( now, now );
        final double oneYr = LuceneSearchProvider.recencyFactor( now - oneYearMs, now );
        final double twoYr = LuceneSearchProvider.recencyFactor( now - twoYearMs, now );
        final double tenYr = LuceneSearchProvider.recencyFactor( now - tenYearMs, now );

        Assertions.assertTrue( fresh > oneYr, "fresh > 1yr" );
        Assertions.assertTrue( oneYr > twoYr, "1yr > 2yr" );
        Assertions.assertTrue( twoYr > tenYr, "2yr > 10yr" );
    }

    // =========================================================================
    // Search metrics: total, zero-result, and latency counters
    // =========================================================================

    @Test
    void testSearchMetricsIncrementOnHit() throws Exception {
        final long totalBefore = m_provider.getTotalSearchCount();
        final long zeroBefore  = m_provider.getZeroResultSearchCount();

        m_engine.saveText( "MetricsPage", "quokkatoken content worth finding" );

        Awaitility.await( "metricspage indexed" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( findsResultsFor( new ArrayList<>(), "quokkatoken" ) );

        Assertions.assertTrue( m_provider.getTotalSearchCount() > totalBefore,
                "Total search counter should increment on each non-blank query" );
        Assertions.assertEquals( zeroBefore, m_provider.getZeroResultSearchCount(),
                "Zero-result counter must NOT increment for queries that returned hits" );
        Assertions.assertTrue( m_provider.getLastQueryElapsedMillis() >= 0L,
                "Last-query elapsed millis should be recorded (>=0)" );

        m_engine.deleteTestPage( "MetricsPage" );
    }

    @Test
    void testZeroResultSearchIncrementsZeroResultCounter() throws Exception {
        final long totalBefore = m_provider.getTotalSearchCount();
        final long zeroBefore  = m_provider.getZeroResultSearchCount();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
        final Collection<SearchResult> results = m_mgr.findPages( "definitelynotapagezzqq7", ctx );

        Assertions.assertTrue( results == null || results.isEmpty(), "sanity check — query must return zero" );
        Assertions.assertEquals( totalBefore + 1, m_provider.getTotalSearchCount(),
                "Total should tick up once for a zero-result query" );
        Assertions.assertEquals( zeroBefore + 1, m_provider.getZeroResultSearchCount(),
                "Zero-result counter should tick up for a zero-result query" );
    }

    @Test
    void testBlankQueryDoesNotAffectMetrics() throws Exception {
        final long totalBefore = m_provider.getTotalSearchCount();
        final long zeroBefore  = m_provider.getZeroResultSearchCount();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( m_engine, request, ContextEnum.PAGE_EDIT.getRequestContext() );
        m_mgr.findPages( "", ctx );
        m_mgr.findPages( "   ", ctx );

        Assertions.assertEquals( totalBefore, m_provider.getTotalSearchCount(),
                "Blank queries are input-validation failures, not real searches — must not count" );
        Assertions.assertEquals( zeroBefore, m_provider.getZeroResultSearchCount(),
                "Blank queries must not increment zero-result counter either" );
    }

    @Test
    void testRecencyFactorHasFloorSoOldPagesStillRank() {
        final long now = 1_000_000_000_000L;
        // After a very long time the factor must not collapse to zero —
        // old pages should still rank, just behind fresh ones.
        final double ancient = LuceneSearchProvider.recencyFactor( 0L, now );
        Assertions.assertTrue( ancient >= 0.5,
                "Very old pages should retain at least a 0.5 score multiplier, was " + ancient );
        Assertions.assertTrue( ancient <= 1.0,
                "Multiplier must never exceed 1.0, was " + ancient );
    }

}
