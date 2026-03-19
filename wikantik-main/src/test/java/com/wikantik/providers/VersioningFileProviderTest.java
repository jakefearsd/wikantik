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
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.Users;
import com.wikantik.cache.CachingManager;
import com.wikantik.pages.PageManager;
import com.wikantik.util.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

// FIXME: Should this thingy go directly to the VersioningFileProvider,
//        or should it rely on the WikiEngine API?

public class VersioningFileProviderTest {

    public static final String NAME1 = "Test1";
    private static final String OLD_AUTHOR = "brian";
    private static final String FAKE_HISTORY =
                "#JSPWiki page properties for page " + NAME1 + "\n"
                + "#Wed Jan 01 12:27:57 GMT 2012" + "\n"
                + "author=" + OLD_AUTHOR + "\n";

    private final Properties PROPS = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
    private TestEngine engine = TestEngine.build( PROPS );

    // this is the testing page directory
    private String files = engine.getWikiProperties().getProperty( AbstractFileProvider.PROP_PAGEDIR );

    @AfterEach
    public void tearDown() {
        engine.stop();
    }

    /*
     * Checks if a page created or last modified by FileSystemProvider
     * will be seen by VersioningFileProvider as the "first" version.
     */
    @Test
    public void testMigrationInfoAvailable() throws IOException {
        // we cannot switch PageProviders within a single test, so the
        // initial FileSystemProvider wiki page must be faked.
        final String fakeWikiPage = "foobar";
        injectFile(NAME1+AbstractFileProvider.FILE_EXT, fakeWikiPage);

       // also create an associated properties file with some history
        injectFile(NAME1+FileSystemProvider.PROP_EXT, FAKE_HISTORY);

        final String res = engine.getManager( PageManager.class ).getText( NAME1 );
        Assertions.assertEquals( fakeWikiPage, res, "fetch latest should work" );

        final Page page = engine.getManager( PageManager.class ).getPage( NAME1, 1 );
        Assertions.assertEquals( 1, page.getVersion(), "original version expected" );
        Assertions.assertEquals( OLD_AUTHOR, page.getAuthor(), "original author" );
    }

    /*
     * Checks if migration from FileSystemProvider to VersioningFileProvider
     * works when a simple text file (without associated properties) exists,
     * but there is not yet any corresponding history content in OLD/
     */
    @Test
    public void testMigrationSimple() throws IOException {
        // we cannot switch PageProviders within a single test, so the
        // initial FileSystemProvider wiki page must be faked.
        injectFile(NAME1+AbstractFileProvider.FILE_EXT, "foobar");

        String res = engine.getManager( PageManager.class ).getText( NAME1 );
        Assertions.assertEquals( "foobar", res, "fetch latest did not work" );

        res = engine.getManager( PageManager.class ).getText( NAME1, 1 ); // Should be the first version.
        Assertions.assertEquals( "foobar", res, "fetch by direct version did not work" );

        final Page page = engine.getManager( PageManager.class ).getPage( NAME1 );
        Assertions.assertEquals( 1, page.getVersion(), "original version expected" );
        Assertions.assertNull( page.getAuthor(), "original author not expected" );
    }

    /*
     * Checks if migration from FileSystemProvider to VersioningFileProvider
     * works when a simple text file and its associated properties exist, but
     * when there is not yet any corresponding history content in OLD/
     */
    @Test
    public void testMigrationWithSimpleHistory() throws IOException {
        // we cannot switch PageProviders within a single test, so the
        // initial FileSystemProvider wiki page must be faked.
        final String fakeWikiPage = "foobar";
        injectFile(NAME1+AbstractFileProvider.FILE_EXT, fakeWikiPage);

       // now create the associated properties file with some history
        injectFile(NAME1+FileSystemProvider.PROP_EXT, FAKE_HISTORY);

        String res = engine.getManager( PageManager.class ).getText( NAME1 );
        Assertions.assertEquals( fakeWikiPage, res, "fetch latest did not work" );

        res = engine.getManager( PageManager.class ).getText( NAME1, 1 ); // Should be the first version.
        Assertions.assertEquals( fakeWikiPage, res, "fetch by direct version did not work" );

        final Page page = engine.getManager( PageManager.class ).getPage( NAME1, 1 );
        Assertions.assertEquals( 1, page.getVersion(), "original version expected" );
        Assertions.assertEquals( OLD_AUTHOR, page.getAuthor(), "original author" );
    }

