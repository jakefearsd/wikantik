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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.QueryItem;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A cache load that read the page from the provider before a delete must not store it after the delete invalidated
 * the cache: the deleted page would read as existing (and a guarded save that requires the page to exist would pass and
 * recreate it).
 */
class CachingProviderLoadRaceTest {

    /** In-memory provider whose page-info read of "Racy" can be held after it has read the page. */
    public static class HoldingProvider implements PageProvider {
        static final Map< String, Page > PAGES = new ConcurrentHashMap<>();
        static volatile CountDownLatch entered;
        static volatile CountDownLatch release;

        @Override public void initialize( final Engine engine, final Properties props ) {
            PAGES.clear();
            final Page p = Wiki.contents().page( engine, "Racy" );
            p.setAuthor( "tester" );
            p.setVersion( 1 );
            p.setLastModified( new Date() );
            PAGES.put( "Racy", p );
        }
        @Override public String getProviderInfo() { return "holding"; }
        @Override public void putPageText( final Page page, final String text ) { }
        @Override public boolean pageExists( final String page ) { return PAGES.containsKey( page ); }
        @Override public boolean pageExists( final String page, final int version ) { return pageExists( page ); }
        @Override public Collection< SearchResult > findPages( final QueryItem[] query ) { return List.of(); }
        @Override public Page getPageInfo( final String page, final int version ) {
            final Page read = PAGES.get( page );
            final CountDownLatch hold = release;
            if( hold != null && "Racy".equals( page ) ) {
                release = null;
                entered.countDown();
                try {
                    hold.await( 10, TimeUnit.SECONDS );
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                }
            }
            return read;
        }
        @Override public Collection< Page > getAllPages() { return new ArrayList<>( PAGES.values() ); }
        @Override public Collection< Page > getAllChangedSince( final Date date ) { return getAllPages(); }
        @Override public int getPageCount() { return PAGES.size(); }
        @Override public List< Page > getVersionHistory( final String page ) { return new ArrayList<>(); }
        @Override public String getPageText( final String page, final int version ) { return PAGES.containsKey( page ) ? "text" : null; }
        @Override public void deleteVersion( final String page, final int version ) { }
        @Override public void deletePage( final String page ) { PAGES.remove( page ); }
        @Override public void movePage( final String from, final String to ) { }
    }

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        HoldingProvider.release = null;
        engine.stop();
    }

    @Test
    void aLoadThatReadThePageBeforeADeleteDoesNotCacheItAfterTheDelete() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "true" );
        props.setProperty( "wikantik.pageProvider", HoldingProvider.class.getName() );
        engine = TestEngine.build( props );
        final CachingProvider cp = ( CachingProvider ) engine.getManager( PageManager.class ).getProvider();
        engine.getManager( CachingManager.class ).remove( CachingManager.CACHE_PAGES, "Racy" );

        final CountDownLatch release = new CountDownLatch( 1 );
        HoldingProvider.entered = new CountDownLatch( 1 );
        HoldingProvider.release = release;
        final ExecutorService ex = Executors.newFixedThreadPool( 2 );
        try {
            final Future< Page > reader = ex.submit( () -> cp.getPageInfo( "Racy", PageProvider.LATEST_VERSION ) );
            assertTrue( HoldingProvider.entered.await( 10, TimeUnit.SECONDS ) );
            final Future< ? > delete = ex.submit( () -> {
                cp.deletePage( "Racy" );
                return null;
            } );
            try {
                delete.get( 300, TimeUnit.MILLISECONDS );
            } catch( final TimeoutException waiting ) {
                // the delete waits for the load in flight: the outcome checked below
            }
            release.countDown();
            reader.get( 10, TimeUnit.SECONDS );
            delete.get( 10, TimeUnit.SECONDS );
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
        assertNull( cp.getPageInfo( "Racy", PageProvider.LATEST_VERSION ), "a deleted page must not read as existing" );
        assertFalse( cp.pageExists( "Racy" ) );
    }
}
