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
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tests for error/edge-case paths in {@link BasicAttachmentProvider}.
 * <p>
 * Covers: multi-version attachment directory scanning, metadata persistence
 * (author and changenote), timestamp-filtered listing, and the
 * AttachmentVersionFilter inner class.
 */
class BasicAttachmentProviderCITest {

    private static final String PAGE_NAME = "AttachmentTestPage";
    private static final String PAGE_NAME_2 = "AnotherAttachmentPage";

    private TestEngine engine;
    private BasicAttachmentProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        provider = new BasicAttachmentProvider();
        provider.initialize( engine, engine.getWikiProperties() );

        engine.saveText( PAGE_NAME, "Page for attachment tests" );
        engine.saveText( PAGE_NAME_2, "Second page for attachment tests" );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private ByteArrayInputStream content( final String text ) {
        return new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) );
    }

    // -------------------------------------------------------------------------
    // Attachment version directory scanning (findLatestVersion)
    // -------------------------------------------------------------------------

    /**
     * After uploading three versions of the same attachment, listAttachments should
     * return a single entry whose version number equals 3.
     */
    @Test
    void testVersionScanningReturnsLatestVersion() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "versioned.txt" );
        provider.putAttachmentData( att, content( "v1" ) );
        provider.putAttachmentData( att, content( "v2" ) );
        provider.putAttachmentData( att, content( "v3" ) );

        final Page page = Wiki.contents().page( engine, PAGE_NAME );
        final List<Attachment> attachments = provider.listAttachments( page );

        Assertions.assertEquals( 1, attachments.size(), "should list one logical attachment" );
        Assertions.assertEquals( 3, attachments.get( 0 ).getVersion(), "latest version should be 3" );
        Assertions.assertEquals( "versioned.txt", attachments.get( 0 ).getFileName() );
    }

    /**
     * getVersionHistory() should return all three versions in descending order.
     */
    @Test
    void testGetVersionHistoryReturnsAllVersions() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "history.txt" );
        provider.putAttachmentData( att, content( "version A" ) );
        provider.putAttachmentData( att, content( "version B" ) );
        provider.putAttachmentData( att, content( "version C" ) );

        final List<Attachment> history = provider.getVersionHistory( att );
        Assertions.assertEquals( 3, history.size(), "should have 3 history entries" );

        // getVersionHistory iterates from latest downward
        Assertions.assertEquals( 3, history.get( 0 ).getVersion(), "first entry should be version 3" );
        Assertions.assertEquals( 2, history.get( 1 ).getVersion(), "second entry should be version 2" );
        Assertions.assertEquals( 1, history.get( 2 ).getVersion(), "third entry should be version 1" );
    }

    /**
     * When there is only one version, getVersionHistory should return a single-element list.
     */
    @Test
    void testGetVersionHistorySingleVersion() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "single.txt" );
        provider.putAttachmentData( att, content( "only version" ) );

        final List<Attachment> history = provider.getVersionHistory( att );
        Assertions.assertEquals( 1, history.size(), "should have exactly 1 history entry" );
        Assertions.assertEquals( 1, history.get( 0 ).getVersion() );
    }

    // -------------------------------------------------------------------------
    // Metadata persistence — author and changenote
    // -------------------------------------------------------------------------

    /**
     * Author set on the Attachment before uploading must be readable back via getAttachmentInfo().
     */
    @Test
    void testAuthorIsPersisted() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "authored.txt" );
        att.setAuthor( "alice" );
        provider.putAttachmentData( att, content( "data" ) );

        final Attachment retrieved = provider.getAttachmentInfo(
            Wiki.contents().page( engine, PAGE_NAME ), "authored.txt", 1 );
        Assertions.assertNotNull( retrieved, "attachment info should be retrievable" );
        Assertions.assertEquals( "alice", retrieved.getAuthor(), "author should be persisted" );
    }

    /**
     * When no author is provided, the provider substitutes "unknown".
     */
    @Test
    void testNullAuthorBecomesUnknown() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "noauthor.txt" );
        // leave author null
        provider.putAttachmentData( att, content( "data" ) );

        final Attachment retrieved = provider.getAttachmentInfo(
            Wiki.contents().page( engine, PAGE_NAME ), "noauthor.txt", 1 );
        Assertions.assertNotNull( retrieved );
        Assertions.assertEquals( "unknown", retrieved.getAuthor(), "null author should be stored as 'unknown'" );
    }

    /**
     * A CHANGENOTE attribute set on the Attachment must be readable back via getAttachmentInfo().
     */
    @Test
    void testChangeNoteIsPersisted() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "noted.txt" );
        att.setAuthor( "bob" );
        att.setAttribute( Page.CHANGENOTE, "Initial upload" );
        provider.putAttachmentData( att, content( "data" ) );

        final Attachment retrieved = provider.getAttachmentInfo(
            Wiki.contents().page( engine, PAGE_NAME ), "noted.txt", 1 );
        Assertions.assertNotNull( retrieved );
        Assertions.assertEquals( "Initial upload", retrieved.getAttribute( Page.CHANGENOTE ),
            "changenote should be persisted" );
    }

    /**
     * Author and changenote for each version must be stored independently.
     */
    @Test
    void testPerVersionMetadataPersisted() throws Exception {
        final Attachment v1 = Wiki.contents().attachment( engine, PAGE_NAME, "multi.txt" );
        v1.setAuthor( "carol" );
        v1.setAttribute( Page.CHANGENOTE, "First upload" );
        provider.putAttachmentData( v1, content( "v1 content" ) );

        final Attachment v2 = Wiki.contents().attachment( engine, PAGE_NAME, "multi.txt" );
        v2.setAuthor( "dave" );
        v2.setAttribute( Page.CHANGENOTE, "Second upload" );
        provider.putAttachmentData( v2, content( "v2 content" ) );

        final Page page = Wiki.contents().page( engine, PAGE_NAME );

        final Attachment r1 = provider.getAttachmentInfo( page, "multi.txt", 1 );
        Assertions.assertNotNull( r1 );
        Assertions.assertEquals( "carol", r1.getAuthor(), "v1 author" );
        Assertions.assertEquals( "First upload", r1.getAttribute( Page.CHANGENOTE ), "v1 changenote" );

        final Attachment r2 = provider.getAttachmentInfo( page, "multi.txt", 2 );
        Assertions.assertNotNull( r2 );
        Assertions.assertEquals( "dave", r2.getAuthor(), "v2 author" );
        Assertions.assertEquals( "Second upload", r2.getAttribute( Page.CHANGENOTE ), "v2 changenote" );
    }

    // -------------------------------------------------------------------------
    // listAllChanged() — timestamp-based filtering
    // -------------------------------------------------------------------------

    /**
     * listAllChanged() with a future timestamp should return an empty list, since no
     * attachment can have been modified after a time that hasn't happened yet.
     */
    @Test
    void testListAllChangedFutureTimestampReturnsEmpty() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "future.txt" );
        provider.putAttachmentData( att, content( "data" ) );

        final java.util.Date future = new java.util.Date( System.currentTimeMillis() + 86_400_000L );
        final List<Attachment> changed = provider.listAllChanged( future );

        Assertions.assertTrue( changed.isEmpty(),
            "no attachments should be listed as changed after a future date" );
    }

    /**
     * listAllChanged() with Date(0) should return all attachments.
     */
    @Test
    void testListAllChangedEpochReturnsAll() throws Exception {
        final Attachment att1 = Wiki.contents().attachment( engine, PAGE_NAME, "epoch1.txt" );
        provider.putAttachmentData( att1, content( "d1" ) );

        final Attachment att2 = Wiki.contents().attachment( engine, PAGE_NAME_2, "epoch2.txt" );
        provider.putAttachmentData( att2, content( "d2" ) );

        final List<Attachment> changed = provider.listAllChanged( new java.util.Date( 0L ) );

        Assertions.assertEquals( 2, changed.size(), "all attachments should be returned for epoch timestamp" );
    }

    // -------------------------------------------------------------------------
    // AttachmentVersionFilter inner class
    // -------------------------------------------------------------------------

    /**
     * AttachmentVersionFilter must reject the property file and accept versioned files.
     */
    @Test
    void testAttachmentVersionFilterRejectsPropertyFile() {
        final BasicAttachmentProvider.AttachmentVersionFilter filter =
            new BasicAttachmentProvider.AttachmentVersionFilter();
        final File dummy = new File( "." );

        Assertions.assertFalse( filter.accept( dummy, BasicAttachmentProvider.PROPERTY_FILE ),
            "AttachmentVersionFilter must reject " + BasicAttachmentProvider.PROPERTY_FILE );
    }

    /**
     * AttachmentVersionFilter must accept versioned attachment files.
     */
    @Test
    void testAttachmentVersionFilterAcceptsVersionedFiles() {
        final BasicAttachmentProvider.AttachmentVersionFilter filter =
            new BasicAttachmentProvider.AttachmentVersionFilter();
        final File dummy = new File( "." );

        Assertions.assertTrue( filter.accept( dummy, "1.txt" ), "1.txt should be accepted" );
        Assertions.assertTrue( filter.accept( dummy, "2.png" ), "2.png should be accepted" );
        Assertions.assertTrue( filter.accept( dummy, "10.pdf" ), "10.pdf should be accepted" );
    }
}
