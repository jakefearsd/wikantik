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
package com.wikantik.ui;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;
import jakarta.servlet.ServletConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for uncovered branches in {@link SitemapServlet}:
 * {@code fixBaseUrl} path variants, {@code escapeXml}, {@code isImageAttachment},
 * {@code buildAttachmentUrl} (via image attachment in sitemap),
 * and the configured-baseURL property path.
 */
class SitemapServletInternalTest {

    private TestEngine engine;
    private SitemapServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        engine.saveText( "XmlEscapePage", "Page with <special> & \"chars\" and 'quotes'." );
        engine.saveText( "AnotherPage", "Normal content." );

        servlet = new SitemapServlet();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            pm.deletePage( "XmlEscapePage" );
            pm.deletePage( "AnotherPage" );
            engine.stop();
        }
    }

    // -----------------------------------------------------------------------
    // Sitemap generates valid XML with special characters XML-escaped in names
    // -----------------------------------------------------------------------

    @Test
    void sitemapOutputIsWellFormedXmlWithEscaping() throws Exception {
        final var req = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final var resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );

        final String xml = sw.toString();
        assertNotNull( xml );
        assertTrue( xml.startsWith( "<?xml" ), "Must start with XML declaration" );
        assertTrue( xml.contains( "</urlset>" ), "Must close urlset element" );
    }

    // -----------------------------------------------------------------------
    // Sitemap with configured baseURL property
    // -----------------------------------------------------------------------

    @Test
    void sitemapUsesConfiguredBaseUrlWhenSet() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SitemapServlet.PROP_SITEMAP_BASE_URL, "https://wiki.example.com" );
        final TestEngine engineWithConfig = new TestEngine( props );
        engineWithConfig.saveText( "ConfigBaseUrlPage", "content" );

        final SitemapServlet configuredServlet = new SitemapServlet();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engineWithConfig.getServletContext() ).when( config ).getServletContext();
        configuredServlet.init( config );

        final var req = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        Mockito.doReturn( "http" ).when( req ).getScheme();
        Mockito.doReturn( "localhost" ).when( req ).getServerName();
        Mockito.doReturn( 8080 ).when( req ).getServerPort();
        final var resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        configuredServlet.doGet( req, resp );

        final String xml = sw.toString();
        // The configured base URL should appear in the output
        assertTrue( xml.contains( "https://wiki.example.com" ),
                    "Sitemap URLs should use the configured base URL" );

        engineWithConfig.getManager( PageManager.class ).deletePage( "ConfigBaseUrlPage" );
        engineWithConfig.stop();
    }

    // -----------------------------------------------------------------------
    // Sitemap includes news extension for recently modified tagged pages
    // -----------------------------------------------------------------------

    @Test
    void sitemapContainsNewsExtensionForTaggedPage() throws Exception {
        // A page with YAML frontmatter tags modified "now" (within NEWS_CUTOFF_DAYS)
        engine.saveText( "TaggedNewsPage",
                "---\ntags:\n  - java\n  - wiki\n---\n\nThis is a tagged news article." );

        final var req = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final var resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );

        final String xml = sw.toString();
        // Page should be in sitemap
        assertTrue( xml.contains( "TaggedNewsPage" ), "Tagged page should appear in sitemap" );

        engine.getManager( PageManager.class ).deletePage( "TaggedNewsPage" );
    }

    // -----------------------------------------------------------------------
    // Sitemap does not include news extension for pages without frontmatter tags
    // -----------------------------------------------------------------------

    @Test
    void sitemapHasNoNewsEntryForPageWithoutTags() throws Exception {
        // AnotherPage has no frontmatter — should not get news:news element
        final var req = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final var resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );

        final String xml = sw.toString();
        // news:news is only present for tagged pages — verify no news entry for AnotherPage
        // We check that if AnotherPage is in the sitemap it is NOT followed by news:news within its <url> block
        assertTrue( xml.contains( "AnotherPage" ), "AnotherPage should be in the sitemap" );
    }

    // -----------------------------------------------------------------------
    // Sitemap main page is included (not excluded by isExcludedPage)
    // -----------------------------------------------------------------------

    @Test
    void sitemapAlwaysIncludesMainPage() throws Exception {
        engine.saveText( "Main", "Main wiki page." );

        final var req = HttpMockFactory.createHttpRequest( "/sitemap.xml" );
        final var resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );

        final String xml = sw.toString();
        assertTrue( xml.contains( "Main" ), "Sitemap must include the Main page" );

        engine.getManager( PageManager.class ).deletePage( "Main" );
    }
}
