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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class OutboundLinksResourceTest {

    private TestEngine engine;
    private OutboundLinksResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        // Create pages with outbound links
        engine.saveText( "RestOutboundTarget", "Target page." );
        engine.saveText( "RestOutboundSource", "This links to [RestOutboundTarget]." );
        engine.saveText( "RestOutboundOrphan", "No outbound links here." );

        servlet = new OutboundLinksResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            engine.deleteQuietly( "RestOutboundTarget", "RestOutboundSource", "RestOutboundOrphan" );
            engine.stop();
        }
    }

    @Test
    void testOutboundLinksFound() throws Exception {
        final String json = doGet( "RestOutboundSource" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestOutboundSource", obj.get( "name" ).getAsString() );
        assertTrue( obj.has( "links" ) );
        assertTrue( obj.has( "count" ) );
        assertTrue( obj.get( "links" ).isJsonArray() );
    }

    @Test
    void testOutboundLinksEmptyForOrphan() throws Exception {
        final String json = doGet( "RestOutboundOrphan" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestOutboundOrphan", obj.get( "name" ).getAsString() );
        final JsonArray links = obj.getAsJsonArray( "links" );
        assertEquals( 0, links.size() );
        assertEquals( 0, obj.get( "count" ).getAsInt() );
    }

    @Test
    void testOutboundLinksNonExistentPage() throws Exception {
        final String json = doGet( "NonExistentPage12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "NonExistentPage12345", obj.get( "name" ).getAsString() );
        final JsonArray links = obj.getAsJsonArray( "links" );
        assertEquals( 0, links.size() );
        assertEquals( 0, obj.get( "count" ).getAsInt() );
    }

    @Test
    void testOutboundLinksOfARestrictedPageAreDeniedToAnonymous() throws Exception {
        engine.saveText( "RestOutboundSecret", "[{ALLOW view Admin}]\n\nLinks to [RestOutboundTarget]." );
        try {
            final JsonObject obj = gson.fromJson( doGet( "RestOutboundSecret" ), JsonObject.class );

            assertTrue( obj.has( "error" ), "a restricted page's link list must be refused: " + obj );
            assertEquals( 403, obj.get( "status" ).getAsInt() );
            assertFalse( obj.toString().contains( "RestOutboundTarget" ) );
        } finally {
            engine.deleteQuietly( "RestOutboundSecret" );
        }
    }

    @Test
    void testOutboundLinksOmitTargetsTheCallerCannotView() throws Exception {
        engine.saveText( "RestOutboundHidden", "[{ALLOW view Admin}]\n\nHidden." );
        engine.saveText( "RestOutboundPublicLinker", "Links to [RestOutboundHidden](RestOutboundHidden) and [RestOutboundTarget](RestOutboundTarget)." );
        try {
            final JsonObject obj = gson.fromJson( doGet( "RestOutboundPublicLinker" ), JsonObject.class );

            final String links = obj.getAsJsonArray( "links" ).toString();
            assertTrue( links.contains( "RestOutboundTarget" ), "positive control: a viewable target is listed" );
            assertFalse( links.contains( "RestOutboundHidden" ), "a hidden target was named: " + links );
            assertEquals( 1, obj.get( "count" ).getAsInt() );
        } finally {
            engine.deleteQuietly( "RestOutboundHidden", "RestOutboundPublicLinker" );
        }
    }

    @Test
    void testOutboundLinksMissingName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/outbound-links" );
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

    private String doGet( final String pageName ) throws Exception {
        // A fresh session id per call: the mock requests otherwise share one session, and its login state.
        final jakarta.servlet.http.HttpSession sess = Mockito.mock( jakarta.servlet.http.HttpSession.class );
        Mockito.doReturn( "anon-outbound-" + System.nanoTime() ).when( sess ).getId();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/outbound-links/" + pageName );
        Mockito.doReturn( sess ).when( request ).getSession();
        Mockito.doReturn( sess ).when( request ).getSession( Mockito.anyBoolean() );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

}