    /**
     * Checks if migration from FileSystemProvider to VersioningFileProvider
     * works when a simple text file and its associated properties exist, but
     * when there is not yet any corresponding history content in OLD/.
     * Update the wiki page and confirm the original simple history was
     * assimilated into the newly-created properties.
     */
    @Test
    public void testMigrationChangesHistory() throws Exception {
        // we cannot switch PageProviders within a single test, so the
        // initial FileSystemProvider wiki page must be faked.
        final String fakeWikiPage = "foobar";
        injectFile(NAME1+AbstractFileProvider.FILE_EXT, fakeWikiPage);

       // also create an associated properties file with some history
        injectFile(NAME1+FileSystemProvider.PROP_EXT, FAKE_HISTORY);

        final String result1 = engine.getManager( PageManager.class ).getText( NAME1 );
        Assertions.assertEquals( fakeWikiPage, result1, "latest should be initial" );

        // now update the wiki page to create a new version
        final String text = "diddo\r\n";
        engine.saveText( NAME1, text );

        // confirm the right number of versions have been recorded
        final List< WikiPage > versionHistory = engine.getManager( PageManager.class ).getVersionHistory(NAME1);
        Assertions.assertEquals( 2, versionHistory.size(), "number of versions" );

        // fetch the updated page
        final String result2 = engine.getManager( PageManager.class ).getText( NAME1 );
        Assertions.assertEquals( text, result2, "latest should be new version" );
        final String result3 = engine.getManager( PageManager.class ).getText( NAME1, 2 ); // Should be the 2nd version.
        Assertions.assertEquals( text, result3, "fetch new by version did not work" );

        // now confirm the original page has been archived
        final String result4 = engine.getManager( PageManager.class ).getText( NAME1, 1 );
        Assertions.assertEquals( fakeWikiPage, result4, "fetch original by version Assertions.failed" );

        final Page pageNew = engine.getManager( PageManager.class ).getPage( NAME1, 2 );
        Assertions.assertEquals( 2, pageNew.getVersion(), "new version" );
        Assertions.assertEquals( "Guest", pageNew.getAuthor(), "new author" );

        final Page pageOld = engine.getManager( PageManager.class ).getPage( NAME1, 1 );
        Assertions.assertEquals( 1, pageOld.getVersion(), "old version" );
        Assertions.assertEquals( OLD_AUTHOR, pageOld.getAuthor(), "old author" );
    }

