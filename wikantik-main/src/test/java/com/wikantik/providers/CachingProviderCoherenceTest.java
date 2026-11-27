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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Coherence of {@link CachingProvider}: after a write made through it, no read may return what was stored
 * before. Runs the cache in front of a {@link MapPageProvider} whose content the tests can also change
 * behind the cache's back.
 */
class CachingProviderCoherenceTest {

    private TestEngine engine;
    private CachingProvider cache;
    private MapPageProvider store;

    @AfterEach
    void tearDown() {
        if( engine != null ) {
            engine.stop();
        }
    }

    private void start( final String allPagesTtlSeconds ) {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", MapPageProvider.class.getName() );
        props.setProperty( CachingProvider.PROP_WATCHER_ENABLED, "false" );
        props.setProperty( CachingProvider.PROP_CACHE_ALLPAGES_TTL, allPagesTtlSeconds );
        engine = TestEngine.build( props );
        cache = ( CachingProvider ) engine.getManager( PageManager.class ).getProvider();
        store = ( MapPageProvider ) cache.getRealProvider();
    }

    private void start() {
        start( "300" );
    }

    private void put( final String name, final String text ) throws Exception {
        final Page p = Wiki.contents().page( engine, name );
        p.setAuthor( "tester" );
        cache.putPageText( p, text );
    }

    private static Set< String > names( final Collection< Page > pages ) {
        return pages.stream().map( Page::getName ).collect( Collectors.toSet() );
    }

    private static List< Integer > versions( final List< Page > history ) {
        return history.stream().map( Page::getVersion ).collect( Collectors.toList() );
    }

    // ---- save ----

    @Test
    void aSaveIsVisibleToTheNextReadOfTextInfoAndHistory() throws Exception {
        start();
        put( "Foo", "one" );
        Assertions.assertEquals( "one", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 1 ), versions( cache.getVersionHistory( "Foo" ) ) );
        Assertions.assertEquals( 1, cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );

        put( "Foo", "two" );

