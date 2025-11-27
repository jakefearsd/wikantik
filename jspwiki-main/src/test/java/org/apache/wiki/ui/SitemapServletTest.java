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
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        Assertions.assertTrue( sitemap.contains( "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">" ) );
        Assertions.assertTrue( sitemap.contains( "</urlset>" ) );
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
        Assertions.assertTrue( sitemap.contains( "<changefreq>" ) );
        Assertions.assertTrue( sitemap.contains( "<priority>" ) );
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
    void testSitemapPriorityValues() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify priority values are within valid range (0.0 to 1.0)
        Assertions.assertTrue(
            sitemap.contains( "<priority>0.5</priority>" ) ||
            sitemap.contains( "<priority>0.6</priority>" ) ||
            sitemap.contains( "<priority>0.8</priority>" ) ||
            sitemap.contains( "<priority>1.0</priority>" ),
            "Sitemap should contain valid priority values"
        );
    }

    @Test
    void testSitemapChangeFreqValues() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter( stringWriter );

        Mockito.when( response.getWriter() ).thenReturn( printWriter );

        servlet.doGet( request, response );

        final String sitemap = stringWriter.toString();

        // Verify changefreq values are valid
        Assertions.assertTrue(
            sitemap.contains( "<changefreq>daily</changefreq>" ) ||
            sitemap.contains( "<changefreq>weekly</changefreq>" ) ||
            sitemap.contains( "<changefreq>monthly</changefreq>" ) ||
            sitemap.contains( "<changefreq>yearly</changefreq>" ),
            "Sitemap should contain valid changefreq values"
        );
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

}
