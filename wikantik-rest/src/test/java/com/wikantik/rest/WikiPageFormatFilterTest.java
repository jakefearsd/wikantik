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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;

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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiPageFormatFilterTest {

    private static TestEngine engine;
    private static WikiPageFormatFilter filter;
    private final Gson gson = new Gson();

    // Per-class engine (see RestTestSupport javadoc): FormatPage is a static,
    // read-only fixture that no test mutates, and no test touches SessionMonitor
    // state or swaps a manager — one engine boot serves all tests.
    //
    // Identity note: engine.saveText() below authenticates the shared mock
    // session (HttpMockFactory.SHARED_SESSION_ID) as admin, and every request in
    // this class (via the default HttpMockFactory.createHttpRequest()) resolves
    // against that same id — so every test here already ran authenticated as
    // admin even in the old per-method version (setUp() called saveText() first,
    // every time). FormatPage carries no ACL, so admin-vs-anonymous identity
    // never affects an assertion; this migration does not change that.
    @BeforeAll
    static void startEngine() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        engine.saveText( "FormatPage",
            "---\n" +
            "title: Format Page\n" +
            "tags:\n" +
            "  - alpha\n" +
            "  - beta\n" +
            "summary: A short description of the format page.\n" +
            "created: 2025-11-14\n" +
            "---\n" +
            "Body content goes here.\n\n" +
            "See [Other](OtherPage) and [External](https://example.com).\n" );

        engine.saveText( "BlockPage", "Para one ^abc\n\nSee [[FormatPage#^zz|claim]] here.\n" );
        engine.saveText( "CommentPage", "Shown prose %%commentprobe%% here.\n" );
        engine.saveText( "BlockHrPage", "---\ntitle: Rules\n---\n---\nPara ^h\n\n---\n\nMore ^m2\n" );

        filter = new WikiPageFormatFilter();
        final FilterConfig config = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        filter.init( config );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void testFormatMdReturnsMarkdownWithH1() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( "md" );
        when( req.getRequestURI() ).thenReturn( "/wiki/FormatPage" );
        when( req.getContextPath() ).thenReturn( "" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ServletOutputStream sos = new ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener listener ) {}
        };
        when( resp.getOutputStream() ).thenReturn( sos );

        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );

        verify( resp ).setContentType( "text/markdown; charset=UTF-8" );
        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        verify( chain, never() ).doFilter( any(), any() );
        // Raw format responses must be noindex + point at the canonical /wiki/ page
        // so they aren't indexed as duplicate content.
        verify( resp ).setHeader( "X-Robots-Tag", "noindex" );
        verify( resp ).setHeader( eq( "Link" ), argThat( v ->
                v != null && v.contains( "/wiki/FormatPage" ) && v.contains( "rel=\"canonical\"" ) ) );

        final String md = baos.toString( StandardCharsets.UTF_8 );
        assertTrue( md.startsWith( "# Format Page" ), "Markdown must start with H1 title, got: " + md );
        assertTrue( md.contains( "Body content goes here." ) );
    }

    @Test
    void formatMdStripsBlockMarkersButKeepsBlockLinkFragments() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/BlockPage" );
        when( req.getParameter( "format" ) ).thenReturn( "md" );
        when( req.getRequestURI() ).thenReturn( "/wiki/BlockPage" );
        when( req.getContextPath() ).thenReturn( "" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when( resp.getOutputStream() ).thenReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener listener ) {}
        } );

        filter.doFilter( req, resp, mock( FilterChain.class ) );

        final String md = baos.toString( StandardCharsets.UTF_8 );
        assertTrue( md.contains( "Para one" ), md );
        assertFalse( md.contains( "one ^abc" ), "the block marker is source, not reader text: " + md );
        assertTrue( md.contains( "#^zz" ), "a block-link fragment must survive the rewrite: " + md );
    }

    /** The body is never read for frontmatter again: its leading "---" pair is two thematic breaks. */
    @Test
    void formatMdAndJsonStripMarkersOfABodyOpeningWithAThematicBreak() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/BlockHrPage" );
        when( req.getParameter( "format" ) ).thenReturn( "md" );
        when( req.getRequestURI() ).thenReturn( "/wiki/BlockHrPage" );
        when( req.getContextPath() ).thenReturn( "" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when( resp.getOutputStream() ).thenReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener listener ) {}
        } );
        filter.doFilter( req, resp, mock( FilterChain.class ) );
        final String md = baos.toString( StandardCharsets.UTF_8 );
        assertTrue( md.contains( "Para" ) && md.contains( "More" ), md );
        assertFalse( md.contains( "^h" ) || md.contains( "^m2" ), md );

        final HttpServletRequest jreq = HttpMockFactory.createHttpRequest( "/wiki/BlockHrPage" );
        when( jreq.getParameter( "format" ) ).thenReturn( "json" );
        when( jreq.getRequestURI() ).thenReturn( "/wiki/BlockHrPage" );
        when( jreq.getContextPath() ).thenReturn( "" );
        final HttpServletResponse jresp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        when( jresp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        filter.doFilter( jreq, jresp, mock( FilterChain.class ) );
        final String content = gson.fromJson( sw.toString(), JsonObject.class ).get( "content" ).getAsString();
        assertFalse( content.contains( "^h" ) || content.contains( "^m2" ), content );
    }

    @Test
    void formatJsonContentAndSummaryOmitBlockMarkers() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/BlockPage" );
        when( req.getParameter( "format" ) ).thenReturn( "json" );
        when( req.getRequestURI() ).thenReturn( "/wiki/BlockPage" );
        when( req.getContextPath() ).thenReturn( "" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        filter.doFilter( req, resp, mock( FilterChain.class ) );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "content" ).getAsString().contains( "Para one" ) );
        assertFalse( obj.get( "content" ).getAsString().contains( "^abc" ) );
        assertFalse( obj.get( "summary" ).getAsString().contains( "^abc" ) );
    }

    @Test
    void testFormatJsonReturnsSchema() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( "json" );
        when( req.getRequestURI() ).thenReturn( "/wiki/FormatPage" );
        when( req.getContextPath() ).thenReturn( "" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );

        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        verify( chain, never() ).doFilter( any(), any() );
        verify( resp ).setHeader( "X-Robots-Tag", "noindex" );
        verify( resp ).setHeader( eq( "Link" ), argThat( v ->
                v != null && v.contains( "/wiki/FormatPage" ) && v.contains( "rel=\"canonical\"" ) ) );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( "FormatPage", obj.get( "slug" ).getAsString() );
        assertEquals( "Format Page", obj.get( "title" ).getAsString() );
        assertTrue( obj.get( "content" ).getAsString().contains( "Body content" ) );
        assertTrue( obj.has( "summary" ) );
        assertTrue( obj.get( "summary" ).getAsString().contains( "short description" ) );
        assertTrue( obj.has( "tags" ) );
        final JsonArray tags = obj.getAsJsonArray( "tags" );
        assertEquals( 2, tags.size() );
        assertEquals( "alpha", tags.get( 0 ).getAsString() );
        assertTrue( obj.has( "created_at" ) );
        assertTrue( obj.has( "modified_at" ) );
    }

    @Test
    void testNoFormatParamPassesThrough() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( null );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( req, resp, chain );
        verify( chain, times( 1 ) ).doFilter( req, resp );
    }

    @Test
    void testUnknownFormatPassesThrough() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/FormatPage" );
        when( req.getParameter( "format" ) ).thenReturn( "pdf" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( req, resp, chain );
        verify( chain, times( 1 ) ).doFilter( req, resp );
    }

    @Test
    void testPageNotFoundReturns404() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/NoSuchPage" );
        when( req.getParameter( "format" ) ).thenReturn( "md" );
        when( req.getRequestURI() ).thenReturn( "/wiki/NoSuchPage" );
        when( req.getContextPath() ).thenReturn( "" );

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( req, resp, chain );

        verify( resp ).sendError( eq( HttpServletResponse.SC_NOT_FOUND ), anyString() );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testInternalLinkRewritingHelper() {
        final String body = "See [Other](OtherPage) and [External](https://example.com) and [Img](/attach/foo.png).";
        final String rewritten = WikiPageFormatFilter.rewriteInternalLinks( body, "https://wiki.example.com" );
        assertEquals( "See [Other](https://wiki.example.com/wiki/OtherPage) and [External](https://example.com) and [Img](/attach/foo.png).", rewritten );
    }

    @Test
    void testStripLeadingH1Helper() {
        final String body = "# Format Page\n\nBody line.\n";
        final String stripped = WikiPageFormatFilter.stripLeadingH1( body, "Format Page" );
        assertEquals( "Body line.\n", stripped );
    }

    @Test
    void testStripLeadingH1NoOpWhenBodyDoesNotStartWithH1() {
        final String body = "Body without heading.\n";
        assertEquals( body, WikiPageFormatFilter.stripLeadingH1( body, "Title" ) );
    }

    @Test
    void testStripLeadingH1NoOpWhenFirstLineIsDifferentTitle() {
        final String body = "# Other\n\nBody.\n";
        assertEquals( body, WikiPageFormatFilter.stripLeadingH1( body, "Title" ) );
    }

    @Test
    void testExtractSummaryFallsBackToDescription() {
        final String raw =
            "---\n" +
            "description: This is the description fallback.\n" +
            "---\n" +
            "Body content.\n";
        final ParsedPage parsed = FrontmatterParser.parse( raw );
        assertEquals( "This is the description fallback.",
                WikiPageFormatFilter.extractSummary( parsed ) );
    }

    @Test
    void testExtractSummaryFallsBackToFirstParagraph() {
        final String raw =
            "---\n" +
            "title: No Summary\n" +
            "---\n" +
            "# Heading\n\nFirst paragraph here.\n\nSecond paragraph.";
        final ParsedPage parsed = FrontmatterParser.parse( raw );
        assertEquals( "First paragraph here.",
                WikiPageFormatFilter.extractSummary( parsed ) );
    }

    @Test
    void testExtractCreatedFallsBackToPageLastModified() {
        final ParsedPage parsed = new ParsedPage( Map.of(), "Body." );
        final Date lastModified = new Date( 1_700_000_000_000L );
        final Page page = Mockito.mock( Page.class );
        when( page.getName() ).thenReturn( "SomePage" );
        when( page.getLastModified() ).thenReturn( lastModified );
        assertEquals( lastModified,
                WikiPageFormatFilter.extractCreated( parsed.metadata(), page ) );
    }

    @Test
    void testExtractCreatedInvalidStringFallsBackToPageLastModified() {
        final Map< String, Object > meta = new HashMap<>();
        meta.put( "created", "not-a-date" );
        final ParsedPage parsed = new ParsedPage( meta, "Body." );
        final Date lastModified = new Date( 1_700_000_000_000L );
        final Page page = Mockito.mock( Page.class );
        when( page.getName() ).thenReturn( "SomePage" );
        when( page.getLastModified() ).thenReturn( lastModified );
        assertEquals( lastModified,
                WikiPageFormatFilter.extractCreated( parsed.metadata(), page ) );
    }

    @Test
    void testExtractTagsHandlesCsvString() {
        final Map< String, Object > meta = new HashMap<>();
        meta.put( "tags", "alpha, beta, gamma" );
        final List< String > tags = WikiPageFormatFilter.extractTags( meta );
        assertEquals( List.of( "alpha", "beta", "gamma" ), tags );
    }

    @Test
    void testRewriteInternalLinksDoesNotRewriteImages() {
        assertEquals( "![alt](verysadday.jpg)",
                WikiPageFormatFilter.rewriteInternalLinks(
                        "![alt](verysadday.jpg)", "https://wiki.example.com" ) );
        assertEquals( "![a](one.png)![b](two.png)",
                WikiPageFormatFilter.rewriteInternalLinks(
                        "![a](one.png)![b](two.png)", "https://wiki.example.com" ) );
    }

    @Test
    void rewriteWikiLinksKeepsABlockFragmentVerbatim() {
        assertEquals( "[see](https://w.example/wiki/T#^abc) [T > ^Ab-1](https://w.example/wiki/T#^Ab-1) [^q](https://w.example/wiki/Cur#^q)",
                WikiPageFormatFilter.rewriteWikiLinks( "[[T#^abc|see]] [[T#^Ab-1]] [[#^q]]", "https://w.example", "Cur",
                        java.util.function.UnaryOperator.identity(), n -> false ) );
    }

    @Test
    void rewriteWikiLinksProducesStandardMarkdownLinks() {
        final String in = "[[Target|A]] [[Target#My H]] [[#Intro]] ![[Target]] ![[Owner/p.png|300]] ![[local.png]] `[[Code]]`";
        final String out = WikiPageFormatFilter.rewriteWikiLinks( in, "https://w.example/", "Cur",
                java.util.function.UnaryOperator.identity(), "local.png"::equals );
        assertEquals( "[A](https://w.example/wiki/Target) [Target > My H](https://w.example/wiki/Target#my-h) "
                + "[Intro](https://w.example/wiki/Cur#intro) [Embedded: Target](https://w.example/wiki/Target) "
                + "![](https://w.example/attach/Owner/p.png) ![](https://w.example/attach/Cur/local.png) `[[Code]]`", out );
        assertEquals( out, WikiPageFormatFilter.rewriteInternalLinks( out, "https://w.example" ) );
    }

    @Test
    void rewriteWikiLinksEscapesLabelCharactersThatWouldBreakTheMarkdownLinkAndEncodesTheUrl() {
        // the token grammar admits no brackets, but a trailing backslash would escape the closing ] of the label
        final String out = WikiPageFormatFilter.rewriteWikiLinks(
                "[[T|a\\]] [[Caf\u00e9 & Bar#H|x\\y]] ![[Caf\u00e9 & Bar]]", "https://w.example", "Cur",
                java.util.function.UnaryOperator.identity(), t -> false );
        assertEquals( "[a\\\\](https://w.example/wiki/T) "
                + "[x\\\\y](https://w.example/wiki/Caf%C3%A9%20%26%20Bar#h) "
                + "[Embedded: Caf\u00e9 & Bar](https://w.example/wiki/Caf%C3%A9%20%26%20Bar)", out );
    }

    @Test
    void rewriteWikiLinksFallsBackToTheTargetWhenTheResolverGivesNothing() {
        final String out = WikiPageFormatFilter.rewriteWikiLinks( "[[Some Page]] [[Other]]", "https://w.example", "Cur",
                t -> t.equals( "Other" ) ? "" : null, t -> false );
        assertEquals( "[Some Page](https://w.example/wiki/Some%20Page) [Other](https://w.example/wiki/Other)", out );
    }

    @Test
    void formatMdAndJsonOmitComments() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/CommentPage" );
        when( req.getParameter( "format" ) ).thenReturn( "md" );
        when( req.getRequestURI() ).thenReturn( "/wiki/CommentPage" );
        when( req.getContextPath() ).thenReturn( "" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when( resp.getOutputStream() ).thenReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener listener ) {}
        } );
        filter.doFilter( req, resp, mock( FilterChain.class ) );
        final String md = baos.toString( StandardCharsets.UTF_8 );
        assertTrue( md.contains( "Shown prose" ), md );
        assertFalse( md.contains( "commentprobe" ), "a comment is hidden, not reader text: " + md );

        final HttpServletRequest jreq = HttpMockFactory.createHttpRequest( "/wiki/CommentPage" );
        when( jreq.getParameter( "format" ) ).thenReturn( "json" );
        when( jreq.getRequestURI() ).thenReturn( "/wiki/CommentPage" );
        when( jreq.getContextPath() ).thenReturn( "" );
        final HttpServletResponse jresp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        when( jresp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        filter.doFilter( jreq, jresp, mock( FilterChain.class ) );
        assertFalse( sw.toString().contains( "commentprobe" ), sw.toString() );
    }
}
