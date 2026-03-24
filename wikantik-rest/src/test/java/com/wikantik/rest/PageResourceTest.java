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

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class PageResourceTest {

    private TestEngine engine;
    private PageResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new PageResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "RestTestPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestTestFrontmatter" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestPutPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestDeletePage" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testGetPage() throws Exception {
        engine.saveText( "RestTestPage", "Hello from REST test." );

        final String json = doGet( "RestTestPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestTestPage", obj.get( "name" ).getAsString() );
        assertTrue( obj.get( "content" ).getAsString().contains( "Hello from REST test." ) );
        assertTrue( obj.get( "exists" ).getAsBoolean() );
        assertTrue( obj.get( "version" ).getAsInt() >= 1 );
    }

    @Test
    void testGetPageWithFrontmatter() throws Exception {
        engine.saveText( "RestTestFrontmatter",
                "---\ntype: article\ntags: [rest, test]\n---\nBody content here." );

        final String json = doGet( "RestTestFrontmatter" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestTestFrontmatter", obj.get( "name" ).getAsString() );
        assertTrue( obj.get( "content" ).getAsString().contains( "Body content here." ) );
        assertNotNull( obj.get( "metadata" ) );
        assertTrue( obj.get( "metadata" ).isJsonObject() );
        assertEquals( "article", obj.getAsJsonObject( "metadata" ).get( "type" ).getAsString() );
    }

    @Test
    void testGetPageNotFound() throws Exception {
        final String json = doGet( "NonExistentPage12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetPageMissingName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages" );
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
    void testPutPageCreateNew() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "New page content" );
        body.addProperty( "changeNote", "Created via REST" );

        final String json = doPut( "RestPutPage", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( "RestPutPage", obj.get( "name" ).getAsString() );

        // Verify page was actually saved
        final PageManager pm = engine.getManager( PageManager.class );
        assertNotNull( pm.getPage( "RestPutPage" ) );
    }

    @Test
    void testDeletePage() throws Exception {
        engine.saveText( "RestDeletePage", "Page to delete." );

        final String json = doDelete( "RestDeletePage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( "RestDeletePage", obj.get( "name" ).getAsString() );

        // Verify page was deleted
        final PageManager pm = engine.getManager( PageManager.class );
        assertNull( pm.getPage( "RestDeletePage" ) );
    }

    @Test
    void testCorsHeaders() throws Exception {
        engine.saveText( "RestTestPage", "CORS test." );

        final HttpServletRequest request = createRequest( "RestTestPage" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        Mockito.verify( response ).setHeader( "Access-Control-Allow-Origin", "*" );
        Mockito.verify( response ).setHeader( "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS" );
        Mockito.verify( response ).setHeader( "Access-Control-Allow-Headers", "Content-Type, Authorization" );
    }

    @Test
    void testOptionsPreflightReturnsCors() throws Exception {
        final HttpServletRequest request = createRequest( "AnyPage" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();

        servlet.doOptions( request, response );

        Mockito.verify( response ).setHeader( "Access-Control-Allow-Origin", "*" );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_OK );
    }

    // ----- Helper methods -----

    private String doGet( final String pageName ) throws Exception {
        final HttpServletRequest request = createRequest( pageName );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private String doPut( final String pageName, final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( pageName );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );
        return sw.toString();
    }

    private String doDelete( final String pageName ) throws Exception {
        final HttpServletRequest request = createRequest( pageName );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pageName ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        return request;
    }

}
