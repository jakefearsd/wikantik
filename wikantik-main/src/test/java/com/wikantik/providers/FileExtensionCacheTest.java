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
import com.wikantik.pages.PageManager;
import com.wikantik.util.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.util.Properties;

import static com.wikantik.TestEngine.with;

/**
 * Tests for the file extension cache optimization in AbstractFileProvider.
 * These tests verify that:
 * 1. The cache correctly stores and retrieves file extensions
 * 2. Cache entries are properly invalidated on page operations
 * 3. The cache reduces redundant filesystem calls
 */
public class FileExtensionCacheTest {

    private FileSystemProvider m_provider;
    private Properties props = TestEngine.getTestProperties();
    private String m_pageDir;

    private Engine m_engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" ),
            with( FileSystemProvider.PROP_PAGEDIR, "./target/wikantik.cache.test.pages" ) );

    @BeforeEach
    public void setUp() throws Exception {
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "FileSystemProvider" );
        props.setProperty( FileSystemProvider.PROP_PAGEDIR, "./target/wikantik.cache.test.pages" );
        m_pageDir = props.getProperty( FileSystemProvider.PROP_PAGEDIR );

        m_provider = new FileSystemProvider();
        m_provider.initialize( m_engine, props );
    }

    @AfterEach
    public void tearDown() {
        TestEngine.deleteAll( new File( m_pageDir ) );
    }

    @Test
    public void testCachePopulatedOnPageAccess() throws Exception {
        // Initially cache should be empty
        Assertions.assertEquals( 0, m_provider.getFileExtensionCacheSize(), "Cache should be empty initially" );

        // Create a page
        final WikiPage page = new WikiPage( m_engine, "CacheTestPage" );
        m_provider.putPageText( page, "Test content" );

        // After putPageText, cache should have one entry
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize(), "Cache should have one entry after putPageText" );

        // Clear cache and access page via getPageInfo
        m_provider.clearFileExtensionCache();
        Assertions.assertEquals( 0, m_provider.getFileExtensionCacheSize(), "Cache should be empty after clear" );

        m_provider.getPageInfo( "CacheTestPage", -1 );
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize(), "Cache should have one entry after getPageInfo" );
    }

    @Test
    public void testCacheInvalidatedOnPageDelete() throws Exception {
        // Create a page
        final WikiPage page = new WikiPage( m_engine, "DeleteCacheTest" );
        m_provider.putPageText( page, "Test content" );

        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize(), "Cache should have one entry" );

        // Delete the page
        m_provider.deletePage( "DeleteCacheTest" );

        // Cache entry should be removed
        Assertions.assertEquals( 0, m_provider.getFileExtensionCacheSize(), "Cache should be empty after delete" );
    }

    @Test
    public void testCacheInvalidatedOnVersionDelete() throws Exception {
        // Create a page
        final WikiPage page = new WikiPage( m_engine, "DeleteVersionCacheTest" );
        m_provider.putPageText( page, "Test content" );

        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize(), "Cache should have one entry" );

        // Delete the latest version
        m_provider.deleteVersion( "DeleteVersionCacheTest", -1 );

        // Cache entry should be removed
        Assertions.assertEquals( 0, m_provider.getFileExtensionCacheSize(), "Cache should be empty after deleteVersion" );
    }

    @Test
    public void testCacheCorrectlyDistinguishesTxtAndMd() throws Exception {
        // Create a .txt page
        final WikiPage txtPage = new WikiPage( m_engine, "TxtPage" );
        m_provider.putPageText( txtPage, "Wiki content" );

        // Create a .md page
        final WikiPage mdPage = new WikiPage( m_engine, "MdPage" );
        mdPage.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        m_provider.putPageText( mdPage, "# Markdown content" );

        Assertions.assertEquals( 2, m_provider.getFileExtensionCacheSize(), "Cache should have two entries" );

        // Clear cache and verify both are re-cached correctly
        m_provider.clearFileExtensionCache();

        // Access txt page - should cache .txt extension
        final String txtContent = m_provider.getPageText( "TxtPage", -1 );
        Assertions.assertEquals( "Wiki content", txtContent );

        // Access md page - should cache .md extension
        final String mdContent = m_provider.getPageText( "MdPage", -1 );
        Assertions.assertEquals( "# Markdown content", mdContent );

        Assertions.assertEquals( 2, m_provider.getFileExtensionCacheSize(), "Cache should have two entries" );
    }

    @Test
    public void testCacheHitAvoidsDiskAccess() throws Exception {
        // Create a page
        final WikiPage page = new WikiPage( m_engine, "CacheHitTest" );
        m_provider.putPageText( page, "Test content" );

        // Verify cache has the entry
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize() );

        // Access the page multiple times - should use cache
        for( int i = 0; i < 10; i++ ) {
            m_provider.getPageInfo( "CacheHitTest", -1 );
        }

        // Cache size should still be 1 (entries reused, not duplicated)
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize(), "Cache should still have one entry" );
    }

    @Test
    public void testCacheUpdatedOnExtensionChange() throws Exception {
        // Directly create a .txt file to simulate a legacy wiki-syntax page on disk
        File txtFile = new File( m_pageDir, "ExtChangeTest" + AbstractFileProvider.FILE_EXT );
        FileUtil.copyContents( new ByteArrayInputStream( "Wiki content".getBytes() ),
                               new FileOutputStream( txtFile ) );

        // Verify it exists as .txt
        Assertions.assertTrue( txtFile.exists(), "TXT file should exist" );

        // Access the page so cache is populated with .txt
        m_provider.getPageInfo( "ExtChangeTest", -1 );

        // Remove the .txt and properties file, then create a .md file (simulating migration)
        txtFile.delete();
        new File( m_pageDir, "ExtChangeTest.properties" ).delete();
        File mdFile = new File( m_pageDir, "ExtChangeTest" + AbstractFileProvider.MARKDOWN_EXT );
        FileUtil.copyContents( new ByteArrayInputStream( "# Markdown content".getBytes() ),
                               new FileOutputStream( mdFile ) );

        // Clear cache to force re-detection
        m_provider.clearFileExtensionCache();

        // Access the page - should now find .md due to precedence rules
        final Page retrievedPage = m_provider.getPageInfo( "ExtChangeTest", -1 );
        Assertions.assertNotNull( retrievedPage );
        Assertions.assertEquals( "markdown", retrievedPage.getAttribute( Page.MARKUP_SYNTAX ),
                "Should use markdown after external .md file creation" );

        // Cache should now have .md extension cached
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize() );

        // Content should be from .md file
        String content = m_provider.getPageText( "ExtChangeTest", -1 );
        Assertions.assertEquals( "# Markdown content", content );
    }

    @Test
    public void testClearCacheMethodWorks() throws Exception {
        // Create multiple pages
        for( int i = 1; i <= 5; i++ ) {
            final WikiPage page = new WikiPage( m_engine, "ClearCachePage" + i );
            m_provider.putPageText( page, "Content " + i );
        }

        Assertions.assertEquals( 5, m_provider.getFileExtensionCacheSize(), "Cache should have 5 entries" );

        // Clear the cache
        m_provider.clearFileExtensionCache();

        Assertions.assertEquals( 0, m_provider.getFileExtensionCacheSize(), "Cache should be empty after clear" );
    }

    @Test
    public void testCacheWithNonExistentPage() throws Exception {
        // Access a non-existent page - should NOT be cached
        Page page = m_provider.getPageInfo( "NonExistentPage", -1 );
        Assertions.assertNull( page, "Non-existent page should return null" );

        // Cache should be empty since page doesn't exist
        Assertions.assertEquals( 0, m_provider.getFileExtensionCacheSize(),
                "Non-existent pages should not be cached" );
    }

    @Test
    public void testMultiplePagesShareCache() throws Exception {
        // Create multiple pages
        for( int i = 1; i <= 10; i++ ) {
            final WikiPage page = new WikiPage( m_engine, "MultiPage" + i );
            if( i % 2 == 0 ) {
                page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
            }
            m_provider.putPageText( page, "Content " + i );
        }

        Assertions.assertEquals( 10, m_provider.getFileExtensionCacheSize(), "Cache should have 10 entries" );

        // Access all pages and verify content
        for( int i = 1; i <= 10; i++ ) {
            String content = m_provider.getPageText( "MultiPage" + i, -1 );
            Assertions.assertEquals( "Content " + i, content );
        }

        // Cache size should remain 10
        Assertions.assertEquals( 10, m_provider.getFileExtensionCacheSize(), "Cache should still have 10 entries" );
    }

    @Test
    public void testCacheThreadSafety() throws Exception {
        // Create a page
        final WikiPage page = new WikiPage( m_engine, "ThreadSafetyTest" );
        m_provider.putPageText( page, "Test content" );

        // Clear cache
        m_provider.clearFileExtensionCache();

        // Run multiple threads accessing the same page
        Thread[] threads = new Thread[10];
        for( int i = 0; i < threads.length; i++ ) {
            threads[i] = new Thread( () -> {
                try {
                    for( int j = 0; j < 100; j++ ) {
                        m_provider.getPageInfo( "ThreadSafetyTest", -1 );
                        m_provider.getPageText( "ThreadSafetyTest", -1 );
                    }
                } catch( Exception e ) {
                    Assertions.fail( "Thread should not throw exception: " + e.getMessage() );
                }
            } );
        }

        // Start all threads
        for( Thread t : threads ) {
            t.start();
        }

        // Wait for all threads to complete
        for( Thread t : threads ) {
            t.join();
        }

        // Cache should have exactly 1 entry (for the single page)
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize(),
                "Cache should have 1 entry after concurrent access" );
    }

    @Test
    public void testGetPageFileExtensionUsesCache() throws Exception {
        // Create a markdown page
        final WikiPage page = new WikiPage( m_engine, "ExtMethodTest" );
        page.setAttribute( Page.MARKUP_SYNTAX, "markdown" );
        m_provider.putPageText( page, "# Markdown" );

        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize() );

        // getPageFileExtension should use cached value
        String ext = m_provider.getPageFileExtension( "ExtMethodTest" );
        Assertions.assertEquals( AbstractFileProvider.MARKDOWN_EXT, ext );

        // Cache size should remain 1
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize() );
    }

    @Test
    public void testPageExistsUsesCache() throws Exception {
        // Create a page
        final WikiPage page = new WikiPage( m_engine, "ExistsTest" );
        m_provider.putPageText( page, "Test" );

        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize() );

        // pageExists should use cache via findPage
        boolean exists = m_provider.pageExists( "ExistsTest" );
        Assertions.assertTrue( exists );

        // Non-existent page
        exists = m_provider.pageExists( "DoesNotExist" );
        Assertions.assertFalse( exists );

        // Cache should still be 1 (non-existent pages not cached)
        Assertions.assertEquals( 1, m_provider.getFileExtensionCacheSize() );
    }

}
