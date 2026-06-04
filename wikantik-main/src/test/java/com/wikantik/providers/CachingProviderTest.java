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

package com.wikantik.providers;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.util.FileUtil;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

class CachingProviderTest {

    TestEngine engine;

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    /**
     *  Checks that at startup we call the provider once, and once only.
     */
    @Test
    void testInitialization() {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", "com.wikantik.providers.CounterProvider" );

        engine = TestEngine.build( props );
        final CounterProvider p = ( CounterProvider )( ( CachingProvider )engine.getManager( PageManager.class ).getProvider() ).getRealProvider();

        Assertions.assertEquals( 1, p.m_initCalls, "init" );
        Assertions.assertEquals( 1, p.m_getAllPagesCalls, "getAllPages" );
        Assertions.assertEquals( 0, p.m_pageExistsCalls, "pageExists" );
        Assertions.assertEquals( 4, p.m_getPageTextCalls, "getPageText" );

        engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertEquals( 0, p.m_pageExistsCalls, "pageExists2" );
    }

    @Test
    void testSneakyAdd() throws Exception {
        engine = TestEngine.build();
        final String dir = engine.getWikiProperties().getProperty( FileSystemProvider.PROP_PAGEDIR );
        final File f = new File( dir, "Testi.txt" );
        final String content = "[fuufaa]()";

        final PrintWriter out = new PrintWriter( new FileWriter(f) );
        FileUtil.copyContents( new StringReader(content), out );
        out.close();

        Awaitility.await( "testSneakyAdd" ).until( () -> engine.getManager( PageManager.class ).getPage( "Testi" ) != null );
        final Page p = engine.getManager( PageManager.class ).getPage( "Testi" );
        Assertions.assertNotNull( p, "page did not exist?" );

        final String text = engine.getManager( PageManager.class ).getText( "Testi");
        Assertions.assertEquals( "[fuufaa]()", text, "text" );
    }

    @Test
    void testGetAllWithCacheTooSmallDelegatesToRealProvider() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.cache.config-file", "ehcache-wikantik-small.xml" );

        engine = TestEngine.build( props );
        engine.saveText( "page1", "page that should be cached" );
        engine.saveText( "page2", "page that should not be cached" );

