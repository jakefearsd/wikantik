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
package com.wikantik.content;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.pages.PageManager;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AtomFeedServletTest {

    private TestEngine engine;
    private AtomFeedServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        // Create test pages with frontmatter
        engine.saveText( "FeedTestArticle",
            "---\ntype: article\ntags: [ai, testing]\nsummary: An article about AI testing\ncluster: technology\n---\n# Feed Test Article\nBody content." );
        engine.saveText( "FeedTestHub",
            "---\ntype: hub\ntags: [hub]\nsummary: Technology hub page\ncluster: technology\n---\n# Technology\nHub page." );
        engine.saveText( "FeedTestOther",
            "---\ntype: article\ntags: [cooking]\nsummary: A cooking article\ncluster: lifestyle\n---\n# Cooking\nBody." );

        servlet = new AtomFeedServlet();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            pm.deletePage( "FeedTestArticle" );
            pm.deletePage( "FeedTestHub" );
            pm.deletePage( "FeedTestOther" );
            engine.stop();
        }
    }

    @Test
    void testFeedGeneratesValidAtomXml() throws Exception {
        final String feed = doGetFeed( null, null );

        assertTrue( feed.contains( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" ) );
        assertTrue( feed.contains( "<feed xmlns=\"http://www.w3.org/2005/Atom\">" ) );
        assertTrue( feed.contains( "</feed>" ) );
    }

    @Test
    void testFeedContainsEntries() throws Exception {
        final String feed = doGetFeed( null, null );

        assertTrue( feed.contains( "<entry>" ), "Feed should contain entry elements" );
        assertTrue( feed.contains( "FeedTestArticle" ), "Feed should contain the test article" );
    }

    @Test
    void testFeedContentType() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/feed.xml" );
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.when( response.getWriter() ).thenReturn( new PrintWriter( sw ) );

        servlet.doGet( request, response );

        Mockito.verify( response ).setContentType( "application/atom+xml" );
        Mockito.verify( response ).setCharacterEncoding( "UTF-8" );
    }

    @Test
    void testFeedIncludesFrontmatterSummary() throws Exception {
        final String feed = doGetFeed( null, null );

        assertTrue( feed.contains( "<summary>An article about AI testing</summary>" ),
            "Feed entry should include frontmatter summary" );
    }

    @Test
    void testFeedIncludesFrontmatterTags() throws Exception {
        final String feed = doGetFeed( null, null );

        assertTrue( feed.contains( "<category term=\"ai\"" ),
            "Feed entry should include ai tag" );
        assertTrue( feed.contains( "<category term=\"testing\"" ),
            "Feed entry should include testing tag" );
    }

    @Test
    void testFeedClusterFilter() throws Exception {
        final String feed = doGetFeed( null, "technology" );

        assertTrue( feed.contains( "FeedTestArticle" ),
            "Technology cluster feed should contain FeedTestArticle" );
        assertTrue( feed.contains( "FeedTestHub" ),
            "Technology cluster feed should contain FeedTestHub" );
        assertFalse( feed.contains( "FeedTestOther" ),
            "Technology cluster feed should NOT contain lifestyle article" );
    }

    @Test
    void testFeedCountParameter() throws Exception {
        final String feed = doGetFeed( "1", null );

        // Count the number of <entry> tags
        final int entryCount = feed.split( "<entry>" ).length - 1;
        assertTrue( entryCount <= 1, "Feed with count=1 should have at most 1 entry" );
    }

    @Test
    void testFeedHasSelfLink() throws Exception {
        final String feed = doGetFeed( null, null );

        assertTrue( feed.contains( "rel=\"self\"" ),
            "Feed should contain a self link" );
        assertTrue( feed.contains( "type=\"application/atom+xml\"" ),
            "Self link should have atom+xml type" );
    }

    @Test
    void testFeedHasRequiredElements() throws Exception {
        final String feed = doGetFeed( null, null );

        assertTrue( feed.contains( "<title>" ), "Feed should have a title" );
        assertTrue( feed.contains( "<id>" ), "Feed should have an id" );
        assertTrue( feed.contains( "<updated>" ), "Feed should have an updated timestamp" );
        assertTrue( feed.contains( "<generator>Wikantik</generator>" ), "Feed should have generator" );
    }

    @Test
    void testFeedEntriesHaveRequiredElements() throws Exception {
        final String feed = doGetFeed( null, null );

        // Every entry should have title, link, id, updated
        if ( feed.contains( "<entry>" ) ) {
            // Extract first entry
            final int start = feed.indexOf( "<entry>" );
            final int end = feed.indexOf( "</entry>", start ) + "</entry>".length();
            final String entry = feed.substring( start, end );

            assertTrue( entry.contains( "<title>" ), "Entry should have title" );
            assertTrue( entry.contains( "<link " ), "Entry should have link" );
            assertTrue( entry.contains( "<id>" ), "Entry should have id" );
            assertTrue( entry.contains( "<updated>" ), "Entry should have updated" );
        }
    }

    private String doGetFeed( final String count, final String cluster ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/feed.xml" );
        Mockito.when( request.getScheme() ).thenReturn( "http" );
        Mockito.when( request.getServerName() ).thenReturn( "localhost" );
        Mockito.when( request.getServerPort() ).thenReturn( 8080 );

        if ( count != null ) {
            Mockito.when( request.getParameter( "count" ) ).thenReturn( count );
        }
        if ( cluster != null ) {
            Mockito.when( request.getParameter( "cluster" ) ).thenReturn( cluster );
        }

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.when( response.getWriter() ).thenReturn( new PrintWriter( sw ) );

        servlet.doGet( request, response );

        return sw.toString();
    }
}
