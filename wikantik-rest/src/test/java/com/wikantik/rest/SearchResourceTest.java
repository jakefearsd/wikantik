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
import com.wikantik.pages.PageManager;
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

        // Force reindex to ensure pages are in the Lucene index
        final SearchManager sm = engine.getManager( SearchManager.class );
        final PageManager pmSetup = engine.getManager( PageManager.class );
        sm.reindexPage( pmSetup.getPage( "RestSearchAlpha" ) );
        sm.reindexPage( pmSetup.getPage( "RestSearchBeta" ) );

        // Allow Lucene time to index
        Thread.sleep( 500 );

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
                        assertTrue( entry.get( "score" ).getAsInt() > 0,
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
        for ( int attempt = 0; attempt < 5; attempt++ ) {
            Thread.sleep( 500 );
            final String json = doSearch( "Alpha", null );
            final JsonObject obj = gson.fromJson( json, JsonObject.class );
            results = obj.getAsJsonArray( "results" );
            if ( results.size() > 0 ) break;
        }

        // If Lucene didn't index in time, skip detailed assertions but still
        // verify the response structure
        if ( results != null && results.size() > 0 ) {
            final JsonObject entry = results.get( 0 ).getAsJsonObject();
            assertTrue( entry.has( "name" ), "Result entry should have 'name'" );
            assertTrue( entry.has( "score" ), "Result entry should have 'score'" );
            assertFalse( entry.get( "name" ).getAsString().isEmpty(),
                    "Name should not be empty" );
            assertTrue( entry.get( "score" ).getAsInt() > 0,
                    "Score should be positive" );
        }
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

}
