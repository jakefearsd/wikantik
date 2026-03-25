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

class RecentChangesResourceTest {

    private TestEngine engine;
    private RecentChangesResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        engine.saveText( "RestRecentPage1", "First recent page." );
        engine.saveText( "RestRecentPage2", "Second recent page." );

        servlet = new RecentChangesResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "RestRecentPage1" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestRecentPage2" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testRecentChangesWithPages() throws Exception {
        final String json = doGet( null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "changes" ) );
        assertTrue( obj.has( "total" ) );
        assertTrue( obj.get( "changes" ).isJsonArray() );

        final JsonArray changes = obj.getAsJsonArray( "changes" );
        assertTrue( changes.size() >= 2, "Should have at least 2 recent changes" );
    }

    @Test
    void testRecentChangesFields() throws Exception {
        final String json = doGet( null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray changes = obj.getAsJsonArray( "changes" );
        assertTrue( changes.size() > 0 );

        final JsonObject entry = changes.get( 0 ).getAsJsonObject();
        assertTrue( entry.has( "name" ) );
        assertTrue( entry.has( "author" ) );
        assertTrue( entry.has( "lastModified" ) );
        assertTrue( entry.has( "version" ) );
    }

    @Test
    void testRecentChangesWithLimit() throws Exception {
        final String json = doGet( "1" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray changes = obj.getAsJsonArray( "changes" );
        assertEquals( 1, changes.size() );
        assertEquals( 1, obj.get( "total" ).getAsInt() );
    }

    @Test
    void testRecentChangesDefaultLimit() throws Exception {
        final String json = doGet( null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // Should not exceed 50 entries (default limit)
        final JsonArray changes = obj.getAsJsonArray( "changes" );
        assertTrue( changes.size() <= 50 );
    }

    // ----- Helper methods -----

    private String doGet( final String limit ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/recent-changes" );
        Mockito.doReturn( limit ).when( request ).getParameter( "limit" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

}
