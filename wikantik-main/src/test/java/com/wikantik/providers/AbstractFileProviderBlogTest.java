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
package com.wikantik.providers;

import com.wikantik.TestEngine;
import com.wikantik.WikiPage;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Properties;

import static com.wikantik.TestEngine.with;

/**
 * Tests for blog-aware name resolution in {@link AbstractFileProvider}.
 * <p>
 * Blog pages (names starting with {@code blog/}) are stored in real subdirectories
 * rather than flat URL-encoded filenames.  These tests verify that the provider
 * correctly resolves, discovers, and writes blog pages using the subdirectory layout.
 */
class AbstractFileProviderBlogTest {

    private Engine engine;
    private FileSystemProvider provider;
    private Properties props;
    private String pageDir;

    @BeforeEach
    void setUp() throws Exception {
        props = TestEngine.getTestProperties();
        final long ts = System.currentTimeMillis();
        pageDir = props.getProperty( AbstractFileProvider.PROP_PAGEDIR ) + ts;
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir );
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" );

        engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" ),
            with( AbstractFileProvider.PROP_PAGEDIR, pageDir )
        );

        provider = new FileSystemProvider();
        provider.initialize( engine, props );
    }

    @AfterEach
    void tearDown() {
        TestEngine.deleteAll( new File( pageDir ) );
        engine.stop();
    }

    // -------------------------------------------------------------------------
    // isBlogPage() static helper
    // -------------------------------------------------------------------------

    @Test
    void testIsBlogPageRecognizesValidBlogPages() {
        Assertions.assertTrue( AbstractFileProvider.isBlogPage( "blog/jake/Blog" ),
            "blog/jake/Blog should be a blog page" );
        Assertions.assertTrue( AbstractFileProvider.isBlogPage( "blog/jake/2026-04-01-First-Post" ),
            "blog/jake/2026-04-01-First-Post should be a blog page" );
    }

    @Test
    void testIsBlogPageRejectsNonBlogPages() {
        Assertions.assertFalse( AbstractFileProvider.isBlogPage( "Main" ),
            "Main is not a blog page" );
        Assertions.assertFalse( AbstractFileProvider.isBlogPage( "blog/" ),
            "blog/ alone is not a blog page (no username/slug)" );
        Assertions.assertFalse( AbstractFileProvider.isBlogPage( "blog/jake" ),
            "blog/jake has no second slash after prefix, so not a blog page" );
        Assertions.assertFalse( AbstractFileProvider.isBlogPage( null ),
            "null should not be a blog page" );
        Assertions.assertFalse( AbstractFileProvider.isBlogPage( "blogpost/jake/Blog" ),
            "blogpost/ prefix is not blog/" );
    }

    // -------------------------------------------------------------------------
    // Blog page file resolution — findPage / pageExists
    // -------------------------------------------------------------------------

    /**
     * Create a blog page file on disk in the subdirectory layout and verify
     * that the provider can find it and read its content.
     */
    @Test
    void testBlogPageManglesAsSubdirectory() throws Exception {
        // Manually create the blog subdirectory and page file
        final File blogDir = new File( pageDir, "blog/jake" );
        blogDir.mkdirs();
        final File blogFile = new File( blogDir, "Blog" + AbstractFileProvider.MARKDOWN_EXT );
        Files.writeString( blogFile.toPath(), "# Jake's Blog" );

        // The provider should find the page
        Assertions.assertTrue( provider.pageExists( "blog/jake/Blog" ),
            "blog/jake/Blog should exist when file is at blog/jake/Blog.md" );

        // Content should be readable
        final String content = provider.getPageText( "blog/jake/Blog", -1 );
        Assertions.assertEquals( "# Jake's Blog", content, "Content should match what was written to disk" );
    }

    /**
     * The username segment in blog paths should be case-insensitive (lowercased).
     * Creating a file under {@code blog/jake/} should be findable as {@code blog/Jake/Blog}.
     */
    @Test
    void testBlogPageUsernameIsLowercase() throws Exception {
        // Create file with lowercase username on disk
        final File blogDir = new File( pageDir, "blog/jake" );
        blogDir.mkdirs();
        final File blogFile = new File( blogDir, "Blog" + AbstractFileProvider.MARKDOWN_EXT );
        Files.writeString( blogFile.toPath(), "# Blog content" );

        // Should find it with mixed-case username
        Assertions.assertTrue( provider.pageExists( "blog/Jake/Blog" ),
            "blog/Jake/Blog should resolve to blog/jake/Blog.md (case-insensitive username)" );

        final String content = provider.getPageText( "blog/Jake/Blog", -1 );
        Assertions.assertEquals( "# Blog content", content,
            "Content should be readable via case-insensitive username" );
    }

    /**
     * Non-blog pages must continue to be stored as flat files with URL-encoded names.
     */
    @Test
    void testNonBlogPagesUnaffected() throws Exception {
        final WikiPage page = new WikiPage( engine, "RegularPage" );
        provider.putPageText( page, "Regular content" );

        // Verify flat file exists in page directory (not a subdirectory)
        final File flatFile = new File( pageDir, "RegularPage" + AbstractFileProvider.MARKDOWN_EXT );
        Assertions.assertTrue( flatFile.exists(), "Regular page should be stored as flat file" );

        // A page with slashes that is NOT a blog page should still be URL-encoded
        final WikiPage slashPage = new WikiPage( engine, "Test/Foobar" );
        provider.putPageText( slashPage, "slash content" );

        final File encodedFile = new File( pageDir, "Test%2FFoobar" + AbstractFileProvider.MARKDOWN_EXT );
        Assertions.assertTrue( encodedFile.exists(),
            "Non-blog slash page should be stored with URL-encoded name" );
    }

    // -------------------------------------------------------------------------
    // getAllPages() includes blog pages from subdirectories
    // -------------------------------------------------------------------------

    /**
     * getAllPages() must return both flat pages and blog pages discovered
     * from {@code blog/<username>/} subdirectories.
     */
    @Test
    void testGetAllPagesIncludesBlogPages() throws Exception {
        // Create a regular flat page via the provider
        final WikiPage regularPage = new WikiPage( engine, "MainPage" );
        provider.putPageText( regularPage, "Main page content" );

        // Create blog pages on disk (simulating BlogManager having created the dirs)
        final File jakeDir = new File( pageDir, "blog/jake" );
        jakeDir.mkdirs();
        Files.writeString(
            new File( jakeDir, "Blog" + AbstractFileProvider.MARKDOWN_EXT ).toPath(),
            "# Jake's Blog" );
        Files.writeString(
            new File( jakeDir, "2026-04-01-First-Post" + AbstractFileProvider.MARKDOWN_EXT ).toPath(),
            "# First Post" );

        final File aliceDir = new File( pageDir, "blog/alice" );
        aliceDir.mkdirs();
        Files.writeString(
            new File( aliceDir, "Blog" + AbstractFileProvider.MARKDOWN_EXT ).toPath(),
            "# Alice's Blog" );

        // getAllPages should include the regular page + 3 blog pages = 4 total
        final Collection< Page > allPages = provider.getAllPages();
        Assertions.assertEquals( 4, allPages.size(),
            "getAllPages() should return 1 regular page + 3 blog pages" );

        // Check that blog page names are present
        final var names = allPages.stream().map( Page::getName ).sorted().toList();
        Assertions.assertTrue( names.contains( "MainPage" ), "Should contain MainPage" );
        Assertions.assertTrue( names.contains( "blog/alice/Blog" ), "Should contain blog/alice/Blog" );
        Assertions.assertTrue( names.contains( "blog/jake/Blog" ), "Should contain blog/jake/Blog" );
        Assertions.assertTrue( names.contains( "blog/jake/2026-04-01-First-Post" ),
            "Should contain blog/jake/2026-04-01-First-Post" );
    }

    // -------------------------------------------------------------------------
    // putPageText() for blog pages — subdirectory creation
    // -------------------------------------------------------------------------

    /**
     * When saving a blog page, the file should be created in the subdirectory
     * layout rather than as a flat URL-encoded file.  The blog directory must
     * already exist (BlogManager creates it).
     */
    @Test
    void testBlogPageSaveCreatesFileInSubdirectory() throws Exception {
        // Pre-create the blog directory (as BlogManager would)
        final File blogDir = new File( pageDir, "blog/jake" );
        blogDir.mkdirs();

        // Save via the provider
        final WikiPage blogPage = new WikiPage( engine, "blog/jake/MyPost" );
        provider.putPageText( blogPage, "# My Blog Post" );

        // The file should be at blog/jake/MyPost.md, NOT at blog%2Fjake%2FMyPost.md
        final File expectedFile = new File( pageDir, "blog/jake/MyPost" + AbstractFileProvider.MARKDOWN_EXT );
        Assertions.assertTrue( expectedFile.exists(),
            "Blog page should be saved in subdirectory: " + expectedFile.getAbsolutePath() );

        final File flatFile = new File( pageDir, "blog%2Fjake%2FMyPost" + AbstractFileProvider.MARKDOWN_EXT );
        Assertions.assertFalse( flatFile.exists(),
            "Blog page should NOT be saved as flat URL-encoded file" );

        // Verify content round-trips
        final String content = provider.getPageText( "blog/jake/MyPost", -1 );
        Assertions.assertEquals( "# My Blog Post", content, "Content should round-trip correctly" );
    }

    /**
     * Saving a blog page when the parent directory does not exist should throw
     * ProviderException — BlogManager is responsible for creating blog directories.
     */
    @Test
    void testBlogPageSaveWithoutDirectoryThrows() {
        final WikiPage blogPage = new WikiPage( engine, "blog/nobody/Post" );
        Assertions.assertThrows( com.wikantik.api.exceptions.ProviderException.class,
            () -> provider.putPageText( blogPage, "content" ),
            "Should throw ProviderException when blog directory does not exist" );
    }
}
