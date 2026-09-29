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
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Properties;

/**
 * Covers {@link VersioningFileProvider} error paths and rarely-exercised branches that
 * the main {@code VersioningFileProviderTest}/{@code VersioningFileProviderCITest} suites
 * (which drive the provider through {@code TestEngine.saveText}) never reach: {@code
 * initialize()}'s {@code OLD/} directory sanity checks, {@code pageExists(String,int)}
 * (only ever exercised via the single-arg overload elsewhere), {@code getPageInfo} on a
 * page with no version history, an unreadable archived version file, a corrupted heritage
 * properties file, {@code deletePage}/{@code deleteVersion}/{@code movePage}'s permission
 * failure branches, and {@code getAllPages()}/{@code getProviderInfo()}.
 *
 * <p>Each test builds its own {@link VersioningFileProvider} directly (bypassing
 * {@code PageManager}) against a private {@code @TempDir}, per CLAUDE.md's guidance that
 * provider tests must keep filesystem fixtures private to avoid parallel-run races.
 */
class VersioningFileProviderErrorPathsTest {

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if( engine != null ) {
            engine.stop();
        }
    }

    private VersioningFileProvider buildProvider( final File pageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );
        final VersioningFileProvider provider = new VersioningFileProvider();
        provider.initialize( engine, props );
        return provider;
    }

    private File oldDirFor( final File pageDir, final String pageName ) {
        return new File( new File( pageDir, VersioningFileProvider.PAGEDIR ), pageName );
    }

    // ============== initialize() OLD/ directory sanity checks ==============

    @Test
    void testInitialize_oldDirIsAFile_throwsIOException( @TempDir final File pageDir ) throws Exception {
        Assertions.assertTrue( pageDir.mkdirs() || pageDir.isDirectory() );
        final File oldAsFile = new File( pageDir, VersioningFileProvider.PAGEDIR );
        Assertions.assertTrue( oldAsFile.createNewFile() );

        engine = TestEngine.build();
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );

        final VersioningFileProvider provider = new VersioningFileProvider();
        final java.io.IOException ex = Assertions.assertThrows( java.io.IOException.class,
                () -> provider.initialize( engine, props ) );
        Assertions.assertTrue( ex.getMessage().contains( "is not a directory" ), "Unexpected message: " + ex.getMessage() );
    }

    @Test
    void testInitialize_oldDirNotWritable_throwsIOException( @TempDir final File pageDir ) throws Exception {
        Assertions.assertTrue( pageDir.mkdirs() || pageDir.isDirectory() );
        final File oldDir = new File( pageDir, VersioningFileProvider.PAGEDIR );
        Assertions.assertTrue( oldDir.mkdirs() );
        Assertions.assertTrue( oldDir.setWritable( false ) );
        try {
            engine = TestEngine.build();
            final Properties props = new Properties();
            props.putAll( engine.getWikiProperties() );
            props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );

            final VersioningFileProvider provider = new VersioningFileProvider();
            final java.io.IOException ex = Assertions.assertThrows( java.io.IOException.class,
                    () -> provider.initialize( engine, props ) );
            Assertions.assertTrue( ex.getMessage().contains( "is not writable" ), "Unexpected message: " + ex.getMessage() );
        } finally {
            oldDir.setWritable( true );
        }
    }

    // ============== pageExists(String, int) ==============

    @Test
    void testPageExists_latestVersionOverload_delegatesToSingleArgCheck( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "ExistsLatest" );
        provider.putPageText( page, "content" );

        Assertions.assertTrue( provider.pageExists( "ExistsLatest", PageProvider.LATEST_VERSION ) );
        Assertions.assertFalse( provider.pageExists( "NeverCreated", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void testPageExists_oldVersionFileOnDisk_returnsTrue( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "ExistsOld" );
        provider.putPageText( page, "v1" );
        provider.putPageText( page, "v2" ); // archives v1 as OLD/ExistsOld/1.txt

        Assertions.assertTrue( provider.pageExists( "ExistsOld", 1 ), "archived version 1 should exist on disk" );
    }

    @Test
    void testPageExists_oldVersionOnNeverSavedPage_returnsFalse( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );

        Assertions.assertFalse( provider.pageExists( "NeverSavedAtAll", 5 ),
                "a page with no OLD directory at all cannot have any old version" );
    }

    // ============== getPageInfo ==============

    @Test
    void testGetPageInfo_oldVersionDirectoryMissing_returnsNull( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );

        Assertions.assertNull( provider.getPageInfo( "NeverSavedPage", 5 ) );
    }

    // ============== getPageText: invalid/unreadable versions ==============

    @Test
    void testGetPageText_versionBeyondLatest_throwsNoSuchVersionException( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "BeyondLatest" );
        provider.putPageText( page, "only version" );

        Assertions.assertThrows( NoSuchVersionException.class, () -> provider.getPageText( "BeyondLatest", 999 ) );
    }

    @Test
    void testGetPageText_oldVersionFileUnreadable_throwsProviderException( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "UnreadableOldVersion" );
        provider.putPageText( page, "v1" );
        provider.putPageText( page, "v2" ); // archives v1 as OLD/UnreadableOldVersion/1.txt

        final File archived = new File( oldDirFor( pageDir, "UnreadableOldVersion" ), "1.txt" );
        Assertions.assertTrue( archived.exists() );
        Assertions.assertTrue( archived.setReadable( false ) );
        try {
            final ProviderException ex = Assertions.assertThrows( ProviderException.class,
                    () -> provider.getPageText( "UnreadableOldVersion", 1 ) );
            Assertions.assertTrue( ex.getMessage().toLowerCase( java.util.Locale.ROOT ).contains( "cannot read" ),
                    "Unexpected message: " + ex.getMessage() );
        } finally {
            archived.setReadable( true );
        }
    }

    // ============== heritage properties (pre-versioning migration) ==============

    @Test
    void testPutPageText_corruptHeritagePropertiesFile_fallsBackToUnknownAuthor( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );

        // Simulate a page that was last saved by the (non-versioning) FileSystemProvider:
        // a "<page>.properties" file sits next to the page in the top-level directory.
        final File heritageFile = new File( pageDir, "HeritagePage" + FileSystemProvider.PROP_EXT );
        try( FileWriter w = new FileWriter( heritageFile ) ) {
            w.write( "author=OldAuthor\n" );
        }
        Assertions.assertTrue( heritageFile.setReadable( false ) );
        try {
            final Page page = Wiki.contents().page( engine, "HeritagePage" );
            Assertions.assertDoesNotThrow( () -> provider.putPageText( page, "new content" ) );
            Assertions.assertEquals( "unknown", page.getAuthor(),
                    "an unreadable heritage properties file should be swallowed and default to 'unknown'" );
        } finally {
            heritageFile.setReadable( true );
        }
    }

    // ============== deletePage permission failures ==============

    @Test
    void testDeletePage_oldVersionDirUnreadable_stopsWithoutDeletingIt( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "DeleteUnreadable" );
        provider.putPageText( page, "v1" );
        provider.putPageText( page, "v2" );

        final File oldDir = oldDirFor( pageDir, "DeleteUnreadable" );
        Assertions.assertTrue( oldDir.isDirectory() );
        Assertions.assertTrue( oldDir.setReadable( false ) );
        try {
            Assertions.assertDoesNotThrow( () -> provider.deletePage( "DeleteUnreadable" ) );
            Assertions.assertTrue( oldDir.exists(), "an unlistable OLD dir should be left alone, not deleted" );
        } finally {
            oldDir.setReadable( true );
        }
    }

    @Test
    void testDeletePage_oldVersionDirNotWritable_logsWarningsButDoesNotThrow( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "DeleteNotWritable" );
        provider.putPageText( page, "v1" );
        provider.putPageText( page, "v2" );

        final File oldDir = oldDirFor( pageDir, "DeleteNotWritable" );
        Assertions.assertTrue( oldDir.isDirectory() );
        Assertions.assertTrue( oldDir.setWritable( false ) );
        try {
            Assertions.assertDoesNotThrow( () -> provider.deletePage( "DeleteNotWritable" ) );
            // The directory couldn't be emptied (files undeletable) so it couldn't be removed either.
            Assertions.assertTrue( oldDir.exists(), "a non-writable OLD dir cannot be removed" );
            Assertions.assertTrue( new File( oldDir, "1.txt" ).exists(), "archived file should survive the failed delete" );
        } finally {
            oldDir.setWritable( true );
        }
    }

    // ============== deleteVersion ==============

    @Test
    void testDeleteVersion_singleVersionPage_removesPageEntirely( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "SingleVersionDelete" );
        provider.putPageText( page, "only version" );

        provider.deleteVersion( "SingleVersionDelete", PageProvider.LATEST_VERSION );

        Assertions.assertFalse( provider.pageExists( "SingleVersionDelete" ),
                "deleting the only version of a page should remove it entirely" );
    }

    @Test
    void testDeleteVersion_specificOldVersionFileNotWritable_logsErrorButDoesNotThrow( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "DeleteOldVersionLocked" );
        provider.putPageText( page, "v1" );
        provider.putPageText( page, "v2" );
        provider.putPageText( page, "v3" ); // archives v1 as 1.txt, v2 as 2.txt

        final File oldDir = oldDirFor( pageDir, "DeleteOldVersionLocked" );
        Assertions.assertTrue( oldDir.setWritable( false ) );
        try {
            Assertions.assertDoesNotThrow( () -> provider.deleteVersion( "DeleteOldVersionLocked", 2 ) );
            Assertions.assertTrue( new File( oldDir, "2.txt" ).exists(),
                    "the version file should survive since its parent directory is not writable" );
        } finally {
            oldDir.setWritable( true );
        }
    }

    @Test
    void testDeleteVersion_propertiesFileNotWritable_throwsProviderException( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page page = Wiki.contents().page( engine, "DeleteLatestPropsLocked" );
        provider.putPageText( page, "v1" );
        provider.putPageText( page, "v2" );

        final File propsFile = new File( oldDirFor( pageDir, "DeleteLatestPropsLocked" ), VersioningFileProvider.PROPERTYFILE );
        Assertions.assertTrue( propsFile.exists() );
        Assertions.assertTrue( propsFile.setWritable( false ) );
        try {
            Assertions.assertThrows( ProviderException.class,
                    () -> provider.deleteVersion( "DeleteLatestPropsLocked", PageProvider.LATEST_VERSION ) );
        } finally {
            propsFile.setWritable( true );
        }
    }

    // ============== getAllPages / getProviderInfo ==============

    @Test
    void testGetAllPages_populatesVersionNumbers( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        provider.putPageText( Wiki.contents().page( engine, "AllPagesOne" ), "one" );
        final Page two = Wiki.contents().page( engine, "AllPagesTwo" );
        provider.putPageText( two, "two-v1" );
        provider.putPageText( two, "two-v2" );

        final List< Page > all = new java.util.ArrayList<>( provider.getAllPages() );
        Assertions.assertEquals( 2, all.size() );
        for( final Page p : all ) {
            if( "AllPagesTwo".equals( p.getName() ) ) {
                Assertions.assertEquals( 2, p.getVersion(), "AllPagesTwo should report its latest version number" );
            } else {
                Assertions.assertEquals( 1, p.getVersion(), "AllPagesOne should report version 1" );
            }
        }
    }

    @Test
    void testGetProviderInfo_returnsEmptyString( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        Assertions.assertEquals( "", provider.getProviderInfo() );
    }

    // ============== movePage ==============

    @Test
    void testMovePage_sourceFileMissing_logsWarningButDoesNotThrow( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );

        Assertions.assertDoesNotThrow( () -> provider.movePage( "NeverExistedSource", "NeverExistedTarget" ) );
    }

    @Test
    void testMovePage_destinationOldDirNonEmpty_logsWarningButDoesNotThrow( @TempDir final File pageDir ) throws Exception {
        final VersioningFileProvider provider = buildProvider( pageDir );
        final Page fromPage = Wiki.contents().page( engine, "MoveFromPage" );
        provider.putPageText( fromPage, "v1" );
        provider.putPageText( fromPage, "v2" ); // creates OLD/MoveFromPage/1.txt

        final File toOldDir = oldDirFor( pageDir, "MoveToPage" );
        Assertions.assertTrue( toOldDir.mkdirs() );
        Assertions.assertTrue( new File( toOldDir, "dummy.txt" ).createNewFile() );

        Assertions.assertDoesNotThrow( () -> provider.movePage( "MoveFromPage", "MoveToPage" ) );

        final File fromOldDir = oldDirFor( pageDir, "MoveFromPage" );
        Assertions.assertTrue( fromOldDir.exists(),
                "a failed directory-level rename (destination non-empty) should leave the source versions dir in place" );
    }
}
