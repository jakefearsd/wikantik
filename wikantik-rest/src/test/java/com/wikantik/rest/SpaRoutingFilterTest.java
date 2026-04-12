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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SpaRoutingFilter} — redirects root paths to /wiki/Main,
 * serves the React SPA's index.html for SPA routes, and lets static assets
 * pass through. Also exercises the context-path-aware asset rewriting so
 * non-root IT test deployments still fetch {@code /assets/index-*.js} from
 * the correct WAR.
 */
class SpaRoutingFilterTest {

    /**
     * Mimics a vite-built index.html closely enough that the filter's string
     * replacements have something to bite on. Kept short — the actual file is
     * irrelevant to the filter, only the src/href attributes on asset tags.
     */
    private static final String FAKE_INDEX_HTML = ""
        + "<!DOCTYPE html>\n"
        + "<html><head>\n"
        + "<meta charset=\"UTF-8\">\n"
        + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">\n"
        + "<script type=\"module\" crossorigin src=\"/assets/index-ABC.js\"></script>\n"
        + "<link rel=\"stylesheet\" crossorigin href=\"/assets/index-XYZ.css\">\n"
        + "</head><body><div id=\"root\"></div></body></html>";

    private SpaRoutingFilter filter;
    private HttpServletResponse response;
    private FilterChain chain;
    private ServletContext servletContext;
    private CapturingServletOutputStream capturedOutput;

    @BeforeEach
    void setUp() throws Exception {
        filter = new SpaRoutingFilter();
        filter.init( null );
        response = mock( HttpServletResponse.class );
        chain = mock( FilterChain.class );
        servletContext = mock( ServletContext.class );
        when( servletContext.getResourceAsStream( "/index.html" ) )
            .thenAnswer( inv -> new ByteArrayInputStream( FAKE_INDEX_HTML.getBytes( StandardCharsets.UTF_8 ) ) );
        capturedOutput = new CapturingServletOutputStream();
        when( response.getOutputStream() ).thenReturn( capturedOutput );
    }

    // ---- Redirect tests ----

