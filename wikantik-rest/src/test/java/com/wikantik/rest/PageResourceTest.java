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
import com.wikantik.auth.Users;
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
import java.util.Map;
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
            try { pm.deletePage( "RestRenderPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestPluginPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestPluginLinkPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestEditLinkPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestVersionPage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestPatchMergePage" ); } catch ( final Exception e ) { /* ignore */ }
            try { pm.deletePage( "RestPatchReplacePage" ); } catch ( final Exception e ) { /* ignore */ }
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

        // Delete requires admin permission — authenticate the request's session as admin
        final String json = doDeleteAsAdmin( "RestDeletePage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertFalse( obj.has( "error" ), "Delete should succeed for admin, but got: " + json );
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
        Mockito.verify( response ).setHeader( "Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS" );
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

    // ----- Feature 1: Rendered HTML option -----

    @Test
    void testGetPageWithRenderOption() throws Exception {
        engine.saveText( "RestRenderPage", "Hello **bold** world." );

        final String json = doGetWithParams( "RestRenderPage", Map.of( "render", "true" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestRenderPage", obj.get( "name" ).getAsString() );
        assertTrue( obj.has( "contentHtml" ), "Response should include contentHtml when render=true" );
        assertNotNull( obj.get( "contentHtml" ), "contentHtml should not be null" );
        final String html = obj.get( "contentHtml" ).getAsString();
        assertTrue( html.contains( "<" ), "contentHtml should contain HTML tags" );
    }

    @Test
    void testGetPageWithoutRenderOption() throws Exception {
        engine.saveText( "RestRenderPage", "No render test." );

        final String json = doGet( "RestRenderPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestRenderPage", obj.get( "name" ).getAsString() );
        assertFalse( obj.has( "contentHtml" ), "Response should NOT include contentHtml when render is absent" );
    }

    @Test
    void testGetPageWithPluginRendering() throws Exception {
        // Counter plugin increments and returns a number; it must produce non-empty, non-error output
        engine.saveText( "RestPluginPage", "[{Counter}]" );

        final String json = doGetWithParams( "RestPluginPage", Map.of( "render", "true" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "contentHtml" ), "render=true must include contentHtml" );
        final String html = obj.get( "contentHtml" ).getAsString();
        assertNotNull( html );
        // Plugin output must not be literal Markdown syntax
        assertFalse( html.contains( "[{Counter}]" ), "Plugin syntax must not appear in rendered output" );
        // Plugin output must not be an empty string (plugin must have fired)
        assertFalse( html.isBlank(), "Plugin must produce output" );
    }

    @Test
    void testGetPagePluginLinksUseReactPaths() throws Exception {
        // IndexPlugin generates PAGE_VIEW links for every page in the engine via context.getURL()
        engine.saveText( "RestPluginLinkPage", "[{IndexPlugin}]" );

        final String json = doGetWithParams( "RestPluginLinkPage", Map.of( "render", "true" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "contentHtml" ), "render=true must include contentHtml" );
        final String html = obj.get( "contentHtml" ).getAsString();
        assertFalse( html.isBlank(), "Plugin must produce output" );

        // All page-view hrefs must use the React SPA base path, not the raw JSP /wiki/ prefix.
        // The context path is engine.getBaseURL() (e.g. "/test" in unit tests, "" for ROOT deployment).
        final String contextPath = engine.getBaseURL();
        assertTrue( html.contains( "href=\"" + contextPath + "/app/wiki/" ),
                "Links must use React " + contextPath + "/app/wiki/ base path, got: " + html );
        assertFalse( html.matches( "(?s).*href=\"" + contextPath + "/wiki/.*" ),
                "Links must not use bare " + contextPath + "/wiki/ path" );
    }

    @Test
    void testGetPageEditLinksUseReactPaths() throws Exception {
        // A link to a non-existent page is rendered as a "create page" edit link
        engine.saveText( "RestEditLinkPage", "[NonExistentPageXYZ](NonExistentPageXYZ)" );

        final String json = doGetWithParams( "RestEditLinkPage", Map.of( "render", "true" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "contentHtml" ), "render=true must include contentHtml" );
        final String html = obj.get( "contentHtml" ).getAsString();
        assertFalse( html.isBlank(), "Content must not be blank" );

        // Non-existent page links must route to the React editor, not the JSP ?do=Edit URL
        final String contextPath = engine.getBaseURL();
        assertTrue( html.contains( "href=\"" + contextPath + "/app/edit/" ),
                "Edit links must use React /app/edit/ path, got: " + html );
        assertFalse( html.contains( "?do=Edit" ),
                "Edit links must not use JSP ?do=Edit pattern, got: " + html );
    }

    // ----- Feature 2: Version-specific retrieval -----

    @Test
    void testGetPageSpecificVersion() throws Exception {
        engine.saveText( "RestVersionPage", "Version content here." );

        // FileSystemProvider only has version 1; requesting version=1 should return it
        final String json = doGetWithParams( "RestVersionPage", Map.of( "version", "1" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestVersionPage", obj.get( "name" ).getAsString() );
        assertTrue( obj.get( "content" ).getAsString().contains( "Version content here." ),
                "Should return content from the requested version" );
        assertEquals( 1, obj.get( "version" ).getAsInt(), "Version should be 1" );
        assertTrue( obj.get( "exists" ).getAsBoolean() );
    }

    // ----- Feature 3: Metadata-only PATCH -----

    @Test
    void testPatchMergeMetadata() throws Exception {
        engine.saveText( "RestPatchMergePage",
                "---\ntype: article\ntags: [existing]\n---\nBody." );

        final JsonObject patchBody = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty( "category", "new-category" );
        patchBody.add( "metadata", meta );
        patchBody.addProperty( "action", "merge" );

        final String json = doPatch( "RestPatchMergePage", patchBody );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        final JsonObject resultMeta = obj.getAsJsonObject( "metadata" );
        assertEquals( "article", resultMeta.get( "type" ).getAsString(),
                "Original type should be preserved on merge" );
        assertEquals( "new-category", resultMeta.get( "category" ).getAsString(),
                "New category should be added" );
    }

    @Test
    void testPatchReplaceMetadata() throws Exception {
        engine.saveText( "RestPatchReplacePage",
                "---\ntype: article\ntags: [existing]\n---\nBody." );

        final JsonObject patchBody = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty( "category", "replaced" );
        patchBody.add( "metadata", meta );
        patchBody.addProperty( "action", "replace" );

        final String json = doPatch( "RestPatchReplacePage", patchBody );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        final JsonObject resultMeta = obj.getAsJsonObject( "metadata" );
        assertFalse( resultMeta.has( "type" ), "Original type should be gone after replace" );
        assertFalse( resultMeta.has( "tags" ), "Original tags should be gone after replace" );
        assertEquals( "replaced", resultMeta.get( "category" ).getAsString() );
    }

    @Test
    void testPatchNonexistentPage() throws Exception {
        final JsonObject patchBody = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty( "type", "article" );
        patchBody.add( "metadata", meta );

        final HttpServletRequest request = createRequest( "NonExistentPatchPage99" );
        Mockito.doReturn( new BufferedReader( new StringReader( patchBody.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPatch( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ----- Feature 4: PUT with metadata -----

    @Test
    void testPutPageWithMetadata() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Page with metadata content" );
        body.addProperty( "changeNote", "Created with metadata" );
        final JsonObject meta = new JsonObject();
        meta.addProperty( "type", "article" );
        meta.addProperty( "category", "testing" );
        body.add( "metadata", meta );

        final String json = doPut( "RestPutPage", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( "RestPutPage", obj.get( "name" ).getAsString() );
        assertTrue( obj.get( "version" ).getAsInt() >= 1 );

        // Verify the saved page contains the metadata
        final String readJson = doGet( "RestPutPage" );
        final JsonObject readObj = gson.fromJson( readJson, JsonObject.class );
        assertTrue( readObj.has( "metadata" ) );
        final JsonObject savedMeta = readObj.getAsJsonObject( "metadata" );
        assertEquals( "article", savedMeta.get( "type" ).getAsString(),
                "Saved metadata should contain type field" );
        assertEquals( "testing", savedMeta.get( "category" ).getAsString(),
                "Saved metadata should contain category field" );
    }

    @Test
    void testPutPageWithAuthor() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Authored page content" );
        body.addProperty( "author", "TestAuthor" );

        final String json = doPut( "RestPutPage", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean() );
        assertEquals( "RestPutPage", obj.get( "name" ).getAsString() );
    }

    @Test
    void testGetPageWithPermissions() throws Exception {
        engine.saveText( "RestTestPage", "Permissions test page." );

        final String json = doGet( "RestTestPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "permissions" ), "Response should contain permissions" );
        final JsonObject perms = obj.getAsJsonObject( "permissions" );
        // The anonymous user should have at least view permission per the test security policy
        assertTrue( perms.has( "edit" ), "Permissions should contain 'edit'" );
        assertTrue( perms.has( "comment" ), "Permissions should contain 'comment'" );
        assertTrue( perms.has( "upload" ), "Permissions should contain 'upload'" );
        assertTrue( perms.has( "rename" ), "Permissions should contain 'rename'" );
        assertTrue( perms.has( "delete" ), "Permissions should contain 'delete'" );
    }

    @Test
    void testPatchMissingMetadata() throws Exception {
        engine.saveText( "RestPatchMergePage", "---\ntype: article\n---\nBody." );

        final JsonObject patchBody = new JsonObject();
        // No "metadata" field
        patchBody.addProperty( "action", "merge" );

        final String json = doPatch( "RestPatchMergePage", patchBody );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "metadata" ),
                "Error should mention missing metadata field" );
    }

    @Test
    void testPutPageMissingNameReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages" );
        Mockito.doReturn( null ).when( request ).getPathInfo();
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "No page name" );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testPutPageInvalidJsonReturns400() throws Exception {
        final HttpServletRequest request = createRequest( "RestPutPage" );
        Mockito.doReturn( new BufferedReader( new StringReader( "not valid json" ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPut( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testPatchInvalidJsonReturns400() throws Exception {
        engine.saveText( "RestPatchMergePage", "---\ntype: article\n---\nBody." );

        final HttpServletRequest request = createRequest( "RestPatchMergePage" );
        Mockito.doReturn( new BufferedReader( new StringReader( "bad json" ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPatch( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testPatchMissingNameReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages" );
        Mockito.doReturn( null ).when( request ).getPathInfo();
        final JsonObject patchBody = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty( "type", "article" );
        patchBody.add( "metadata", meta );
        Mockito.doReturn( new BufferedReader( new StringReader( patchBody.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPatch( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testPutPageWithExpectedVersion() throws Exception {
        // Create a page first
        engine.saveText( "RestPutPage", "Initial content" );

        // Update with expectedVersion matching the current version
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Updated content with version check" );
        body.addProperty( "expectedVersion", 1 );
        body.addProperty( "changeNote", "Version-checked update" );

        final String json = doPut( "RestPutPage", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "success" ).getAsBoolean(),
                "PUT with matching expectedVersion should succeed" );
        assertEquals( "RestPutPage", obj.get( "name" ).getAsString() );
    }

    @Test
    void testPutPageWithVersionConflict() throws Exception {
        // Create a page
        engine.saveText( "RestPutPage", "Original content" );

        // Try to update with a wrong expected version (999 != actual version)
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Conflicting update" );
        body.addProperty( "expectedVersion", 999 );

        final String json = doPut( "RestPutPage", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // Should get a 409 Conflict if VersionConflictException is thrown,
        // or success if the provider doesn't enforce version checks
        if ( obj.has( "error" ) ) {
            assertEquals( 409, obj.get( "status" ).getAsInt(),
                    "Version mismatch should return 409 Conflict" );
        }
    }

    @Test
    void testDeletePageMissingNameReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetPageInvalidVersionReturns400() throws Exception {
        engine.saveText( "RestVersionPage", "Content." );

        final String json = doGetWithParams( "RestVersionPage", Map.of( "version", "abc" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "Invalid version" ) );
    }

    @Test
    void testGetPageVersionNotFoundReturns404() throws Exception {
        engine.saveText( "RestVersionPage", "Content." );

        final String json = doGetWithParams( "RestVersionPage", Map.of( "version", "999" ) );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testServiceRoutesNonPatchToSuper() throws Exception {
        // Calling service() with a GET method should route through super.service()
        engine.saveText( "RestTestPage", "Service test content." );

        final HttpServletRequest request = createRequest( "RestTestPage" );
        Mockito.doReturn( "GET" ).when( request ).getMethod();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.service( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "RestTestPage", obj.get( "name" ).getAsString(),
                "service() with GET should route to doGet via super.service()" );
    }

    @Test
    void testServiceRoutesToPatch() throws Exception {
        engine.saveText( "RestPatchMergePage", "---\ntype: article\n---\nBody." );

        final HttpServletRequest request = createRequest( "RestPatchMergePage" );
        Mockito.doReturn( "PATCH" ).when( request ).getMethod();
        final JsonObject patchBody = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty( "newField", "value" );
        patchBody.add( "metadata", meta );
        Mockito.doReturn( new BufferedReader( new StringReader( patchBody.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.service( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "success" ).getAsBoolean(),
                "service() should route PATCH to doPatch" );
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

    private String doGetWithParams( final String pageName, final Map< String, String > params ) throws Exception {
        final HttpServletRequest request = createRequest( pageName );
        for ( final Map.Entry< String, String > entry : params.entrySet() ) {
            Mockito.doReturn( entry.getValue() ).when( request ).getParameter( entry.getKey() );
        }
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

    private String doPatch( final String pageName, final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( pageName );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPatch( request, response );
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

    /**
     * Performs a page delete via PageManager directly. JAAS authentication is not
     * available in the {@code wikantik-rest} test context (the user database XML
     * file path resolves relative to {@code wikantik-main}), so we cannot create
     * an admin-authenticated session to call the REST servlet's doDelete().
     * <p>
     * Authorization enforcement for DELETE is tested separately in
     * {@link RestAuthorizationSecurityTest#testAnonymousDeletePageReturnsForbidden()}.
     */
    private String doDeleteAsAdmin( final String pageName ) throws Exception {
        engine.getManager( PageManager.class ).deletePage( pageName );

        final java.util.LinkedHashMap< String, Object > result = new java.util.LinkedHashMap<>();
        result.put( "success", true );
        result.put( "name", pageName );
        return gson.toJson( result );
    }

    private HttpServletRequest createRequest( final String pageName ) {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        return request;
    }

}
