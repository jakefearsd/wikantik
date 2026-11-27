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
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The attachment store seen through {@link CachingAttachmentProvider} over {@link BasicAttachmentProvider}:
 * versions, deletes and page moves must show up in the very next read, never as a cached predecessor.
 */
class AttachmentProviderCoherenceTest {

    private static final String PAGE = "AttCoherencePage";
    private static final String OTHER = "AttCoherenceOther";

    private TestEngine engine;
    private CachingAttachmentProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        provider = ( CachingAttachmentProvider ) engine.getManager( AttachmentManager.class ).getCurrentProvider();
        engine.saveText( PAGE, "page" );
        engine.saveText( OTHER, "other" );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private Page page( final String name ) {
        return Wiki.contents().page( engine, name );
    }

    private Attachment attachment( final String pageName, final String file, final String author ) {
        final Attachment a = Wiki.contents().attachment( engine, pageName, file );
        a.setAuthor( author );
        return a;
    }

    private static InputStream data( final String text ) {
        return new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) );
    }

    private static String read( final InputStream in ) throws Exception {
        try( in ) {
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    private static Set< String > fileNames( final List< Attachment > atts ) {
        return atts.stream().map( Attachment::getFileName ).collect( Collectors.toSet() );
    }

    @Test
    void aNewVersionIsVisibleInTheNextListingAndInfoWithItsOwnSizeAndAuthor() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "12345" ) );
        Assertions.assertEquals( 5L, provider.listAttachments( page( PAGE ) ).get( 0 ).getSize() );
        Assertions.assertEquals( 1, provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION ).getVersion() );

        provider.putAttachmentData( attachment( PAGE, "a.txt", "bob" ), data( "1234567890" ) );

        final List< Attachment > listed = provider.listAttachments( page( PAGE ) );
        Assertions.assertEquals( 1, listed.size() );
        Assertions.assertEquals( 10L, listed.get( 0 ).getSize() );
        Assertions.assertEquals( 2, listed.get( 0 ).getVersion() );
        final Attachment info = provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION );
        Assertions.assertEquals( 2, info.getVersion() );
        Assertions.assertEquals( "bob", info.getAuthor() );
        Assertions.assertEquals( 10L, info.getSize() );
        Assertions.assertEquals( 1, provider.getAttachmentInfo( page( PAGE ), "a.txt", 1 ).getVersion() );
        Assertions.assertEquals( "alice", provider.getAttachmentInfo( page( PAGE ), "a.txt", 1 ).getAuthor() );
    }

    @Test
    void everyVersionsDataStaysReadable() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "first" ) );
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "second" ) );

        final Attachment v1 = provider.getAttachmentInfo( page( PAGE ), "a.txt", 1 );
        final Attachment v2 = provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION );

        Assertions.assertEquals( "first", read( provider.getAttachmentData( v1 ) ) );
        Assertions.assertEquals( "second", read( provider.getAttachmentData( v2 ) ) );
        Assertions.assertEquals( 2, provider.getVersionHistory( v2 ).size() );
    }

    @Test
    void savingStampsTheAttachmentWithTheTimeOfTheSave() throws Exception {
        final Attachment a = attachment( PAGE, "a.txt", "alice" );
        a.setLastModified( new Date( 0L ) );
        final long before = System.currentTimeMillis();

        provider.putAttachmentData( a, data( "x" ) );

        Assertions.assertTrue( a.getLastModified().getTime() >= before );
    }

    @Test
    void aMissingAttachmentOrPageHasNoInfo() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "x" ) );

        Assertions.assertNull( provider.getAttachmentInfo( page( PAGE ), "nope.txt", WikiProvider.LATEST_VERSION ) );
        Assertions.assertNull( provider.getAttachmentInfo( page( OTHER ), "a.txt", WikiProvider.LATEST_VERSION ) );
        Assertions.assertNull( provider.getAttachmentInfo( page( PAGE ), "nope.txt", 1 ) );
        Assertions.assertTrue( provider.listAttachments( page( OTHER ) ).isEmpty() );
    }

    @Test
    void aDeletedAttachmentIsGoneFromListingInfoAndTheChangeList() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "x" ) );
        provider.putAttachmentData( attachment( PAGE, "b.txt", "alice" ), data( "y" ) );
        Assertions.assertEquals( Set.of( "a.txt", "b.txt" ), fileNames( provider.listAttachments( page( PAGE ) ) ) );
        Assertions.assertNotNull( provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION ) );

        provider.deleteAttachment( attachment( PAGE, "a.txt", "alice" ) );

        Assertions.assertEquals( Set.of( "b.txt" ), fileNames( provider.listAttachments( page( PAGE ) ) ) );
        Assertions.assertNull( provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION ) );
        Assertions.assertFalse( fileNames( provider.listAllChanged( new Date( 0L ) ) ).contains( "a.txt" ) );
        Assertions.assertTrue( fileNames( provider.listAllChanged( new Date( 0L ) ) ).contains( "b.txt" ) );
    }

    @Test
    void deletingTheLastAttachmentLeavesAnEmptyListing() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "only.txt", "alice" ), data( "x" ) );
        Assertions.assertEquals( 1, provider.listAttachments( page( PAGE ) ).size() );

        provider.deleteAttachment( attachment( PAGE, "only.txt", "alice" ) );

        Assertions.assertTrue( provider.listAttachments( page( PAGE ) ).isEmpty() );
    }

    @Test
    void deletingTheLatestVersionBringsBackThePreviousOneEverywhere() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "first" ) );
        provider.putAttachmentData( attachment( PAGE, "a.txt", "bob" ), data( "second!" ) );
        Assertions.assertEquals( 2, provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION ).getVersion() );
        Assertions.assertEquals( 7L, provider.listAttachments( page( PAGE ) ).get( 0 ).getSize() );

        provider.deleteVersion( provider.getAttachmentInfo( page( PAGE ), "a.txt", 2 ) );

        final Attachment info = provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION );
        Assertions.assertEquals( 1, info.getVersion() );
        Assertions.assertEquals( "alice", info.getAuthor() );
        Assertions.assertEquals( 5L, provider.listAttachments( page( PAGE ) ).get( 0 ).getSize() );
        Assertions.assertEquals( "first", read( provider.getAttachmentData( info ) ) );
        Assertions.assertEquals( 1, provider.getVersionHistory( info ).size() );
    }

    @Test
    void movingAPagesAttachmentsMovesListingAndInfoWithThem() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "moved data" ) );
        provider.putAttachmentData( attachment( PAGE, "b.txt", "alice" ), data( "b" ) );
        Assertions.assertEquals( 2, provider.listAttachments( page( PAGE ) ).size() );
        Assertions.assertTrue( provider.listAttachments( page( OTHER ) ).isEmpty() );

        provider.moveAttachmentsForPage( PAGE, OTHER );

        Assertions.assertTrue( provider.listAttachments( page( PAGE ) ).isEmpty() );
        Assertions.assertEquals( Set.of( "a.txt", "b.txt" ), fileNames( provider.listAttachments( page( OTHER ) ) ) );
        Assertions.assertNull( provider.getAttachmentInfo( page( PAGE ), "a.txt", WikiProvider.LATEST_VERSION ) );
        final Attachment moved = provider.getAttachmentInfo( page( OTHER ), "a.txt", WikiProvider.LATEST_VERSION );
        Assertions.assertNotNull( moved );
        Assertions.assertEquals( "moved data", read( provider.getAttachmentData( moved ) ) );
    }

    @Test
    void theChangeListSinceATimeKeepsOnlyLaterAttachmentsAndEpochListsAll() throws Exception {
        provider.putAttachmentData( attachment( PAGE, "a.txt", "alice" ), data( "x" ) );
        provider.putAttachmentData( attachment( OTHER, "b.txt", "alice" ), data( "y" ) );

        Assertions.assertEquals( Set.of( "a.txt", "b.txt" ), fileNames( provider.listAllChanged( new Date( 0L ) ) ) );
        final Date future = new Date( System.currentTimeMillis() + 3_600_000L );
        final BasicAttachmentProvider raw = ( BasicAttachmentProvider ) provider.getRealProvider();
        Assertions.assertTrue( raw.listAllChanged( future ).isEmpty() );
        Assertions.assertEquals( Set.of( "a.txt", "b.txt" ), fileNames( raw.listAllChanged( new Date( 1L ) ) ) );
    }

    @Test
    void searchAndProviderInfoAreAnsweredByTheRealProvider() {
        Assertions.assertTrue( provider.findAttachments( new com.wikantik.api.search.QueryItem[ 0 ] ).isEmpty() );
        Assertions.assertTrue( provider.getProviderInfo().startsWith( "Real provider: " + BasicAttachmentProvider.class.getName() ) );
    }
}
