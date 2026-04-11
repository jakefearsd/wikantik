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
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiPageFormatFilterTest {

    private TestEngine engine;
    private WikiPageFormatFilter filter;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
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

        filter = new WikiPageFormatFilter();
        final FilterConfig config = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        filter.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "FormatPage" ); } catch ( final Exception ignored ) {}
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

        final String md = baos.toString( StandardCharsets.UTF_8 );
        assertTrue( md.startsWith( "# Format Page" ), "Markdown must start with H1 title, got: " + md );
        assertTrue( md.contains( "Body content goes here." ) );
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
}
