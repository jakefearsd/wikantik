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
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.util.TextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Tests for {@link BasicAttachmentProvider#moveAttachmentsForPage(String, String)} (the D9
 * page-rename attachment mover, previously untested), the {@code initialize()} error paths,
 * the client-cache-disabling regex, and {@code findPageDir}'s "not a directory" guard.
 *
 * <p>Each test builds its own {@link TestEngine} with a private {@code @TempDir}-backed
 * storage directory, per CLAUDE.md's guidance that provider tests must keep filesystem
 * fixtures private to avoid parallel-run races.
 */
class BasicAttachmentProviderMoveTest {

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if( engine != null ) {
            engine.stop();
        }
    }

    private BasicAttachmentProvider buildProvider( final File storageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( BasicAttachmentProvider.PROP_STORAGEDIR, storageDir.getAbsolutePath() );
        final BasicAttachmentProvider provider = new BasicAttachmentProvider();
        provider.initialize( engine, props );
        return provider;
    }

    private ByteArrayInputStream content( final String text ) {
        return new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) );
    }

    // ============== moveAttachmentsForPage ==============

    @Test
    void testMoveAttachmentsForPage_noSourceDirectory_isNoOp( @TempDir final File storageDir ) throws Exception {
        final BasicAttachmentProvider provider = buildProvider( storageDir );

        Assertions.assertDoesNotThrow(
                () -> provider.moveAttachmentsForPage( "PageWithNoAttachments", "RenamedPageWithNoAttachments" ) );

        final File destDir = new File( storageDir,
                TextUtil.urlEncodeUTF8( "RenamedPageWithNoAttachments" ) + BasicAttachmentProvider.DIR_EXTENSION );
        Assertions.assertFalse( destDir.exists(), "No source directory means nothing should be created at the destination" );
    }

    @Test
    void testMoveAttachmentsForPage_destinationAlreadyExists_throws( @TempDir final File storageDir ) throws Exception {
        final BasicAttachmentProvider provider = buildProvider( storageDir );

        provider.putAttachmentData( Wiki.contents().attachment( engine, "SourcePage", "file.txt" ), content( "hello" ) );
        provider.putAttachmentData( Wiki.contents().attachment( engine, "TargetPage", "other.txt" ), content( "world" ) );

        final ProviderException ex = Assertions.assertThrows( ProviderException.class,
                () -> provider.moveAttachmentsForPage( "SourcePage", "TargetPage" ) );
        Assertions.assertTrue( ex.getMessage().contains( "already exists" ), "Unexpected message: " + ex.getMessage() );

        // Source must be left untouched since the move was refused.
        final File srcDir = new File( storageDir, TextUtil.urlEncodeUTF8( "SourcePage" ) + BasicAttachmentProvider.DIR_EXTENSION );
        Assertions.assertTrue( srcDir.exists(), "Refused move must not remove the source directory" );
    }

    @Test
    void testMoveAttachmentsForPage_movesDirectoryAndContents( @TempDir final File storageDir ) throws Exception {
        final BasicAttachmentProvider provider = buildProvider( storageDir );

        provider.putAttachmentData( Wiki.contents().attachment( engine, "OldPageName", "keep.txt" ), content( "payload" ) );

        provider.moveAttachmentsForPage( "OldPageName", "NewPageName" );

        final File oldDir = new File( storageDir, TextUtil.urlEncodeUTF8( "OldPageName" ) + BasicAttachmentProvider.DIR_EXTENSION );
        Assertions.assertFalse( oldDir.exists(), "Old attachment directory should be gone after a successful move" );

        final Page newPage = Wiki.contents().page( engine, "NewPageName" );
        final Attachment moved = provider.getAttachmentInfo( newPage, "keep.txt", WikiProvider.LATEST_VERSION );
        Assertions.assertNotNull( moved, "Attachment should be discoverable under the new page name" );
        try( InputStream in = provider.getAttachmentData( moved ) ) {
            Assertions.assertEquals( "payload", new String( in.readAllBytes(), StandardCharsets.UTF_8 ) );
        }
    }

    // ============== initialize() error paths ==============

    @Test
    void testInitialize_disableCachePatternMatches_marksAttachmentNonCacheable( @TempDir final File storageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties liveProps = engine.getWikiProperties();
        liveProps.setProperty( BasicAttachmentProvider.PROP_DISABLECACHE, ".*\\.secret" );
        liveProps.setProperty( BasicAttachmentProvider.PROP_STORAGEDIR, storageDir.getAbsolutePath() );

        final BasicAttachmentProvider provider = new BasicAttachmentProvider();
        provider.initialize( engine, liveProps );

        provider.putAttachmentData( Wiki.contents().attachment( engine, "CachePage", "data.secret" ), content( "shh" ) );

        final Attachment info = provider.getAttachmentInfo(
                Wiki.contents().page( engine, "CachePage" ), "data.secret", WikiProvider.LATEST_VERSION );
        Assertions.assertNotNull( info );
        Assertions.assertFalse( info.isCacheable(), "Attachment matching the disableCache pattern should not be cacheable" );
    }

    @Test
    void testInitialize_storageDirParentNotWritable_throwsIOException( @TempDir final File tempDir ) throws Exception {
        final File readOnlyParent = new File( tempDir, "readonly-parent" );
        Assertions.assertTrue( readOnlyParent.mkdirs() );
        Assertions.assertTrue( readOnlyParent.setWritable( false ) );
        Assumptions.assumeFalse( readOnlyParent.canWrite(), "permission bits not enforced here (running as root?)" );
        try {
            final File storageDir = new File( readOnlyParent, "attachments" );
            engine = TestEngine.build();
            final Properties props = new Properties();
            props.putAll( engine.getWikiProperties() );
            props.setProperty( BasicAttachmentProvider.PROP_STORAGEDIR, storageDir.getAbsolutePath() );

            final BasicAttachmentProvider provider = new BasicAttachmentProvider();
            final IOException ex = Assertions.assertThrows( IOException.class, () -> provider.initialize( engine, props ) );
            Assertions.assertTrue( ex.getMessage().contains( "Could not find or create" ), "Unexpected message: " + ex.getMessage() );
        } finally {
            readOnlyParent.setWritable( true );
        }
    }

    @Test
    void testInitialize_storageDirIsAFile_throwsIOException( @TempDir final File tempDir ) throws Exception {
        final File storageAsFile = new File( tempDir, "not-a-directory" );
        Assertions.assertTrue( storageAsFile.createNewFile() );

        engine = TestEngine.build();
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( BasicAttachmentProvider.PROP_STORAGEDIR, storageAsFile.getAbsolutePath() );

        final BasicAttachmentProvider provider = new BasicAttachmentProvider();
        final IOException ex = Assertions.assertThrows( IOException.class, () -> provider.initialize( engine, props ) );
        Assertions.assertTrue( ex.getMessage().contains( "points to a file" ), "Unexpected message: " + ex.getMessage() );
    }

    @Test
    void testInitialize_storageDirNotWritable_throwsIOException( @TempDir final File tempDir ) throws Exception {
        final File storageDir = new File( tempDir, "readonly-storage" );
        Assertions.assertTrue( storageDir.mkdirs() );
        Assertions.assertTrue( storageDir.setWritable( false ) );
        Assumptions.assumeFalse( storageDir.canWrite(), "permission bits not enforced here (running as root?)" );
        try {
            engine = TestEngine.build();
            final Properties props = new Properties();
            props.putAll( engine.getWikiProperties() );
            props.setProperty( BasicAttachmentProvider.PROP_STORAGEDIR, storageDir.getAbsolutePath() );

            final BasicAttachmentProvider provider = new BasicAttachmentProvider();
            final IOException ex = Assertions.assertThrows( IOException.class, () -> provider.initialize( engine, props ) );
            Assertions.assertTrue( ex.getMessage().contains( "Cannot write" ), "Unexpected message: " + ex.getMessage() );
        } finally {
            storageDir.setWritable( true );
        }
    }

    // ============== findPageDir ==============

    @Test
    void testFindPageDir_existingFileInsteadOfDirectory_throwsProviderException( @TempDir final File storageDir ) throws Exception {
        final BasicAttachmentProvider provider = buildProvider( storageDir );

        final File bogus = new File( storageDir, TextUtil.urlEncodeUTF8( "BogusPage" ) + BasicAttachmentProvider.DIR_EXTENSION );
        Assertions.assertTrue( bogus.createNewFile() );

        final Page page = Wiki.contents().page( engine, "BogusPage" );
        final ProviderException ex = Assertions.assertThrows( ProviderException.class, () -> provider.listAttachments( page ) );
        Assertions.assertTrue( ex.getMessage().contains( "is not a directory" ), "Unexpected message: " + ex.getMessage() );
    }
}
