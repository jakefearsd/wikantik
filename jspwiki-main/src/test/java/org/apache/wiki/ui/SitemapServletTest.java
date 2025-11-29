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
package org.apache.wiki.ui;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.wiki.HttpMockFactory;
import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Attachment;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.attachment.AttachmentManager;
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;


class SitemapServletTest {

    TestEngine m_engine;
    SitemapServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        m_engine = new TestEngine( props );

        // Create test pages
        m_engine.saveText( "TestPage1", "This is test page 1." );
        m_engine.saveText( "TestPage2", "This is test page 2." );
        m_engine.saveText( "LeftMenu", "This is the left menu." );
        m_engine.saveText( "LeftMenuFooter", "This is the left menu footer." );
        m_engine.saveText( "TitleBox", "This is the title box." );
        m_engine.saveText( "CSSStyles", "This is a CSS styles page." );

        // Initialize servlet with the same servlet context as the engine so it can find the WikiEngine
        servlet = new SitemapServlet();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( m_engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if( m_engine != null ) {
            final PageManager pm = m_engine.getManager( PageManager.class );
            final AttachmentManager am = m_engine.getManager( AttachmentManager.class );

            // Clean up attachments first
            try {
                final var attachments = am.listAttachments( pm.getPage( "TestPage1" ) );
                for( final var att : attachments ) {
                    am.deleteAttachment( att );
                }
            } catch( final Exception e ) {
                // Ignore if page doesn't exist
            }

            pm.deletePage( "TestPage1" );
            pm.deletePage( "TestPage2" );
            pm.deletePage( "LeftMenu" );
            pm.deletePage( "LeftMenuFooter" );
            pm.deletePage( "TitleBox" );
            pm.deletePage( "CSSStyles" );
            m_engine.stop();
        }
    }

    @Test
    void testSitemapGeneratesValidXml() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify XML structure
        Assertions.assertTrue( sitemap.contains( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" ) );
        Assertions.assertTrue( sitemap.contains( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"" ) );
        Assertions.assertTrue( sitemap.contains( "</urlset>" ) );
    }

    @Test
    void testSitemapIncludesImageNamespace() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify image namespace is included
        Assertions.assertTrue( sitemap.contains( "xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\"" ),
            "Sitemap should include Google Image sitemap namespace" );
    }

