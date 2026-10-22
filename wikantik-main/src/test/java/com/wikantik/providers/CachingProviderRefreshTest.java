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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.QueryItem;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The all-pages TTL refresh must diff against the provider, not flush: unchanged pages keep their
 * cached text, vanished pages are evicted, changed pages are re-read. Also: recent-changes queries
 * are served from the cached page list while it is valid.
 */
class CachingProviderRefreshTest {

    /** Mutable in-memory provider that counts the calls we care about. Static state: one engine per test. */
    public static class MutableProvider implements PageProvider {
        static final Map< String, Page > PAGES = new LinkedHashMap<>();
        static final Map< String, String > TEXT = new LinkedHashMap<>();
        static final AtomicInteger TEXT_CALLS = new AtomicInteger();
        static final AtomicInteger ALL_PAGES_CALLS = new AtomicInteger();
        static final AtomicInteger CHANGED_SINCE_CALLS = new AtomicInteger();
        static Engine eng;

        static void put( final String name, final String text, final int version, final long modified ) {
            final Page p = Wiki.contents().page( eng, name );
            p.setAuthor( "tester" );
            p.setVersion( version );
            p.setLastModified( new Date( modified ) );
            PAGES.put( name, p );
            TEXT.put( name, text );
        }

        @Override public void initialize( final Engine engine, final Properties props ) {
            eng = engine;
            PAGES.clear();
            TEXT.clear();
            put( "Foo", "foo text", 1, 1_000L );
            put( "Bar", "bar text", 1, 2_000L );
            put( "Baz", "baz text", 1, 3_000L );
        }
        @Override public String getProviderInfo() { return "mutable"; }
        @Override public void putPageText( final Page page, final String text ) { }
        @Override public boolean pageExists( final String page ) { return PAGES.containsKey( page ); }
        @Override public boolean pageExists( final String page, final int version ) { return pageExists( page ); }
        @Override public Collection< SearchResult > findPages( final QueryItem[] query ) { return null; }
        @Override public Page getPageInfo( final String page, final int version ) { return PAGES.get( page ); }
        @Override public Collection< Page > getAllPages() {
            ALL_PAGES_CALLS.incrementAndGet();
            final List< Page > l = new ArrayList<>();
            for( final Page p : PAGES.values() ) {
                final Page copy = Wiki.contents().page( eng, p.getName() );
                copy.setAuthor( p.getAuthor() );
                copy.setVersion( p.getVersion() );
                copy.setLastModified( p.getLastModified() );
                l.add( copy );
            }
            return l;
        }
        @Override public Collection< Page > getAllChangedSince( final Date date ) {
            CHANGED_SINCE_CALLS.incrementAndGet();
            final List< Page > l = new ArrayList<>();
            for( final Page p : getAllPages() ) {
                if( date == null || date.getTime() <= 0 || p.getLastModified().getTime() >= date.getTime() ) {
                    l.add( p );
                }
            }
            return l;
        }
        @Override public int getPageCount() { return PAGES.size(); }
        @Override public List< Page > getVersionHistory( final String page ) { return new ArrayList<>(); }
        @Override public String getPageText( final String page, final int version ) {
            if( "Foo".equals( page ) ) {
                TEXT_CALLS.incrementAndGet();
            }
            return TEXT.get( page );
        }
        @Override public void deleteVersion( final String page, final int version ) { }
        @Override public void deletePage( final String page ) { PAGES.remove( page ); }
        @Override public void movePage( final String from, final String to ) throws ProviderException { }
    }

    private TestEngine engine;
    private CachingProvider cp;

