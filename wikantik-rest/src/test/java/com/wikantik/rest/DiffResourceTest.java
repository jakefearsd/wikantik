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

class DiffResourceTest {

    private TestEngine engine;
    private DiffResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        // Create a test page with two versions
        engine.saveText( "RestDiffPage", "Version 1 content." );
        engine.saveText( "RestDiffPage", "Version 2 content with changes." );

        servlet = new DiffResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "RestDiffPage" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testDiffBetweenVersions() throws Exception {
        final String json = doGet( "RestDiffPage", "1", "2" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestDiffPage", obj.get( "page" ).getAsString() );
        assertEquals( 1, obj.get( "from" ).getAsInt() );
        assertEquals( 2, obj.get( "to" ).getAsInt() );
        assertTrue( obj.has( "diff" ) );
    }

    @Test
    void testDiffMissingFromParam() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/diff/RestDiffPage" );
        Mockito.doReturn( "/RestDiffPage" ).when( request ).getPathInfo();
        Mockito.doReturn( null ).when( request ).getParameter( "from" );
        Mockito.doReturn( "2" ).when( request ).getParameter( "to" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDiffMissingToParam() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/diff/RestDiffPage" );
        Mockito.doReturn( "/RestDiffPage" ).when( request ).getPathInfo();
        Mockito.doReturn( "1" ).when( request ).getParameter( "from" );
        Mockito.doReturn( null ).when( request ).getParameter( "to" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDiffInvalidVersionParam() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/diff/RestDiffPage" );
        Mockito.doReturn( "/RestDiffPage" ).when( request ).getPathInfo();
        Mockito.doReturn( "abc" ).when( request ).getParameter( "from" );
        Mockito.doReturn( "2" ).when( request ).getParameter( "to" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDiffPageNotFound() throws Exception {
        final String json = doGet( "NonExistentPage12345", "1", "2" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDiffMissingPageName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/diff" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ----- Helper methods -----

    private String doGet( final String pageName, final String from, final String to ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/diff/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        Mockito.doReturn( from ).when( request ).getParameter( "from" );
        Mockito.doReturn( to ).when( request ).getParameter( "to" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

}
