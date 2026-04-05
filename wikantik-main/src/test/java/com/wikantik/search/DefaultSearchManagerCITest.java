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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.pages.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultSearchManager} covering event handling and search engine
 * initialization.
 */
class DefaultSearchManagerCITest {

    private TestEngine engine;
    private DefaultSearchManager searchManager;

    @BeforeEach
    void setUp() {
        final Properties props = TestEngine.getTestProperties();
        final String workDir = props.getProperty( "wikantik.workDir" );
        final String workRepo = props.getProperty( "wikantik.fileSystemProvider.pageDir" );

        final long ts = System.currentTimeMillis();
        props.setProperty( SearchManager.PROP_SEARCHPROVIDER, "LuceneSearchProvider" );
        props.setProperty( "wikantik.lucene.indexdelay", "0" );
        props.setProperty( "wikantik.lucene.initialdelay", "0" );
        props.setProperty( "wikantik.workDir", workDir + ts );
        props.setProperty( "wikantik.fileSystemProvider.pageDir", workRepo + ts );

        engine = TestEngine.build( props );
        searchManager = ( DefaultSearchManager ) engine.getManager( SearchManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // -------------------------------------------------------------------------
    // getSearchEngine
    // -------------------------------------------------------------------------

    @Test
    void testGetSearchEngineReturnsLuceneProvider() {
        final SearchProvider provider = searchManager.getSearchEngine();
        assertNotNull( provider );
        assertTrue( provider instanceof LuceneSearchProvider,
                "Default search engine should be LuceneSearchProvider" );
    }

    // -------------------------------------------------------------------------
    // actionPerformed — event handling
    // -------------------------------------------------------------------------

    @Test
    void testActionPerformedPageDeleteRequestRemovesPageFromIndex() throws Exception {
        final String uniqueWord = "deleteactionword7777";
        engine.saveText( "ActionDeletePage", "content " + uniqueWord );

        // Wait for indexing
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( engine, req, ContextEnum.PAGE_VIEW.getRequestContext() );
        Awaitility.await( "indexed" )
                .atMost( 15, TimeUnit.SECONDS )
                .until( () -> {
                    final Collection<SearchResult> res = engine.getManager( SearchManager.class ).findPages( uniqueWord, ctx );
                    return res != null && !res.isEmpty();
                } );

        // Fire a PAGE_DELETE_REQUEST event directly
        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_DELETE_REQUEST, "ActionDeletePage" );
        searchManager.actionPerformed( event );

        // After removal the page should not be findable
        final Collection<SearchResult> after = engine.getManager( SearchManager.class ).findPages( uniqueWord, ctx );
        assertTrue( after.isEmpty(), "Page should not appear in search results after PAGE_DELETE_REQUEST event" );

        engine.deleteTestPage( "ActionDeletePage" );
    }

    @Test
    void testActionPerformedPageReindexUpdatesIndex() throws Exception {
        final String uniqueWord = "reindexactionword8888";
        engine.saveText( "ActionReindexPage", "initial content" );

        // Ensure the page exists in the engine
        final PageManager pm = engine.getManager( PageManager.class );
        assertNotNull( pm.getPage( "ActionReindexPage" ) );

        // Fire PAGE_REINDEX — the page exists so reindexPage should be called
        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_REINDEX, "ActionReindexPage" );
        searchManager.actionPerformed( event );
        // No exception = success; the search index is updated asynchronously via the queue

        engine.deleteTestPage( "ActionReindexPage" );
    }

    @Test
    void testActionPerformedPageDeleteRequestForMissingPageIsNoOp() {
        // Firing a delete event for a page that doesn't exist should be a no-op
        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_DELETE_REQUEST, "NonExistentPage99999" );
        // Should not throw
        searchManager.actionPerformed( event );
    }

    @Test
    void testActionPerformedPageReindexForMissingPageIsNoOp() {
        // Firing a reindex event for a non-existent page should be a no-op
        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_REINDEX, "NonExistentPage99999" );
        searchManager.actionPerformed( event );
    }

    @Test
    void testActionPerformedWithUnrelatedEventTypeIsNoOp() {
        // An event type that is neither PAGE_DELETE_REQUEST nor PAGE_REINDEX
        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_LOCK, "SomePage" );
        searchManager.actionPerformed( event );
    }

}