        Assertions.assertEquals( "two", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 2, 1 ), versions( cache.getVersionHistory( "Foo" ) ) );
        Assertions.assertEquals( 2, cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );
        Assertions.assertTrue( names( cache.getAllPages() ).contains( "Foo" ) );
    }

    @Test
    void aSaveStampsTheSavedPageWithTheTimeOfTheSave() throws Exception {
        start();
        final Page p = Wiki.contents().page( engine, "Foo" );
        p.setAuthor( "tester" );
        p.setLastModified( new Date( 0L ) );
        final long before = System.currentTimeMillis();

        cache.putPageText( p, "text" );

        Assertions.assertTrue( p.getLastModified().getTime() >= before, "lastModified set to the save time" );
    }

    @Test
    void getPageInfoParsesMetadataOnceAndMarksThePageParsed() throws Exception {
        start();
        put( "Foo", "plain body" );

        Assertions.assertTrue( cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).hasMetadata() );
    }

    // ---- delete ----

    @Test
    void aDeletedPageIsGoneFromEveryRead() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Bar", "bar" );
        Assertions.assertEquals( "one", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( 1, cache.getVersionHistory( "Foo" ).size() );
        Assertions.assertTrue( cache.pageExists( "Foo" ) );
        Assertions.assertEquals( Set.of( "Foo", "Bar" ), names( cache.getAllPages() ) );

        cache.deletePage( "Foo" );

        Assertions.assertFalse( cache.pageExists( "Foo" ) );
        Assertions.assertNull( cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertTrue( cache.getVersionHistory( "Foo" ).isEmpty() );
        Assertions.assertNull( cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( Set.of( "Bar" ), names( cache.getAllPages() ) );
        Assertions.assertEquals( "bar", cache.getPageText( "Bar", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void deletingTheLatestVersionPromotesThePreviousOneInEveryRead() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Foo", "two" );
        put( "Foo", "three" );
        Assertions.assertEquals( "three", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( 3, cache.getVersionHistory( "Foo" ).size() );
        Assertions.assertEquals( 3, cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );

        cache.deleteVersion( "Foo", PageProvider.LATEST_VERSION );

        Assertions.assertEquals( "two", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 2, 1 ), versions( cache.getVersionHistory( "Foo" ) ) );
        Assertions.assertEquals( 2, cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );
    }

    @Test
    void deletingTheVersionNumberedAsTheLatestEvictsTheCachedLatest() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Foo", "two" );
        Assertions.assertEquals( "two", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );

        cache.deleteVersion( "Foo", 2 );

        Assertions.assertEquals( "one", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( 1, cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );
    }

    @Test
    void deletingAnArchivedVersionKeepsTheCachedLatestAndRefreshesHistory() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Foo", "two" );
        put( "Foo", "three" );
        Assertions.assertEquals( "three", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( 3, cache.getVersionHistory( "Foo" ).size() );
        final int textReads = store.textReads;

        cache.deleteVersion( "Foo", 1 );

        Assertions.assertEquals( "three", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( textReads, store.textReads, "the cached latest text was not evicted" );
        final int historyReads = store.historyReads;
        cache.getVersionHistory( "Foo" );
        Assertions.assertEquals( historyReads + 1, store.historyReads, "history was re-read after the delete" );
    }

    // ---- move ----

    @Test
    void aMovedPageIsReadUnderItsNewNameOnlyEvenOverACachedTarget() throws Exception {
        start();
        put( "From", "from one" );
        put( "From", "from two" );
        put( "To", "old target" );
        for( final String n : new String[] { "From", "To" } ) {
            cache.getPageText( n, PageProvider.LATEST_VERSION );
            cache.getVersionHistory( n );
            cache.getPageInfo( n, PageProvider.LATEST_VERSION );
        }

        cache.movePage( "From", "To" );

        Assertions.assertFalse( cache.pageExists( "From" ) );
        Assertions.assertNull( cache.getPageText( "From", PageProvider.LATEST_VERSION ) );
        Assertions.assertTrue( cache.getVersionHistory( "From" ).isEmpty() );
        Assertions.assertNull( cache.getPageInfo( "From", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "from two", cache.getPageText( "To", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 2, 1 ), versions( cache.getVersionHistory( "To" ) ) );
        Assertions.assertEquals( Set.of( "To" ), names( cache.getAllPages() ) );
    }

    // ---- existence ----

    @Test
    void pageExistsAnswersFalseForNullAndMissingNamesAndTrueForSavedOnes() throws Exception {
        start();
        put( "Foo", "one" );

        Assertions.assertFalse( cache.pageExists( null ) );
        Assertions.assertFalse( cache.pageExists( null, 1 ) );
        Assertions.assertTrue( cache.pageExists( "Foo" ) );
        Assertions.assertFalse( cache.pageExists( "Missing" ) );
        Assertions.assertFalse( cache.pageExists( "Missing", 1 ) );
        Assertions.assertFalse( cache.pageExists( "Missing", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void pageExistsForTheLatestVersionIsAnsweredFromTheCacheAndOlderOnesFromTheProvider() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Foo", "two" );
        put( "Foo", "three" );
        Assertions.assertNotNull( cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ) );
        final int before = store.existsWithVersionCalls;

        Assertions.assertTrue( cache.pageExists( "Foo", 3 ) );
        Assertions.assertTrue( cache.pageExists( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( before, store.existsWithVersionCalls, "latest asked of the cache only" );

        Assertions.assertTrue( cache.pageExists( "Foo", 1 ) );
        Assertions.assertTrue( cache.pageExists( "Foo", 2 ) );
        Assertions.assertFalse( cache.pageExists( "Foo", 4 ) );
        Assertions.assertFalse( cache.pageExists( "Foo", 0 ) );
        Assertions.assertEquals( before + 4, store.existsWithVersionCalls, "older versions asked of the provider" );
    }

    @Test
    void aProviderFailureWhileCheckingExistenceReadsAsNotExisting() throws Exception {
        start();
        put( "Foo", "one" );
        cache.deletePage( "Foo" );      // nothing cached for Foo any more
        store.outOfBandPut( "Foo", "x" );
        store.failInfo = true;          // so the next lookup, which must ask the provider, fails

        Assertions.assertFalse( cache.pageExists( "Foo" ) );
        Assertions.assertFalse( cache.pageExists( "Foo", 1 ) );
    }

    // ---- versioned reads ----

    @Test
    void aVersionedTextReadUsesTheCacheOnlyForTheLatestVersion() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Foo", "two" );
        Assertions.assertEquals( "two", cache.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        final int reads = store.textReads;

        Assertions.assertEquals( "two", cache.getPageText( "Foo", 2 ) );
        Assertions.assertEquals( reads, store.textReads, "version 2 is the cached latest" );
        Assertions.assertEquals( "one", cache.getPageText( "Foo", 1 ) );
        Assertions.assertEquals( reads + 1, store.textReads, "version 1 comes from the provider" );
        Assertions.assertNull( cache.getPageText( null, 1 ) );
    }

    @Test
    void aVersionedInfoReadUsesTheCacheOnlyForTheLatestVersion() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Foo", "two" );
        Assertions.assertNotNull( cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ) );
        final int reads = store.infoReads;

        Assertions.assertEquals( 2, cache.getPageInfo( "Foo", 2 ).getVersion() );
        Assertions.assertEquals( reads, store.infoReads );
        Assertions.assertEquals( 1, cache.getPageInfo( "Foo", 1 ).getVersion() );
        Assertions.assertEquals( reads + 1, store.infoReads );
    }

    // ---- refresh from the provider ----

    @Test
    void anOutOfBandChangeIsNotSeenInTheListingWithinTheTtl() throws Exception {
        start( "300" );
        put( "Foo", "one" );
        Assertions.assertEquals( Set.of( "Foo" ), names( cache.getAllPages() ) );

        store.outOfBandPut( "Surprise", "x" );

        Assertions.assertEquals( Set.of( "Foo" ), names( cache.getAllPages() ) );
    }

    @Test
    void anExpiredListingIsRefreshedFromTheProviderAndStaleEntriesAreEvicted() throws Exception {
        start( "0" );
        put( "Changed", "one" );
        put( "Same", "same" );
        put( "Gone", "gone" );
        cache.getAllPages();
        for( final String n : new String[] { "Changed", "Same", "Gone" } ) {
            cache.getPageText( n, PageProvider.LATEST_VERSION );
            cache.getVersionHistory( n );
        }
        Thread.sleep( 20L );
        store.outOfBandPut( "Changed", "two" );
        store.outOfBandRemove( "Gone" );
        store.outOfBandPut( "Added", "added" );
        final int sameReadsBefore = store.textReads;

        final Collection< Page > listing = cache.getAllPages();

        Assertions.assertEquals( Set.of( "Changed", "Same", "Added" ), names( listing ) );
        Assertions.assertEquals( "two", cache.getPageText( "Changed", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( List.of( 2, 1 ), versions( cache.getVersionHistory( "Changed" ) ) );
        Assertions.assertNull( cache.getPageText( "Gone", PageProvider.LATEST_VERSION ) );
        Assertions.assertTrue( cache.getVersionHistory( "Gone" ).isEmpty() );
        Assertions.assertEquals( "same", cache.getPageText( "Same", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( sameReadsBefore + 1, store.textReads,
                "only the changed page's text was read again; the unchanged page kept its cached text" );
        Assertions.assertEquals( "added", cache.getPageText( "Added", PageProvider.LATEST_VERSION ) );
    }

    // ---- delegations ----

    @Test
    void pageCountSearchAndInfoAreDelegatedToTheProvider() throws Exception {
        start();
        put( "Foo", "one" );
        put( "Bar", "two" );

        Assertions.assertEquals( 2, cache.getPageCount() );
        Assertions.assertTrue( cache.findPages( new com.wikantik.api.search.QueryItem[ 0 ] ).isEmpty() );
        final String info = cache.getProviderInfo();
        Assertions.assertTrue( info.startsWith( "Real provider: " + MapPageProvider.class.getName() ), info );
        Assertions.assertTrue( info.contains( "Page cache hits: " ) && info.contains( "History cache misses: " ), info );
    }

    @Test
    void changedSinceListsPagesFromTheCachedListingByModificationTime() throws Exception {
        start();
        put( "Foo", "one" );
        final Date saved = cache.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getLastModified();

        Assertions.assertEquals( Set.of( "Foo" ), names( cache.getAllChangedSince( new Date( saved.getTime() ) ) ) );
        Assertions.assertTrue( cache.getAllChangedSince( new Date( saved.getTime() + 60_000L ) ).isEmpty() );
        Assertions.assertEquals( Set.of( "Foo" ), names( cache.getAllChangedSince( null ) ) );
    }
}
