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

class BacklinksResourceTest {

    private TestEngine engine;
    private BacklinksResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        // Create a target page and a page that links to it
        engine.saveText( "RestBacklinkTarget", "Target page content." );
        engine.saveText( "RestBacklinkSource", "This page links to [RestBacklinkTarget]." );

        servlet = new BacklinksResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "RestBacklinkTarget" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestBacklinkSource" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestBacklinkOrphan" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testBacklinksFound() throws Exception {
        final String json = doGet( "RestBacklinkTarget" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestBacklinkTarget", obj.get( "name" ).getAsString() );
        assertTrue( obj.has( "backlinks" ) );
        assertTrue( obj.has( "count" ) );
        assertTrue( obj.get( "backlinks" ).isJsonArray() );
    }

    @Test
    void testBacklinksEmptyForOrphanPage() throws Exception {
        engine.saveText( "RestBacklinkOrphan", "Nobody links to this page." );

        final String json = doGet( "RestBacklinkOrphan" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestBacklinkOrphan", obj.get( "name" ).getAsString() );
        final JsonArray backlinks = obj.getAsJsonArray( "backlinks" );
        assertEquals( 0, backlinks.size() );
        assertEquals( 0, obj.get( "count" ).getAsInt() );
    }

    @Test
    void testBacklinksMissingName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/backlinks" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testBacklinksNonExistentPageReturnsEmptyArray() throws Exception {
        final String json = doGet( "NonExistentPage12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "NonExistentPage12345", obj.get( "name" ).getAsString() );
        final JsonArray backlinks = obj.getAsJsonArray( "backlinks" );
        assertEquals( 0, backlinks.size() );
        assertEquals( 0, obj.get( "count" ).getAsInt() );
    }

    // ----- Helper methods -----

    private String doGet( final String pageName ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/backlinks/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

}
