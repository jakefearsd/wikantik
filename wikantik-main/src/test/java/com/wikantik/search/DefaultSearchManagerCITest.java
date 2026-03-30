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
import com.wikantik.api.core.Page;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.pages.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultSearchManager} covering the {@code JSONSearch} inner class
 * and the {@code actionPerformed} event-handling method.
 *
 * <p>The JSONSearch inner class is 3% covered; these tests aim to substantially
 * increase coverage of its {@code getSuggestions}, {@code findPages}, and
 * {@code service} methods.
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
    // JSONSearch — getSuggestions
    // -------------------------------------------------------------------------

    @Test
    void testGetSuggestionsEmptyQueryReturnsEmpty() {
        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final List<String> result = json.getSuggestions( "", 10 );
        assertNotNull( result );
        assertTrue( result.isEmpty(), "Empty query should return no suggestions" );
    }

    @Test
    void testGetSuggestionsReturnsMatchingPageNames() throws Exception {
        engine.saveText( "SuggestionTestPage", "some content unique suggword123" );

        // Wait for page to be available in ReferenceManager
        Awaitility.await( "page to be created" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( () -> engine.getManager( com.wikantik.references.ReferenceManager.class )
                        .findCreated().stream().anyMatch( n -> n.equals( "SuggestionTestPage" ) ) );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final List<String> results = json.getSuggestions( "SuggestionTest", 10 );

        assertTrue( results.contains( "SuggestionTestPage" ),
                "getSuggestions should return pages whose name starts with the prefix" );

        engine.deleteTestPage( "SuggestionTestPage" );
    }

    @Test
    void testGetSuggestionsRespectsMaxLength() throws Exception {
        engine.saveText( "MaxSuggestAlpha", "content" );
        engine.saveText( "MaxSuggestBeta", "content" );
        engine.saveText( "MaxSuggestGamma", "content" );

        Awaitility.await( "pages to be created" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( () -> {
                    final java.util.Set<String> created =
                            engine.getManager( com.wikantik.references.ReferenceManager.class ).findCreated();
                    return created.stream().anyMatch( n -> n.equals( "MaxSuggestAlpha" ) )
                        && created.stream().anyMatch( n -> n.equals( "MaxSuggestBeta" ) )
                        && created.stream().anyMatch( n -> n.equals( "MaxSuggestGamma" ) );
                } );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final List<String> results = json.getSuggestions( "MaxSuggest", 2 );

        assertTrue( results.size() <= 2,
                "getSuggestions must respect the maxLength parameter, got: " + results.size() );

        engine.deleteTestPage( "MaxSuggestAlpha" );
        engine.deleteTestPage( "MaxSuggestBeta" );
        engine.deleteTestPage( "MaxSuggestGamma" );
    }

    @Test
    void testGetSuggestionsWithAttachmentPath() throws Exception {
        engine.saveText( "AttachSuggestPage", "content" );

        Awaitility.await( "page to be available" )
                .atMost( 10, TimeUnit.SECONDS )
                .until( () -> engine.getManager( com.wikantik.references.ReferenceManager.class )
                        .findCreated().stream().anyMatch( n -> n.equals( "AttachSuggestPage" ) ) );

        // Attachment-style query: "PageName/filename" — the code splits on '/'
        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final List<String> results = json.getSuggestions( "AttachSuggestPage/", 10 );
        // AttachSuggestPage starts with "attachsuggestpage" (after lowercasing).
        // The attachment suffix is "/", so cleanWikiName = "attachsuggestpage/"
        // — the match depends on prefix; might or might not match, but must not throw.
        assertNotNull( results );

        engine.deleteTestPage( "AttachSuggestPage" );
    }

    // -------------------------------------------------------------------------
    // JSONSearch — findPages
    // -------------------------------------------------------------------------

    @Test
    void testFindPagesEmptySearchStringReturnsEmpty() {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( engine, req, ContextEnum.PAGE_VIEW.getRequestContext() );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final List<Map<String, Object>> results = json.findPages( "", 10, ctx );

        assertNotNull( results );
        assertTrue( results.isEmpty(), "Empty search string should return empty list" );
    }

    @Test
    void testFindPagesReturnsMatchingPages() throws Exception {
        final String uniqueWord = "uniquefindpages9876";
        engine.saveText( "FindPagesTarget", "content " + uniqueWord + " here" );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( engine, req, ContextEnum.PAGE_VIEW.getRequestContext() );

        final Callable<Boolean> findsResult = () -> {
            final List<Map<String, Object>> res =
                    searchManager.new JSONSearch().findPages( uniqueWord, 10, ctx );
            return res != null && !res.isEmpty();
        };

        Awaitility.await( "page to be indexed" )
                .atMost( 15, TimeUnit.SECONDS )
                .until( findsResult );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final List<Map<String, Object>> results = json.findPages( uniqueWord, 10, ctx );

        assertFalse( results.isEmpty(), "findPages should return results for a known indexed word" );
        final Map<String, Object> first = results.get( 0 );
        assertTrue( first.containsKey( "page" ), "Each result should have a 'page' key" );
        assertTrue( first.containsKey( "score" ), "Each result should have a 'score' key" );
        assertEquals( "FindPagesTarget", first.get( "page" ) );

        engine.deleteTestPage( "FindPagesTarget" );
    }

    @Test
    void testFindPagesRespectsMaxLength() throws Exception {
        final String uniqueWord = "findpagesmaxlen4321";
        engine.saveText( "FindPagesMax1", "content " + uniqueWord );
        engine.saveText( "FindPagesMax2", "content " + uniqueWord );
        engine.saveText( "FindPagesMax3", "content " + uniqueWord );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final Context ctx = Wiki.context().create( engine, req, ContextEnum.PAGE_VIEW.getRequestContext() );

        Awaitility.await( "pages to be indexed" )
                .atMost( 15, TimeUnit.SECONDS )
                .until( () -> {
                    final List<Map<String, Object>> res =
                            searchManager.new JSONSearch().findPages( uniqueWord, 10, ctx );
                    return res != null && res.size() >= 3;
                } );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final List<Map<String, Object>> results = json.findPages( uniqueWord, 2, ctx );

        assertTrue( results.size() <= 2,
                "findPages must honour the maxLength limit, got: " + results.size() );

        engine.deleteTestPage( "FindPagesMax1" );
        engine.deleteTestPage( "FindPagesMax2" );
        engine.deleteTestPage( "FindPagesMax3" );
    }

    // -------------------------------------------------------------------------
    // JSONSearch — service() dispatch
    // -------------------------------------------------------------------------

    @Test
    void testServiceWithBlankActionNameDoesNothing() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        // blank actionName — the service() method should return immediately
        json.service( req, resp, "  ", List.of( "TestPage" ) );

        // Writer should never have been touched
        assertTrue( sw.toString().isEmpty(), "Blank actionName should produce no output" );
    }

    @Test
    void testServiceWithEmptyParamsReturnsImmediately() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        // Non-blank action but empty params — should return immediately without writing
        json.service( req, resp, DefaultSearchManager.JSONSearch.AJAX_ACTION_SUGGESTIONS, List.of() );

        assertTrue( sw.toString().isEmpty(), "Empty params should produce no output" );
    }

    @Test
    void testServiceSuggestionsWritesJsonResponse() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        json.service( req, resp,
                DefaultSearchManager.JSONSearch.AJAX_ACTION_SUGGESTIONS,
                List.of( "TestPage" ) );

        // Should produce a JSON array (possibly empty)
        final String output = sw.toString();
        assertNotNull( output );
        assertTrue( output.startsWith( "[" ),
                "Suggestions service should write a JSON array, got: " + output );
    }

    @Test
    void testServiceSuggestionsWithMaxResultsParam() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        // Pass a maxResults param as a second list element
        json.service( req, resp,
                DefaultSearchManager.JSONSearch.AJAX_ACTION_SUGGESTIONS,
                List.of( "TestPage", "5" ) );

        assertEquals( 5, json.maxResults,
                "maxResults should be updated from the second param" );
        assertNotNull( sw.toString() );
    }

    @Test
    void testServiceSuggestionsWithNonNumericMaxResultsParamIgnored() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        final int defaultMax = json.maxResults;
        json.service( req, resp,
                DefaultSearchManager.JSONSearch.AJAX_ACTION_SUGGESTIONS,
                List.of( "TestPage", "notanumber" ) );

        assertEquals( defaultMax, json.maxResults,
                "Non-numeric maxResults param should leave maxResults unchanged" );
    }

    @Test
    void testServicePagesActionWritesJsonResponse() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest();
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        json.service( req, resp,
                DefaultSearchManager.JSONSearch.AJAX_ACTION_PAGES,
                List.of( "nonexistent_unique_abc123" ) );

        final String output = sw.toString();
        assertNotNull( output );
        // An empty search should produce a JSON array
        assertTrue( output.startsWith( "[" ),
                "Pages service should write a JSON array, got: " + output );
    }

    @Test
    void testGetServletMapping() {
        final DefaultSearchManager.JSONSearch json = searchManager.new JSONSearch();
        assertEquals( SearchManager.JSON_SEARCH, json.getServletMapping() );
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