    /*
     * Checks migration from FileSystemProvider to VersioningFileProvider
     * works after multiple updates to a page with existing properties.
     */
    @Test
    public void testMigrationMultiChangesHistory() throws Exception {
        // we cannot switch PageProviders within a single test, so the
        // initial FileSystemProvider wiki page must be faked.
        final String fakeWikiPage = "foobar";
        injectFile(NAME1+AbstractFileProvider.FILE_EXT, fakeWikiPage);

       // also create an associated properties file with some history
        injectFile(NAME1+FileSystemProvider.PROP_EXT, FAKE_HISTORY);

        // next update the wiki page to create a version number 2
        // with a different username
        final String text2 = "diddo\r\n";
        engine.saveTextAsJanne( NAME1, text2 );

        // finally, update the wiki page to create a version number 3
        final String text3 = "whateverNext\r\n";
        engine.saveText( NAME1, text3 );

        // confirm the right number of versions have been recorded
        final Collection< Page > versionHistory = engine.getManager( PageManager.class ).getVersionHistory(NAME1);
        Assertions.assertEquals( 3, versionHistory.size(), "number of versions" );

        // fetch the latest version of the page
        final String result = engine.getManager( PageManager.class ).getText( NAME1 );
        Assertions.assertEquals( text3, result, "latest should be newest version" );
        final String result2 = engine.getManager( PageManager.class ).getText( NAME1, 3 );
        Assertions.assertEquals( text3, result2, "fetch new by version did not work" );

        // confirm the original page was archived
        final String result3 = engine.getManager( PageManager.class ).getText( NAME1, 1 );
        Assertions.assertEquals( fakeWikiPage, result3, "fetch original by version Assertions.failed" );

        // confirm the first update was archived
        final String result4 = engine.getManager( PageManager.class ).getText( NAME1, 2 );
        Assertions.assertEquals( text2, result4, "fetch original by version Assertions.failed" );

        final Page pageNew = engine.getManager( PageManager.class ).getPage( NAME1 );
        Assertions.assertEquals( 3, pageNew.getVersion(), "newest version" );
        Assertions.assertEquals( pageNew.getAuthor(), "Guest", "newest author" );

        final Page pageMiddle = engine.getManager( PageManager.class ).getPage( NAME1, 2 );
        Assertions.assertEquals( 2, pageMiddle.getVersion(), "middle version" );
        Assertions.assertEquals( Users.JANNE, pageMiddle.getAuthor(), "middle author" );

        final Page pageOld = engine.getManager( PageManager.class ).getPage( NAME1, 1 );
        Assertions.assertEquals( 1, pageOld.getVersion(), "old version" );
        Assertions.assertEquals( OLD_AUTHOR, pageOld.getAuthor(), "old author" );
    }

    /**
     * A variation of testMigrationMultiChangesHistory when caching is disabled.
     */
    @Test
    public void testMigrationMultiChangesNoCache() throws Exception {
        // discard the default engine, and get another with different properties
        // note: the originating properties file is unchanged.
        String cacheState = PROPS.getProperty( CachingManager.PROP_CACHE_ENABLE );
        Assertions.assertEquals( "true", cacheState, "should cache" );
        cacheState = "false";
        PROPS.setProperty( CachingManager.PROP_CACHE_ENABLE, cacheState );
        engine = new TestEngine(PROPS);

        // the new TestEngine will have assigned a new page directory
        files = engine.getWikiProperties().getProperty( AbstractFileProvider.PROP_PAGEDIR );

        // we cannot switch PageProviders within a single test, so the
        // initial FileSystemProvider wiki page must be faked.
        final String fakeWikiPage = "foobar";
        injectFile(NAME1+AbstractFileProvider.FILE_EXT, fakeWikiPage);

       // also create an associated properties file with some history
        injectFile(NAME1+FileSystemProvider.PROP_EXT, FAKE_HISTORY);

        // next update the wiki page to create a version number 2
        // with a different username
        final String text2 = "diddo\r\n";
        engine.saveTextAsJanne( NAME1, text2 );

        // finally, update the wiki page to create a version number 3
        final String text3 = "whateverNext\r\n";
        engine.saveText( NAME1, text3 );

        // confirm the right number of versions have been recorded
        final Collection< Page > versionHistory = engine.getManager( PageManager.class ).getVersionHistory(NAME1);
        Assertions.assertEquals( 3, versionHistory.size(), "number of versions" );

        // fetch the latest version of the page
        final String result = engine.getManager( PageManager.class ).getText( NAME1 );
        Assertions.assertEquals( text3, result, "latest should be newest version" );
        final String result2 = engine.getManager( PageManager.class ).getText( NAME1, 3 );
        Assertions.assertEquals( text3, result2, "fetch new by version did not work" );

        // confirm the original page was archived
        final String result3 = engine.getManager( PageManager.class ).getText( NAME1, 1 );
        Assertions.assertEquals( fakeWikiPage, result3, "fetch original by version Assertions.failed" );

        // confirm the first update was archived
        final String result4 = engine.getManager( PageManager.class ).getText( NAME1, 2 );
        Assertions.assertEquals( text2, result4, "fetch original by version Assertions.failed" );

        final Page pageNew = engine.getManager( PageManager.class ).getPage( NAME1 );
        Assertions.assertEquals( 3, pageNew.getVersion(), "newest version" );
        Assertions.assertEquals( "Guest", pageNew.getAuthor(), "newest author" );

        final Page pageMiddle = engine.getManager( PageManager.class ).getPage( NAME1, 2 );
        Assertions.assertEquals( 2, pageMiddle.getVersion(), "middle version" );
        Assertions.assertEquals( Users.JANNE, pageMiddle.getAuthor(), "middle author" );

        final Page pageOld = engine.getManager( PageManager.class ).getPage( NAME1, 1 );
        Assertions.assertEquals( 1, pageOld.getVersion(), "old version" );
        Assertions.assertEquals( OLD_AUTHOR, pageOld.getAuthor(), "old author" );
    }

