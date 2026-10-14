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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.util.AclDirectives;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Access-rule directives ({@code [{ALLOW edit alice@example.test}]}) name people by login, so raw page
 * text reaches only callers who may edit the page — {@code GET /api/pages}, a diff and
 * {@code /wiki/Page?format=md|json}. Everyone else reads the page without them.
 *
 * <p>Fixture: view is open to {@code All}, edit to {@code Admin} and one named login. An anonymous reader can
 * therefore read but not edit; the admin session that {@code TestEngine.saveText} leaves on the shared mock
 * session id can do both. Requests from {@code HttpMockFactory.createHttpRequest} share that admin session
 * (the editor); {@code createIsolatedHttpRequest} gets a fresh anonymous one (the reader).
 */
class AclDirectiveVisibilityTest {

    private static final String LOGIN = "leak-probe@example.test";
    private static final String VERSION_LOGIN = "alice@example.test";
    private static final String RULES = "[{ALLOW view All}]\n[{ALLOW edit Admin," + LOGIN + "}]\n";

    private static TestEngine engine;
    private static PageResource pageResource;
    private static DiffResource diffResource;
    private static WikiPageFormatFilter formatFilter;
    private final Gson gson = new Gson();

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        pageResource = RestTestSupport.initServlet( PageResource::new, engine );
        diffResource = RestTestSupport.initServlet( DiffResource::new, engine );
        formatFilter = new WikiPageFormatFilter();
        final FilterConfig config = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        formatFilter.init( config );

        engine.saveText( "AclVisibilityPage",
                "---\ntitle: Acl Visibility\n---\n" + RULES + "# Heading\n\nPublic prose.\n" );
        // The ACL manager enforces the edit rule (a lone backtick on the first line pairs with the first one on the
        // second, so the rule is plain text), but a scanner that only looks at one line at a time would call it code.
        engine.saveText( "AclTrickyPage",
                "[{ALLOW view All}]\na `b\nc` [{ALLOW edit Admin," + LOGIN + "}] `d\n\nPublic prose.\n" );
        engine.saveText( "AclDiffPage", RULES + "First revision.\n" );
        engine.saveText( "AclDiffPage",
                "[{ALLOW view All}]\n[{ALLOW edit Admin,other-" + LOGIN + "}]\nSecond revision.\n" );

