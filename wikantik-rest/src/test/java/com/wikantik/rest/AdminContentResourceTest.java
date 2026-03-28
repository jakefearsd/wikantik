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

class AdminContentResourceTest {

    private TestEngine engine;
    private AdminContentResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new AdminContentResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void testGetStats() throws Exception {
        final String json = doGet( "/stats" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pageCount" ), "Response should contain 'pageCount'" );
        assertTrue( obj.has( "caches" ), "Response should contain 'caches'" );
        assertTrue( obj.get( "pageCount" ).getAsInt() >= 0, "Page count should be non-negative" );
    }

    @Test
    void testGetOrphanedPages() throws Exception {
        final String json = doGet( "/orphaned-pages" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pages" ), "Response should contain 'pages' key" );
        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertNotNull( pages, "Pages array should not be null" );
    }

    @Test
    void testGetBrokenLinks() throws Exception {
        final String json = doGet( "/broken-links" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "links" ), "Response should contain 'links' key" );
        final JsonArray links = obj.getAsJsonArray( "links" );
        assertNotNull( links, "Links array should not be null" );
    }

    @Test
    void testBulkDeleteWithEmptyList() throws Exception {
        final JsonObject body = new JsonObject();
        body.add( "pages", new JsonArray() );

        final String json = doPost( "/bulk-delete", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testReindex() throws Exception {
        final JsonObject body = new JsonObject();

        final String json = doPost( "/reindex", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "started" ), "Response should contain 'started' key" );
        assertTrue( obj.get( "started" ).getAsBoolean() );
        assertTrue( obj.has( "pagesQueued" ), "Response should contain 'pagesQueued' key" );
    }

    @Test
    void testCacheFlush() throws Exception {
        final JsonObject body = new JsonObject();

        final String json = doPost( "/cache/flush", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "flushed" ), "Response should contain 'flushed' key" );
        assertTrue( obj.get( "flushed" ).getAsBoolean() );
        assertTrue( obj.has( "entriesRemoved" ), "Response should contain 'entriesRemoved' key" );
    }

    @Test
    void testUnknownGetEndpoint() throws Exception {
        final String json = doGet( "/unknown" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testUnknownPostEndpoint() throws Exception {
        final JsonObject body = new JsonObject();

        final String json = doPost( "/unknown", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testIsCrossOriginAllowedReturnsFalse() {
        assertFalse( servlet.isCrossOriginAllowed(),
                "Admin content endpoint should not allow cross-origin requests" );
    }

    // ----- Helper methods -----

    private String doGet( final String pathInfo ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private String doPost( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pathInfo ) {
        final String path = "/admin/content" + ( pathInfo != null ? pathInfo : "" );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( path );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        return request;
    }
}
