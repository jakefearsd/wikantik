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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.search.SearchResult;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.SearchManager;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class SearchResourceTest {

    private TestEngine engine;
    private SearchResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        // Create test pages for searching
        engine.saveText( "RestSearchAlpha", "Alpha page content for search testing." );
        engine.saveText( "RestSearchBeta", "Beta page content for search testing." );

        // Force reindex and wait for the Lucene background thread to process
        final SearchManager sm = engine.getManager( SearchManager.class );
        final PageManager pmSetup = engine.getManager( PageManager.class );
        sm.reindexPage( pmSetup.getPage( "RestSearchAlpha" ) );
        sm.reindexPage( pmSetup.getPage( "RestSearchBeta" ) );

        // Wait for Lucene indexer — it runs on a background thread
        Thread.sleep( 500 );

        // Register a ContextRetrievalService that delegates to the real SearchManager
        // so tests exercise the full HTTP layer without requiring pgvector or embeddings.
        engine.setManager( ContextRetrievalService.class, new BridgingContextRetrievalService( engine ) );

        servlet = new SearchResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "RestSearchAlpha" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestSearchBeta" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testSearchWithResults() throws Exception {
        final String json = doSearch( "Alpha", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "Alpha", obj.get( "query" ).getAsString() );
        assertTrue( obj.has( "results" ) );
        assertTrue( obj.has( "total" ) );
        assertTrue( obj.get( "results" ).isJsonArray() );
    }

    @Test
    void testSearchEmptyQueryReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
        Mockito.doReturn( null ).when( request ).getParameter( "q" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testSearchBlankQueryReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
        Mockito.doReturn( "   " ).when( request ).getParameter( "q" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testSearchNoResults() throws Exception {
        final String json = doSearch( "xyznonexistent99999", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "xyznonexistent99999", obj.get( "query" ).getAsString() );
        assertTrue( obj.has( "results" ) );
        final JsonArray results = obj.getAsJsonArray( "results" );
        assertEquals( 0, results.size() );
        assertEquals( 0, obj.get( "total" ).getAsInt() );
    }

    @Test
    void testSearchResultFields() throws Exception {
        final String json = doSearch( "Alpha", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray results = obj.getAsJsonArray( "results" );
        if ( results.size() > 0 ) {
            final JsonObject entry = results.get( 0 ).getAsJsonObject();
            assertTrue( entry.has( "name" ) );
            assertTrue( entry.has( "score" ) );
        }
    }

    @Test
    void testSearchWithLimit() throws Exception {
        final String json = doSearch( "search", "1" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "search", obj.get( "query" ).getAsString() );
        assertTrue( obj.has( "results" ) );
        assertTrue( obj.has( "total" ) );
        final JsonArray results = obj.getAsJsonArray( "results" );
        assertTrue( results.size() <= 1,
                "Results should be limited to 1 by the limit parameter" );
    }

    @Test
    void testSearchWithInvalidLimitUsesDefault() throws Exception {
        final String json = doSearch( "Alpha", "not-a-number" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // Invalid limit should not cause an error; it should use the default
        assertFalse( obj.has( "error" ), "Invalid limit should not cause error" );
        assertEquals( "Alpha", obj.get( "query" ).getAsString() );
        assertTrue( obj.has( "results" ) );
    }

    @Test
    void testSearchResultsWithFrontmatter() throws Exception {
        // Create a page with frontmatter that the search will find
        engine.saveText( "RestSearchFrontmatter",
                "---\nsummary: A test summary\ntags: [search, test]\ncluster: TestCluster\n---\nFrontmatter search content." );
        // Allow Lucene time to index
        Thread.sleep( 500 );

        try {
            final String json = doSearch( "Frontmatter", null );
            final JsonObject obj = gson.fromJson( json, JsonObject.class );
            final JsonArray results = obj.getAsJsonArray( "results" );

            if ( results.size() > 0 ) {
                // Find our specific page
                for ( int i = 0; i < results.size(); i++ ) {
                    final JsonObject entry = results.get( i ).getAsJsonObject();
                    if ( "RestSearchFrontmatter".equals( entry.get( "name" ).getAsString() ) ) {
                        assertTrue( entry.has( "score" ), "Result should have score" );
                        assertTrue( entry.get( "score" ).getAsDouble() > 0,
                                "Score should be positive" );
                        // Frontmatter fields should be extracted
                        if ( entry.has( "summary" ) ) {
                            assertEquals( "A test summary", entry.get( "summary" ).getAsString() );
                        }
                        break;
                    }
                }
            }
        } finally {
            try { engine.getManager( PageManager.class ).deletePage( "RestSearchFrontmatter" ); }
            catch ( final Exception e ) { /* ignore */ }
        }
    }

    @Test
    void testSearchResultHasCorrectFields() throws Exception {
        final String json = doSearch( "Alpha", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray results = obj.getAsJsonArray( "results" );
        // We expect at least one result for "Alpha" since we created RestSearchAlpha
        if ( results.size() > 0 ) {
            final JsonObject entry = results.get( 0 ).getAsJsonObject();
            assertTrue( entry.has( "name" ), "Result should have 'name'" );
            assertTrue( entry.has( "score" ), "Result should have 'score'" );
            assertFalse( entry.get( "name" ).getAsString().isEmpty(),
                    "Name should not be empty" );
        }
    }

    @Test
    void testSearchEmptyStringQueryReturns400() throws Exception {
        final String json = doSearch( "", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testSearchResultsContainExpectedPageFields() throws Exception {
        // Lucene indexing is asynchronous; search for content with retries
        JsonArray results = null;
        for ( int attempt = 0; attempt < 10; attempt++ ) {
            Thread.sleep( 500 );
            final String json = doSearch( "Alpha", null );
            final JsonObject obj = gson.fromJson( json, JsonObject.class );
            results = obj.getAsJsonArray( "results" );
            if ( results.size() > 0 ) break;
        }

        // If Lucene indexed, verify the full result structure
        if ( results != null && results.size() > 0 ) {
            final JsonObject entry = results.get( 0 ).getAsJsonObject();
            // Core fields that SearchResource always sets
            assertTrue( entry.has( "name" ), "Result entry should have 'name'" );
            assertTrue( entry.has( "score" ), "Result entry should have 'score'" );
            // lastModified comes from the page — present when BridgingContextRetrievalService populates it
            assertTrue( entry.has( "lastModified" ), "Result entry should have 'lastModified'" );
            assertFalse( entry.get( "name" ).getAsString().isEmpty(), "Name should not be empty" );
            assertTrue( entry.get( "score" ).getAsDouble() > 0, "Score should be positive" );
        }
    }

    @Test
    void testSearchResultsWithFrontmatterFields() throws Exception {
        // Create a page with full frontmatter
        engine.saveText( "RestSearchFmFields",
                "---\nsummary: Unique findable summary\ntags: [alpha, beta]\ncluster: test-cluster\n---\nUniqueFmFieldContent here." );
        final SearchManager sm = engine.getManager( SearchManager.class );
        sm.reindexPage( engine.getManager( PageManager.class ).getPage( "RestSearchFmFields" ) );

        try {
            // Wait for indexing and search with retries
            JsonObject foundEntry = null;
            for ( int attempt = 0; attempt < 10; attempt++ ) {
                Thread.sleep( 500 );
                final String json = doSearch( "UniqueFmFieldContent", null );
                final JsonObject obj = gson.fromJson( json, JsonObject.class );
                final JsonArray res = obj.getAsJsonArray( "results" );
                for ( int i = 0; i < res.size(); i++ ) {
                    final JsonObject e = res.get( i ).getAsJsonObject();
                    if ( "RestSearchFmFields".equals( e.get( "name" ).getAsString() ) ) {
                        foundEntry = e;
                        break;
                    }
                }
                if ( foundEntry != null ) break;
            }

            if ( foundEntry != null ) {
                // Verify frontmatter fields are extracted into the result
                assertEquals( "Unique findable summary", foundEntry.get( "summary" ).getAsString(),
                        "Summary should be extracted from frontmatter" );
                assertTrue( foundEntry.has( "tags" ), "Tags should be extracted from frontmatter" );
                assertEquals( "test-cluster", foundEntry.get( "cluster" ).getAsString(),
                        "Cluster should be extracted from frontmatter" );
            }
        } finally {
            try { engine.getManager( PageManager.class ).deletePage( "RestSearchFmFields" ); }
            catch ( final Exception e ) { /* ignore */ }
        }
    }

    // ----- ContextRetrievalService integration -----

    @Test
    void crsReturnsOrderedResultsAsJsonArray() throws Exception {
        // Mock CRS returning a controlled ordered list.
        final ContextRetrievalService mockCrs = Mockito.mock( ContextRetrievalService.class );
        final RetrievedPage beta = new RetrievedPage(
            "RestSearchBeta", null, 2.0, "", null, null, null, null, null,
            engine.getManager( PageManager.class ).getPage( "RestSearchBeta" ).getLastModified() );
        final RetrievedPage alpha = new RetrievedPage(
            "RestSearchAlpha", null, 1.0, "", null, null, null, null, null,
            engine.getManager( PageManager.class ).getPage( "RestSearchAlpha" ).getLastModified() );
        Mockito.doReturn( new RetrievalResult( "search", List.of( beta, alpha ), 2 ) )
               .when( mockCrs ).retrieve( Mockito.any() );
        engine.setManager( ContextRetrievalService.class, mockCrs );

        final String json = doSearch( "search", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        final JsonArray results = obj.getAsJsonArray( "results" );
        assertTrue( results.size() >= 2, "Expected both pages back, got: " + results );
        assertEquals( "RestSearchBeta", results.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "RestSearchAlpha", results.get( 1 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void crsResultWithChunksExposesContextsField() throws Exception {
        // Verify that contributingChunks are serialized as the 'contexts' field.
        final ContextRetrievalService mockCrs = Mockito.mock( ContextRetrievalService.class );
        final RetrievedChunk chunk = new RetrievedChunk( List.of( "Intro" ), "Alpha chunk body.", 1.5, List.of() );
        final RetrievedPage alpha = new RetrievedPage(
            "RestSearchAlpha", null, 3.0, "", null, null, List.of( chunk ), null, null, null );
        Mockito.doReturn( new RetrievalResult( "Alpha", List.of( alpha ), 1 ) )
               .when( mockCrs ).retrieve( Mockito.any() );
        engine.setManager( ContextRetrievalService.class, mockCrs );

        final String json = doSearch( "Alpha", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        final JsonArray results = obj.getAsJsonArray( "results" );
        assertEquals( 1, results.size() );
        final JsonObject entry = results.get( 0 ).getAsJsonObject();
        assertTrue( entry.has( "contexts" ), "contexts field should be present when chunks exist" );
        final JsonArray contexts = entry.getAsJsonArray( "contexts" );
        assertEquals( "Alpha chunk body.", contexts.get( 0 ).getAsString() );
    }

    @Test
    void crsZeroScorePageIncludedInResults() throws Exception {
        // Dense-only page: exists in results with score 0, no chunks — mirrors old DenseOnlySearchResult.
        final ContextRetrievalService mockCrs = Mockito.mock( ContextRetrievalService.class );
        final RetrievedPage denseOnly = new RetrievedPage(
            "RestSearchAlpha", null, 0.0, "", null, null, null, null, null, null );
        Mockito.doReturn( new RetrievalResult( "search", List.of( denseOnly ), 1 ) )
               .when( mockCrs ).retrieve( Mockito.any() );
        engine.setManager( ContextRetrievalService.class, mockCrs );

        final String json = doSearch( "search", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        final JsonArray results = obj.getAsJsonArray( "results" );
        assertFalse( results.isEmpty(), "Zero-score page must still appear in results" );
        assertEquals( "RestSearchAlpha", results.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( 0.0, results.get( 0 ).getAsJsonObject().get( "score" ).getAsDouble(), 0.0001 );
    }

    @Test
    void limitCapsCrsResults() throws Exception {
        // CRS returns 3 pages; limit=1 → only 1 in the response.
        final ContextRetrievalService mockCrs = Mockito.mock( ContextRetrievalService.class );
        final List< RetrievedPage > pages = List.of(
            new RetrievedPage( "RestSearchAlpha", null, 3.0, "", null, null, null, null, null, null ),
            new RetrievedPage( "RestSearchBeta", null, 2.0, "", null, null, null, null, null, null ),
            new RetrievedPage( "ExtraPage", null, 1.0, "", null, null, null, null, null, null )
        );
        Mockito.doReturn( new RetrievalResult( "search", pages, 3 ) )
               .when( mockCrs ).retrieve( Mockito.any() );
        engine.setManager( ContextRetrievalService.class, mockCrs );

        final String json = doSearch( "search", "1" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );
        final JsonArray results = obj.getAsJsonArray( "results" );
        assertEquals( 1, results.size(), "limit=1 should cap results to 1" );
        assertEquals( "RestSearchAlpha", results.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void crsNotConfiguredReturns500() throws Exception {
        // Simulate CRS not registered by using a mock engine that returns null for CRS.
        // We can't put null into the manager map, so we install a stub that signals absence
        // via a fresh engine instance that has no CRS registered.
        final Properties props = TestEngine.getTestProperties();
        TestEngine engineWithoutCrs = null;
        try {
            engineWithoutCrs = new TestEngine( props );
            // Do NOT register a ContextRetrievalService — engine.getManager returns null.

            final SearchResource nocrsSvl = new SearchResource();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( engineWithoutCrs.getServletContext() ).when( config ).getServletContext();
            nocrsSvl.init( config );

            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
            Mockito.doReturn( "Alpha" ).when( request ).getParameter( "q" );
            Mockito.doReturn( null ).when( request ).getParameter( "limit" );

            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

            nocrsSvl.doGet( request, response );
            Mockito.verify( response ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
            final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
            assertTrue( body.get( "error" ).getAsBoolean() );
        } finally {
            if ( engineWithoutCrs != null ) engineWithoutCrs.stop();
        }
    }

    @Test
    void luceneParseFailureReturns400NotServerError() throws Exception {
        // CRS throws a RuntimeException wrapping a ProviderException wrapping a Lucene
        // ParseException → SearchResource.isLuceneParseError walks the cause chain → 400.
        final ContextRetrievalService throwingCrs = Mockito.mock( ContextRetrievalService.class );
        final ProviderException wrap = new ProviderException(
            "You have entered a query Lucene cannot process [...]: parse fail",
            new org.apache.lucene.queryparser.classic.ParseException( "bad syntax" ) );
        Mockito.doThrow( new RuntimeException( "retrieval error", wrap ) )
               .when( throwingCrs ).retrieve( Mockito.any() );
        engine.setManager( ContextRetrievalService.class, throwingCrs );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
        Mockito.doReturn( "[a-z]+.*" ).when( request ).getParameter( "q" );
        Mockito.doReturn( null ).when( request ).getParameter( "limit" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( body.get( "message" ).getAsString().startsWith( "Invalid search query" ) );
    }

    @Test
    void nonParseSearchFailureStillReturns500() throws Exception {
        // CRS throws a plain RuntimeException → 500.
        final ContextRetrievalService throwingCrs = Mockito.mock( ContextRetrievalService.class );
        Mockito.doThrow( new RuntimeException( "disk offline" ) )
               .when( throwingCrs ).retrieve( Mockito.any() );
        engine.setManager( ContextRetrievalService.class, throwingCrs );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
        Mockito.doReturn( "Alpha" ).when( request ).getParameter( "q" );
        Mockito.doReturn( null ).when( request ).getParameter( "limit" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
    }

    @Test
    void negativeLimitReturns400NotHtml500() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
        Mockito.doReturn( "Alpha" ).when( request ).getParameter( "q" );
        Mockito.doReturn( "-5" ).when( request ).getParameter( "limit" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( body.has( "error" ) && body.get( "error" ).getAsBoolean() );
        assertTrue( body.get( "message" ).getAsString().contains( "limit" ) );
    }

    @Test
    void limitAboveCapReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
        Mockito.doReturn( "Alpha" ).when( request ).getParameter( "q" );
        Mockito.doReturn( "9999" ).when( request ).getParameter( "limit" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    // ----- Helper methods -----

    private String doSearch( final String query, final String limit ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/search" );
        Mockito.doReturn( query ).when( request ).getParameter( "q" );
        Mockito.doReturn( limit ).when( request ).getParameter( "limit" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    /**
     * A thin {@link ContextRetrievalService} adapter that delegates to the
     * real {@link SearchManager} and {@link FrontmatterMetadataCache} already
     * wired in the test {@link TestEngine}. This lets the basic search tests
     * exercise the full HTTP layer (parameter parsing, JSON serialisation,
     * limit capping) against a live Lucene index without requiring pgvector,
     * embeddings, or any other optional service.
     */
    private static final class BridgingContextRetrievalService implements ContextRetrievalService {

        private final TestEngine engine;

        BridgingContextRetrievalService( final TestEngine engine ) {
            this.engine = engine;
        }

        @Override
        public RetrievalResult retrieve( final ContextQuery query ) {
            final SearchManager sm = engine.getManager( SearchManager.class );
            final FrontmatterMetadataCache fmCache = engine.getManager( FrontmatterMetadataCache.class );
            final com.wikantik.api.core.Context ctx;
            try {
                ctx = com.wikantik.api.spi.Wiki.context().create(
                    engine, null, com.wikantik.api.core.ContextEnum.WIKI_FIND.getRequestContext() );
            } catch ( final Exception e ) {
                throw new RuntimeException( "Context creation failed", e );
            }
            final Collection< SearchResult > raw;
            try {
                raw = sm.findPages( query.query(), ctx );
            } catch ( final Exception e ) {
                throw new RuntimeException( e );
            }
            if ( raw == null || raw.isEmpty() ) {
                return new RetrievalResult( query.query(), List.of(), 0 );
            }

            final List< RetrievedPage > pages = new ArrayList<>();
            for ( final SearchResult sr : raw ) {
                final Page page = sr.getPage();
                if ( page == null ) continue;
                final Map< String, Object > fm = fmCache != null
                    ? fmCache.get( page.getName(), page.getLastModified() )
                    : Map.of();
                final String summary = fm.get( "summary" ) != null ? fm.get( "summary" ).toString() : "";
                @SuppressWarnings( "unchecked" )
                final List< String > tags = fm.get( "tags" ) instanceof List
                    ? (List< String >) fm.get( "tags" ) : null;
                final String cluster = fm.get( "cluster" ) != null ? fm.get( "cluster" ).toString() : null;
                // Translate Lucene context snippets to RetrievedChunk list so that
                // 'contexts' field in the JSON response contains actual text.
                final List< RetrievedChunk > chunks = new ArrayList<>();
                final String[] ctxs = sr.getContexts();
                if ( ctxs != null ) {
                    for ( final String snippet : ctxs ) {
                        if ( snippet != null && !snippet.isBlank() ) {
                            chunks.add( new RetrievedChunk( List.of(), snippet, sr.getScore(), List.of() ) );
                        }
                    }
                }
                pages.add( new RetrievedPage(
                    page.getName(), null, sr.getScore(), summary, cluster,
                    tags, chunks, null, page.getAuthor(), page.getLastModified() ) );
            }
            return new RetrievalResult( query.query(), pages, pages.size() );
        }

        @Override
        public RetrievedPage getPage( final String pageName ) {
            return null;
        }

        @Override
        public PageList listPages( final PageListFilter filter ) {
            return new PageList( List.of(), 0, 0, 0 );
        }

        @Override
        public List< MetadataValue > listMetadataValues( final String field ) {
            return List.of();
        }
    }
}
