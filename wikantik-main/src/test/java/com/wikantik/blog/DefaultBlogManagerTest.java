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
package com.wikantik.blog;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.pages.PageManager;
import com.wikantik.providers.AbstractFileProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultBlogManager}.
 */
class DefaultBlogManagerTest {

    private TestEngine engine;
    private BlogManager blogManager;
    private PageManager pageManager;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        blogManager = engine.getManager( BlogManager.class );
        pageManager = engine.getManager( PageManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testManagerAvailable() {
        assertNotNull( blogManager, "BlogManager should be registered and available" );
    }

    @Test
    void testCreateBlog() throws Exception {
        final Session session = engine.janneSession();
        final Page blogPage = blogManager.createBlog( session );

        assertNotNull( blogPage, "createBlog should return the Blog page" );
        assertTrue( blogPage.getName().startsWith( "blog/" ), "Blog page name should start with blog/" );
        assertTrue( blogPage.getName().endsWith( "/Blog" ), "Blog page name should end with /Blog" );

        // The blog directory should exist on disk
        final String pageDir = engine.getWikiProperties().getProperty( AbstractFileProvider.PROP_PAGEDIR );
        final String username = session.getLoginPrincipal().getName().toLowerCase();
        final File blogDir = new File( pageDir, "blog/" + username );
        assertTrue( blogDir.isDirectory(), "Blog directory should exist on disk: " + blogDir );

        // Blog.md should be readable via PageManager and contain template content
        assertTrue( blogManager.blogExists( username ), "blogExists should return true" );
        final String content = pageManager.getPureText( blogPage );
        assertNotNull( content, "Blog.md content should be readable" );
        assertTrue( content.contains( "blog" ) || content.contains( "Blog" ),
            "Blog.md should contain blog-related content from the template" );
    }

    @Test
    void testCreateBlogDuplicateThrows() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );

        // Second creation should throw BlogAlreadyExistsException
        assertThrows( BlogAlreadyExistsException.class, () -> blogManager.createBlog( session ),
            "Creating a blog twice for the same user should throw BlogAlreadyExistsException" );
    }

    @Test
    void testCreateEntry() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );

        final Page entryPage = blogManager.createEntry( session, "MyFirstPost" );

        assertNotNull( entryPage, "createEntry should return the entry page" );
        // Entry name should contain YYYYMMDD prefix
        final String username = session.getLoginPrincipal().getName().toLowerCase();
        assertTrue( entryPage.getName().startsWith( "blog/" + username + "/" ),
            "Entry page name should be under blog/<username>/" );
        // Verify the entry name contains a date-like prefix (8 digits)
        final String slug = entryPage.getName().substring( entryPage.getName().lastIndexOf( '/' ) + 1 );
        assertTrue( slug.matches( "\\d{8}.*" ),
            "Entry slug should start with YYYYMMDD: " + slug );

        // Entry content should have frontmatter with title, date, author
        final String content = pageManager.getPureText( entryPage );
        assertNotNull( content, "Entry content should be readable" );

        final ParsedPage parsed = FrontmatterParser.parse( content );
        assertNotNull( parsed.metadata().get( "title" ), "Frontmatter should have a title" );
        assertNotNull( parsed.metadata().get( "date" ), "Frontmatter should have a date" );
        assertNotNull( parsed.metadata().get( "author" ), "Frontmatter should have an author" );
    }

    @Test
    void testCreateEntryWithContent() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );

        final Page entryPage = blogManager.createEntry( session, "RichPost", "Hello world, this is my first post." );

        assertNotNull( entryPage, "createEntry with content should return the entry page" );

        final String content = pageManager.getPureText( entryPage );
        final ParsedPage parsed = FrontmatterParser.parse( content );
        assertNotNull( parsed.metadata().get( "title" ), "Frontmatter should have a title" );
        assertNotNull( parsed.metadata().get( "date" ), "Frontmatter should have a date" );
        assertNotNull( parsed.metadata().get( "author" ), "Frontmatter should have an author" );
        assertTrue( parsed.body().contains( "# Rich Post" ), "Body should contain the heading" );
        assertTrue( parsed.body().contains( "Hello world, this is my first post." ),
            "Body should contain the user-supplied content" );
    }

    @Test
    void testCreateEntryWithNullContentMatchesOriginal() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );

        final Page entryPage = blogManager.createEntry( session, "PlainPost", null );

        assertNotNull( entryPage, "createEntry with null content should return the entry page" );

        final String content = pageManager.getPureText( entryPage );
        final ParsedPage parsed = FrontmatterParser.parse( content );
        assertNotNull( parsed.metadata().get( "title" ), "Frontmatter should have a title" );
        // Body should only have the heading, no additional content
        final String body = parsed.body().trim();
        assertEquals( "# Plain Post", body, "Body should only contain the heading when content is null" );
    }

    @Test
    void testListEntries() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );
        blogManager.createEntry( session, "PostOne" );
        blogManager.createEntry( session, "PostTwo" );

        final String username = session.getLoginPrincipal().getName().toLowerCase();
        final List< Page > entries = blogManager.listEntries( username );

        assertNotNull( entries, "listEntries should return a list" );
        assertEquals( 2, entries.size(), "Should have exactly 2 entries (Blog.md excluded)" );

        // Entries should be sorted by name descending (newest first)
        // Both have the same date prefix since they're created in the same test run,
        // so just verify they don't include the Blog page
        for ( final Page entry : entries ) {
            assertFalse( entry.getName().endsWith( "/Blog" ),
                "Blog.md should not appear in entry listing: " + entry.getName() );
        }
    }

    @Test
    void testListBlogs() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );

        final List< BlogInfo > blogs = blogManager.listBlogs();

        assertNotNull( blogs, "listBlogs should return a list" );
        assertFalse( blogs.isEmpty(), "listBlogs should contain at least one blog" );

        final String username = session.getLoginPrincipal().getName().toLowerCase();
        final BlogInfo found = blogs.stream()
            .filter( b -> b.username().equals( username ) )
            .findFirst()
            .orElse( null );
        assertNotNull( found, "listBlogs should include the blog we just created" );
        assertNotNull( found.title(), "Blog title should not be null" );
        assertNotNull( found.authorFullName(), "Author full name should not be null" );
    }

    @Test
    void testDeleteBlog() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );
        blogManager.createEntry( session, "ToBeDeleted" );

        final String username = session.getLoginPrincipal().getName().toLowerCase();
        assertTrue( blogManager.blogExists( username ), "Blog should exist before deletion" );

        blogManager.deleteBlog( session, username );

        assertFalse( blogManager.blogExists( username ), "Blog should not exist after deletion" );
        assertNull( blogManager.getBlog( username ), "getBlog should return null after deletion" );
    }

    @Test
    void testGetBlogReturnsNullForNonExistentBlog() throws Exception {
        assertNull( blogManager.getBlog( "nosuchuser" ), "getBlog for non-existent user should return null" );
    }

    @Test
    void testBlogExistsReturnsFalseForNonExistentBlog() {
        assertFalse( blogManager.blogExists( "nosuchuser" ), "blogExists for non-existent user should return false" );
    }

    @Test
    void testGetBlogInfoReturnsNullForNonExistentBlog() throws Exception {
        assertNull( blogManager.getBlogInfo( "nosuchuser" ) );
    }

    @Test
    void testGetBlogInfoReturnsInfoForExistingBlog() throws Exception {
        final Session session = engine.janneSession();
        blogManager.createBlog( session );
        final String username = session.getLoginPrincipal().getName().toLowerCase();

        final BlogInfo info = blogManager.getBlogInfo( username );
        assertNotNull( info, "getBlogInfo should return info for existing blog" );
        assertEquals( username, info.username() );
        assertNotNull( info.title() );
        assertEquals( 0, info.entryCount(), "New blog should have no entries" );
    }

    @Test
    void testDeleteBlogByAdminForDifferentUser() throws Exception {
        // Create blog as janne
        final Session janneSession = engine.janneSession();
        blogManager.createBlog( janneSession );
        final String janneUsername = janneSession.getLoginPrincipal().getName().toLowerCase();

        // Admin should be able to delete janne's blog
        final Session adminSession = engine.adminSession();
        blogManager.deleteBlog( adminSession, janneUsername );

        assertFalse( blogManager.blogExists( janneUsername ), "Blog should be deleted by admin" );
    }
}
