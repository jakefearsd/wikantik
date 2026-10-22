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
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Page and properties writes by the file providers must be atomic (temp file + rename) and must
 * surface failures as {@link ProviderException} instead of reporting a save that did not happen.
 * Failure is provoked with an unwritable directory: replacing an existing file in place still
 * succeeds there (the file itself is writable), whereas a temp-file + rename write cannot.
 */
class ProviderAtomicWriteTest {

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if( engine != null ) {
            engine.stop();
        }
    }

    private Properties propsFor( final File pageDir ) {
        engine = TestEngine.build();
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( AbstractFileProvider.PROP_PAGEDIR, pageDir.getAbsolutePath() );
        return props;
    }

    private Page page( final String name ) {
        final Page p = Wiki.contents().page( engine, name );
        p.setAuthor( "tester" );
        return p;
    }

    private static boolean noTempFiles( final Path dir ) throws Exception {
        try( Stream< Path > s = Files.walk( dir ) ) {
            return s.noneMatch( p -> p.getFileName().toString().endsWith( ".tmp" ) );
        }
    }

    @Test
    void fileSystemProvider_failedWriteThrowsAndKeepsPreviousText( @TempDir final File pageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties props = propsFor( pageDir );
        final FileSystemProvider provider = new FileSystemProvider();
        provider.initialize( engine, props );
        provider.putPageText( page( "Doomed" ), "version one" );

        Assertions.assertTrue( pageDir.setWritable( false ) );
        Assumptions.assumeFalse( pageDir.canWrite(), "permission bits not enforced here (running as root?)" );
        try {
            Assertions.assertThrows( ProviderException.class, () -> provider.putPageText( page( "Doomed" ), "version two" ) );
        } finally {
            pageDir.setWritable( true );
        }
        Assertions.assertEquals( "version one", provider.getPageText( "Doomed", PageProvider.LATEST_VERSION ) );
    }

    @Test
    void versioningProvider_failedWriteThrowsAndRecordsNoVersion( @TempDir final File pageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties props = propsFor( pageDir );
        final VersioningFileProvider provider = new VersioningFileProvider();
        provider.initialize( engine, props );
        provider.putPageText( page( "Doomed" ), "version one" );

        Assertions.assertTrue( pageDir.setWritable( false ) );
        Assumptions.assumeFalse( pageDir.canWrite(), "permission bits not enforced here (running as root?)" );
        try {
            Assertions.assertThrows( ProviderException.class, () -> provider.putPageText( page( "Doomed" ), "version two" ) );
        } finally {
            pageDir.setWritable( true );
        }
        Assertions.assertEquals( "version one", provider.getPageText( "Doomed", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( 1, provider.getPageInfo( "Doomed", PageProvider.LATEST_VERSION ).getVersion(),
                "a failed text write must not advance the recorded version" );
        Assertions.assertEquals( 1, provider.getVersionHistory( "Doomed" ).size() );
    }

    @Test
    void successfulSavesLeaveNoTempFiles( @TempDir final File pageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties props = propsFor( pageDir );
        final VersioningFileProvider provider = new VersioningFileProvider();
        provider.initialize( engine, props );
        provider.putPageText( page( "Clean" ), "one" );
        provider.putPageText( page( "Clean" ), "two" );

        Assertions.assertEquals( "two", provider.getPageText( "Clean", PageProvider.LATEST_VERSION ) );
        Assertions.assertEquals( "one", provider.getPageText( "Clean", 1 ) );
        Assertions.assertTrue( noTempFiles( pageDir.toPath() ), "temp files must be gone after a successful save" );
    }

    /** Counts getPageInfo calls so we can see how many times getAllPages visits each page. */
    static class CountingVersioningProvider extends VersioningFileProvider {
        final AtomicInteger infoCalls = new AtomicInteger();

        @Override
        public Page getPageInfo( final String page, final int version ) throws ProviderException {
            infoCalls.incrementAndGet();
            return super.getPageInfo( page, version );
        }
    }

    @Test
    void versioningGetAllPagesReadsEachPageOnce( @TempDir final File pageDir ) throws Exception {
        engine = TestEngine.build();
        final Properties props = propsFor( pageDir );
        final CountingVersioningProvider provider = new CountingVersioningProvider();
        provider.initialize( engine, props );
        for( final String name : new String[] { "A", "B", "C" } ) {
            provider.putPageText( page( name ), "text " + name );
        }
        provider.infoCalls.set( 0 );

        Assertions.assertEquals( 3, provider.getAllPages().size() );
        Assertions.assertEquals( 3, provider.infoCalls.get(), "each page's info must be loaded exactly once per getAllPages" );
    }

    @Test
    void deletingLastAttachmentRemovesEmptyAttachmentDirectory() throws Exception {
        engine = TestEngine.build();
        final BasicAttachmentProvider provider = new BasicAttachmentProvider();
        provider.initialize( engine, engine.getWikiProperties() );
        engine.saveText( "AttPageForCleanup", "body" );
        final Attachment att = Wiki.contents().attachment( engine, "AttPageForCleanup", "file.txt" );
        att.setAuthor( "tester" );
        provider.putAttachmentData( att, new ByteArrayInputStream( "data".getBytes( StandardCharsets.UTF_8 ) ) );

        final File storage = new File( engine.getWikiProperties().getProperty( BasicAttachmentProvider.PROP_STORAGEDIR ) );
        final File pageAttDir = new File( storage, "AttPageForCleanup" + BasicAttachmentProvider.DIR_EXTENSION );
        Assertions.assertTrue( pageAttDir.isDirectory() );

        provider.deleteAttachment( att );

        Assertions.assertFalse( pageAttDir.exists(),
                "empty page attachment dir left behind: " + Arrays.toString( storage.list() ) );
    }

    @Test
    void attachmentUploadIsAtomicAndLeavesNoTempFiles() throws Exception {
        engine = TestEngine.build();
        final BasicAttachmentProvider provider = new BasicAttachmentProvider();
        provider.initialize( engine, engine.getWikiProperties() );
        engine.saveText( "AttPageAtomic", "body" );
        final Attachment att = Wiki.contents().attachment( engine, "AttPageAtomic", "file.txt" );
        att.setAuthor( "tester" );
        provider.putAttachmentData( att, new ByteArrayInputStream( "data".getBytes( StandardCharsets.UTF_8 ) ) );

        final File storage = new File( engine.getWikiProperties().getProperty( BasicAttachmentProvider.PROP_STORAGEDIR ) );
        final Path attDir = new File( storage, "AttPageAtomic" + BasicAttachmentProvider.DIR_EXTENSION ).toPath();
        Assertions.assertTrue( noTempFiles( attDir ), Files.walk( attDir ).map( Path::toString ).collect( Collectors.joining( "," ) ) );
        Assertions.assertEquals( "data", new String( provider.getAttachmentData( att ).readAllBytes(), StandardCharsets.UTF_8 ) );
        provider.deleteAttachment( att );
    }
}