    @Test
    public void testMillionChanges() throws Exception {
        String text = "";
        final int maxver = 100;           // Save 100 versions.
        for( int i = 0; i < maxver; i++ ) {
            text = text + ".";
            engine.saveText( NAME1, text );
        }

        final Page pageinfo = engine.getManager( PageManager.class ).getPage( NAME1 );
        Assertions.assertEquals( maxver, pageinfo.getVersion(), "wrong version" );

        // +2 comes from \r\n.
        Assertions.assertEquals( maxver+2, engine.getManager( PageManager.class ).getText(NAME1).length(), "wrong text" );
    }

    @Test
    public void testCheckin() throws Exception {
        final String text = "diddo\r\n";
        engine.saveText( NAME1, text );
        final String res = engine.getManager( PageManager.class ).getText(NAME1);
        Assertions.assertEquals( text, res );
    }

    @Test
    public void testGetByVersion() throws Exception {
        final String text = "diddo\r\n";
        engine.saveText( NAME1, text );
        final Page page = engine.getManager( PageManager.class ).getPage( NAME1, 1 );

        Assertions.assertEquals( NAME1, page.getName(), "name" );
        Assertions.assertEquals( 1, page.getVersion(), "version" );
    }

    @Test
    public void testPageInfo() throws Exception {
        final String text = "diddo\r\n";
        engine.saveText( NAME1, text );
        final Page res = engine.getManager( PageManager.class ).getPage(NAME1);
        Assertions.assertEquals( 1, res.getVersion() );
    }

    @Test
    public void testGetOldVersion() throws Exception {
        final String text = "diddo\r\n";
        final String text2 = "barbar\r\n";
        final String text3 = "Barney\r\n";

        engine.saveText( NAME1, text );
        engine.saveText( NAME1, text2 );
        engine.saveText( NAME1, text3 );

        final Page res = engine.getManager( PageManager.class ).getPage(NAME1);
        Assertions.assertEquals( 3, res.getVersion(), "wrong version" );
        Assertions.assertEquals( text, engine.getManager( PageManager.class ).getText( NAME1, 1 ), "ver1" );
        Assertions.assertEquals( text2, engine.getManager( PageManager.class ).getText( NAME1, 2 ), "ver2" );
        Assertions.assertEquals( text3, engine.getManager( PageManager.class ).getText( NAME1, 3 ), "ver3" );
    }

    @Test
    public void testGetOldVersion2() throws Exception {
        final String text = "diddo\r\n";
        final String text2 = "barbar\r\n";
        final String text3 = "Barney\r\n";

        engine.saveText( NAME1, text );
        engine.saveText( NAME1, text2 );
        engine.saveText( NAME1, text3 );

        final Page res = engine.getManager( PageManager.class ).getPage(NAME1);
        Assertions.assertEquals( 3, res.getVersion(), "wrong version" );
        Assertions.assertEquals( 1, engine.getManager( PageManager.class ).getPage( NAME1, 1 ).getVersion(), "ver1" );
        Assertions.assertEquals( 2, engine.getManager( PageManager.class ).getPage( NAME1, 2 ).getVersion(), "ver2" );
        Assertions.assertEquals( 3, engine.getManager( PageManager.class ).getPage( NAME1, 3 ).getVersion(), "ver3" );
}

