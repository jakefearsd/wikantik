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
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.QueryItem;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/**
 * Covers {@link AbstractFileProvider} branches the happy-path suites
 * ({@code AbstractFileProviderTest}, {@code AbstractFileProviderChangedSinceTest}) never
 * reach: {@code initialize()}'s directory-creation/writability failures, {@code
 * getPageFileExtension}'s filesystem cache-miss branches, the 2-arg {@code pageExists}
 * delegate, the {@code .txt}-to-{@code .md} markup-syntax migration in {@code
 * putPageText} (success and rename-failure), {@code getAllPages}/{@code
 * getAllChangedSince} skipping a listed file that fails to resolve, {@code getPageCount}
 * when the directory disappears, {@code findPages} (brute-force search), the base
 * {@code getVersionHistory}, and {@code getProviderInfo}.
 *
 * <p>Each test builds its own {@link FileSystemProvider} against a private
 * {@code @TempDir}, per CLAUDE.md's guidance that provider tests must keep filesystem
 * fixtures private to avoid parallel-run races.
 */
class AbstractFileProviderErrorPathsTest {

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if( engine != null ) {
            engine.stop();
        }
    }

    private FileSystemProvider buildProvider( final File pageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );
        final FileSystemProvider provider = new FileSystemProvider();
        provider.initialize( engine, props );
        return provider;
    }

    // ============== initialize() ==============

    @Test
    void testInitialize_mkdirsFails_throwsIOException( @TempDir final File tempDir ) throws Exception {
        final File readOnlyParent = new File( tempDir, "readonly-parent" );
        Assertions.assertTrue( readOnlyParent.mkdirs() );
        Assertions.assertTrue( readOnlyParent.setWritable( false ) );
        try {
            final File pageDir = new File( readOnlyParent, "pages" );
            engine = TestEngine.build();
            final Properties props = new Properties();
            props.putAll( engine.getWikiProperties() );
            props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );

            final FileSystemProvider provider = new FileSystemProvider();
            final IOException ex = Assertions.assertThrows( IOException.class, () -> provider.initialize( engine, props ) );
            Assertions.assertTrue( ex.getMessage().contains( "Failed to create page directory" ), "Unexpected message: " + ex.getMessage() );
        } finally {
            readOnlyParent.setWritable( true );
        }
    }

    @Test
    void testInitialize_pageDirNotWritable_throwsIOException( @TempDir final File pageDir ) throws Exception {
        Assertions.assertTrue( pageDir.setWritable( false ) );
        try {
            engine = TestEngine.build();
            final Properties props = new Properties();
            props.putAll( engine.getWikiProperties() );
            props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );

            final FileSystemProvider provider = new FileSystemProvider();
            final IOException ex = Assertions.assertThrows( IOException.class, () -> provider.initialize( engine, props ) );
            Assertions.assertTrue( ex.getMessage().contains( "is not writable" ), "Unexpected message: " + ex.getMessage() );
        } finally {
            pageDir.setWritable( true );
        }
    }

    // ============== getPageFileExtension() cache-miss branches ==============

    @Test
    void testGetPageFileExtension_cacheMissMarkdownFileOnDisk( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        Assertions.assertTrue( new File( pageDir, "PreExisting.md" ).createNewFile() );

        Assertions.assertEquals( AbstractFileProvider.MARKDOWN_EXT, provider.getPageFileExtension( "PreExisting" ) );
    }

    @Test
    void testGetPageFileExtension_cacheMissTxtFileOnDisk( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        Assertions.assertTrue( new File( pageDir, "PreExistingTxt.txt" ).createNewFile() );

        Assertions.assertEquals( AbstractFileProvider.FILE_EXT, provider.getPageFileExtension( "PreExistingTxt" ) );
    }

    @Test
    void testGetPageFileExtension_neitherFileExists_defaultsToMarkdown( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );

        Assertions.assertEquals( AbstractFileProvider.MARKDOWN_EXT, provider.getPageFileExtension( "NeverCreated" ) );
    }

    // ============== pageExists(String, int) base delegate ==============

    @Test
    void testPageExists_twoArgOverload_delegatesToSingleArg( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        provider.putPageText( Wiki.contents().page( engine, "ExistsCheck" ), "content" );

        Assertions.assertTrue( provider.pageExists( "ExistsCheck", PageProvider.LATEST_VERSION ) );
        Assertions.assertFalse( provider.pageExists( "NeverExisted", PageProvider.LATEST_VERSION ) );
    }

    // ============== putPageText: .txt -> .md markup-syntax migration ==============

    @Test
    void testPutPageText_migratesTxtToMdWhenSyntaxIsMarkdown( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        final File legacyTxt = new File( pageDir, "LegacyPage.txt" );
        try( FileWriter w = new FileWriter( legacyTxt ) ) {
            w.write( "legacy wiki-syntax content" );
        }

        final Page page = Wiki.contents().page( engine, "LegacyPage" );
        page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        provider.putPageText( page, "new markdown content" );

        Assertions.assertFalse( legacyTxt.exists(), "the .txt file should have been renamed away" );
        Assertions.assertTrue( new File( pageDir, "LegacyPage.md" ).exists(), "a .md file should now exist" );
        Assertions.assertEquals( AbstractFileProvider.MARKDOWN_EXT, provider.getPageFileExtension( "LegacyPage" ) );
    }

    @Test
    void testPutPageText_migrationRenameFails_stillWritesContentToOldFile( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        final File legacyTxt = new File( pageDir, "LockedLegacyPage.txt" );
        try( FileWriter w = new FileWriter( legacyTxt ) ) {
            w.write( "legacy wiki-syntax content" );
        }

        Assertions.assertTrue( pageDir.setWritable( false ) );
        try {
            final Page page = Wiki.contents().page( engine, "LockedLegacyPage" );
            page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );

            Assertions.assertDoesNotThrow( () -> provider.putPageText( page, "updated content" ) );
        } finally {
            pageDir.setWritable( true );
        }

        // The directory couldn't be modified, so the rename must have failed and the
        // original .txt file should have been overwritten in place instead.
        Assertions.assertTrue( legacyTxt.exists(), "rename should have failed, leaving the .txt file in place" );
        Assertions.assertFalse( new File( pageDir, "LockedLegacyPage.md" ).exists() );
    }

    // ============== getAllPages / getAllChangedSince: unresolvable listed file ==============

    @Test
    void testGetAllPages_skipsListedFileThatCannotBeResolved( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        // A raw filename containing a space: listed by WikiFileFilter, but findPage()
        // re-mangles the unmangled base name (URL-encoding the space), so it looks for
        // a DIFFERENT file than the one actually on disk -- getPageInfo() returns null.
        Assertions.assertTrue( new File( pageDir, "Bogus Name.md" ).createNewFile() );

        final Collection< Page > all = Assertions.assertDoesNotThrow( provider::getAllPages );
        Assertions.assertTrue( all.isEmpty(), "the unresolvable listed file should be skipped, not crash the listing" );
    }

    @Test
    void testGetAllChangedSince_skipsListedFileThatCannotBeResolved( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        Assertions.assertTrue( new File( pageDir, "Bogus Name.md" ).createNewFile() );

        final Collection< Page > changed = provider.getAllChangedSince( null );
        Assertions.assertTrue( changed.isEmpty(), "the unresolvable listed file should be skipped, not crash the listing" );
    }

    @Test
    void testGetAllChangedSince_getPageInfoThrows_isSkippedNotPropagated( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "PropsUnreadable" );
        provider.putPageText( page, "content" );

        final File propsFile = new File( pageDir, "PropsUnreadable" + FileSystemProvider.PROP_EXT );
        Assertions.assertTrue( propsFile.exists(), "putPageText on FileSystemProvider should also write a .properties file" );
        Assertions.assertTrue( propsFile.setReadable( false ) );
        try {
            final Collection< Page > changed = Assertions.assertDoesNotThrow( () -> provider.getAllChangedSince( null ) );
            Assertions.assertTrue( changed.isEmpty(), "a page whose properties can't be read should be skipped, not thrown" );
        } finally {
            propsFile.setReadable( true );
        }
    }

    // ============== getPageCount() when the directory disappears ==============

    @Test
    void testGetPageCount_directoryMissing_returnsZero( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        TestEngine.deleteAll( pageDir );

        Assertions.assertEquals( 0, provider.getPageCount() );
    }

    // ============== findPages() brute-force search ==============

    @Test
    void testFindPages_matchingQuery_returnsResult( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        provider.putPageText( Wiki.contents().page( engine, "SearchableMd" ), "the quick brown fox" );
        // A raw .txt file alongside, to exercise the FILE_EXT cutpoint branch too.
        try( FileWriter w = new FileWriter( new File( pageDir, "SearchableTxt.txt" ) ) ) {
            w.write( "jumps over the lazy dog" );
        }

        final QueryItem hit = new QueryItem();
        hit.word = "fox";
        hit.type = QueryItem.REQUIRED;

        final Collection< SearchResult > results = provider.findPages( new QueryItem[]{ hit } );
        Assertions.assertEquals( 1, results.size() );
        Assertions.assertEquals( "SearchableMd", results.iterator().next().getPage().getName() );
    }

    @Test
    void testFindPages_noMatch_returnsEmpty( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        provider.putPageText( Wiki.contents().page( engine, "NoMatchPage" ), "completely unrelated content" );

        final QueryItem miss = new QueryItem();
        miss.word = "zzzznotpresent";
        miss.type = QueryItem.REQUIRED;

        Assertions.assertTrue( provider.findPages( new QueryItem[]{ miss } ).isEmpty() );
    }

    // ============== getVersionHistory() base implementation ==============

    @Test
    void testGetVersionHistory_baseImplementationReturnsSingleEntry( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        provider.putPageText( Wiki.contents().page( engine, "SingleHistoryPage" ), "content" );

        final List< Page > history = provider.getVersionHistory( "SingleHistoryPage" );
        Assertions.assertEquals( 1, history.size() );
        Assertions.assertEquals( "SingleHistoryPage", history.get( 0 ).getName() );
    }

    // ============== getProviderInfo() ==============

    @Test
    void testGetProviderInfo_returnsEmptyString( @TempDir final File pageDir ) throws Exception {
        final FileSystemProvider provider = buildProvider( pageDir );
        Assertions.assertEquals( "", provider.getProviderInfo() );
    }
}