        // The directive that names a login lives only in version 1; version 2 has dropped it.
        engine.saveText( "AclVersionedPage",
                "[{ALLOW view All}]\n[{ALLOW edit Admin," + VERSION_LOGIN + "}]\nOriginal text.\n" );
        engine.saveText( "AclVersionedPage", "[{ALLOW view All}]\n[{ALLOW edit Admin}]\nRevised text.\n" );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.deleteQuietly( "AclVisibilityPage", "AclDiffPage", "AclVersionedPage", "AclTrickyPage" );
            engine.stop();
        }
    }

    private static HttpServletRequest reader( final String path ) {
        return HttpMockFactory.createIsolatedHttpRequest( path );
    }

    private static HttpServletRequest editor( final String path ) {
        return HttpMockFactory.createHttpRequest( path );
    }

    // ---- GET /api/pages/{name}

    private JsonObject getPage( final HttpServletRequest request, final String pageName ) throws Exception {
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        pageResource.doGet( request, response );
        return gson.fromJson( sw.toString(), JsonObject.class );
    }

    @Test
    void readerGetsPageContentWithoutDirectives() throws Exception {
        final JsonObject page = getPage( reader( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" );

        final String content = page.get( "content" ).getAsString();
        assertFalse( content.contains( LOGIN ), content );
        assertFalse( content.contains( "[{ALLOW" ), content );
        assertTrue( content.contains( "Public prose." ), content );
        assertFalse( page.getAsJsonObject( "permissions" ).get( "edit" ).getAsBoolean(),
                "fixture is vacuous: the reader can edit the page" );
        assertTrue( page.getAsJsonObject( "metadata" ).get( "title" ).getAsString().contains( "Acl Visibility" ),
                "frontmatter is untouched" );
    }

    @Test
    void readerGetsNoEnforcedDirectiveEvenWhereACodeScannerAndTheMarkdownParserDisagree() throws Exception {
        final JsonObject page = getPage( reader( "/api/pages/AclTrickyPage" ), "AclTrickyPage" );

        final String content = page.get( "content" ).getAsString();
        assertFalse( page.getAsJsonObject( "permissions" ).get( "edit" ).getAsBoolean(),
                "fixture is vacuous: the edit rule is not enforced, so the reader can edit" );
        assertFalse( content.contains( LOGIN ), content );
        assertTrue( content.contains( "Public prose." ), content );
    }

    @Test
    void editorGetsPageContentWithDirectives() throws Exception {
        final JsonObject page = getPage( editor( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" );

        final String stored = FrontmatterParser.parse( engine.getManager( PageManager.class )
                .getPureText( "AclVisibilityPage", PageProvider.LATEST_VERSION ) ).body();
        assertTrue( stored.contains( "[{ALLOW edit Admin," + LOGIN + "}]" ), "fixture is vacuous: no directive is stored" );
        assertEquals( stored, page.get( "content" ).getAsString(),
                "an editor round-trips this text, so it must be byte-identical to what is stored" );
        assertTrue( page.getAsJsonObject( "permissions" ).get( "edit" ).getAsBoolean() );
    }

    /**
     * The content hash is how an editor round-trips GET to PUT. A reader never PUTs, and a hash of the full text
     * would let them confirm a guess at a hidden directive by hashing candidate texts against it.
     */
    @Test
    void aReadersContentHashIsNotAnOracleForTheHiddenDirectives() throws Exception {
        final String raw = engine.getManager( PageManager.class )
                .getPureText( "AclVisibilityPage", PageProvider.LATEST_VERSION );
        final String readerHash = getPage( reader( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" )
                .get( "contentHash" ).getAsString();
        final String editorHash = getPage( editor( "/api/pages/AclVisibilityPage" ), "AclVisibilityPage" )
                .get( "contentHash" ).getAsString();

        assertEquals( PageSaveHelper.computeContentHash( raw ), editorHash, "an editor gets the hash of the full text" );
        assertEquals( PageSaveHelper.computeContentHash( AclDirectives.strip( raw ) ), readerHash,
                "a reader gets the hash of what they can see" );
        assertNotEquals( editorHash, readerHash );
    }

    @Test
    void readerGetsAnOldVersionWithoutDirectives() throws Exception {
        final HttpServletRequest request = reader( "/api/pages/AclDiffPage" );
        Mockito.doReturn( "1" ).when( request ).getParameter( "version" );

        final JsonObject page = getPage( request, "AclDiffPage" );

        final String content = page.get( "content" ).getAsString();
        assertFalse( content.contains( LOGIN ), content );
        assertTrue( content.contains( "First revision." ), content );
    }

    /**
     * An old version is the easiest way round a current-text filter: the login is gone from the page today but
     * still sits in the history. {@code ?version=N} must go through the same gate as the current text — verbatim
     * for an editor (who may restore it), stripped for everyone else.
     */
    @Test
    void anOldVersionIsStrippedForAReaderAndVerbatimForAnEditor() throws Exception {
        final HttpServletRequest readerRequest = reader( "/api/pages/AclVersionedPage" );
        Mockito.doReturn( "1" ).when( readerRequest ).getParameter( "version" );
        final String readerContent = getPage( readerRequest, "AclVersionedPage" ).get( "content" ).getAsString();

        assertFalse( readerContent.contains( VERSION_LOGIN ), readerContent );
        assertFalse( readerContent.contains( "ALLOW" ), readerContent );
        assertTrue( readerContent.contains( "Original text." ), readerContent );

        final HttpServletRequest editorRequest = editor( "/api/pages/AclVersionedPage" );
        Mockito.doReturn( "1" ).when( editorRequest ).getParameter( "version" );
        final String editorContent = getPage( editorRequest, "AclVersionedPage" ).get( "content" ).getAsString();

        final String storedVersionOne = FrontmatterParser.parse(
                engine.getManager( PageManager.class ).getPureText( "AclVersionedPage", 1 ) ).body();
        assertTrue( storedVersionOne.contains( "[{ALLOW edit Admin," + VERSION_LOGIN + "}]" ),
                "fixture is vacuous: version 1 stores no directive" );
        assertEquals( storedVersionOne, editorContent, "the editor gets version 1 byte for byte" );
    }

    // ---- GET /api/diff/{name}

    private String getDiff( final HttpServletRequest request, final String format ) throws Exception {
        Mockito.doReturn( "/AclDiffPage" ).when( request ).getPathInfo();
        Mockito.doReturn( "1" ).when( request ).getParameter( "from" );
        Mockito.doReturn( "2" ).when( request ).getParameter( "to" );
        if ( format != null ) {
            Mockito.doReturn( format ).when( request ).getParameter( "format" );
        }
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        diffResource.doGet( request, response );
        return sw.toString();
    }

    @Test
    void readerDiffCarriesNoDirectives() throws Exception {
        final String structured = getDiff( reader( "/api/diff/AclDiffPage" ), null );
        assertFalse( structured.contains( LOGIN ), structured );
        assertTrue( structured.contains( "Second revision." ), structured );

        final String html = getDiff( reader( "/api/diff/AclDiffPage" ), "html" );
        assertFalse( html.contains( LOGIN ), html );
    }

    @Test
    void editorDiffShowsTheDirectiveThatChanged() throws Exception {
        final String structured = getDiff( editor( "/api/diff/AclDiffPage" ), null );
        assertTrue( structured.contains( LOGIN ), structured );
    }

    // ---- /wiki/{name}?format=md|json

    private String getFormat( final HttpServletRequest request, final String pageName, final String format )
            throws Exception {
        Mockito.doReturn( format ).when( request ).getParameter( "format" );
        Mockito.doReturn( "/wiki/" + pageName ).when( request ).getRequestURI();
        Mockito.doReturn( "" ).when( request ).getContextPath();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Mockito.doReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { bytes.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener listener ) { }
        } ).when( response ).getOutputStream();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        formatFilter.doFilter( request, response, Mockito.mock( FilterChain.class ) );
        return bytes.toString( StandardCharsets.UTF_8 ) + sw;
    }

    @Test
    void readerRawMarkdownCarriesNoDirectives() throws Exception {
        final String md = getFormat( reader( "/wiki/AclVisibilityPage" ), "AclVisibilityPage", "md" );
        assertTrue( md.contains( "Public prose." ), md );
        assertFalse( md.contains( LOGIN ), md );
    }

    @Test
    void editorRawMarkdownKeepsDirectives() throws Exception {
        final String md = getFormat( editor( "/wiki/AclVisibilityPage" ), "AclVisibilityPage", "md" );
        assertTrue( md.contains( LOGIN ), md );
    }

    @Test
    void readerJsonCarriesNoDirectivesInContentOrInTheDerivedSummary() throws Exception {
        // No frontmatter summary on this page, so the summary is the body's first paragraph — which,
        // with the directives still in it, is the directives.
        final JsonObject json = gson.fromJson(
                getFormat( reader( "/wiki/AclVisibilityPage" ), "AclVisibilityPage", "json" ), JsonObject.class );
        assertFalse( json.get( "content" ).getAsString().contains( LOGIN ), json.toString() );
        assertFalse( json.get( "summary" ).getAsString().contains( LOGIN ), json.toString() );
        assertTrue( json.get( "summary" ).getAsString().contains( "Public prose." ), json.toString() );
    }
}
