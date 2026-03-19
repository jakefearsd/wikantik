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
import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.Properties;

import static com.wikantik.TestEngine.with;

/**
 * Tests for {@link AbstractFileProvider#getAllChangedSince(Date)}.
 */
class AbstractFileProviderChangedSinceTest {

    private FileSystemProvider provider;
    private Properties props;
    private com.wikantik.api.core.Engine engine;
    private String pageDir;

    @BeforeEach
    void setUp() throws Exception {
        props = TestEngine.getTestProperties();
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" );
        props.setProperty( FileSystemProvider.PROP_PAGEDIR, "./target/jspwiki.changedSince.test.pages" );
        pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );

        engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" ),
            with( FileSystemProvider.PROP_PAGEDIR, pageDir )
        );

        provider = new FileSystemProvider();
        provider.initialize( engine, props );
    }

    @AfterEach
    void tearDown() {
        TestEngine.deleteAll( new File( pageDir ) );
    }

    @Test
    void testEpochDateReturnsAllPages() throws Exception {
        // Create some pages
        provider.putPageText( new WikiPage( engine, "Page1" ), "content1" );
        provider.putPageText( new WikiPage( engine, "Page2" ), "content2" );
        provider.putPageText( new WikiPage( engine, "Page3" ), "content3" );

        // Date(0) should return all pages, same as getAllPages()
        final Collection<Page> changedSince = provider.getAllChangedSince( new Date( 0L ) );
        final Collection<Page> allPages = provider.getAllPages();

        Assertions.assertEquals( allPages.size(), changedSince.size(),
            "Date(0) should return same number of pages as getAllPages()" );
        Assertions.assertEquals( 3, changedSince.size() );
    }

    @Test
    void testNullDateReturnsAllPages() throws Exception {
        provider.putPageText( new WikiPage( engine, "Page1" ), "content1" );
        provider.putPageText( new WikiPage( engine, "Page2" ), "content2" );

        final Collection<Page> changedSince = provider.getAllChangedSince( null );
        final Collection<Page> allPages = provider.getAllPages();

        Assertions.assertEquals( allPages.size(), changedSince.size(),
            "null date should return same number of pages as getAllPages()" );
    }

    @Test
    void testFutureDateReturnsEmpty() throws Exception {
        provider.putPageText( new WikiPage( engine, "Page1" ), "content1" );

        // A date far in the future should return nothing
        final Date futureDate = new Date( System.currentTimeMillis() + 86400_000L );
        final Collection<Page> changedSince = provider.getAllChangedSince( futureDate );

        Assertions.assertTrue( changedSince.isEmpty(),
            "Future date should return empty collection" );
    }

    @Test
    void testRecentDateFiltersOlderPages() throws Exception {
        // Create a page
        provider.putPageText( new WikiPage( engine, "OldPage" ), "old content" );

        // Set the file's last modified to 2 days ago
        final File oldFile = new File( pageDir, "OldPage" + AbstractFileProvider.FILE_EXT );
        final long twoDaysAgo = System.currentTimeMillis() - 2 * 86400_000L;
        oldFile.setLastModified( twoDaysAgo );

        // Create another page (will have current timestamp)
        provider.putPageText( new WikiPage( engine, "NewPage" ), "new content" );

        // Ask for changes since 1 day ago - should only include NewPage
        final Date oneDayAgo = new Date( System.currentTimeMillis() - 86400_000L );
        final Collection<Page> changedSince = provider.getAllChangedSince( oneDayAgo );

        Assertions.assertEquals( 1, changedSince.size(),
            "Should only return pages modified since the cutoff date" );
        Assertions.assertEquals( "NewPage", changedSince.iterator().next().getName() );
    }

    @Test
    void testEmptyDirectoryReturnsEmpty() {
        final Collection<Page> changedSince = provider.getAllChangedSince( new Date( 0L ) );
        Assertions.assertTrue( changedSince.isEmpty(),
            "Empty directory should return empty collection" );
    }

    @Test
    void testMixedMarkdownAndWikiFiles() throws Exception {
        // Create a .txt wiki page
        final WikiPage wikiPage = new WikiPage( engine, "WikiPage" );
        provider.putPageText( wikiPage, "wiki content" );

        // Create a .md markdown page
        final WikiPage mdPage = new WikiPage( engine, "MarkdownPage" );
        mdPage.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        provider.putPageText( mdPage, "# Markdown content" );

        // Both should be returned with Date(0)
        final Collection<Page> changedSince = provider.getAllChangedSince( new Date( 0L ) );
        Assertions.assertEquals( 2, changedSince.size(),
            "Should return both .txt and .md pages" );
    }

    @Test
    void testOnlyRecentMarkdownFileReturned() throws Exception {
        // Create an old wiki page
        final WikiPage oldPage = new WikiPage( engine, "OldWiki" );
        provider.putPageText( oldPage, "old wiki" );
        final File oldFile = new File( pageDir, "OldWiki" + AbstractFileProvider.FILE_EXT );
        oldFile.setLastModified( System.currentTimeMillis() - 2 * 86400_000L );

        // Create a recent markdown page
        final WikiPage newMd = new WikiPage( engine, "NewMarkdown" );
        newMd.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        provider.putPageText( newMd, "# New markdown" );

        final Date oneDayAgo = new Date( System.currentTimeMillis() - 86400_000L );
        final Collection<Page> changedSince = provider.getAllChangedSince( oneDayAgo );

        Assertions.assertEquals( 1, changedSince.size() );
        Assertions.assertEquals( "NewMarkdown", changedSince.iterator().next().getName() );
    }
}