    /**
     *  2.0.7 and before got this wrong.
     */
    @Test
    public void testGetOldVersionUTF8() throws Exception {
        final String text = "\u00e5\u00e4\u00f6\r\n";
        final String text2 = "barbar\u00f6\u00f6\r\n";
        final String text3 = "Barney\u00e4\u00e4\r\n";

        engine.saveText( NAME1, text );
        engine.saveText( NAME1, text2 );
        engine.saveText( NAME1, text3 );

        final Page res = engine.getManager( PageManager.class ).getPage(NAME1);
        Assertions.assertEquals( 3, res.getVersion(), "wrong version" );
        Assertions.assertEquals( text, engine.getManager( PageManager.class ).getText( NAME1, 1 ), "ver1" );
        Assertions.assertEquals( text2, engine.getManager( PageManager.class ).getText( NAME1, 2 ), "ver2" );
        Assertions.assertEquals( text3, engine.getManager( PageManager.class ).getText( NAME1, 3 ), "ver3" );
    }

    @Test
    public void testNonexistentPage() {
        Assertions.assertNull( engine.getManager( PageManager.class ).getPage("fjewifjeiw") );
    }

    @Test
    public void testVersionHistory() throws Exception {
        final String text = "diddo\r\n";
        final String text2 = "barbar\r\n";
        final String text3 = "Barney\r\n";

        engine.saveText( NAME1, text );
        engine.saveText( NAME1, text2 );
        engine.saveText( NAME1, text3 );

        final Collection< Page > history = engine.getManager( PageManager.class ).getVersionHistory(NAME1);
        Assertions.assertEquals( 3, history.size(), "size" );
    }

    @Test
    public void testDelete() throws Exception {
        engine.saveText( NAME1, "v1" );
        engine.saveText( NAME1, "v2" );
        engine.saveText( NAME1, "v3" );

        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        provider.deletePage( NAME1 );
        final File f = new File( files, NAME1+AbstractFileProvider.FILE_EXT );
        Assertions.assertFalse( f.exists(), "file exists" );
    }

    @Test
    public void testDeleteVersion() throws Exception {
        engine.saveText( NAME1, "v1\r\n" );
        engine.saveText( NAME1, "v2\r\n" );
        engine.saveText( NAME1, "v3\r\n" );

        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        List< Page > l = provider.getVersionHistory( NAME1 );
        Assertions.assertEquals( 3, l.size(), "wrong # of versions" );
        provider.deleteVersion( NAME1, 2 );
        l = provider.getVersionHistory( NAME1 );
        Assertions.assertEquals( 2, l.size(), "wrong # of versions" );
        Assertions.assertEquals( "v1\r\n", provider.getPageText( NAME1, 1 ), "v1" );
        Assertions.assertEquals( "v3\r\n", provider.getPageText( NAME1, 3 ), "v3" );

        try {
            provider.getPageText( NAME1, 2 );
            Assertions.fail( "v2" );
        } catch ( final NoSuchVersionException e ) {
            // This is expected
        }
    }


    @Test
    public void testChangeNote() throws Exception {
        final Page p = Wiki.contents().page( engine, NAME1 );
        p.setAttribute( Page.CHANGENOTE, "Test change" );
        final Context context = Wiki.context().create( engine, p );
        engine.getManager( PageManager.class ).saveText( context, "test" );
        final Page p2 = engine.getManager( PageManager.class ).getPage( NAME1 );
        Assertions.assertEquals( "Test change", p2.getAttribute( Page.CHANGENOTE ) );
    }