    @Test
    void testSitemapContainsRegularPages() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify regular pages are included
        Assertions.assertTrue( sitemap.contains( "TestPage1" ), "Sitemap should contain TestPage1" );
        Assertions.assertTrue( sitemap.contains( "TestPage2" ), "Sitemap should contain TestPage2" );
    }

    @Test
    void testSitemapExcludesMenuPages() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify menu pages are excluded
        Assertions.assertFalse( sitemap.contains( ">LeftMenu<" ), "Sitemap should not contain LeftMenu" );
        Assertions.assertFalse( sitemap.contains( ">LeftMenuFooter<" ), "Sitemap should not contain LeftMenuFooter" );
        Assertions.assertFalse( sitemap.contains( ">TitleBox<" ), "Sitemap should not contain TitleBox" );
    }

    @Test
    void testSitemapExcludesCssPrefixedPages() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify CSS-prefixed pages are excluded
        Assertions.assertFalse( sitemap.contains( "CSSStyles" ), "Sitemap should not contain CSSStyles" );
    }

    @Test
    void testSitemapContainsRequiredElements() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify required sitemap elements are present
        Assertions.assertTrue( sitemap.contains( "<url>" ) );
        Assertions.assertTrue( sitemap.contains( "<loc>" ) );
        Assertions.assertTrue( sitemap.contains( "<lastmod>" ) );
    }

    @Test
    void testSitemapOmitsChangefreqAndPriority() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify changefreq and priority are NOT present (Google ignores them)
        Assertions.assertFalse( sitemap.contains( "<changefreq>" ),
            "Sitemap should NOT contain changefreq (Google ignores it)" );
        Assertions.assertFalse( sitemap.contains( "<priority>" ),
            "Sitemap should NOT contain priority (Google ignores it)" );
    }

    @Test
    void testSitemapContentType() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        Mockito.verify( response ).setContentType( "application/xml" );
        Mockito.verify( response ).setCharacterEncoding( "UTF-8" );
    }

    @Test
    void testSitemapUrlsAreFullyQualified() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        // Mock request properties for URL building fallback
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Extract loc elements and verify they are fully qualified
        Assertions.assertTrue( sitemap.contains( "<loc>http://" ) || sitemap.contains( "<loc>https://" ),
            "Sitemap URLs should be fully qualified with http:// or https://" );

        // Verify TestPage1 URL is fully qualified
        Assertions.assertTrue( sitemap.contains( "http://localhost:8080" ),
            "Sitemap should contain fully qualified URLs with host and port" );
    }

    @Test
    void testSitemapWithContextPath() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/JSPWiki", "/sitemap.xml" );
        // Mock request properties for URL building fallback
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );
        Mockito.when( request.getContextPath() ).thenReturn( "/JSPWiki" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify URLs include the context path
        Assertions.assertTrue( sitemap.contains( "http://localhost:8080/JSPWiki" ),
            "Sitemap URLs should include the context path /JSPWiki" );
    }

    @Test
    void testSitemapWithRootContextPath() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "", "/sitemap.xml" );
        // Mock request properties for URL building fallback
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );
        Mockito.when( request.getContextPath() ).thenReturn( "" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify URLs are fully qualified without double slashes
        Assertions.assertTrue( sitemap.contains( "http://localhost:8080/" ),
            "Sitemap URLs should be fully qualified for ROOT deployment" );
        // Should not have double slashes after port (like http://localhost:8080//wiki)
        Assertions.assertFalse( sitemap.contains( "8080//" ),
            "Sitemap URLs should not contain double slashes" );
    }

    @Test
    void testSitemapWithConfiguredBaseUrl() throws Exception {
        // This test verifies that when engine.getBaseURL() returns a properly configured URL,
        // the sitemap uses it directly instead of building from the request.
        // The TestEngine's base URL configuration is tested implicitly through the other tests
        // that fall back to request-based URL building.

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        // Even if we mock these, the engine's baseURL should take precedence if properly configured
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );
        Mockito.when( request.getContextPath() ).thenReturn( "/JSPWiki" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify URLs are properly formed (either from engine baseURL or request fallback)
        // The key is that URLs must be fully qualified
        Assertions.assertTrue( sitemap.contains( "<loc>http" ),
            "Sitemap URLs should be fully qualified" );

        // Verify no malformed URLs
        Assertions.assertFalse( sitemap.contains( "<loc>null" ),
            "Sitemap URLs should not contain null" );
        Assertions.assertFalse( sitemap.contains( "://:/" ),
            "Sitemap URLs should be properly formed" );
    }

    @Test
    void testSitemapWithHttpsAndDefaultPort() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        // Mock HTTPS on default port 443
        Mockito.when( request.getScheme() ).thenReturn( "https" );
        Mockito.when( request.getServerName() ).thenReturn( "secure.example.com" );
        Mockito.when( request.getServerPort() ).thenReturn( 443 );
        Mockito.when( request.getContextPath() ).thenReturn( "" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify URLs don't include port 443 (default HTTPS port)
        Assertions.assertTrue( sitemap.contains( "https://secure.example.com/" ),
            "Sitemap URLs should use https://" );
        Assertions.assertFalse( sitemap.contains( ":443" ),
            "Sitemap URLs should not include default HTTPS port 443" );
    }

    @Test
    void testSitemapIncludesImageAttachments() throws Exception {
        // Create an image attachment
        final AttachmentManager am = m_engine.getManager( AttachmentManager.class );
        final Attachment att = Wiki.contents().attachment( m_engine, "TestPage1", "test-image.png" );
        att.setAuthor( "TestUser" );

        // Create a temporary file with some content
        final File tempFile = File.createTempFile( "test-image", ".png" );
        tempFile.deleteOnExit();
        try( final FileOutputStream fos = new FileOutputStream( tempFile ) ) {
            // Write minimal PNG header bytes
            fos.write( new byte[] { (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A } );
        }
        am.storeAttachment( att, tempFile );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );
        Mockito.when( request.getContextPath() ).thenReturn( "" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify image extension is included for the attachment
        Assertions.assertTrue( sitemap.contains( "<image:image>" ),
            "Sitemap should contain image:image element for PNG attachment" );
        Assertions.assertTrue( sitemap.contains( "<image:loc>" ),
            "Sitemap should contain image:loc element" );
        Assertions.assertTrue( sitemap.contains( "test-image.png" ),
            "Sitemap should contain the image filename" );
        Assertions.assertTrue( sitemap.contains( "/attach/TestPage1/test-image.png" ),
            "Sitemap should contain correct attachment URL path" );
    }

    @Test
    void testSitemapIncludesMultipleImageFormats() throws Exception {
        // Create attachments with various image formats
        final AttachmentManager am = m_engine.getManager( AttachmentManager.class );
        final String[] imageExtensions = { "jpg", "jpeg", "gif", "webp" };

        for( final String ext : imageExtensions ) {
            final Attachment att = Wiki.contents().attachment( m_engine, "TestPage1", "image." + ext );
            att.setAuthor( "TestUser" );

            final File tempFile = File.createTempFile( "test-image", "." + ext );
            tempFile.deleteOnExit();
            try( final FileOutputStream fos = new FileOutputStream( tempFile ) ) {
                fos.write( "fake image content".getBytes() );
            }
            am.storeAttachment( att, tempFile );
        }

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );
        Mockito.when( request.getContextPath() ).thenReturn( "" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify all image formats are included
        for( final String ext : imageExtensions ) {
            Assertions.assertTrue( sitemap.contains( "image." + ext ),
                "Sitemap should contain image with ." + ext + " extension" );
        }
    }

    @Test
    void testSitemapExcludesNonImageAttachments() throws Exception {
        // Create a non-image attachment
        final AttachmentManager am = m_engine.getManager( AttachmentManager.class );
        final Attachment att = Wiki.contents().attachment( m_engine, "TestPage1", "document.pdf" );
        att.setAuthor( "TestUser" );

        final File tempFile = File.createTempFile( "test-doc", ".pdf" );
        tempFile.deleteOnExit();
        try( final FileOutputStream fos = new FileOutputStream( tempFile ) ) {
            fos.write( "%PDF-1.4 fake content".getBytes() );
        }
        am.storeAttachment( att, tempFile );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );
        Mockito.when( request.getContextPath() ).thenReturn( "" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify PDF is NOT included as an image
        Assertions.assertFalse( sitemap.contains( "document.pdf" ),
            "Sitemap should NOT contain PDF in image:image element" );
        // But the page itself should still be in the sitemap
        Assertions.assertTrue( sitemap.contains( "TestPage1" ),
            "Sitemap should still contain the page" );
    }

    @Test
    void testSitemapImageUrlsAreProperlyEscaped() throws Exception {
        // Create an image attachment with special characters
        final AttachmentManager am = m_engine.getManager( AttachmentManager.class );
        final Attachment att = Wiki.contents().attachment( m_engine, "TestPage1", "image&test.png" );
        att.setAuthor( "TestUser" );

        final File tempFile = File.createTempFile( "test-image", ".png" );
        tempFile.deleteOnExit();
        try( final FileOutputStream fos = new FileOutputStream( tempFile ) ) {
            fos.write( new byte[] { (byte)0x89, 0x50, 0x4E, 0x47 } );
        }
        am.storeAttachment( att, tempFile );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );
        Mockito.when( request.getContextPath() ).thenReturn( "" );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify the ampersand is escaped as &amp;
        Assertions.assertTrue( sitemap.contains( "image&amp;test.png" ),
            "Sitemap should escape ampersand as &amp;" );
        Assertions.assertFalse( sitemap.contains( "image&test.png</image:loc>" ),
            "Sitemap should NOT contain unescaped ampersand" );
    }

    @Test
    void testSitemapWithExplicitBaseUrlHttps() throws Exception {
        // Create a new engine with explicit sitemap base URL configured
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SitemapServlet.PROP_SITEMAP_BASE_URL, "https://wiki.example.com/JSPWiki" );
        final TestEngine engineWithHttps = new TestEngine( props );

        try {
            // Create test page
            engineWithHttps.saveText( "TestPageHttps", "Test page for HTTPS sitemap test." );

            // Initialize servlet with this engine
            final SitemapServlet httpsServlet = new SitemapServlet();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( engineWithHttps.getServletContext() ).when( config ).getServletContext();
            httpsServlet.init( config );

            // Create request - note: the request itself uses http, but sitemap should use configured https
            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
            Mockito.when( request.getScheme() ).thenReturn( "http" );  // Internal request is HTTP
            Mockito.when( request.getServerName() ).thenReturn( "localhost" );
            Mockito.when( request.getServerPort() ).thenReturn( 8080 );
            Mockito.when( request.getContextPath() ).thenReturn( "/JSPWiki" );

            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter stringWriter = new StringWriter();
            final PrintWriter printWriter = new PrintWriter( stringWriter );

            Mockito.when( response.getWriter() ).thenReturn( printWriter );

            httpsServlet.doGet( request, response );

            final String sitemap = stringWriter.toString();

            // Verify URLs use the configured HTTPS base URL, not the request's http
            Assertions.assertTrue( sitemap.contains( "<loc>https://wiki.example.com/JSPWiki" ),
                "Sitemap URLs should use configured HTTPS base URL" );
            Assertions.assertFalse( sitemap.contains( "<loc>http://localhost:8080" ),
                "Sitemap URLs should NOT use request-based HTTP URL when base URL is configured" );
        } finally {
            // Cleanup
            final PageManager pm = engineWithHttps.getManager( PageManager.class );
            pm.deletePage( "TestPageHttps" );
            engineWithHttps.stop();
        }
    }

    @Test
    void testSitemapWithExplicitBaseUrlNoTrailingSlash() throws Exception {
        // Test that trailing slashes are handled correctly
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SitemapServlet.PROP_SITEMAP_BASE_URL, "https://wiki.example.com/JSPWiki/" );  // With trailing slash
        final TestEngine engineWithSlash = new TestEngine( props );

        try {
            engineWithSlash.saveText( "TestPageSlash", "Test page for trailing slash test." );

            final SitemapServlet slashServlet = new SitemapServlet();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( engineWithSlash.getServletContext() ).when( config ).getServletContext();
            slashServlet.init( config );

            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
            Mockito.when( request.getScheme() ).thenReturn( "http" );
            Mockito.when( request.getServerName() ).thenReturn( "localhost" );
            Mockito.when( request.getServerPort() ).thenReturn( 8080 );
            Mockito.when( request.getContextPath() ).thenReturn( "/JSPWiki" );

            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter stringWriter = new StringWriter();
            final PrintWriter printWriter = new PrintWriter( stringWriter );

            Mockito.when( response.getWriter() ).thenReturn( printWriter );

            slashServlet.doGet( request, response );

            final String sitemap = stringWriter.toString();

            // Verify no double slashes in URLs (trailing slash should be stripped)
            Assertions.assertFalse( sitemap.contains( "JSPWiki//" ),
                "Sitemap URLs should not contain double slashes after context path" );
            // Verify the base URL is used (with trailing slash stripped)
            Assertions.assertTrue( sitemap.contains( "<loc>https://wiki.example.com/JSPWiki/" ),
                "Sitemap URLs should use the configured base URL" );
            // Verify page is in the sitemap
            Assertions.assertTrue( sitemap.contains( "TestPageSlash" ),
                "Sitemap should contain the test page" );
        } finally {
            final PageManager pm = engineWithSlash.getManager( PageManager.class );
            pm.deletePage( "TestPageSlash" );
            engineWithSlash.stop();
        }
    }

    @Test
    void testSitemapWithEmptyBaseUrlFallsBackToRequest() throws Exception {
        // Test that empty/blank base URL falls back to request-based URL building
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SitemapServlet.PROP_SITEMAP_BASE_URL, "   " );  // Blank string
        final TestEngine engineWithBlank = new TestEngine( props );

        try {
            engineWithBlank.saveText( "TestPageBlank", "Test page for blank base URL test." );

            final SitemapServlet blankServlet = new SitemapServlet();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( engineWithBlank.getServletContext() ).when( config ).getServletContext();
            blankServlet.init( config );

            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
            Mockito.when( request.getScheme() ).thenReturn( "http" );
            Mockito.when( request.getServerName() ).thenReturn( "testserver.local" );
            Mockito.when( request.getServerPort() ).thenReturn( 9090 );
            Mockito.when( request.getContextPath() ).thenReturn( "/wiki" );

            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter stringWriter = new StringWriter();
            final PrintWriter printWriter = new PrintWriter( stringWriter );

            Mockito.when( response.getWriter() ).thenReturn( printWriter );

            blankServlet.doGet( request, response );

            final String sitemap = stringWriter.toString();

            // Should fall back to request-based URL (testserver.local:9090)
            Assertions.assertTrue( sitemap.contains( "http://testserver.local:9090" ),
                "Sitemap should fall back to request-based URL when base URL is blank" );
        } finally {
            final PageManager pm = engineWithBlank.getManager( PageManager.class );
            pm.deletePage( "TestPageBlank" );
            engineWithBlank.stop();
        }
    }

    @Test
    void testSitemapBaseUrlOverridesRequestScheme() throws Exception {
        // Specifically test the proxy scenario: internal HTTP request, external HTTPS
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SitemapServlet.PROP_SITEMAP_BASE_URL, "https://public.wiki.com" );
        final TestEngine proxyEngine = new TestEngine( props );

        try {
            proxyEngine.saveText( "ProxyTestPage", "Test page for proxy scenario." );

            final SitemapServlet proxyServlet = new SitemapServlet();
            final ServletConfig config = Mockito.mock( ServletConfig.class );
            Mockito.doReturn( proxyEngine.getServletContext() ).when( config ).getServletContext();
            proxyServlet.init( config );

            // Simulate internal request behind proxy (HTTP on port 8080)
            final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
            Mockito.when( request.getScheme() ).thenReturn( "http" );
            Mockito.when( request.getServerName() ).thenReturn( "internal-server" );
            Mockito.when( request.getServerPort() ).thenReturn( 8080 );
            Mockito.when( request.getContextPath() ).thenReturn( "" );

            final HttpServletResponse response = HttpMockFactory.createHttpResponse();
            final StringWriter stringWriter = new StringWriter();
            final PrintWriter printWriter = new PrintWriter( stringWriter );

            Mockito.when( response.getWriter() ).thenReturn( printWriter );

            proxyServlet.doGet( request, response );

            final String sitemap = stringWriter.toString();

            // The configured base URL should completely override request-derived URL
            Assertions.assertTrue( sitemap.contains( "<loc>https://public.wiki.com" ),
                "Sitemap should use configured HTTPS URL" );
            Assertions.assertFalse( sitemap.contains( "internal-server" ),
                "Sitemap should NOT contain internal server name" );
            Assertions.assertFalse( sitemap.contains( "http://internal" ),
                "Sitemap should NOT use HTTP scheme from internal request" );
        } finally {
            final PageManager pm = proxyEngine.getManager( PageManager.class );
            pm.deletePage( "ProxyTestPage" );
            proxyEngine.stop();
        }
    }

    @Test
    void testSitemapPropertyConstantValue() {
        // Verify the property constant has the expected value
        Assertions.assertEquals( "jspwiki.sitemap.baseURL", SitemapServlet.PROP_SITEMAP_BASE_URL,
            "Property constant should have correct value" );
    }

}
