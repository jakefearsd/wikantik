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

class PageListResourceTest {

    private TestEngine engine;
    private PageListResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        engine.saveText( "RestListAlpha", "Alpha page." );
        engine.saveText( "RestListBeta", "Beta page." );
        engine.saveText( "RestListGamma", "Gamma page." );

        servlet = new PageListResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "RestListAlpha" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestListBeta" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestListGamma" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testListPagesReturnsAll() throws Exception {
        final String json = doGetList( null, null, null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pages" ) );
        assertTrue( obj.has( "total" ) );
        assertTrue( obj.get( "total" ).getAsInt() >= 3 );

        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertTrue( pages.size() >= 3 );
    }

    @Test
    void testListPagesWithPrefix() throws Exception {
        final String json = doGetList( "RestList", null, null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( 3, obj.get( "total" ).getAsInt() );

        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertEquals( 3, pages.size() );

        // Verify alphabetical order
        assertEquals( "RestListAlpha", pages.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "RestListBeta", pages.get( 1 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "RestListGamma", pages.get( 2 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void testListPagesWithPagination() throws Exception {
        final String json = doGetList( "RestList", "2", "0" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( 3, obj.get( "total" ).getAsInt() );
        assertEquals( 0, obj.get( "offset" ).getAsInt() );
        assertEquals( 2, obj.get( "limit" ).getAsInt() );

        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertEquals( 2, pages.size() );
    }

    @Test
    void testListPagesWithOffset() throws Exception {
        final String json = doGetList( "RestList", "10", "2" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( 3, obj.get( "total" ).getAsInt() );
        assertEquals( 2, obj.get( "offset" ).getAsInt() );

        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertEquals( 1, pages.size() );
        assertEquals( "RestListGamma", pages.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void testListPageEntryFields() throws Exception {
        final String json = doGetList( "RestListAlpha", null, null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertTrue( pages.size() >= 1 );

        final JsonObject entry = pages.get( 0 ).getAsJsonObject();
        assertTrue( entry.has( "name" ) );
        assertTrue( entry.has( "lastModified" ) );
        assertTrue( entry.has( "version" ) );
        assertTrue( entry.has( "author" ) );
    }

    @Test
    void testListPagesWithInvalidLimit() throws Exception {
        final String json = doGetList( null, "not-a-number", null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // Invalid limit should fallback to default (100), not cause an error
        assertFalse( obj.has( "error" ), "Invalid limit should not cause an error" );
        assertTrue( obj.has( "pages" ) );
        assertEquals( 100, obj.get( "limit" ).getAsInt(),
                "Invalid limit should default to 100" );
    }

    @Test
    void testListPagesWithInvalidOffset() throws Exception {
        final String json = doGetList( null, null, "xyz" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertFalse( obj.has( "error" ), "Invalid offset should not cause an error" );
        assertTrue( obj.has( "pages" ) );
        assertEquals( 0, obj.get( "offset" ).getAsInt(),
                "Invalid offset should default to 0" );
    }

    // ----- Helper methods -----

    private String doGetList( final String prefix, final String limit, final String offset ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages" );
        Mockito.doReturn( prefix ).when( request ).getParameter( "prefix" );
        Mockito.doReturn( limit ).when( request ).getParameter( "limit" );
        Mockito.doReturn( offset ).when( request ).getParameter( "offset" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

}
