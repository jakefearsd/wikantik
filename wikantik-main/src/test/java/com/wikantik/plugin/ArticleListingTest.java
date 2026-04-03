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
import com.wikantik.pages.PageManager;
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
 * Tests for {@link ArticleListing} plugin.
 */
class ArticleListingTest {

    private TestEngine engine;
    private ArticleListing plugin;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        plugin = new ArticleListing();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void listsEntriesWithTitlesAndDates() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        blogManager.createEntry( janneSession, "FirstPost" );
        blogManager.createEntry( janneSession, "SecondPost" );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        assertNotNull( result, "Result should not be null" );
        assertTrue( result.contains( "article-listing" ),
            "Result should contain the article-listing CSS class: " + result );
        // Should list both entries
        assertTrue( result.contains( "First Post" ),
            "Result should contain 'First Post': " + result );
        assertTrue( result.contains( "Second Post" ),
            "Result should contain 'Second Post': " + result );
        // Should contain date info
        assertTrue( result.contains( "entry-date" ),
            "Result should contain entry-date CSS class: " + result );
    }

    @Test
    void countParameter() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        blogManager.createEntry( janneSession, "PostA" );
        blogManager.createEntry( janneSession, "PostB" );
        blogManager.createEntry( janneSession, "PostC" );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        // Limit to 2
        final Map< String, String > params = new HashMap<>();
        params.put( "count", "2" );
        final String result = plugin.execute( context, params );

        // Count the entry-item occurrences -- should be exactly 2
        final int count = countOccurrences( result, "entry-item" );
        assertEquals( 2, count, "Should have exactly 2 entry items when count=2" );
    }

    @Test
    void noEntriesReturnsMessage() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        assertEquals( "<p>No entries yet.</p>", result );
    }

    @Test
    void excerptDisabled() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        final Page entry = blogManager.createEntry( janneSession, "NoExcerpt" );

        final String body = "This is the body content of the blog entry.";
        final String content = "---\ntitle: \"No Excerpt\"\ndate: 2026-01-01\nauthor: \"janne\"\n---\n\n" + body;
        engine.getManager( PageManager.class ).putPageText( entry, content );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        params.put( "excerpt", "false" );
        final String result = plugin.execute( context, params );

        assertFalse( result.contains( "entry-excerpt" ),
            "Should not contain excerpt class when excerpt=false: " + result );
    }

    @Test
    void excerptTruncated() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        final Page entry = blogManager.createEntry( janneSession, "TruncPost" );

        final String body = "C".repeat( 500 );
        final String content = "---\ntitle: \"Trunc Post\"\ndate: 2026-01-01\nauthor: \"janne\"\n---\n\n" + body;
        engine.getManager( PageManager.class ).putPageText( entry, content );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        assertTrue( result.contains( "..." ),
            "Excerpt should be truncated with ellipsis: " + result );
        assertFalse( result.contains( body ),
            "Full 500-char body should not appear: " + result );
    }

    @Test
    void userParameterOverridesContext() throws Exception {
        final Session janneSession = engine.janneSession();
        final String janneUsername = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        blogManager.createEntry( janneSession, "JanneEntry" );

        // Create context on an unrelated virtual page (no saveText needed)
        final Page mainPage = Wiki.contents().page( engine, "Main" );
        final Context context = Wiki.context().create( engine, mainPage );

        final Map< String, String > params = new HashMap<>();
        params.put( "user", janneUsername );
        final String result = plugin.execute( context, params );

        assertTrue( result.contains( "Janne Entry" ),
            "Should list janne's entries when user param is set: " + result );
    }

    @Test
    void linksPointToBlogEntries() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        blogManager.createEntry( janneSession, "LinkedPost" );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        // Links should go to /blog/<username>/<entryFile>
        assertTrue( result.contains( "/blog/" + username + "/" ),
            "Links should point to blog entry URLs: " + result );
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