    @Test
    public void testChangeNoteOldVersion() throws Exception {
        final Page p = Wiki.contents().page( engine, NAME1 );
        final Context context = Wiki.context().create(engine,p);
        context.getPage().setAttribute(Page.CHANGENOTE, "Test change" );
        engine.getManager( PageManager.class ).saveText( context, "test" );
        context.getPage().setAttribute(Page.CHANGENOTE, "Change 2" );
        engine.getManager( PageManager.class ).saveText( context, "test2" );
        final Page p2 = engine.getManager( PageManager.class ).getPage( NAME1, 1 );
        Assertions.assertEquals( "Test change", p2.getAttribute(Page.CHANGENOTE) );
        final Page p3 = engine.getManager( PageManager.class ).getPage( NAME1, 2 );
        Assertions.assertEquals( "Change 2", p3.getAttribute(Page.CHANGENOTE) );
    }

    @Test
    public void testChangeNoteOldVersion2() throws Exception {
        final Page p = Wiki.contents().page( engine, NAME1 );
        final Context context = Wiki.context().create(engine,p);
        context.getPage().setAttribute( Page.CHANGENOTE, "Test change" );
        engine.getManager( PageManager.class ).saveText( context, "test" );
        for( int i = 0; i < 5; i++ ) {
            final Page p2 = engine.getManager( PageManager.class ).getPage( NAME1 ).clone();
            p2.removeAttribute(Page.CHANGENOTE);
            context.setPage( p2 );
            engine.getManager( PageManager.class ).saveText( context, "test"+i );
        }
        final Page p3 = engine.getManager( PageManager.class ).getPage( NAME1, -1 );
        Assertions.assertNull( p3.getAttribute( Page.CHANGENOTE ) );
    }

    /**
     * Tests that movePage invalidates the file extension cache for both source and target pages.
     */
    @Test
    public void testMovePageCacheInvalidation() throws Exception {
        final String sourceName = "MoveSourcePage";
        final String targetName = "MoveTargetPage";

        engine.saveText( sourceName, "Original content" );

        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        // Verify source page exists
        Assertions.assertTrue( provider.pageExists( sourceName ), "Source page should exist" );

        // Get cache size before move (accessing page populates cache)
        provider.getPageInfo( sourceName, -1 );

        // Move the page
        provider.movePage( sourceName, targetName );

        // Verify source page no longer exists and target does
        Assertions.assertFalse( provider.pageExists( sourceName ), "Source page should not exist after move" );
        Assertions.assertTrue( provider.pageExists( targetName ), "Target page should exist after move" );

        // Verify content was moved (saveText adds a newline)
        final String content = provider.getPageText( targetName, -1 );
        Assertions.assertTrue( content.startsWith( "Original content" ), "Content should be preserved after move" );
    }

    /**
     * Tests movePage with a markdown page to verify cache properly handles extension.
     */
    @Test
    public void testMoveMarkdownPageCacheInvalidation() throws Exception {
        final String sourceName = "MoveMarkdownSource";
        final String targetName = "MoveMarkdownTarget";

        // Create a markdown page
        final Page p = Wiki.contents().page( engine, sourceName );
        p.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        final Context context = Wiki.context().create( engine, p );
        engine.getManager( PageManager.class ).saveText( context, "# Markdown Content" );

        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        // Move the page
        provider.movePage( sourceName, targetName );

        // Verify target page exists and content is preserved
        Assertions.assertTrue( provider.pageExists( targetName ), "Target page should exist after move" );

        final String content = provider.getPageText( targetName, -1 );
        Assertions.assertTrue( content.startsWith( "# Markdown Content" ), "Markdown content should be preserved" );
    }

    /**
     * Creates a file of the given name in the wiki page directory, containing the data provided.
     */
    private void injectFile( final String fileName, final String fileContent) throws IOException {
        final File ft = new File( files, fileName );
        final Writer out = new FileWriter( ft );
        FileUtil.copyContents( new StringReader(fileContent), out );
        out.close();
    }

    // ============== CachedProperties Behavior Tests ==============
    // These tests verify the CachedProperties single-entry cache behavior indirectly

