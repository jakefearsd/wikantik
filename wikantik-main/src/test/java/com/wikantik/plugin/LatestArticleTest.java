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
 * Tests for {@link LatestArticle} plugin.
 */
class LatestArticleTest {

    private TestEngine engine;
    private LatestArticle plugin;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        plugin = new LatestArticle();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void showsLatestEntry() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        blogManager.createEntry( janneSession, "FirstPost" );
        blogManager.createEntry( janneSession, "SecondPost" );

        // Create context on the blog homepage
        final Page blogPage = blogManager.getBlog( username );
        assertNotNull( blogPage, "Blog page should exist" );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        assertNotNull( result, "Result should not be null" );
        assertTrue( result.contains( "latest-article" ),
            "Result should contain the latest-article CSS class: " + result );
        // Should contain the title from frontmatter
        assertTrue( result.contains( "Second Post" ),
            "Result should show the latest entry title 'Second Post': " + result );
    }

    @Test
    void noBlogEntriesReturnsMessage() throws Exception {
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
    void userParameterOverridesContextInference() throws Exception {
        final Session janneSession = engine.janneSession();
        final String janneUsername = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        blogManager.createEntry( janneSession, "JannePost" );

        // Create context on a completely unrelated page (no saveText -- use virtual page)
        final Page mainPage = Wiki.contents().page( engine, "Main" );
        final Context context = Wiki.context().create( engine, mainPage );

        // Pass user param explicitly
        final Map< String, String > params = new HashMap<>();
        params.put( "user", janneUsername );
        final String result = plugin.execute( context, params );

        assertNotNull( result, "Result should not be null" );
        assertTrue( result.contains( "Janne Post" ),
            "Should show janne's latest entry when user param is set: " + result );
    }

    @Test
    void excerptIsTruncatedByDefault() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        final Page entry = blogManager.createEntry( janneSession, "LongPost" );

        // Write a long body to the entry
        final String longContent = "---\ntitle: \"Long Post\"\ndate: 2026-01-01\nauthor: \"janne\"\n---\n\n"
            + "A".repeat( 500 );
        engine.getManager( PageManager.class ).putPageText( entry, longContent );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        // Excerpt should be truncated (default 200 chars + "...")
        assertTrue( result.contains( "..." ),
            "Excerpt should be truncated with ellipsis: " + result );
    }

    @Test
    void excerptFalseShowsFullContent() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        final Page entry = blogManager.createEntry( janneSession, "FullPost" );

        final String body = "This is the full content of the blog post.";
        final String content = "---\ntitle: \"Full Post\"\ndate: 2026-01-01\nauthor: \"janne\"\n---\n\n" + body;
        engine.getManager( PageManager.class ).putPageText( entry, content );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        params.put( "excerpt", "false" );
        final String result = plugin.execute( context, params );

        assertTrue( result.contains( body ),
            "Full content should be shown when excerpt=false: " + result );
    }

    @Test
    void customExcerptLength() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        final Page entry = blogManager.createEntry( janneSession, "CustomLen" );

        final String body = "B".repeat( 100 );
        final String content = "---\ntitle: \"Custom Len\"\ndate: 2026-01-01\nauthor: \"janne\"\n---\n\n" + body;
        engine.getManager( PageManager.class ).putPageText( entry, content );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        params.put( "excerptLength", "50" );
        final String result = plugin.execute( context, params );

        // The excerpt should be truncated to 50 chars
        assertTrue( result.contains( "..." ),
            "Excerpt should be truncated at custom length: " + result );
        // The full 100-char body should not appear
        assertFalse( result.contains( body ),
            "Full body should not appear with excerptLength=50: " + result );
    }

    @Test
    void synopsisOverridesBodyExcerpt() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        final Page entry = blogManager.createEntry( janneSession, "SynopsisPost" );

        final String synopsis = "A hand-crafted summary for listing pages.";
        final String content = "---\ntitle: \"Synopsis Post\"\ndate: 2026-01-01\nauthor: \"janne\"\n"
            + "synopsis: \"" + synopsis + "\"\n---\n\nThis is the actual body which is much longer.";
        engine.getManager( PageManager.class ).putPageText( entry, content );

        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        final Map< String, String > params = new HashMap<>();
        final String result = plugin.execute( context, params );

        assertTrue( result.contains( synopsis ),
            "Should display synopsis from frontmatter: " + result );
        assertFalse( result.contains( "actual body" ),
            "Should not display body text when synopsis is present: " + result );
    }

    @Test
    void resolveUsernameFromPageContext() throws Exception {
        final Session janneSession = engine.janneSession();
        final String username = janneSession.getLoginPrincipal().getName().toLowerCase();
        final BlogManager blogManager = engine.getManager( BlogManager.class );
        blogManager.createBlog( janneSession );
        blogManager.createEntry( janneSession, "ContextPost" );

        // Create context on the blog home page (path: blog/<username>/Blog)
        final Page blogPage = blogManager.getBlog( username );
        final Context context = Wiki.context().create( engine, blogPage );

        // No user param -- should infer from page name
        final Map< String, String > params = new HashMap<>();
        final String resolved = LatestArticle.resolveUsername( context, params );

        assertEquals( username, resolved, "Should resolve username from page name" );
    }
}