        Assertions.assertEquals( 2, engine.getManager( PageManager.class ).getAllPages().size(), engine.getManager( PageManager.class ).getAllPages().toString() );
    }

    @Test
    void testGetAllWithCacheTooSmallDelegatesToRealProviderWithInitialPageLoad() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", "com.wikantik.providers.CounterProvider" );
        props.setProperty( "wikantik.cache.config-file", "ehcache-wikantik-small.xml" );

        engine = TestEngine.build( props );

        Assertions.assertEquals( 4, engine.getManager( PageManager.class ).getAllPages().size() );
    }

    // ============== Cache Invalidation Tests ==============

    /**
     * Helper to get CounterProvider from CachingProvider wrapper
     */
    private CounterProvider getCounterProvider( final TestEngine engine ) {
        return (CounterProvider) ((CachingProvider) engine.getManager( PageManager.class ).getProvider()).getRealProvider();
    }

    /**
     * Helper to set up engine with CounterProvider
     */
    private TestEngine buildWithCounterProvider() {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", "com.wikantik.providers.CounterProvider" );
        return TestEngine.build( props );
    }

    /**
     * Tests that getPageText uses cache on subsequent calls.
     */
    @Test
    void testGetPageTextCaching() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );
        final int initialGetTextCalls = cp.m_getPageTextCalls;

        // First call should use cache from initialization
        engine.getManager( PageManager.class ).getText( "Foo" );
        // Cache was populated during init, so no new provider calls
        Assertions.assertEquals( initialGetTextCalls, cp.m_getPageTextCalls, "First getText should use cache" );

        // Second call should still use cache
        engine.getManager( PageManager.class ).getText( "Foo" );
        Assertions.assertEquals( initialGetTextCalls, cp.m_getPageTextCalls, "Second getText should use cache" );
    }

    /**
     * Tests that putPageText invalidates the page cache.
     */
    @Test
    void testPutPageTextInvalidatesCache() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Reset counters after initialization
        cp.resetCounters();

        // Get page - should use cache
        engine.getManager( PageManager.class ).getText( "Foo" );
        Assertions.assertEquals( 0, cp.m_getPageTextCalls, "getText should use cache initially" );

        // Now save new text - this should invalidate cache
        final Page page = engine.getManager( PageManager.class ).getPage( "Foo" );
        engine.getManager( PageManager.class ).getProvider().putPageText( page, "Updated content" );
        Assertions.assertEquals( 1, cp.m_putPageTextCalls, "putPageText should call provider" );

        // Get page info again — verify the page is still accessible after putPageText
        final Page refreshed = engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertNotNull( refreshed, "page should still be accessible after putPageText" );
    }

    /**
     * Tests that deletePage invalidates all related caches.
     */
    @Test
    void testDeletePageInvalidatesCache() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Ensure Foo exists in cache
        final Page p = engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertNotNull( p );

        // Reset counters
        cp.resetCounters();

        // Delete the page
        engine.getManager( PageManager.class ).getProvider().deletePage( "Foo" );
        Assertions.assertEquals( 1, cp.m_deletePageCalls, "deletePage should call provider" );
    }

    /**
     * Tests that deleteVersion invalidates appropriate caches.
     */
    @Test
    void testDeleteVersionInvalidatesCache() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Reset counters after init
        cp.resetCounters();

        // Delete version
        engine.getManager( PageManager.class ).getProvider().deleteVersion( "Foo", 1 );
        Assertions.assertEquals( 1, cp.m_deleteVersionCalls, "deleteVersion should call provider" );
    }

    /**
     * Tests that movePage invalidates caches for both source and destination.
     */
    @Test
    void testMovePageInvalidatesBothCaches() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Ensure Foo exists in cache
        engine.getManager( PageManager.class ).getPage( "Foo" );

        // Reset counters
        cp.resetCounters();

        // Move the page
        engine.getManager( PageManager.class ).getProvider().movePage( "Foo", "FooMoved" );
        Assertions.assertEquals( 1, cp.m_movePageCalls, "movePage should call provider" );
    }

    /**
     * Tests that getVersionHistory is cached.
     */
    @Test
    void testGetVersionHistoryCaching() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Reset counters
        cp.resetCounters();

        // First call - should hit provider
        engine.getManager( PageManager.class ).getProvider().getVersionHistory( "Foo" );
        Assertions.assertEquals( 1, cp.m_getVersionHistoryCalls, "First getVersionHistory should call provider" );

        // Second call - should use cache
        engine.getManager( PageManager.class ).getProvider().getVersionHistory( "Foo" );
        Assertions.assertEquals( 1, cp.m_getVersionHistoryCalls, "Second getVersionHistory should use cache" );
    }

    /**
     * Tests that pageExists uses cache.
     */
    @Test
    void testPageExistsUsesCache() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Reset counters after init
        cp.resetCounters();

        // Check if page exists - should use cache
        final boolean exists = engine.getManager( PageManager.class ).pageExists( "Foo" );
        Assertions.assertTrue( exists );
        Assertions.assertEquals( 0, cp.m_pageExistsCalls, "pageExists should use cache" );
    }

    /**
     * Tests that pageExists for non-existent page doesn't call provider when all pages are in cache.
     */
    @Test
    void testPageExistsNonExistentUsesCache() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Reset counters after init - at this point all pages are in cache
        cp.resetCounters();

        // Check for non-existent page - should use cache and return false without calling provider
        final boolean exists = engine.getManager( PageManager.class ).pageExists( "NonExistent" );
        Assertions.assertFalse( exists );
        // Since all pages fit in cache, should not need to call provider
        Assertions.assertEquals( 0, cp.m_pageExistsCalls, "pageExists for non-existent should use cache" );
    }

    /**
     * Tests that getPageInfo with latest version uses cache.
     */
    @Test
    void testGetPageInfoLatestUsesCache() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Let initialization settle, then capture baseline
        engine.getManager( PageManager.class ).getPage( "Foo" );
        final int baseline = cp.m_getPageCalls;

        // Subsequent gets should come from cache — no additional provider calls
        engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertEquals( baseline, cp.m_getPageCalls, "getPage for latest should use cache (no new calls)" );

        engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertEquals( baseline, cp.m_getPageCalls, "getPage for latest should still use cache" );
    }

    /**
     * Tests that getAllPages uses cache after first call.
     */
    @Test
    void testGetAllPagesCaching() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // First getAllPages call happens during init
        final int initialCalls = cp.m_getAllPagesCalls;
        Assertions.assertEquals( 1, initialCalls, "getAllPages should be called once during init" );

        // Reset counters
        cp.resetCounters();

        // Get all pages again - should use cache
        final Collection<Page> pages = engine.getManager( PageManager.class ).getAllPages();
        Assertions.assertEquals( 4, pages.size() );
        Assertions.assertEquals( 0, cp.m_getAllPagesCalls, "getAllPages should use cache" );
    }

    /**
     * Tests that cache properly handles null page name in getPageText.
     */
    @Test
    void testGetPageTextNullReturnsNull() throws Exception {
        engine = buildWithCounterProvider();

        final String text = engine.getManager( PageManager.class ).getProvider().getPageText( null, 1 );
        Assertions.assertNull( text, "getPageText with null should return null" );
    }

    /**
     * Tests that cache properly handles null page name in pageExists.
     */
    @Test
    void testPageExistsNullReturnsFalse() throws Exception {
        engine = buildWithCounterProvider();

        final boolean exists = engine.getManager( PageManager.class ).getProvider().pageExists( null );
        Assertions.assertFalse( exists, "pageExists with null should return false" );
    }

    /**
     * Tests that cache properly handles null page name in pageExists with version.
     */
    @Test
    void testPageExistsWithVersionNullReturnsFalse() throws Exception {
        engine = buildWithCounterProvider();

        final boolean exists = engine.getManager( PageManager.class ).getProvider().pageExists( null, 1 );
        Assertions.assertFalse( exists, "pageExists with null and version should return false" );
    }

    /**
     * Tests that cache properly handles null page name in getPageInfo.
     */
    @Test
    void testGetPageInfoNullReturnsNull() throws Exception {
        engine = buildWithCounterProvider();

        final Page page = engine.getManager( PageManager.class ).getProvider().getPageInfo( null, 1 );
        Assertions.assertNull( page, "getPageInfo with null should return null" );
    }

    /**
     * Tests that cache properly handles null page name in getVersionHistory.
     */
    @Test
    void testGetVersionHistoryNullReturnsEmptyList() throws Exception {
        engine = buildWithCounterProvider();

        final List<Page> history = engine.getManager( PageManager.class ).getProvider().getVersionHistory( null );
        Assertions.assertNotNull( history, "getVersionHistory with null should return empty list, not null" );
        Assertions.assertTrue( history.isEmpty(), "getVersionHistory with null should return empty list" );
    }

    /**
     * Tests that putPageText increments the page counter.
     */
    @Test
    void testPutPageTextIncrementsPageCount() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Get existing page
        final Page page = engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertNotNull( page );

        // Reset counters
        cp.resetCounters();

        // Save the page
        engine.getManager( PageManager.class ).getProvider().putPageText( page, "New content" );

        // Verify provider was called
        Assertions.assertEquals( 1, cp.m_putPageTextCalls, "putPageText should call provider once" );
    }

    /**
     * Tests that multiple cache operations work correctly in sequence.
     */
    @Test
    void testCacheOperationSequence() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Initial state after init
        Assertions.assertEquals( 1, cp.m_initCalls );
        Assertions.assertEquals( 1, cp.m_getAllPagesCalls );

        // Reset counters
        cp.resetCounters();

        // Sequence of operations — use deltas to avoid depending on exact init call counts
        // 1. Get page (cached — establish baseline)
        engine.getManager( PageManager.class ).getPage( "Foo" );
        final int baselineGetPage = cp.m_getPageCalls;

        // 2. Get text (cached — use delta from baseline since init may read texts)
        final int baselineGetText = cp.m_getPageTextCalls;
        engine.getManager( PageManager.class ).getText( "Foo" );
        Assertions.assertEquals( baselineGetText, cp.m_getPageTextCalls, "Step 2: getText should use cache" );

        // 3. Check existence (cached)
        engine.getManager( PageManager.class ).pageExists( "Foo" );
        Assertions.assertEquals( 0, cp.m_pageExistsCalls, "Step 3: pageExists should use cache" );

        // 4. Get version history (first call, not cached yet)
        engine.getManager( PageManager.class ).getProvider().getVersionHistory( "Foo" );
        Assertions.assertEquals( 1, cp.m_getVersionHistoryCalls, "Step 4: getVersionHistory first call" );

        // 5. Get version history again (now cached)
        engine.getManager( PageManager.class ).getProvider().getVersionHistory( "Foo" );
        Assertions.assertEquals( 1, cp.m_getVersionHistoryCalls, "Step 5: getVersionHistory should use cache" );
    }

    // ============== VP-T4: putPageText eviction + re-parse regression test ==============

    /**
     * VP-T4 regression: verifies that putPageText evicts the cached Page so the next
     * getPageInfo re-parses with the new content (hasMetadata contract).
     *
     * VP-T3 made the cached Page sticky — once parsed, hasMetadata==true short-circuits
     * refreshMetadata on every subsequent cache hit.  That means a stale cached Page
     * (hasMetadata==true, old text) after an edit would suppress the re-parse forever.
     *
     * The correctness contract: putPageText removes CACHE_PAGES (and _TEXT / _HISTORY),
     * then re-fetches a fresh Page from the underlying provider (hasMetadata==false).
     * The next caller's getPageInfo triggers refreshMetadata → provider.getPageText.
     *
     * Assertion: m_getPageTextCalls increases by exactly 1 after getPageInfo is called
     * post-edit, proving the stale sticky Page was not reused.
     */
    @Test
    void testPutPageText_evictsCachedPage_soNextGetPageInfoReParses() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Warm the cache: getPage triggers refreshMetadata, sets hasMetadata=true on the
        // cached Page.  After this call, a second getPage would NOT call getPageText again.
        engine.getManager( PageManager.class ).getPage( "Foo" );

        // Reset counters so the baseline is clean.
        cp.resetCounters();

        // Confirm the short-circuit is in place: a second getPage does NOT call getPageText.
        engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertEquals( 0, cp.m_getPageTextCalls,
                "pre-condition: second getPage must not call provider.getPageText (hasMetadata short-circuit)" );

        // Now perform an edit: putPageText must evict the cached (sticky) Page.
        final Page page = engine.getManager( PageManager.class ).getPage( "Foo" );
        engine.getManager( PageManager.class ).getProvider().putPageText( page, "v2 content" );

        // Reset counters again so only post-edit calls are measured.
        cp.resetCounters();

        // The next getPageInfo must re-parse: the evicted Page is replaced with a fresh one
        // (hasMetadata==false), so refreshMetadata fires and calls provider.getPageText once.
        engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertEquals( 1, cp.m_getPageTextCalls,
                "post-edit getPage must call provider.getPageText once to re-parse (eviction correctness)" );
    }

    // ============== refreshMetadata short-circuit Tests ==============

    /**
     * VP-T3: Verifies that refreshMetadata does NOT call provider.getPageText a second time once
     * the page has been successfully parsed (hasMetadata() == true after the first successful parse).
     *
     * The guard {@code if (page != null && !page.hasMetadata())} in refreshMetadata exists to
     * prevent redundant full-page reads on cache hits.  This test asserts the contract:
     * two consecutive getPage calls for the same page result in AT MOST one provider.getPageText
     * call via the refreshMetadata path.
     *
     * The mechanism: MarkdownParser (and CachingProvider.refreshMetadata itself, per the one-line
     * fix) calls page.setHasMetadata() after a successful parse, so the guard short-circuits
     * on the second invocation.
     */
    @Test
    void testRefreshMetadataShortCircuitsAfterFirstParse() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Reset counters so initialization noise is excluded; page objects still carry hasMetadata
        // state from init — any page already parsed will have hasMetadata==true and not add calls.
        cp.resetCounters();

        // First getPage — either hits the refreshMetadata guard (hasMetadata already true from init)
        // or triggers refreshMetadata once; record the post-call count as the baseline.
        engine.getManager( PageManager.class ).getPage( "Foo" );
        final int afterFirst = cp.m_getPageTextCalls;

        // Second getPage for the same page — refreshMetadata MUST short-circuit; count must not grow.
        engine.getManager( PageManager.class ).getPage( "Foo" );
        Assertions.assertEquals( afterFirst, cp.m_getPageTextCalls,
                "Second getPage must NOT call provider.getPageText again (hasMetadata must short-circuit)" );
    }

    // ============== All-Pages Cache TTL Tests ==============

    /**
     * Helper to set up engine with CounterProvider and short TTL for testing.
     */
    private TestEngine buildWithCounterProviderAndShortTTL( final int ttlSeconds ) {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", "com.wikantik.providers.CounterProvider" );
        props.setProperty( CachingProvider.PROP_CACHE_ALLPAGES_TTL, String.valueOf( ttlSeconds ) );
        return TestEngine.build( props );
    }

    /**
     * Tests that getAllPages cache refreshes after TTL expires.
     */
    @Test
    void testGetAllPagesCacheRefreshesAfterTTL() throws Exception {
        // Use a very short TTL (1 second) for testing
        engine = buildWithCounterProviderAndShortTTL( 1 );
        final CounterProvider cp = getCounterProvider( engine );

        // First getAllPages call happens during init
        Assertions.assertEquals( 1, cp.m_getAllPagesCalls, "getAllPages should be called once during init" );

        // Reset counters
        cp.resetCounters();

        // Immediate call should use cache
        engine.getManager( PageManager.class ).getAllPages();
        Assertions.assertEquals( 0, cp.m_getAllPagesCalls, "getAllPages should use cache immediately" );

        // Wait for TTL to expire and cache to refresh
        Awaitility.await().atMost( 3, java.util.concurrent.TimeUnit.SECONDS )
                  .pollInterval( 50, java.util.concurrent.TimeUnit.MILLISECONDS )
                  .until( () -> {
                      engine.getManager( PageManager.class ).getAllPages();
                      return cp.m_getAllPagesCalls >= 1;
                  } );
        Assertions.assertEquals( 1, cp.m_getAllPagesCalls, "getAllPages should refresh from provider after TTL" );

        // Reset counters
        cp.resetCounters();

        // Immediate call should use cache again
        engine.getManager( PageManager.class ).getAllPages();
        Assertions.assertEquals( 0, cp.m_getAllPagesCalls, "getAllPages should use cache after refresh" );
    }

    /**
     * Tests that the default TTL property is used when not specified.
     */
    @Test
    void testDefaultAllPagesTTL() throws Exception {
        engine = buildWithCounterProvider();
        final CounterProvider cp = getCounterProvider( engine );

        // Reset counters after init
        cp.resetCounters();

        // Multiple calls should use cache (default TTL is 5 minutes)
        for ( int i = 0; i < 10; i++ ) {
            engine.getManager( PageManager.class ).getAllPages();
        }
        Assertions.assertEquals( 0, cp.m_getAllPagesCalls, "getAllPages should use cache with default TTL" );
    }

    /**
     * Tests that externally added pages are detected after TTL expires.
     * This simulates the scenario where files are added directly to the filesystem.
     */
    @Test
    void testExternallyAddedPagesDetectedAfterTTL() throws Exception {
        // Use short TTL for testing
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( CachingProvider.PROP_CACHE_ALLPAGES_TTL, "1" );
        engine = TestEngine.build( props );

        // Get initial page count
        final int initialCount = engine.getManager( PageManager.class ).getAllPages().size();

        // Add a page directly to the filesystem (bypassing the wiki API)
        final String dir = engine.getWikiProperties().getProperty( FileSystemProvider.PROP_PAGEDIR );
        final File f = new File( dir, "ExternalPage.txt" );
        try ( final PrintWriter out = new PrintWriter( new FileWriter( f ) ) ) {
            out.println( "This page was added externally" );
        }

        // Immediate getAllPages should return old count (cache not expired)
        Assertions.assertEquals( initialCount, engine.getManager( PageManager.class ).getAllPages().size(),
                "getAllPages should return cached count before TTL expires" );

        // Wait for TTL to expire and the new page to be detected
        Awaitility.await().atMost( 3, java.util.concurrent.TimeUnit.SECONDS )
                  .pollInterval( 50, java.util.concurrent.TimeUnit.MILLISECONDS )
                  .until( () -> engine.getManager( PageManager.class ).getAllPages().size() >= initialCount + 1 );

        // Now getAllPages should detect the new page
        final Collection<Page> pagesAfterTTL = engine.getManager( PageManager.class ).getAllPages();
        Assertions.assertEquals( initialCount + 1, pagesAfterTTL.size(),
                "getAllPages should detect externally added page after TTL expires" );

        // Verify the new page is in the list
        final boolean found = pagesAfterTTL.stream().anyMatch( p -> "ExternalPage".equals( p.getName() ) );
        Assertions.assertTrue( found, "ExternalPage should be in the page list" );
    }

}
