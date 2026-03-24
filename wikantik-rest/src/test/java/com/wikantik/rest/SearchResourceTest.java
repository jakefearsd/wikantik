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
