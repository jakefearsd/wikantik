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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /wiki/{slug}?format=md|json} must apply the page's view permission exactly like a page view: a caller who may
 * not view the page gets the same 404 as for a missing page, and never a byte of its text. Also pins the request
 * routing (what passes through to the SPA) and the response framing of the md representation.
 */
class WikiPageFormatFilterAccessTest {

    private static TestEngine engine;
    private static WikiPageFormatFilter filter;

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "OpenFormatPage", "[{ALLOW view All}]\nOpen secretless text.\n" );
        engine.saveText( "LockedFormatPage", "[{ALLOW view Admin}]\nLOCKEDPROBE text.\n" );
        engine.saveText( "AttachFormatPage", "See [[logo.png]] and [[missing.png]].\n" );
        engine.addAttachment( "AttachFormatPage", "logo.png", new byte[] { 1, 2, 3 } );
        filter = new WikiPageFormatFilter();
        final FilterConfig config = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        filter.init( config );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.deleteQuietly( "OpenFormatPage", "LockedFormatPage", "AttachFormatPage" );
            engine.stop();
        }
    }

    private static final class Capture {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final StringWriter chars = new StringWriter();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();

        Capture() throws Exception {
            when( resp.getOutputStream() ).thenReturn( new ServletOutputStream() {
                @Override public void write( final int b ) { bytes.write( b ); }
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener( final WriteListener l ) { }
            } );
            when( resp.getWriter() ).thenReturn( new PrintWriter( chars ) );
        }

        String all() {
            return bytes.toString( StandardCharsets.UTF_8 ) + chars;
        }
    }

    private static HttpServletRequest request( final boolean asAdmin, final String uri, final String contextPath,
                                               final String format ) {
        final HttpServletRequest req = asAdmin ? HttpMockFactory.createHttpRequest( uri )
                : HttpMockFactory.createIsolatedHttpRequest( uri );
        when( req.getParameter( "format" ) ).thenReturn( format );
        when( req.getRequestURI() ).thenReturn( uri );
        when( req.getContextPath() ).thenReturn( contextPath );
        return req;
    }

    @ParameterizedTest
    @CsvSource( { "md", "json" } )
    void aCallerWhoMayNotViewAPageGetsA404AndNoText( final String format ) throws Exception {
        final Capture out = new Capture();
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request( false, "/wiki/LockedFormatPage", "", format ), out.resp, chain );

        verify( out.resp ).sendError( eq( HttpServletResponse.SC_NOT_FOUND ), eq( "Page not found: LockedFormatPage" ) );
        verify( chain, never() ).doFilter( any(), any() );
        verify( out.resp, never() ).setStatus( HttpServletResponse.SC_OK );
        assertFalse( out.all().contains( "LOCKEDPROBE" ), out.all() );
    }

    @ParameterizedTest
    @CsvSource( { "md", "json" } )
    void anAdminMayReadTheSameLockedPage( final String format ) throws Exception {
        final Capture out = new Capture();

        filter.doFilter( request( true, "/wiki/LockedFormatPage", "", format ), out.resp, mock( FilterChain.class ) );

        verify( out.resp, never() ).sendError( Mockito.anyInt(), anyString() );
        assertTrue( out.all().contains( "LOCKEDPROBE" ), out.all() );
    }

    @Test
    void anAnonymousReaderGetsAnOpenPageAndTheFormatIsCaseInsensitive() throws Exception {
        final Capture out = new Capture();

        filter.doFilter( request( false, "/wiki/OpenFormatPage", "", "MD" ), out.resp, mock( FilterChain.class ) );

        verify( out.resp ).setStatus( HttpServletResponse.SC_OK );
        assertTrue( out.all().contains( "Open secretless text." ), out.all() );
    }

    @Test
    void theMarkdownResponseIsFramedAsUtf8WithItsExactLength() throws Exception {
        final Capture out = new Capture();

        filter.doFilter( request( true, "/wiki/OpenFormatPage", "", "md" ), out.resp, mock( FilterChain.class ) );

        verify( out.resp ).setCharacterEncoding( "UTF-8" );
        verify( out.resp ).setContentLength( out.bytes.size() );
        assertTrue( out.bytes.size() > 0 );
    }

    @Test
    void theJsonResponseIsFramedAsUtf8() throws Exception {
        final Capture out = new Capture();

        filter.doFilter( request( true, "/wiki/OpenFormatPage", "", "json" ), out.resp, mock( FilterChain.class ) );

        verify( out.resp ).setContentType( "application/json; charset=UTF-8" );
        verify( out.resp ).setCharacterEncoding( "UTF-8" );
    }

    @Test
    void theContextPathIsRemovedBeforeTheSlugIsRead() throws Exception {
        final Capture out = new Capture();

        filter.doFilter( request( true, "/wikantik/wiki/OpenFormatPage", "/wikantik", "md" ), out.resp,
                mock( FilterChain.class ) );

        verify( out.resp ).setStatus( HttpServletResponse.SC_OK );
        assertTrue( out.all().contains( "Open secretless text." ), out.all() );
    }

    @Test
    void aNullContextPathIsTreatedAsRoot() throws Exception {
        final Capture out = new Capture();

        filter.doFilter( request( true, "/wiki/OpenFormatPage", null, "md" ), out.resp, mock( FilterChain.class ) );

        verify( out.resp ).setStatus( HttpServletResponse.SC_OK );
    }

    @ParameterizedTest
    @CsvSource( { "/api/pages/OpenFormatPage", "/other/OpenFormatPage", "/wiki/" } )
    void aPathThatIsNotAWikiPageGoesOnToTheNextFilter( final String uri ) throws Exception {
        final Capture out = new Capture();
        final FilterChain chain = mock( FilterChain.class );
        final HttpServletRequest req = request( true, uri, "", "md" );

        filter.doFilter( req, out.resp, chain );

        verify( chain ).doFilter( req, out.resp );
        verify( out.resp, never() ).sendError( Mockito.anyInt(), anyString() );
        verify( out.resp, never() ).setStatus( HttpServletResponse.SC_OK );
    }

    @Test
    void aNullRequestUriGoesOnToTheNextFilter() throws Exception {
        final Capture out = new Capture();
        final FilterChain chain = mock( FilterChain.class );
        final HttpServletRequest req = request( true, "/wiki/OpenFormatPage", "", "md" );
        when( req.getRequestURI() ).thenReturn( null );

        filter.doFilter( req, out.resp, chain );

        verify( chain ).doFilter( req, out.resp );
    }

    @Test
    void anEmptyFormatGoesOnToTheNextFilter() throws Exception {
        final Capture out = new Capture();
        final FilterChain chain = mock( FilterChain.class );
        final HttpServletRequest req = request( true, "/wiki/OpenFormatPage", "", "" );

        filter.doFilter( req, out.resp, chain );

        verify( chain ).doFilter( req, out.resp );
    }

    @Test
    void anAttachmentThatExistsIsLinkedAsAnAttachmentAndOneThatDoesNotIsLinkedAsAPage() throws Exception {
        final Capture out = new Capture();

        filter.doFilter( request( true, "/wiki/AttachFormatPage", "", "md" ), out.resp, mock( FilterChain.class ) );

        final String md = out.all();
        assertTrue( md.contains( "/attach/AttachFormatPage/logo.png" ), md );
        assertFalse( md.contains( "/attach/AttachFormatPage/missing.png" ), md );
        assertTrue( md.contains( "/wiki/Missing.png" ), md );
    }

    // ---- helpers

    private static String summaryOfBodyLength( final int length ) {
        return WikiPageFormatFilter.extractSummary( new ParsedPage( Map.of( "summary", "x".repeat( length ) ), "" ) );
    }

    @Test
    void aSummaryOfTheLimitIsKeptAndOneLongerIsTruncatedWithAnEllipsisWithinTheLimit() {
        assertEquals( "x".repeat( 299 ), summaryOfBodyLength( 299 ) );
        assertEquals( "x".repeat( 300 ), summaryOfBodyLength( 300 ) );
        assertEquals( "x".repeat( 299 ) + "…", summaryOfBodyLength( 301 ) );
        assertEquals( "x".repeat( 299 ) + "…", summaryOfBodyLength( 1000 ) );
    }

    @Test
    void theFirstParagraphFallbackStopsAtTheBlankLineAfterItAndSkipsHeadings() {
        assertEquals( "one two", WikiPageFormatFilter.extractSummary(
                new ParsedPage( Map.of(), "\n# Head\n\none\ntwo\n\nthree\n" ) ) );
        assertEquals( "", WikiPageFormatFilter.extractSummary( new ParsedPage( Map.of(), "# only a heading\n" ) ) );
    }

    @Test
    void aBlankSummaryAndDescriptionFallThroughToTheBody() {
        assertEquals( "Body.", WikiPageFormatFilter.extractSummary(
                new ParsedPage( Map.of( "summary", " ", "description", " " ), "Body." ) ) );
    }

    @Test
    void aBlankTitleFallsBackToThePageName() {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "SomePage" );
        assertEquals( "SomePage", WikiPageFormatFilter.extractTitle( page, new ParsedPage( Map.of( "title", " " ), "" ) ) );
        assertEquals( "SomePage", WikiPageFormatFilter.extractTitle( page, new ParsedPage( Map.of(), "" ) ) );
        assertEquals( "Given", WikiPageFormatFilter.extractTitle( page, new ParsedPage( Map.of( "title", "Given" ), "" ) ) );
    }

    @Test
    void createdIsADateAnIsoDayAtUtcMidnightOrTheFallbackInThatOrder() {
        final Page page = mock( Page.class );
        final Date modified = new Date( 5L );
        when( page.getLastModified() ).thenReturn( modified );
        final Date explicit = new Date( 1_000_000L );

        final Map< String, Object > asDate = new HashMap<>();
        asDate.put( "created", explicit );
        assertEquals( explicit, WikiPageFormatFilter.extractCreated( asDate, page ) );

        final Map< String, Object > asString = new HashMap<>();
        asString.put( "created", "2025-11-14" );
        assertEquals( new Date( 1_763_078_400_000L ), WikiPageFormatFilter.extractCreated( asString, page ) );

        final Map< String, Object > blankCreatedUsesDate = new HashMap<>();
        blankCreatedUsesDate.put( "created", " " );
        blankCreatedUsesDate.put( "date", explicit );
        assertEquals( explicit, WikiPageFormatFilter.extractCreated( blankCreatedUsesDate, page ) );

        final Map< String, Object > unparseableCreatedUsesDate = new HashMap<>();
        unparseableCreatedUsesDate.put( "created", "garbage" );
        unparseableCreatedUsesDate.put( "date", explicit );
        assertEquals( explicit, WikiPageFormatFilter.extractCreated( unparseableCreatedUsesDate, page ) );

        final Map< String, Object > stringDateIsNotADate = new HashMap<>();
        stringDateIsNotADate.put( "date", "2025-11-14" );
        assertEquals( modified, WikiPageFormatFilter.extractCreated( stringDateIsNotADate, page ) );
    }

    @Test
    void tagsOfEveryShapeAreTrimmedAndEmptyOnesDropped() {
        assertEquals( List.of(), WikiPageFormatFilter.extractTags( Map.of() ) );
        assertEquals( List.of(), WikiPageFormatFilter.extractTags( Map.of( "tags", "  " ) ) );
        assertEquals( List.of( "a", "b" ), WikiPageFormatFilter.extractTags( Map.of( "tags", " a ,, b ," ) ) );
        final List< Object > withNull = new java.util.ArrayList<>();
        withNull.add( " x " );
        withNull.add( null );
        withNull.add( "y" );
        assertEquals( List.of( "x", "y" ), WikiPageFormatFilter.extractTags( Map.of( "tags", withNull ) ) );
    }

    @Test
    void aLeadingHeadingIsStrippedOnlyWhenItIsExactlyTheTitle() {
        assertEquals( "", WikiPageFormatFilter.stripLeadingH1( "# T", "T" ) );
        assertEquals( "rest", WikiPageFormatFilter.stripLeadingH1( "\n\n# T\n\nrest", "T" ) );
        assertEquals( "# Other\nrest", WikiPageFormatFilter.stripLeadingH1( "# Other\nrest", "T" ) );
        assertEquals( "#T\nrest", WikiPageFormatFilter.stripLeadingH1( "#T\nrest", "T" ) );
        assertEquals( "x", WikiPageFormatFilter.stripLeadingH1( "x", "x" ) );
    }

    @Test
    void internalLinksAreRewrittenWithoutADoubledSlashAndLeaveDanglingBracketsAlone() {
        assertEquals( "[a](https://w.example/wiki/A)", WikiPageFormatFilter.rewriteInternalLinks( "[a](A)", "https://w.example/" ) );
        assertEquals( "[a](/wiki/A)", WikiPageFormatFilter.rewriteInternalLinks( "[a](A)", null ) );
        assertEquals( "[a](B/C) [b](http://x) [c](#h)", WikiPageFormatFilter.rewriteInternalLinks( "[a](B/C) [b](http://x) [c](#h)", "https://w" ) );
        assertEquals( "tail ](unterminated", WikiPageFormatFilter.rewriteInternalLinks( "tail ](unterminated", "https://w" ) );
        assertEquals( "no link", WikiPageFormatFilter.rewriteInternalLinks( "no link", "https://w" ) );
        assertEquals( "[x] ](A) ![i](p.png) [j](J)", WikiPageFormatFilter.rewriteInternalLinks( "[x] ](A) ![i](p.png) [j](J)", null )
                .replace( "/wiki/A", "A" ).replace( "/wiki/J", "J" ) );
        assertEquals( "x](A)", WikiPageFormatFilter.rewriteInternalLinks( "x](A)", "https://w" ).replace( "https://w/wiki/A", "A" ) );
    }
}
