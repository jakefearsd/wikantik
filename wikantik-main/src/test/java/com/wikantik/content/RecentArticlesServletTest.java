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
package com.wikantik.content;

import com.google.gson.Gson;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RecentArticlesServletTest {

    private TestEngine engine;
    private RecentArticlesServlet servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        engine.saveText( "RecentTestPage", "# Test Page\nSome content for testing." );
        engine.saveText( "AnotherTestPage", "# Another Page\nMore content." );

        servlet = new RecentArticlesServlet();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > doGet( final HttpServletRequest request ) throws Exception {
        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
        final StringWriter writer = new StringWriter();
        Mockito.doReturn( new PrintWriter( writer ) ).when( response ).getWriter();
        servlet.doGet( request, response );
        return gson.fromJson( writer.toString(), Map.class );
    }

    @Test
    void testBasicResponse() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();

        final Map< String, Object > data = doGet( request );

        assertNotNull( data.get( "articles" ) );
        assertNotNull( data.get( "returned" ) );
        assertNotNull( data.get( "query" ) );
    }

    @Test
    void testReturnsArticles() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();

        final Map< String, Object > data = doGet( request );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > articles = ( List< Map< String, Object > > ) data.get( "articles" );
        assertNotNull( articles );
        assertTrue( articles.size() >= 2, "Should return at least the 2 test pages" );
    }

    @Test
    void testCountParameter() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "1" ).when( request ).getParameter( "count" );

        final Map< String, Object > data = doGet( request );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > articles = ( List< Map< String, Object > > ) data.get( "articles" );
        assertTrue( articles.size() <= 1 );
    }

    @Test
    void testCountExceedsMaxIsCapped() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "999" ).when( request ).getParameter( "count" );

        final Map< String, Object > data = doGet( request );

        @SuppressWarnings( "unchecked" )
        final Map< String, Object > query = ( Map< String, Object > ) data.get( "query" );
        // Should be capped at 100
        assertTrue( ( ( Number ) query.get( "count" ) ).intValue() <= 100 );
    }

    @Test
    void testInvalidCountReturnsError() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "notanumber" ).when( request ).getParameter( "count" );

        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
        final StringWriter writer = new StringWriter();
        Mockito.doReturn( new PrintWriter( writer ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
        final String json = writer.toString();
        assertTrue( json.contains( "error" ) );
        assertTrue( json.contains( "Invalid count" ) );
    }

    @Test
    void testNegativeCountReturnsError() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "-5" ).when( request ).getParameter( "count" );

        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
        final StringWriter writer = new StringWriter();
        Mockito.doReturn( new PrintWriter( writer ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void testInvalidSinceReturnsError() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "abc" ).when( request ).getParameter( "since" );

        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
        final StringWriter writer = new StringWriter();
        Mockito.doReturn( new PrintWriter( writer ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void testQueryInfoInResponse() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( "5" ).when( request ).getParameter( "count" );
        Mockito.doReturn( "7" ).when( request ).getParameter( "since" );

        final Map< String, Object > data = doGet( request );

        @SuppressWarnings( "unchecked" )
        final Map< String, Object > query = ( Map< String, Object > ) data.get( "query" );
        assertEquals( 5, ( ( Number ) query.get( "count" ) ).intValue() );
        assertEquals( 7, ( ( Number ) query.get( "sinceDays" ) ).intValue() );
    }

    @Test
    void testContentTypeIsJson() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
        final StringWriter writer = new StringWriter();
        Mockito.doReturn( new PrintWriter( writer ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        Mockito.verify( response ).setContentType( "application/json" );
        Mockito.verify( response ).setCharacterEncoding( "UTF-8" );
    }

    @Test
    void testCorsHeaders() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
        final StringWriter writer = new StringWriter();
        Mockito.doReturn( new PrintWriter( writer ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        Mockito.verify( response ).setHeader( "Access-Control-Allow-Origin", "*" );
    }

    @Test
    void testOptionsHandlesCors() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );

        servlet.doOptions( request, response );

        Mockito.verify( response ).setHeader( "Access-Control-Allow-Origin", "*" );
        Mockito.verify( response ).setStatus( HttpServletResponse.SC_OK );
    }
}
