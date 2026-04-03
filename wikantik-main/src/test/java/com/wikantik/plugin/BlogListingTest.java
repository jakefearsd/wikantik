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
package com.wikantik.plugin;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.blog.BlogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BlogListing} plugin.
 */
class BlogListingTest {

    private TestEngine engine;
    private BlogListing plugin;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        plugin = new BlogListing();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    /**
     * Creates a Context against a simple virtual page (no saveText needed,
     * avoiding session contamination from TestEngine.saveText which logs in as admin).
     */
    private Context createContext() {
        final Page page = Wiki.contents().page( engine, "Main" );
        return Wiki.context().create( engine, page );
    }

    @Test
    void listsBlogsAsLinks() throws Exception {
        // Create a blog for janne and capture username BEFORE any saveText calls
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        engine.getManager( BlogManager.class ).createBlog( janneSession );

        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        assertNotNull( result, "Result should not be null" );
        assertTrue( result.contains( "blog/" + username + "/Blog" ),
            "Result should contain link to janne's blog: " + result );
        assertTrue( result.contains( "blog-listing" ),
            "Result should contain the blog-listing CSS class" );
    }

    @Test
    void noBlogsReturnsMessage() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        assertEquals( "<p>No blogs found.</p>", result );
    }

    @Test
    void includeFilterRestrictsResults() throws Exception {
        final Session janneSession = engine.janneSession();
        engine.getManager( BlogManager.class ).createBlog( janneSession );

        final Context context = createContext();

        // Include filter that does NOT match janne should produce empty result
        final Map< String, String > params = new HashMap<>();
        params.put( "include", "nonexistentuser" );
        final String result = plugin.execute( context, params );

        assertEquals( "<p>No blogs found.</p>", result,
            "Include filter that matches no users should give 'no blogs' message" );
    }

    @Test
    void includeFilterKeepsMatchingBlogs() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        engine.getManager( BlogManager.class ).createBlog( janneSession );

        final Context context = createContext();

        // Include filter that matches janne should show her blog
        final Map< String, String > params = new HashMap<>();
        params.put( "include", username );
        final String result = plugin.execute( context, params );

        assertTrue( result.contains( "blog/" + username + "/Blog" ),
            "Include filter matching janne should show janne's blog: " + result );
    }

    @Test
    void excludeFilterRemovesResults() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        engine.getManager( BlogManager.class ).createBlog( janneSession );

        final Context context = createContext();

        // Exclude janne -- should result in no blogs
        final Map< String, String > params = new HashMap<>();
        params.put( "exclude", username );
        final String result = plugin.execute( context, params );

        assertEquals( "<p>No blogs found.</p>", result,
            "Excluding the only blog should give 'no blogs' message" );
    }

    @Test
    void countParameterLimitsResults() throws Exception {
        final Session janneSession = engine.janneSession();
        engine.getManager( BlogManager.class ).createBlog( janneSession );

        final Context context = createContext();

        // Limit to 1 result -- with only one blog, count=1 should produce exactly 1
        final Map< String, String > params = new HashMap<>();
        params.put( "count", "1" );
        final String result = plugin.execute( context, params );

        assertNotNull( result, "Result should not be null" );
        final int count = countOccurrences( result, "blog-item" );
        assertEquals( 1, count, "Should have exactly 1 blog item when count=1: " + result );
    }

    @Test
    void showsEntryCount() throws Exception {
        final Session janneSession = engine.janneSession();
        engine.getManager( BlogManager.class ).createBlog( janneSession );
        engine.getManager( BlogManager.class ).createEntry( janneSession, "PostOne" );
        engine.getManager( BlogManager.class ).createEntry( janneSession, "PostTwo" );

        final Context context = createContext();

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        // Should mention 2 entries
        assertTrue( result.contains( "2" ),
            "Result should show entry count of 2: " + result );
    }

    @Test
    void escapesHtmlInTitles() throws Exception {
        final Session janneSession = engine.janneSession();
        engine.getManager( BlogManager.class ).createBlog( janneSession );

        final Context context = createContext();

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        // Must not contain unescaped HTML tags from titles
        assertFalse( result.contains( "<script>" ), "Output must not contain unescaped script tags" );
    }

    private int countOccurrences( final String text, final String sub ) {
        int count = 0;
        int idx = 0;
        while ( ( idx = text.indexOf( sub, idx ) ) != -1 ) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
