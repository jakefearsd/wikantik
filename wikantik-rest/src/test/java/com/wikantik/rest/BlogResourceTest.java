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
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.blog.BlogManager;

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
import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link BlogResource} REST servlet.
 *
 * <p>Because JAAS authentication is not available in the {@code wikantik-rest}
 * test context (the user database XML file resolves relative to
 * {@code wikantik-main}), tests that require an authenticated session use a
 * mock {@link Session} and a Mockito spy on the servlet to bypass authorization
 * checks. Authorization enforcement is tested separately in
 * {@link RestAuthorizationSecurityTest}.
 */
class BlogResourceTest {

    private static final String TEST_USER = "testblogger";

    private TestEngine engine;
    private BlogResource servlet;
    private Session mockSession;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new BlogResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );

        // Create a mock authenticated session with a known principal.
        // JAAS is not available in the wikantik-rest test context, so we cannot
        // call engine.adminSession(). Instead we build a mock that satisfies
        // both the BlogResource auth check (isAuthenticated) and the
        // BlogManager session introspection (getLoginPrincipal, getRoles).
        mockSession = createMockSession( TEST_USER, true );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            // Clean up any blogs created during tests
            final BlogManager blogManager = engine.getManager( BlogManager.class );
            try {
                if ( blogManager.blogExists( TEST_USER ) ) {
                    blogManager.deleteBlog( mockSession, TEST_USER );
                }
            } catch ( final Exception e ) {
                /* ignore cleanup errors */
            }
            engine.stop();
        }
    }

    // ----- GET /api/blog — List blogs -----

    @Test
    void testListBlogsEmpty() throws Exception {
        final String json = doGet( null );
        final JsonArray arr = gson.fromJson( json, JsonArray.class );

        assertNotNull( arr, "Response should be a JSON array" );
        assertEquals( 0, arr.size(), "Should be empty when no blogs exist" );
    }

    @Test
    void testListBlogsReturnsCreatedBlog() throws Exception {
        // Create a blog directly via BlogManager using the mock session
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        final String json = doGet( null );
        final JsonArray arr = gson.fromJson( json, JsonArray.class );

        assertNotNull( arr );
        assertEquals( 1, arr.size(), "Should have one blog after creation" );

        final JsonObject blog = arr.get( 0 ).getAsJsonObject();
        assertEquals( TEST_USER, blog.get( "username" ).getAsString() );
        assertTrue( blog.has( "title" ), "Blog should have a title" );
        assertTrue( blog.has( "entryCount" ), "Blog should have entryCount" );
    }

    // ----- POST /api/blog — Create blog -----

    @Test
    void testCreateBlog() throws Exception {
        final String json = doPostAuthenticated( null, null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean(), "Blog creation should succeed" );
        assertTrue( obj.has( "username" ), "Response should include username" );
        assertEquals( TEST_USER, obj.get( "username" ).getAsString() );

        // Verify blog actually exists
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        assertTrue( blogManager.blogExists( TEST_USER ), "Blog should exist after creation" );
    }

    @Test
    void testCreateBlogDuplicate() throws Exception {
        // Create blog first using the mock session
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        // Try to create again via REST
        final String json = doPostAuthenticated( null, null );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(), "Duplicate creation should fail" );
        assertEquals( 409, obj.get( "status" ).getAsInt(), "Should return 409 Conflict" );
    }

    // ----- GET /api/blog/{username} — Get blog metadata -----

    @Test
    void testGetBlogNotFound() throws Exception {
        final String json = doGet( "nonexistent" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetBlogMetadata() throws Exception {
        // Create a blog directly
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        final String json = doGet( TEST_USER );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( TEST_USER, obj.get( "username" ).getAsString() );
        assertTrue( obj.has( "title" ), "Response should include title" );
        assertTrue( obj.has( "entryCount" ), "Response should include entryCount" );
    }

    // ----- DELETE /api/blog/{username} — Delete blog -----

    @Test
    void testDeleteBlog() throws Exception {
        // Create blog first using mock session
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        // Delete via the manager directly (same pattern as PageResourceTest.doDeleteAsAdmin)
        engine.getManager( BlogManager.class ).deleteBlog( mockSession, TEST_USER );

        // Verify gone
        assertFalse( engine.getManager( BlogManager.class ).blogExists( TEST_USER ) );
    }

    @Test
    void testDeleteBlogNotFound() throws Exception {
        final String json = doDeleteAuthenticated( "nonexistent" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ----- POST /api/blog/{username}/entries — Create entry -----

    @Test
    void testCreateEntry() throws Exception {
        // Create blog first
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        final JsonObject body = new JsonObject();
        body.addProperty( "topic", "MyFirstPost" );

        final String json = doPostAuthenticated( TEST_USER + "/entries", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean(), "Entry creation should succeed" );
        assertTrue( obj.has( "name" ), "Response should include entry name" );
        assertTrue( obj.get( "name" ).getAsString().contains( "MyFirstPost" ),
                "Entry name should contain the topic" );
    }

    @Test
    void testCreateEntryMissingTopic() throws Exception {
        // Create blog first
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        final JsonObject body = new JsonObject();
        // No topic field

        final String json = doPostAuthenticated( TEST_USER + "/entries", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testCreateEntryNoBlog() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "topic", "OrphanPost" );

        final String json = doPostAuthenticated( TEST_USER + "/entries", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean(), "Should fail when blog does not exist" );
    }

    // ----- GET /api/blog/{username}/entries — List entries -----

    @Test
    void testListEntries() throws Exception {
        // Create blog and an entry
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( mockSession );
        blogManager.createEntry( mockSession, "TestEntry" );

        final String json = doGet( TEST_USER + "/entries" );
        final JsonArray arr = gson.fromJson( json, JsonArray.class );

        assertNotNull( arr );
        assertEquals( 1, arr.size(), "Should have one entry" );

        final JsonObject entry = arr.get( 0 ).getAsJsonObject();
        assertTrue( entry.has( "name" ), "Entry should have name" );
        assertTrue( entry.get( "name" ).getAsString().contains( "TestEntry" ),
                "Entry name should contain 'TestEntry'" );
    }

    @Test
    void testListEntriesEmpty() throws Exception {
        // Create blog but no entries
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        final String json = doGet( TEST_USER + "/entries" );
        final JsonArray arr = gson.fromJson( json, JsonArray.class );

        assertNotNull( arr );
        assertEquals( 0, arr.size(), "Should be empty with no entries" );
    }

    @Test
    void testListEntriesNoBlog() throws Exception {
        final String json = doGet( "nonexistent/entries" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ----- GET /api/blog/{username}/entries/{name} — Get entry -----

    @Test
    void testGetEntry() throws Exception {
        // Create blog and entry
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( mockSession );
        final Page entryPage = blogManager.createEntry( mockSession, "ReadMe" );

        // Extract the entry slug from the full page name (e.g., "blog/testblogger/20260402ReadMe" -> "20260402ReadMe")
        final String entrySlug = entryPage.getName().substring( entryPage.getName().lastIndexOf( '/' ) + 1 );

        final String json = doGet( TEST_USER + "/entries/" + entrySlug );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "name" ), "Response should include name" );
        assertTrue( obj.has( "content" ), "Response should include content" );
        assertTrue( obj.has( "metadata" ), "Response should include metadata" );
    }

    @Test
    void testGetEntryNotFound() throws Exception {
        // Create blog but no entry with that name
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        final String json = doGet( TEST_USER + "/entries/99991231NoSuchEntry" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ----- PUT /api/blog/{username}/entries/{name} — Update entry -----

    @Test
    void testUpdateEntry() throws Exception {
        // Create blog and entry
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( mockSession );
        final Page entryPage = blogManager.createEntry( mockSession, "Updatable" );
        final String entrySlug = entryPage.getName().substring( entryPage.getName().lastIndexOf( '/' ) + 1 );

        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Updated blog entry content." );

        final String json = doPutAuthenticated( TEST_USER + "/entries/" + entrySlug, body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean(), "Update should succeed" );
    }

    @Test
    void testUpdateEntryNotFound() throws Exception {
        // Create blog but no such entry
        engine.getManager( BlogManager.class ).createBlog( mockSession );

        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Ghost content" );

        final String json = doPutAuthenticated( TEST_USER + "/entries/99991231NoSuchEntry", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ----- DELETE /api/blog/{username}/entries/{name} — Delete entry -----

    @Test
    void testDeleteEntry() throws Exception {
        // Create blog and entry
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( mockSession );
        final Page entryPage = blogManager.createEntry( mockSession, "Deletable" );
        final String entrySlug = entryPage.getName().substring( entryPage.getName().lastIndexOf( '/' ) + 1 );

        final String json = doDeleteAuthenticated( TEST_USER + "/entries/" + entrySlug );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean(), "Delete should succeed" );
    }

    // ----- GET /api/blog/{username}/entries/{name}?render=true — Rendered HTML -----

    @Test
    void testGetEntryWithRender() throws Exception {
        // Create blog and entry
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( mockSession );
        final Page entryPage = blogManager.createEntry( mockSession, "Rendered" );
        final String entrySlug = entryPage.getName().substring( entryPage.getName().lastIndexOf( '/' ) + 1 );

        final HttpServletRequest request = createRequest( TEST_USER + "/entries/" + entrySlug );
        Mockito.doReturn( "true" ).when( request ).getParameter( "render" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.has( "contentHtml" ), "Response should include contentHtml when render=true" );
    }

    // ----- Bad path handling -----

    @Test
    void testPostInvalidPath() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/blog/" + TEST_USER + "/invalid" );
        Mockito.doReturn( "/" + TEST_USER + "/invalid" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        final BlogResource spy = createAuthenticatedSpy();
        spy.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ----- Helper methods -----

    /**
     * Creates a mock {@link Session} with the given username and authentication status.
     * The session's login principal returns the username (lowercased), and
     * if admin is true, the session also has an "Admin" role principal.
     */
    private static Session createMockSession( final String username, final boolean admin ) {
        final Session session = Mockito.mock( Session.class );
        Mockito.doReturn( true ).when( session ).isAuthenticated();

        final Principal loginPrincipal = Mockito.mock( Principal.class );
        Mockito.doReturn( username ).when( loginPrincipal ).getName();
        Mockito.doReturn( loginPrincipal ).when( session ).getLoginPrincipal();
        Mockito.doReturn( loginPrincipal ).when( session ).getUserPrincipal();

        if ( admin ) {
            final Principal adminRole = Mockito.mock( Principal.class );
            Mockito.doReturn( "Admin" ).when( adminRole ).getName();
            Mockito.doReturn( new Principal[]{ adminRole } ).when( session ).getRoles();
        } else {
            Mockito.doReturn( new Principal[]{} ).when( session ).getRoles();
        }

        return session;
    }

    /**
     * Performs a GET request against the blog servlet.
     * @param pathSuffix path after /api/blog/, or null for the root blog list
     */
    private String doGet( final String pathSuffix ) throws Exception {
        final HttpServletRequest request;
        if ( pathSuffix == null ) {
            request = HttpMockFactory.createHttpRequest( "/api/blog" );
            Mockito.doReturn( null ).when( request ).getPathInfo();
        } else {
            request = createRequest( pathSuffix );
        }

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    /**
     * Performs a POST request as an authenticated user (bypassing auth via spy).
     */
    private String doPostAuthenticated( final String pathSuffix, final JsonObject body ) throws Exception {
        final BlogResource spy = createAuthenticatedSpy();

        final HttpServletRequest request;
        if ( pathSuffix == null ) {
            request = HttpMockFactory.createHttpRequest( "/api/blog" );
            Mockito.doReturn( null ).when( request ).getPathInfo();
        } else {
            request = HttpMockFactory.createHttpRequest( "/api/blog/" + pathSuffix );
            Mockito.doReturn( "/" + pathSuffix ).when( request ).getPathInfo();
        }

        if ( body != null ) {
            Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        }

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        spy.doPost( request, response );
        return sw.toString();
    }

    /**
     * Performs a PUT request as an authenticated user (bypassing auth via spy).
     */
    private String doPutAuthenticated( final String pathSuffix, final JsonObject body ) throws Exception {
        final BlogResource spy = createAuthenticatedSpy();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/blog/" + pathSuffix );
        Mockito.doReturn( "/" + pathSuffix ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        spy.doPut( request, response );
        return sw.toString();
    }

    /**
     * Performs a DELETE request as an authenticated user (bypassing auth via spy).
     */
    private String doDeleteAuthenticated( final String pathSuffix ) throws Exception {
        final BlogResource spy = createAuthenticatedSpy();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/blog/" + pathSuffix );
        Mockito.doReturn( "/" + pathSuffix ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        spy.doDelete( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pathSuffix ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/blog/" + pathSuffix );
        Mockito.doReturn( "/" + pathSuffix ).when( request ).getPathInfo();
        return request;
    }

    /**
     * Creates a spy of the servlet that resolves the wiki session to the
     * mock admin session, so BlogManager sees an authenticated user with
     * the correct principal.
     */
    private BlogResource createAuthenticatedSpy() {
        final BlogResource spy = Mockito.spy( servlet );
        Mockito.doReturn( mockSession ).when( spy ).resolveSession( Mockito.any() );
        return spy;
    }

}
