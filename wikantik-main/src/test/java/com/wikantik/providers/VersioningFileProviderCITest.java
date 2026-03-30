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
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import static com.wikantik.TestEngine.with;

/**
 * Tests for error/edge-case paths in {@link VersioningFileProvider}.
 * <p>
 * Covers: property cache strategy selection (LRU / single-entry / no-op),
 * version deletion behaviour, and the IOException path in findLatestVersion.
 */
class VersioningFileProviderCITest {

    private TestEngine buildVersioningEngine( final String extraKey, final String extraValue ) {
        return TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "VersioningFileProvider" ),
            with( extraKey, extraValue )
        );
    }

    // -------------------------------------------------------------------------
    // Property-cache strategy selection during initialize()
    // -------------------------------------------------------------------------

    /**
     * When cacheSize is 0, the provider should start successfully with SingleEntryPropertyCache.
     * We verify observable behaviour: the provider answers version-history questions correctly
     * regardless of which cache is behind it.
     */
    @Test
    void testCacheSizeZeroUsesSingleEntryCache() throws Exception {
        final TestEngine engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "VersioningFileProvider" ),
            with( VersioningFileProvider.PROP_CACHE_SIZE, "0" )
        );
        try {
            engine.saveText( "CacheSizeZeroPage", "v1" );
            engine.saveText( "CacheSizeZeroPage", "v2" );

            final PageManager mgr = engine.getManager( PageManager.class );
            final Collection<Page> history = mgr.getVersionHistory( "CacheSizeZeroPage" );
            Assertions.assertEquals( 2, history.size(), "single-entry cache: expected 2 versions" );
        } finally {
            engine.stop();
        }
    }

    /**
     * When cacheSize is negative, the provider should use NoOpPropertyCache (no caching).
     * Verify that reads still work correctly (every call loads from disk).
     */
    @Test
    void testCacheSizeNegativeUsesNoOpCache() throws Exception {
        final TestEngine engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "VersioningFileProvider" ),
            with( VersioningFileProvider.PROP_CACHE_SIZE, "-1" )
        );
        try {
            engine.saveText( "NoOpCachePage", "first" );
            engine.saveText( "NoOpCachePage", "second" );
            engine.saveText( "NoOpCachePage", "third" );

            final PageManager mgr = engine.getManager( PageManager.class );

            // Repeated calls should still return correct results even with no caching
            final Collection<Page> h1 = mgr.getVersionHistory( "NoOpCachePage" );
            final Collection<Page> h2 = mgr.getVersionHistory( "NoOpCachePage" );
            Assertions.assertEquals( 3, h1.size(), "no-op cache: first call expected 3 versions" );
            Assertions.assertEquals( 3, h2.size(), "no-op cache: second call expected 3 versions" );

            Assertions.assertTrue( mgr.getText( "NoOpCachePage", 1 ).startsWith( "first" ),
                "version 1 content" );
            Assertions.assertTrue( mgr.getText( "NoOpCachePage", 2 ).startsWith( "second" ),
                "version 2 content" );
            Assertions.assertTrue( mgr.getText( "NoOpCachePage", 3 ).startsWith( "third" ),
                "version 3 content" );
        } finally {
            engine.stop();
        }
    }

    /**
     * When cacheSize is a positive value > 1, the provider uses LruPropertyCache.
     * Check that version history remains consistent across multiple page accesses.
     */
    @Test
    void testCacheSizeLargeUsesLruCache() throws Exception {
        final TestEngine engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "VersioningFileProvider" ),
            with( VersioningFileProvider.PROP_CACHE_SIZE, "50" )
        );
        try {
            // Create several pages to exercise the LRU eviction path
            for ( int i = 1; i <= 5; i++ ) {
                engine.saveText( "LruPage" + i, "content" );
                engine.saveText( "LruPage" + i, "updated" );
            }

            final PageManager mgr = engine.getManager( PageManager.class );
            for ( int i = 1; i <= 5; i++ ) {
                final Collection<Page> history = mgr.getVersionHistory( "LruPage" + i );
                Assertions.assertEquals( 2, history.size(), "LRU cache: LruPage" + i + " expected 2 versions" );
            }
        } finally {
            engine.stop();
        }
    }

    // -------------------------------------------------------------------------
    // deleteVersion() — latest version deletion promotes previous version
    // -------------------------------------------------------------------------

    /**
     * Deleting the latest version of a page that has at least two versions should
     * promote the previous version to become the new current file.
     */
    @Test
    void testDeleteLatestVersionPromotesPreviousVersion() throws Exception {
        final Properties props = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
        final TestEngine engine = TestEngine.build( props );
        try {
            engine.saveText( "DeleteLatest", "version-one\r\n" );
            engine.saveText( "DeleteLatest", "version-two\r\n" );

            final PageManager mgr = engine.getManager( PageManager.class );
            final PageProvider provider = mgr.getProvider();

            // Confirm 2 versions exist
            List<Page> history = provider.getVersionHistory( "DeleteLatest" );
            Assertions.assertEquals( 2, history.size() );

            // Delete the latest version
            provider.deleteVersion( "DeleteLatest", PageProvider.LATEST_VERSION );

            // The page should still exist (version 1 was promoted)
            Assertions.assertTrue( provider.pageExists( "DeleteLatest" ),
                "page should still exist after deleting latest version" );

            final String currentText = provider.getPageText( "DeleteLatest", PageProvider.LATEST_VERSION );
            Assertions.assertTrue( currentText.startsWith( "version-one" ),
                "version 1 content should be the new current page" );
        } finally {
            engine.stop();
        }
    }

    /**
     * Deleting a specific older version (not the latest) should remove that version's
     * archive file while leaving the current page intact.
     */
    @Test
    void testDeleteSpecificOldVersion() throws Exception {
        final Properties props = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
        final TestEngine engine = TestEngine.build( props );
        try {
            engine.saveText( "DeleteOld", "v1\r\n" );
            engine.saveText( "DeleteOld", "v2\r\n" );
            engine.saveText( "DeleteOld", "v3\r\n" );

            final PageManager mgr = engine.getManager( PageManager.class );
            final PageProvider provider = mgr.getProvider();

            // Delete version 2 (not the latest)
            provider.deleteVersion( "DeleteOld", 2 );

            // Version 3 (current) must remain
            final String current = provider.getPageText( "DeleteOld", PageProvider.LATEST_VERSION );
            Assertions.assertTrue( current.startsWith( "v3" ), "current page should still be v3" );

            // Requesting the deleted version should throw NoSuchVersionException
            Assertions.assertThrows( NoSuchVersionException.class,
                () -> provider.getPageText( "DeleteOld", 2 ),
                "requesting deleted version 2 should throw NoSuchVersionException" );
        } finally {
            engine.stop();
        }
    }

    /**
     * Requesting a version that was never created should throw NoSuchVersionException.
     */
    @Test
    void testDeleteNonExistentVersionThrows() throws Exception {
        final Properties props = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
        final TestEngine engine = TestEngine.build( props );
        try {
            engine.saveText( "NoSuchVer", "v1\r\n" );

            final PageManager mgr = engine.getManager( PageManager.class );
            final PageProvider provider = mgr.getProvider();

            Assertions.assertThrows( NoSuchVersionException.class,
                () -> provider.deleteVersion( "NoSuchVer", 99 ),
                "deleting a non-existent version should throw NoSuchVersionException" );
        } finally {
            engine.stop();
        }
    }

    // -------------------------------------------------------------------------
    // findLatestVersion() — IOException path (property file becomes unreadable)
    // -------------------------------------------------------------------------

    /**
     * When the page.properties file exists but cannot be read, findLatestVersion should
     * log the error and return -1, causing subsequent operations to treat the page as if
     * it has a single version.
     */
    @Test
    void testFindLatestVersionWithUnreadablePropertyFile() throws Exception {
        final Properties props = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
        final TestEngine engine = TestEngine.build( props );
        try {
            engine.saveText( "UnreadableProps", "initial content\r\n" );
            engine.saveText( "UnreadableProps", "updated content\r\n" );

            final String pageDir = engine.getWikiProperties()
                .getProperty( AbstractFileProvider.PROP_PAGEDIR );
            final File propFile = new File( pageDir + File.separator
                + VersioningFileProvider.PAGEDIR + File.separator
                + "UnreadableProps" + File.separator
                + VersioningFileProvider.PROPERTYFILE );

            Assertions.assertTrue( propFile.exists(), "property file should exist" );

            final boolean madeUnreadable = propFile.setReadable( false );
            if ( !madeUnreadable ) {
                // Running as root or on a filesystem that ignores permissions — skip gracefully
                return;
            }

            try {
                // pageExists() calls findLatestVersion() under the hood; the IOException
                // path should be swallowed and the method should not throw.
                final PageManager mgr = engine.getManager( PageManager.class );
                final PageProvider provider = mgr.getProvider();
                // Should not throw even with unreadable properties; page file itself is readable
                Assertions.assertDoesNotThrow(
                    () -> provider.pageExists( "UnreadableProps" ),
                    "pageExists should not throw when property file is unreadable" );
            } finally {
                propFile.setReadable( true );
            }
        } finally {
            engine.stop();
        }
    }

    // -------------------------------------------------------------------------
    // getAllPages() — version numbers are populated
    // -------------------------------------------------------------------------

    /**
     * getAllPages() on the VersioningFileProvider should return pages with their
     * version number populated (overrides the base class which leaves version = 0).
     */
    @Test
    void testGetAllPagesContainsVersionNumbers() throws Exception {
        final Properties props = TestEngine.getTestProperties( "/wikantik-vers-custom.properties" );
        final TestEngine engine = TestEngine.build( props );
        try {
            engine.saveText( "VersionedPage1", "first" );
            engine.saveText( "VersionedPage1", "second" );
            engine.saveText( "VersionedPage2", "only one version" );

            final PageManager mgr = engine.getManager( PageManager.class );
            final PageProvider provider = mgr.getProvider();

            final Collection<Page> allPages = provider.getAllPages();
            Assertions.assertFalse( allPages.isEmpty(), "should have pages" );

            for ( final Page page : allPages ) {
                Assertions.assertTrue( page.getVersion() >= 1,
                    "getAllPages() should populate version >= 1 for " + page.getName() );
            }
        } finally {
            engine.stop();
        }
    }
}
