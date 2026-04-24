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
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConvertResourceTest {

    private TestEngine engine;
    private ConvertResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new ConvertResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    @Test
    void doPost_unknownActionReturns404() throws Exception {
        final HttpServletRequest request = createRequest( "bogus" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void doPost_unauthenticatedReturns401() throws Exception {
        final HttpServletRequest request = createRequest( "wiki-to-markdown" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 401, obj.get( "status" ).getAsInt() );
    }

    @Test
    void doPost_invalidJsonReturns400() throws Exception {
        final HttpServletRequest request = createRequest( "wiki-to-markdown" );
        Mockito.doReturn( new BufferedReader( new StringReader( "not json" ) ) ).when( request ).getReader();

        final Session authed = Mockito.mock( Session.class );
        Mockito.when( authed.isAuthenticated() ).thenReturn( true );
        try ( final MockedStatic< Wiki > wiki = mockWikiSession( authed ) ) {
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
            servlet.doPost( request, response );
            final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
            assertTrue( obj.get( "error" ).getAsBoolean() );
            assertEquals( 400, obj.get( "status" ).getAsInt() );
        }
    }

    @Test
    void doPost_convertsWikiToMarkdown() throws Exception {
        final HttpServletRequest request = createRequest( "wiki-to-markdown" );
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "!!Hello World\nLine two" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) )
            .when( request ).getReader();

        final Session authed = Mockito.mock( Session.class );
        Mockito.when( authed.isAuthenticated() ).thenReturn( true );
        try ( final MockedStatic< Wiki > wiki = mockWikiSession( authed ) ) {
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
            servlet.doPost( request, response );
            final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
            assertTrue( obj.has( "markdown" ),
                "Response must include a markdown field, got: " + sw );
            assertTrue( obj.has( "warnings" ),
                "Response must include a warnings field" );
        }
    }

    @Test
    void doPost_missingContentDefaultsToEmpty() throws Exception {
        final HttpServletRequest request = createRequest( "wiki-to-markdown" );
        Mockito.doReturn( new BufferedReader( new StringReader( "{}" ) ) ).when( request ).getReader();

        final Session authed = Mockito.mock( Session.class );
        Mockito.when( authed.isAuthenticated() ).thenReturn( true );
        try ( final MockedStatic< Wiki > wiki = mockWikiSession( authed ) ) {
            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter sw = new StringWriter();
            Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
            servlet.doPost( request, response );
            final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
            assertTrue( obj.has( "markdown" ) );
        }
    }

    private HttpServletRequest createRequest( final String action ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/convert/" + action );
        Mockito.doReturn( "/" + action ).when( request ).getPathInfo();
        return request;
    }

    /**
     * Stubs {@code Wiki.session().find(...)} to return the supplied mock session.
     * The REST convert endpoint gates on {@code session.isAuthenticated()} — stubbing
     * this lets us exercise the authed path without a real JAAS login.
     */
    private MockedStatic< Wiki > mockWikiSession( final Session session ) {
        final MockedStatic< Wiki > wiki = Mockito.mockStatic( Wiki.class, Mockito.CALLS_REAL_METHODS );
        final com.wikantik.api.spi.SessionSPI spi = Mockito.mock( com.wikantik.api.spi.SessionSPI.class );
        Mockito.when( spi.find( Mockito.any(), Mockito.any() ) ).thenReturn( session );
        wiki.when( Wiki::session ).thenReturn( spi );
        return wiki;
    }
}
