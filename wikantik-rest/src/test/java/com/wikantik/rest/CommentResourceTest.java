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

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class CommentResourceTest {

    private TestEngine engine;
    private CommentResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new CommentResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "CommentTestPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "CommentEmptyPage" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testGetCommentsOnPageWithNoComments() throws Exception {
        engine.saveText( "CommentEmptyPage", "Just some content, no comments." );

        final String json = doGet( "CommentEmptyPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "CommentEmptyPage", obj.get( "pageName" ).getAsString() );
        assertTrue( obj.has( "comments" ) );
        final JsonArray comments = obj.getAsJsonArray( "comments" );
        assertEquals( 0, comments.size(), "Page with no comments should return empty array" );
    }

    @Test
    void testAddCommentAndRetrieve() throws Exception {
        engine.saveText( "CommentTestPage", "Page content here." );

        // POST a comment (bypass authorization since JAAS is not available in test context)
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "This is a test comment." );

        final String postJson = doPostAsAuthenticated( "CommentTestPage", body );
        final JsonObject postResult = gson.fromJson( postJson, JsonObject.class );

        // POST should return the new comment
        assertFalse( postResult.has( "error" ), "POST should succeed, got: " + postJson );
        assertTrue( postResult.has( "author" ), "Response should contain author" );
        assertTrue( postResult.has( "date" ), "Response should contain date" );
        assertEquals( "This is a test comment.", postResult.get( "text" ).getAsString() );

        // GET the comments to verify it was persisted
        final String getJson = doGet( "CommentTestPage" );
        final JsonObject getResult = gson.fromJson( getJson, JsonObject.class );

        assertEquals( "CommentTestPage", getResult.get( "pageName" ).getAsString() );
        final JsonArray comments = getResult.getAsJsonArray( "comments" );
        assertEquals( 1, comments.size(), "Should have exactly one comment" );

        final JsonObject comment = comments.get( 0 ).getAsJsonObject();
        assertEquals( "This is a test comment.", comment.get( "text" ).getAsString() );
        assertNotNull( comment.get( "author" ).getAsString() );
        assertNotNull( comment.get( "date" ).getAsString() );
    }

    @Test
    void testAddCommentMissingText() throws Exception {
        engine.saveText( "CommentTestPage", "Page content here." );

        // POST without text field
        final JsonObject body = new JsonObject();

        final String json = doPostAsAuthenticated( "CommentTestPage", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "text" ) );
    }

    @Test
    void testAddCommentBlankText() throws Exception {
        engine.saveText( "CommentTestPage", "Page content here." );

        final JsonObject body = new JsonObject();
        body.addProperty( "text", "   " );

        final String json = doPostAsAuthenticated( "CommentTestPage", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testAddCommentOnNonexistentPage() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "Comment on missing page" );

        final String json = doPostAsAuthenticated( "NonExistentCommentPage99", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetCommentsMissingPageName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/comments" );
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
    void testGetCommentsOnNonexistentPage() throws Exception {
        final String json = doGet( "NonExistentCommentPage99" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testPostCommentMissingPageName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/comments" );
        Mockito.doReturn( null ).when( request ).getPathInfo();
        final JsonObject body = new JsonObject();
        body.addProperty( "text", "some comment" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testParseCommentsFromPageText() {
        final String pageText = "Some page content.\n\n"
                + "<!-- comment:author=Alice:date=2026-03-28T12:00:00Z -->\n"
                + "> **Alice** — March 28, 2026:\n"
                + "> \n"
                + "> Hello from Alice.\n"
                + "\n"
                + "<!-- comment:author=Bob:date=2026-03-28T13:00:00Z -->\n"
                + "> **Bob** — March 28, 2026:\n"
                + "> \n"
                + "> Hello from Bob.\n";

        final List< Map< String, String > > comments = CommentResource.parseComments( pageText );
        assertEquals( 2, comments.size() );

        assertEquals( "Alice", comments.get( 0 ).get( "author" ) );
        assertEquals( "2026-03-28T12:00:00Z", comments.get( 0 ).get( "date" ) );
        assertEquals( "Hello from Alice.", comments.get( 0 ).get( "text" ) );

        assertEquals( "Bob", comments.get( 1 ).get( "author" ) );
        assertEquals( "2026-03-28T13:00:00Z", comments.get( 1 ).get( "date" ) );
        assertEquals( "Hello from Bob.", comments.get( 1 ).get( "text" ) );
    }

    @Test
    void testParseCommentsFromEmptyText() {
        final List< Map< String, String > > comments = CommentResource.parseComments( "" );
        assertTrue( comments.isEmpty() );
    }

    @Test
    void testBuildCommentBlock() {
        final String block = CommentResource.buildCommentBlock( "Alice", "2026-03-28T12:00:00Z", "Test comment." );

        assertTrue( block.contains( "<!-- comment:author=Alice:date=2026-03-28T12:00:00Z -->" ) );
        assertTrue( block.contains( "> **Alice**" ) );
        assertTrue( block.contains( "> Test comment." ) );
    }

    @Test
    void testPostInvalidJsonReturns400() throws Exception {
        engine.saveText( "CommentTestPage", "Page content." );

        final CommentResource spy = Mockito.spy( servlet );
        Mockito.doReturn( true ).when( spy ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString() );

        final HttpServletRequest request = createRequest( "CommentTestPage" );
        Mockito.doReturn( new BufferedReader( new StringReader( "not valid json" ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        spy.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
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

    /**
     * Performs a POST request using a spy servlet that bypasses authorization.
     * JAAS authentication is not available in the {@code wikantik-rest} test context,
     * so we spy on the servlet and stub {@code checkPagePermission} to always allow.
     */
    private String doPostAsAuthenticated( final String pageName, final JsonObject body ) throws Exception {
        final CommentResource spy = Mockito.spy( servlet );
        Mockito.doReturn( true ).when( spy ).checkPagePermission(
                Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString() );

        final HttpServletRequest request = createRequest( pageName );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        spy.doPost( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pageName ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/comments/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        return request;
    }

}