    @Test
    void testRootRedirectsToWikiMain() throws Exception {
        final HttpServletRequest request = mockRequest( "/" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wiki/Main" );
        verify( response ).setHeader( "Cache-Control", "no-store" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testWikiSlashRedirectsToWikiMain() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testWikiNoSlashRedirectsToWikiMain() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    // ---- SPA serving tests (at root context) ----

    @Test
    void testWikiPageServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/SomePage" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setContentType( "text/html;charset=UTF-8" );
        assertTrue( capturedOutput.asString().contains( "<div id=\"root\">" ),
                    "response body should contain index.html content" );
    }

    @Test
    void testEditPageServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/edit/SomePage" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "<div id=\"root\">" ) );
    }

    @Test
    void testDiffPageServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/diff/SomePage" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "<div id=\"root\">" ) );
    }

    @Test
    void testSearchServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/search" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "<div id=\"root\">" ) );
    }

    @Test
    void testPreferencesServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/preferences" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "<div id=\"root\">" ) );
    }

    @Test
    void testResetPasswordServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/reset-password" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "<div id=\"root\">" ) );
    }

    @Test
    void testAdminServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/admin/security" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "<div id=\"root\">" ) );
    }

    // ---- Passthrough tests (API calls with non-HTML Accept) ----

    @Test
    void testAdminApiOrphanedPagesPassesThrough() throws Exception {
        final HttpServletRequest request = mockApiRequest( "/admin/content/orphaned-pages" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @Test
    void testAdminApiStatsPassesThrough() throws Exception {
        final HttpServletRequest request = mockApiRequest( "/admin/content/stats" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @Test
    void testAdminApiUsersPassesThrough() throws Exception {
        final HttpServletRequest request = mockApiRequest( "/admin/users" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @Test
    void testAdminApiBrokenLinksPassesThrough() throws Exception {
        final HttpServletRequest request = mockApiRequest( "/admin/content/broken-links" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    // ---- Cache-Control header tests ----

    @Test
    void testSpaForwardSetsNoStoreHeader() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/SomePage" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Cache-Control", "no-store" );
    }

    @Test
    void testSpaForwardSetsPragmaNoCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/SomePage" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Pragma", "no-cache" );
    }

    @Test
    void testSpaForwardSetsExpiresZero() throws Exception {
        final HttpServletRequest request = mockRequest( "/wiki/SomePage" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Expires", "0" );
    }

    @Test
    void testRedirectSetsPragmaNoCache() throws Exception {
        final HttpServletRequest request = mockRequest( "/" );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Pragma", "no-cache" );
    }

    // ---- Passthrough tests (static assets) ----

    @Test
    void testJsAssetsPassThrough() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-ABC.js" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @Test
    void testCssAssetsPassThrough() throws Exception {
        final HttpServletRequest request = mockRequest( "/assets/index-XYZ.css" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    @Test
    void testFaviconPassesThrough() throws Exception {
        final HttpServletRequest request = mockRequest( "/favicon.ico" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( request, never() ).getRequestDispatcher( anyString() );
    }

    // ---- Non-root context tests (IT test WARs deploy under subcontexts) ----

    @Test
    void testWikiPageUnderSubcontextServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/wikantik-it-test-custom", "/wiki/SomePage" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        final String body = capturedOutput.asString();
        assertTrue( body.contains( "src=\"/wikantik-it-test-custom/assets/index-ABC.js\"" ),
                    "js asset src should be prefixed with context path" );
        assertTrue( body.contains( "href=\"/wikantik-it-test-custom/assets/index-XYZ.css\"" ),
                    "css asset href should be prefixed with context path" );
        assertTrue( body.contains( "href=\"/wikantik-it-test-custom/favicon.svg\"" ),
                    "favicon href should be prefixed with context path" );
        assertTrue( body.contains( "window.__WIKANTIK_BASE__=\"/wikantik-it-test-custom\"" ),
                    "index.html should expose the context path to client JS" );
    }

    @Test
    void testEditPageUnderSubcontextServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/wikantik-it-test-custom", "/edit/SomePage" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "/wikantik-it-test-custom/assets/" ) );
    }

    @Test
    void testSearchUnderSubcontextServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/wikantik-it-test-custom", "/search" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "/wikantik-it-test-custom/assets/" ) );
    }

    @Test
    void testAdminUnderSubcontextServesIndexHtml() throws Exception {
        final HttpServletRequest request = mockRequest( "/wikantik-it-test-custom", "/admin/security" );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        assertTrue( capturedOutput.asString().contains( "/wikantik-it-test-custom/assets/" ) );
    }

    @Test
    void testRootUnderSubcontextRedirectsWithContextPrefix() throws Exception {
        final HttpServletRequest request = mockRequest( "/wikantik-it-test-custom", "/" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wikantik-it-test-custom/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testWikiSlashUnderSubcontextRedirectsWithContextPrefix() throws Exception {
        final HttpServletRequest request = mockRequest( "/wikantik-it-test-custom", "/wiki/" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wikantik-it-test-custom/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testWikiNoSlashUnderSubcontextRedirectsWithContextPrefix() throws Exception {
        final HttpServletRequest request = mockRequest( "/wikantik-it-test-custom", "/wiki" );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wikantik-it-test-custom/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    @Test
    void testContextRootBareRedirectsToWikiMain() throws Exception {
        // When the browser hits just the context root like /wikantik-it-test-custom
        // (no trailing slash), the request URI equals the context path and the
        // post-strip path is "".
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getContextPath() ).thenReturn( "/wikantik-it-test-custom" );
        when( request.getRequestURI() ).thenReturn( "/wikantik-it-test-custom" );
        when( request.getHeader( "Accept" ) ).thenReturn( "text/html" );
        when( request.getServletContext() ).thenReturn( servletContext );

        filter.doFilter( request, response, chain );

        verify( response ).sendRedirect( "/wikantik-it-test-custom/wiki/Main" );
        verify( chain, never() ).doFilter( any(), any() );
    }

    // ---- rewriteIndexHtml unit tests ----

    @Test
    void testRewriteIndexHtmlWithEmptyContextPreservesAssets() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, "" );

        assertTrue( out.contains( "src=\"/assets/index-ABC.js\"" ),
                    "root context should leave asset src paths unchanged" );
        assertTrue( out.contains( "href=\"/assets/index-XYZ.css\"" ),
                    "root context should leave asset href paths unchanged" );
        assertTrue( out.contains( "window.__WIKANTIK_BASE__=\"\"" ),
                    "root context should still inject an empty __WIKANTIK_BASE__" );
    }

    @Test
    void testRewriteIndexHtmlWithNullContextTreatedAsEmpty() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, null );

        assertTrue( out.contains( "src=\"/assets/index-ABC.js\"" ) );
        assertTrue( out.contains( "window.__WIKANTIK_BASE__=\"\"" ) );
    }

    @Test
    void testRewriteIndexHtmlWithSubcontextPrefixesAssets() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, "/wikantik-it-test-custom" );

        assertTrue( out.contains( "src=\"/wikantik-it-test-custom/assets/index-ABC.js\"" ) );
        assertTrue( out.contains( "href=\"/wikantik-it-test-custom/assets/index-XYZ.css\"" ) );
        assertTrue( out.contains( "href=\"/wikantik-it-test-custom/favicon.svg\"" ) );
        assertTrue( out.contains( "window.__WIKANTIK_BASE__=\"/wikantik-it-test-custom\"" ) );
        assertFalse( out.contains( "src=\"/assets/" ),
                     "original absolute asset path should be gone" );
    }

    @Test
    void testRewriteIndexHtmlWithSubcontextInjectsImportMap() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, "/wikantik-it-test-custom" );

        assertTrue( out.contains( "<script type=\"importmap\">" ),
                    "non-root context should inject an import map for lazy chunks" );
        assertTrue( out.contains( "\"/assets/\":\"/wikantik-it-test-custom/assets/\"" ),
                    "import map should remap /assets/ to context-prefixed path" );
        final int importMapIdx = out.indexOf( "<script type=\"importmap\">" );
        final int firstModuleIdx = out.indexOf( "type=\"module\"" );
        assertTrue( importMapIdx < firstModuleIdx,
                    "import map must appear before the first module script" );
    }

    @Test
    void testRewriteIndexHtmlAtRootContextOmitsImportMap() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, "" );

        assertFalse( out.contains( "<script type=\"importmap\">" ),
                     "root context should not inject an import map" );
    }

    @Test
    void testRewriteIndexHtmlWithSubcontextInjectsPreloadErrorHandler() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, "/wikantik-it-test-custom" );

        assertTrue( out.contains( "vite:preloadError" ),
                    "non-root context should inject a vite:preloadError handler" );
    }

    @Test
    void testRewriteIndexHtmlAtRootContextOmitsPreloadErrorHandler() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, "" );

        assertFalse( out.contains( "vite:preloadError" ),
                     "root context should not inject vite:preloadError handler" );
    }

    @Test
    void testRewriteIndexHtmlInjectsBaseBeforeFirstScript() {
        final String out = SpaRoutingFilter.rewriteIndexHtml( FAKE_INDEX_HTML, "/ctx" );

        final int baseIdx = out.indexOf( "window.__WIKANTIK_BASE__" );
        final int firstBundleScriptIdx = out.indexOf( "src=\"/ctx/assets/" );
        assertTrue( baseIdx >= 0 && firstBundleScriptIdx >= 0 && baseIdx < firstBundleScriptIdx,
                    "base script must run before the bundled vite script" );
    }

    @Test
    void testRewriteIndexHtmlWithoutScriptTagInjectsBeforeHeadClose() {
        final String htmlNoScript = "<html><head><title>X</title></head><body></body></html>";
        final String out = SpaRoutingFilter.rewriteIndexHtml( htmlNoScript, "/ctx" );

        final int baseIdx = out.indexOf( "window.__WIKANTIK_BASE__" );
        final int headCloseIdx = out.indexOf( "</head>" );
        assertTrue( baseIdx >= 0 && headCloseIdx >= 0 && baseIdx < headCloseIdx,
                    "base script should be injected before </head> when no <script> tag present" );
    }

    // ---- helpers ----

    private HttpServletRequest mockRequest( final String uri ) {
        return mockRequest( "", uri );
    }

    private HttpServletRequest mockRequest( final String contextPath, final String pathAfterContext ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getContextPath() ).thenReturn( contextPath );
        when( request.getRequestURI() ).thenReturn( contextPath + pathAfterContext );
        when( request.getHeader( "Accept" ) ).thenReturn( "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" );
        when( request.getServletContext() ).thenReturn( servletContext );
        return request;
    }

    private HttpServletRequest mockApiRequest( final String uri ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getContextPath() ).thenReturn( "" );
        when( request.getRequestURI() ).thenReturn( uri );
        when( request.getHeader( "Accept" ) ).thenReturn( "application/json" );
        when( request.getServletContext() ).thenReturn( servletContext );
        return request;
    }

    /**
     * Minimal {@link ServletOutputStream} that buffers bytes in memory so tests
     * can assert on what the filter wrote.
     */
    private static final class CapturingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void write( final int b ) throws IOException {
            buffer.write( b );
        }

        @Override
        public void write( final byte[] b, final int off, final int len ) throws IOException {
            buffer.write( b, off, len );
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener( final WriteListener writeListener ) {
            // Not needed for blocking-style tests.
        }

        String asString() {
            return buffer.toString( StandardCharsets.UTF_8 );
        }
    }
}