    /**
     * Tests that repeated version history calls for the same page work correctly.
     * This indirectly tests the CachedProperties cache hit scenario.
     */
    @Test
    public void testRepeatedVersionHistoryCallsSamePage() throws Exception {
        // Create page with multiple versions
        engine.saveText( NAME1, "version1" );
        engine.saveText( NAME1, "version2" );
        engine.saveText( NAME1, "version3" );

        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        // Call getVersionHistory multiple times for same page
        // CachedProperties should cache and return consistent results
        final Collection<Page> history1 = provider.getVersionHistory( NAME1 );
        final Collection<Page> history2 = provider.getVersionHistory( NAME1 );
        final Collection<Page> history3 = provider.getVersionHistory( NAME1 );

        Assertions.assertEquals( 3, history1.size(), "First call should return 3 versions" );
        Assertions.assertEquals( 3, history2.size(), "Second call should return 3 versions" );
        Assertions.assertEquals( 3, history3.size(), "Third call should return 3 versions" );
    }

    /**
     * Tests that alternating version history calls between different pages work correctly.
     * This indirectly tests the CachedProperties cache miss scenario where the
     * single-entry cache must be replaced.
     */
    @Test
    public void testAlternatingVersionHistoryCallsDifferentPages() throws Exception {
        final String page1 = "TestPage1";
        final String page2 = "TestPage2";

        // Create two pages with different version counts
        engine.saveText( page1, "p1v1" );
        engine.saveText( page1, "p1v2" );

        engine.saveText( page2, "p2v1" );
        engine.saveText( page2, "p2v2" );
        engine.saveText( page2, "p2v3" );

        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        // Alternate between pages - this causes cache misses
        final Collection<Page> h1a = provider.getVersionHistory( page1 );
        final Collection<Page> h2a = provider.getVersionHistory( page2 );
        final Collection<Page> h1b = provider.getVersionHistory( page1 );
        final Collection<Page> h2b = provider.getVersionHistory( page2 );

        Assertions.assertEquals( 2, h1a.size(), "Page1 first call" );
        Assertions.assertEquals( 3, h2a.size(), "Page2 first call" );
        Assertions.assertEquals( 2, h1b.size(), "Page1 second call" );
        Assertions.assertEquals( 3, h2b.size(), "Page2 second call" );
    }

    /**
     * Tests that version history includes correct author information.
     * This tests that CachedProperties correctly reads and caches author metadata.
     */
    @Test
    public void testVersionHistoryAuthorMetadata() throws Exception {
        // Create versions with different authors
        engine.saveText( NAME1, "guest version" );
        engine.saveTextAsJanne( NAME1, "janne version" );
        engine.saveText( NAME1, "guest again" );

        final PageManager mgr = engine.getManager( PageManager.class );

        // Get version history and verify authors
        final Page v1 = mgr.getPage( NAME1, 1 );
        final Page v2 = mgr.getPage( NAME1, 2 );
        final Page v3 = mgr.getPage( NAME1, 3 );

        Assertions.assertEquals( "Guest", v1.getAuthor(), "Version 1 author" );
        Assertions.assertEquals( Users.JANNE, v2.getAuthor(), "Version 2 author" );
        Assertions.assertEquals( "Guest", v3.getAuthor(), "Version 3 author" );
    }

    /**
     * Tests that properties are correctly updated when a new version is saved.
     * This tests that CachedProperties is invalidated/updated on write.
     */
    @Test
    public void testPropertiesUpdatedOnSave() throws Exception {
        // Create initial version
        engine.saveText( NAME1, "initial" );

        final PageManager mgr = engine.getManager( PageManager.class );

        // Get initial page info
        final Page p1 = mgr.getPage( NAME1, 1 );
        Assertions.assertEquals( 1, p1.getVersion() );
        Assertions.assertEquals( "Guest", p1.getAuthor() );

        // Save a new version with different author
        engine.saveTextAsJanne( NAME1, "updated" );

        // Verify the new version has correct metadata
        final Page p2 = mgr.getPage( NAME1, 2 );
        Assertions.assertEquals( 2, p2.getVersion() );
        Assertions.assertEquals( Users.JANNE, p2.getAuthor() );

        // Verify we can still access the old version with correct metadata
        final Page p1again = mgr.getPage( NAME1, 1 );
        Assertions.assertEquals( 1, p1again.getVersion() );
        Assertions.assertEquals( "Guest", p1again.getAuthor() );
    }

