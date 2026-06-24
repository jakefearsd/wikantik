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
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

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

/**
 * Security tests that verify REST API endpoints enforce authorization.
 * <p>
 * These tests focus on <b>denial of unauthorized access</b> — the critical
 * security invariant that was missing before the fix. Positive tests for
 * authenticated users (admin delete, authorized ACL view) are not feasible
 * here because JAAS authentication requires {@code userdatabase.xml} at a
 * filesystem path relative to {@code wikantik-main}, which is not available
 * when running from the {@code wikantik-rest} module. Authenticated-session
 * tests are covered by the {@code wikantik-main} test suite.
 * <p>
 * The test security policy grants:
 * <ul>
 *   <li>All (including anonymous): {@code PagePermission "*:*", "view"}</li>
 *   <li>Anonymous: {@code PagePermission "*:*", "edit"}</li>
 *   <li>Admin only: {@code AllPermission} (which includes delete)</li>
 * </ul>
 * Page-level ACLs (e.g., {@code [{ALLOW view Janne Jalkanen}]}) further restrict access.
 */
class RestAuthorizationSecurityTest {

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
            try { pm.deletePage( "SecTestDeletePage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestAclPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestPublicPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestEditPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestAttPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestDiffAcl" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestHistAcl" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestBacklinkAcl" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestRecentPublic" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestRecentAcl" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestListPublic" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "SecTestListAcl" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    // ===== Security tests (should FAIL before fix, PASS after) =====

    /**
     * Anonymous users lack delete permission per the security policy.
     * The REST endpoint must return 403 and the page must remain.
     */
    @Test
    void testAnonymousDeletePageReturnsForbidden() throws Exception {
        engine.saveText( "SecTestDeletePage", "Content that should survive." );

        final HttpServletRequest request = createAnonymousRequest( "SecTestDeletePage" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertNotNull( obj.get( "error" ),
                "Response must contain 'error' field -- endpoint should deny anonymous delete" );
        assertTrue( obj.get( "error" ).getAsBoolean(), "Response should be an error" );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "Anonymous delete must return 403 Forbidden" );

        // Page must still exist
        final PageManager pm = engine.getManager( PageManager.class );
        assertNotNull( pm.getPage( "SecTestDeletePage" ),
                "Page must not be deleted when permission is denied" );
    }

    /**
     * A page with ACL [{ALLOW view Janne Jalkanen}] should deny anonymous view access.
     * The security policy grants view to "All", but the ACL restricts it to Janne only.
     */
    @Test
    void testAclRestrictedPageDeniesUnauthorizedView() throws Exception {
        engine.saveText( "SecTestAclPage", "[{ALLOW view Janne Jalkanen}]\nRestricted content." );

        final HttpServletRequest request = createAnonymousRequest( "SecTestAclPage" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertNotNull( obj.get( "error" ),
                "Response must contain 'error' field -- endpoint should deny unauthorized view of ACL-restricted page" );
        assertTrue( obj.get( "error" ).getAsBoolean(), "Response should be an error" );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "ACL-restricted page must return 403 for unauthorized user" );
    }

    // ===== Control tests (should PASS both before and after fix) =====

    /**
     * Anonymous users can view unrestricted pages per the security policy.
     */
    @Test
    void testAnonymousCanViewUnrestrictedPage() throws Exception {
        engine.saveText( "SecTestPublicPage", "Public content here." );

        final HttpServletRequest request = createAnonymousRequest( "SecTestPublicPage" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertFalse( obj.has( "error" ), "Anonymous user should be able to view unrestricted page" );
        assertEquals( "SecTestPublicPage", obj.get( "name" ).getAsString() );
        assertTrue( obj.get( "content" ).getAsString().contains( "Public content here." ) );
    }

    /**
     * Anonymous users can edit unrestricted pages per the security policy.
     */
    @Test
    void testAnonymousCanEditUnrestrictedPage() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Anonymously edited content" );
        body.addProperty( "changeNote", "Anonymous edit via REST" );

        final HttpServletRequest request = createAnonymousRequest( "SecTestEditPage" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final String responseBody = sw.toString();
        final JsonObject obj = gson.fromJson( responseBody, JsonObject.class );
        assertFalse( obj.has( "error" ),
                "Anonymous user should be able to edit unrestricted page, but got: " + responseBody );
        assertTrue( obj.get( "success" ).getAsBoolean(),
                "Anonymous user should be able to edit unrestricted page" );
        assertEquals( "SecTestEditPage", obj.get( "name" ).getAsString() );
    }

    // ===== Attachment ACL test =====

    /**
     * Verifies that anonymous users cannot list attachments on a page restricted by ACL.
     * The page ACL restricts view to Admin only, so an anonymous request should get 403.
     */
    @Test
    void testAnonymousCannotAccessAttachmentOnRestrictedPage() throws Exception {
        engine.saveText( "SecTestAttPage", "[{ALLOW view Admin}]\nRestricted." );

        // Set up an AttachmentResource servlet
        final AttachmentResource attachServlet = new AttachmentResource();
        final ServletConfig attConfig = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( attConfig ).getServletContext();
        attachServlet.init( attConfig );

        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "anon-att-session-" + System.nanoTime() ).when( httpSession ).getId();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/SecTestAttPage" );
        Mockito.doReturn( "/SecTestAttPage" ).when( request ).getPathInfo();
        Mockito.doReturn( httpSession ).when( request ).getSession();
        Mockito.doReturn( httpSession ).when( request ).getSession( Mockito.anyBoolean() );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        attachServlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertNotNull( obj.get( "error" ),
                "Response must contain 'error' field -- should deny anonymous attachment listing on ACL-restricted page" );
        assertTrue( obj.get( "error" ).getAsBoolean(), "Response should be an error" );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "Anonymous attachment listing on ACL-restricted page must return 403" );
    }

    // ===== Read-path ACL enforcement (regression for the unauthenticated read-endpoint leak) =====

    /**
     * {@code /api/diff} returned {@code PageManager.getPureText()} — the full raw wiki
     * source of any version — with no permission check. An ACL-restricted page must be
     * denied (403) and its raw text must never appear in the response.
     */
    @Test
    void testAclRestrictedPageDeniesAnonymousDiff() throws Exception {
        engine.saveText( "SecTestDiffAcl", "[{ALLOW view Admin}]\nRestricted secret xyzzy123." );
        engine.saveText( "SecTestDiffAcl", "[{ALLOW view Admin}]\nRestricted secret xyzzy123 revised." );

        final DiffResource diff = initServlet( new DiffResource() );
        final HttpServletRequest req = anonRequest( "/api/diff/SecTestDiffAcl", "/SecTestDiffAcl" );
        Mockito.doReturn( "1" ).when( req ).getParameter( "from" );
        Mockito.doReturn( "2" ).when( req ).getParameter( "to" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        diff.doGet( req, resp );

        final String body = sw.toString();
        final JsonObject obj = gson.fromJson( body, JsonObject.class );
        assertNotNull( obj.get( "error" ), "Diff of ACL-restricted page must be denied for anonymous" );
        assertTrue( obj.get( "error" ).getAsBoolean(), "Response should be an error" );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "Diff of ACL-restricted page must return 403 for unauthorized user" );
        assertFalse( body.contains( "xyzzy123" ),
                "Raw page text must never leak through the diff endpoint" );
    }

    /**
     * {@code /api/history} disclosed version authors/timestamps/change-notes with no
     * permission check. An ACL-restricted page must be denied (403).
     */
    @Test
    void testAclRestrictedPageDeniesAnonymousHistory() throws Exception {
        engine.saveText( "SecTestHistAcl", "[{ALLOW view Admin}]\nRestricted." );

        final HistoryResource hist = initServlet( new HistoryResource() );
        final HttpServletRequest req = anonRequest( "/api/history/SecTestHistAcl", "/SecTestHistAcl" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        hist.doGet( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertNotNull( obj.get( "error" ), "History of ACL-restricted page must be denied for anonymous" );
        assertTrue( obj.get( "error" ).getAsBoolean(), "Response should be an error" );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "History of ACL-restricted page must return 403 for unauthorized user" );
    }

    /**
     * {@code /api/backlinks} exposed the link graph of any page. Enumerating the
     * backlinks of an ACL-restricted page must be denied (403).
     */
    @Test
    void testAclRestrictedPageDeniesAnonymousBacklinks() throws Exception {
        engine.saveText( "SecTestBacklinkAcl", "[{ALLOW view Admin}]\nRestricted." );

        final BacklinksResource bl = initServlet( new BacklinksResource() );
        final HttpServletRequest req = anonRequest( "/api/backlinks/SecTestBacklinkAcl", "/SecTestBacklinkAcl" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        bl.doGet( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertNotNull( obj.get( "error" ), "Backlinks of ACL-restricted page must be denied for anonymous" );
        assertTrue( obj.get( "error" ).getAsBoolean(), "Response should be an error" );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "Backlinks of ACL-restricted page must return 403 for unauthorized user" );
    }

    /**
     * {@code /api/recent-changes} listed every recently-modified page system-wide,
     * including ACL-restricted ones. Restricted pages must be filtered out for an
     * anonymous caller while public pages remain.
     */
    @Test
    void testRecentChangesExcludesAclRestrictedPagesForAnonymous() throws Exception {
        engine.saveText( "SecTestRecentPublic", "Public recent content." );
        engine.saveText( "SecTestRecentAcl", "[{ALLOW view Admin}]\nRestricted recent content." );

        final RecentChangesResource rc = initServlet( new RecentChangesResource() );
        final HttpServletRequest req = anonRequest( "/api/recent-changes", null );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        rc.doGet( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        final java.util.Set< String > names = pageNames( obj.getAsJsonArray( "changes" ) );
        assertTrue( names.contains( "SecTestRecentPublic" ),
                "Public page should appear in anonymous recent-changes" );
        assertFalse( names.contains( "SecTestRecentAcl" ),
                "ACL-restricted page must NOT appear in anonymous recent-changes" );
    }

    /**
     * {@code /api/pages} listed every page name. Restricted pages must be filtered out
     * for an anonymous caller while public pages remain.
     */
    @Test
    void testPageListExcludesAclRestrictedPagesForAnonymous() throws Exception {
        engine.saveText( "SecTestListPublic", "Public list content." );
        engine.saveText( "SecTestListAcl", "[{ALLOW view Admin}]\nRestricted list content." );

        final PageListResource pl = initServlet( new PageListResource() );
        final HttpServletRequest req = anonRequest( "/api/pages", null );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        pl.doGet( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        final java.util.Set< String > names = pageNames( obj.getAsJsonArray( "pages" ) );
        assertTrue( names.contains( "SecTestListPublic" ),
                "Public page should appear in the anonymous page list" );
        assertFalse( names.contains( "SecTestListAcl" ),
                "ACL-restricted page must NOT appear in the anonymous page list" );
    }

    // ===== Helper methods =====

    /** Initialises a servlet against the test engine's servlet context. */
    private < T extends jakarta.servlet.http.HttpServlet > T initServlet( final T servlet ) throws Exception {
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
        return servlet;
    }

    /**
     * Builds a request backed by a fresh (not-logged-in) HttpSession id. {@code pathInfo}
     * may be {@code null} for endpoints that key off query parameters rather than the path.
     */
    private HttpServletRequest anonRequest( final String uri, final String pathInfo ) {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "anon-session-" + System.nanoTime() ).when( httpSession ).getId();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( uri );
        if ( pathInfo != null ) {
            Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        }
        Mockito.doReturn( httpSession ).when( request ).getSession();
        Mockito.doReturn( httpSession ).when( request ).getSession( Mockito.anyBoolean() );
        return request;
    }

    /** Collects the {@code name} field of every object in a results array. */
    private static java.util.Set< String > pageNames( final JsonArray arr ) {
        final java.util.Set< String > names = new java.util.HashSet<>();
        for ( int i = 0; i < arr.size(); i++ ) {
            names.add( arr.get( i ).getAsJsonObject().get( "name" ).getAsString() );
        }
        return names;
    }

    /**
     * Creates a mock request with a fresh HttpSession that is NOT logged in (anonymous/guest).
     */
    private HttpServletRequest createAnonymousRequest( final String pageName ) {
        final HttpSession httpSession = Mockito.mock( HttpSession.class );
        Mockito.doReturn( "anon-session-" + System.nanoTime() ).when( httpSession ).getId();

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        Mockito.doReturn( httpSession ).when( request ).getSession();
        Mockito.doReturn( httpSession ).when( request ).getSession( Mockito.anyBoolean() );
        return request;
    }

}