    @BeforeEach
    void setUp() {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", MutableProvider.class.getName() );
        props.setProperty( CachingProvider.PROP_CACHE_ALLPAGES_TTL, "1" );
        engine = TestEngine.build( props );
        cp = ( CachingProvider ) engine.getManager( PageManager.class ).getProvider();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    /** Waits for the TTL to expire and forces the refresh; returns once the provider has been asked again. */
    private void awaitRefresh() {
        final int before = MutableProvider.ALL_PAGES_CALLS.get();
        Awaitility.await().atMost( 10, TimeUnit.SECONDS ).pollInterval( 100, TimeUnit.MILLISECONDS ).until( () -> {
            cp.getAllPages();
            return MutableProvider.ALL_PAGES_CALLS.get() > before;
        } );
    }

    @Test
    void refreshKeepsTextOfUnchangedPages() throws Exception {
        Assertions.assertEquals( "foo text", cp.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        MutableProvider.TEXT_CALLS.set( 0 );

        awaitRefresh();

        Assertions.assertEquals( "foo text", cp.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( 0, MutableProvider.TEXT_CALLS.get(),
                "an unchanged page's text must stay cached across the all-pages refresh" );
    }

    @Test
    void refreshEvictsVanishedPage() throws Exception {
        Assertions.assertEquals( 3, cp.getAllPages().size() );
        MutableProvider.PAGES.remove( "Bar" );

        awaitRefresh();

        final Set< String > names = new TreeSet<>();
        cp.getAllPages().forEach( p -> names.add( p.getName() ) );
        Assertions.assertEquals( Set.of( "Foo", "Baz" ), names );
    }

    @Test
    void refreshReloadsChangedPage() throws Exception {
        Assertions.assertEquals( "foo text", cp.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        MutableProvider.put( "Foo", "foo text v2", 2, 9_000L );

        awaitRefresh();

        Assertions.assertEquals( "foo text v2", cp.getPageText( "Foo", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( 2, cp.getPageInfo( "Foo", PageProvider.LATEST_VERSION ).getVersion() );
    }

    @Test
    void refreshAddsNewPage() throws Exception {
        MutableProvider.put( "Qux", "qux", 1, 4_000L );

        awaitRefresh();

        Assertions.assertEquals( 4, cp.getAllPages().size() );
    }

    @Test
    void changedSinceIsServedFromCacheWhileValid() throws Exception {
        MutableProvider.CHANGED_SINCE_CALLS.set( 0 );
        final Collection< Page > since = cp.getAllChangedSince( new Date( 2_000L ) );

        Assertions.assertEquals( 0, MutableProvider.CHANGED_SINCE_CALLS.get(),
                "recent-changes must not hit the underlying provider while the page cache is valid" );
        final Set< String > names = new TreeSet<>();
        since.forEach( p -> names.add( p.getName() ) );
        Assertions.assertEquals( Set.of( "Bar", "Baz" ), names, "inclusive lower bound on lastModified" );
        for( final Page p : since ) {
            Assertions.assertEquals( "tester", p.getAuthor() );
            Assertions.assertEquals( 1, p.getVersion() );
        }
        Assertions.assertEquals( 3, cp.getAllChangedSince( new Date( 0L ) ).size() );
        Assertions.assertEquals( 3, cp.getAllChangedSince( null ).size() );
    }

    /** Rebuilds the engine with a long all-pages TTL, as shipped (300 s), so no refresh hides the listing. */
    private void rebuildWithLongTtl() {
        engine.stop();
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", MutableProvider.class.getName() );
        props.setProperty( CachingProvider.PROP_CACHE_ALLPAGES_TTL, "300" );
        engine = TestEngine.build( props );
        cp = ( CachingProvider ) engine.getManager( PageManager.class ).getProvider();
    }

    private Set< String > listedNames() throws ProviderException {
        final Set< String > names = new TreeSet<>();
        cp.getAllPages().forEach( p -> names.add( p.getName() ) );
        return names;
    }

    @Test
    void listingStaysCompleteWhenPerPageCacheEntriesExpire() throws Exception {
        rebuildWithLongTtl();
        Assertions.assertEquals( Set.of( "Foo", "Bar", "Baz" ), listedNames() );

        // The per-page cache expires entries after 60 s while the listing is kept for 300 s.
        engine.getManager( CachingManager.class ).remove( CachingManager.CACHE_PAGES, "Bar" );

        Assertions.assertEquals( Set.of( "Foo", "Bar", "Baz" ), listedNames(),
                "a page whose per-page cache entry expired must still be listed" );
    }

    @Test
    void savesDeletesAndMovesKeepTheListingCurrentBetweenRefreshes() throws Exception {
        rebuildWithLongTtl();
        Assertions.assertEquals( 3, cp.getAllPages().size() );

        MutableProvider.put( "Qux", "qux", 1, 4_000L );
        cp.putPageText( Wiki.contents().page( engine, "Qux" ), "qux" );
        Assertions.assertEquals( Set.of( "Foo", "Bar", "Baz", "Qux" ), listedNames() );

        MutableProvider.PAGES.remove( "Bar" );
        cp.deletePage( "Bar" );
        Assertions.assertEquals( Set.of( "Foo", "Baz", "Qux" ), listedNames() );

        MutableProvider.put( "Foo2", "foo text", 1, 5_000L );
        MutableProvider.PAGES.remove( "Foo" );
        cp.movePage( "Foo", "Foo2" );
        Assertions.assertEquals( Set.of( "Foo2", "Baz", "Qux" ), listedNames(),
                "a renamed page is listed under its new name, not dropped" );
    }
}