    /**
     * Tests rapid sequential updates to verify property caching handles updates correctly.
     */
    @Test
    public void testRapidSequentialUpdates() throws Exception {
        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        // Rapidly create many versions
        for( int i = 1; i <= 10; i++ ) {
            engine.saveText( NAME1, "content " + i );
        }

        // Verify all versions are accessible
        final Collection<Page> history = provider.getVersionHistory( NAME1 );
        Assertions.assertEquals( 10, history.size(), "Should have 10 versions" );

        // Verify content of each version
        for( int i = 1; i <= 10; i++ ) {
            final String text = provider.getPageText( NAME1, i );
            Assertions.assertTrue( text.startsWith( "content " + i ),
                    "Version " + i + " should have correct content" );
        }
    }

    /**
     * Tests that getPageInfo retrieves correct version-specific metadata.
     * This tests the CachedProperties caching behavior for getPageInfo operations.
     */
    @Test
    public void testGetPageInfoVersionSpecificMetadata() throws Exception {
        final PageManager mgr = engine.getManager( PageManager.class );
        final PageProvider provider = mgr.getProvider();

        // Create versions
        engine.saveText( NAME1, "v1" );
        engine.saveText( NAME1, "v2" );

        // Get page info for different versions in different order
        final Page latest = provider.getPageInfo( NAME1, PageProvider.LATEST_VERSION );
        Assertions.assertEquals( 2, latest.getVersion(), "Latest should be version 2" );

        final Page v1 = provider.getPageInfo( NAME1, 1 );
        Assertions.assertEquals( 1, v1.getVersion(), "Specific request for v1" );

        // Access latest again to test cache
        final Page latestAgain = provider.getPageInfo( NAME1, PageProvider.LATEST_VERSION );
        Assertions.assertEquals( 2, latestAgain.getVersion(), "Latest again should be version 2" );
    }

    /**
     * Tests that change notes are properly cached and retrieved across versions.
     */
    @Test
    public void testChangeNotesAcrossVersions() throws Exception {
        final PageManager mgr = engine.getManager( PageManager.class );

        // Create versions with change notes
        Page p = Wiki.contents().page( engine, NAME1 );
        p.setAttribute( Page.CHANGENOTE, "First change" );
        Context context = Wiki.context().create( engine, p );
        mgr.saveText( context, "v1" );

        p = Wiki.contents().page( engine, NAME1 );
        p.setAttribute( Page.CHANGENOTE, "Second change" );
        context = Wiki.context().create( engine, p );
        mgr.saveText( context, "v2" );

        p = Wiki.contents().page( engine, NAME1 );
        p.setAttribute( Page.CHANGENOTE, "Third change" );
        context = Wiki.context().create( engine, p );
        mgr.saveText( context, "v3" );

        // Access versions in non-sequential order to test caching
        final Page v3 = mgr.getPage( NAME1, 3 );
        Assertions.assertEquals( "Third change", v3.getAttribute( Page.CHANGENOTE ) );

        final Page v1 = mgr.getPage( NAME1, 1 );
        Assertions.assertEquals( "First change", v1.getAttribute( Page.CHANGENOTE ) );

        final Page v2 = mgr.getPage( NAME1, 2 );
        Assertions.assertEquals( "Second change", v2.getAttribute( Page.CHANGENOTE ) );

        // Access same version multiple times
        final Page v2again = mgr.getPage( NAME1, 2 );
        Assertions.assertEquals( "Second change", v2again.getAttribute( Page.CHANGENOTE ) );
    }

}
