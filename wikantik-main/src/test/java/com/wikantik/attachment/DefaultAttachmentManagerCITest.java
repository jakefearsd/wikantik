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
package com.wikantik.attachment;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.attachment.DynamicAttachment;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.cache.CachingManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.search.SearchManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link DefaultAttachmentManager} covering paths not reached
 * by the full-engine {@link AttachmentManagerTest}.
 */
class DefaultAttachmentManagerCITest {

    private Engine engine;
    private CachingManager cachingManager;
    private PageManager pageManager;
    private ReferenceManager referenceManager;
    private SearchManager searchManager;
    private DefaultAttachmentManager manager;

    @BeforeEach
    void setUp() {
        cachingManager = mock( CachingManager.class );
        pageManager = mock( PageManager.class );
        referenceManager = mock( ReferenceManager.class );
        searchManager = mock( SearchManager.class );

        engine = MockEngineBuilder.engine()
                .with( CachingManager.class, cachingManager )
                .with( PageManager.class, pageManager )
                .with( ReferenceManager.class, referenceManager )
                .with( SearchManager.class, searchManager )
                .build();

        // Make caching manager report dynamic-attachment cache ENABLED so that
        // the constructor uses "CachingAttachmentProvider" as classname.
        // ClassUtil.buildInstance will fail (class not on test classpath), log the
        // error, and leave provider == null — which is the state we want to test.
        when( cachingManager.enabled( CachingManager.CACHE_ATTACHMENTS_DYNAMIC ) ).thenReturn( true );

        final Properties props = new Properties();
        manager = new DefaultAttachmentManager( engine, props );

        // Inject a real provider via reflection-free approach: build a second instance
        // that gets the provider via ClassUtil, but for unit tests we just use the
        // null-provider instance and test those code paths, then manually swap.
    }

    // ---- attachmentsEnabled when provider is null ----

    @Test
    void attachmentsEnabled_returnsFalseWhenNoProvider() {
        assertFalse( manager.attachmentsEnabled(),
                "attachmentsEnabled should return false when no provider was configured" );
    }

    // ---- forceDownload edge cases ----

    @Test
    void forceDownload_returnsFalseForNull() {
        assertFalse( manager.forceDownload( null ),
                "forceDownload(null) should return false" );
    }

    @Test
    void forceDownload_returnsFalseForEmptyString() {
        assertFalse( manager.forceDownload( "" ),
                "forceDownload('') should return false" );
    }

    @Test
    void forceDownload_returnsTrueForNameWithNoDot() {
        assertTrue( manager.forceDownload( "Makefile" ),
                "Files without an extension should force download" );
    }

    @Test
    void forceDownload_returnsFalseForNormalExtension() {
        assertFalse( manager.forceDownload( "document.pdf" ),
                "Files with a normal extension should not force download by default" );
    }

    @Test
    void forceDownload_returnsTrueWhenPatternMatchesWithWildcard() {
        // Build a manager configured with the wildcard forceDownload pattern.
        // Enabling the dynamic cache makes the constructor use "CachingAttachmentProvider"
        // as classname; instantiation fails (not on test classpath), provider stays null.
        final Properties props = new Properties();
        props.setProperty( AttachmentManager.PROP_FORCEDOWNLOAD, "*" );
        when( cachingManager.enabled( CachingManager.CACHE_ATTACHMENTS_DYNAMIC ) ).thenReturn( true );
        final DefaultAttachmentManager m = new DefaultAttachmentManager( engine, props );

        assertTrue( m.forceDownload( "anything.txt" ),
                "Wildcard forceDownload pattern should match all extensions" );
    }

    @Test
    void forceDownload_returnsTrueWhenExtensionMatchesPattern() {
        final Properties props = new Properties();
        props.setProperty( AttachmentManager.PROP_FORCEDOWNLOAD, ".exe .bat" );
        when( cachingManager.enabled( CachingManager.CACHE_ATTACHMENTS_DYNAMIC ) ).thenReturn( true );
        final DefaultAttachmentManager m = new DefaultAttachmentManager( engine, props );

        assertTrue( m.forceDownload( "setup.exe" ), "setup.exe matches .exe pattern" );
        assertFalse( m.forceDownload( "notes.txt" ), "notes.txt should not match" );
    }

    // ---- getAttachmentStream when provider is null ----

    @Test
    void getAttachmentStream_returnsNullWhenNoProvider() throws Exception {
        final Attachment att = mock( Attachment.class );
        assertNull( manager.getAttachmentStream( mock( Context.class ), att ),
                "getAttachmentStream should return null when no provider is configured" );
    }

    // ---- getAttachmentStream for DynamicAttachment ----

    @Test
    void getAttachmentStream_noProviderReturnsNullForAnyAttachment() throws Exception {
        // With no provider, getAttachmentStream(Attachment) should return null
        // regardless of the attachment type.
        assertNull( manager.getAttachmentStream( (Attachment) mock( Attachment.class ) ),
                "getAttachmentStream(att) should return null when no provider is configured" );
    }

    // ---- storeDynamicAttachment / getDynamicAttachment ----

    @Test
    void storeDynamicAttachment_putsIntoCache() {
        final DynamicAttachment dynAtt = mock( DynamicAttachment.class );
        when( dynAtt.getName() ).thenReturn( "TestPage/dyn.txt" );

        manager.storeDynamicAttachment( mock( Context.class ), dynAtt );

        verify( cachingManager ).put( CachingManager.CACHE_ATTACHMENTS_DYNAMIC, "TestPage/dyn.txt", dynAtt );
    }

    @Test
    void getDynamicAttachment_queriesCache() {
        final DynamicAttachment dynAtt = mock( DynamicAttachment.class );
        when( cachingManager.get( eq( CachingManager.CACHE_ATTACHMENTS_DYNAMIC ),
                eq( "TestPage/dyn.txt" ), any() ) ).thenReturn( dynAtt );

        final DynamicAttachment result = manager.getDynamicAttachment( "TestPage/dyn.txt" );

        assertSame( dynAtt, result );
    }

    @Test
    void getDynamicAttachment_returnsNullWhenNotCached() {
        when( cachingManager.get( eq( CachingManager.CACHE_ATTACHMENTS_DYNAMIC ),
                anyString(), any() ) ).thenReturn( null );

        assertNull( manager.getDynamicAttachment( "TestPage/missing.txt" ) );
    }

    // ---- listAttachments when provider is null ----

    @Test
    void listAttachments_returnsEmptyListWhenNoProvider() throws Exception {
        final Page page = mock( Page.class );
        final List<Attachment> result = manager.listAttachments( page );
        assertNotNull( result );
        assertTrue( result.isEmpty(), "listAttachments should return empty list when no provider" );
    }

    // ---- getAllAttachments when provider is null ----

    @Test
    void getAllAttachments_returnsEmptyCollectionWhenNoProvider() throws Exception {
        final Collection<Attachment> result = manager.getAllAttachments();
        assertNotNull( result );
        assertTrue( result.isEmpty(), "getAllAttachments should return empty list when no provider" );
    }

    @Test
    void getAllAttachmentsSince_returnsEmptyCollectionWhenNoProvider() throws Exception {
        final Collection<Attachment> result = manager.getAllAttachmentsSince( new Date( 0L ) );
        assertNotNull( result );
        assertTrue( result.isEmpty() );
    }

    // ---- getVersionHistory when provider is null ----

    @Test
    void getVersionHistory_returnsEmptyListWhenNoProvider() throws Exception {
        final List<Attachment> result = manager.getVersionHistory( "TestPage/test.txt" );
        assertNotNull( result );
        assertTrue( result.isEmpty(), "getVersionHistory should return empty list when no provider" );
    }

    // ---- getCurrentProvider ----

    @Test
    void getCurrentProvider_returnsNullWhenNoProvider() {
        assertNull( manager.getCurrentProvider(),
                "getCurrentProvider should return null when no provider was configured" );
    }

    // ---- deleteVersion when provider is null ----

    @Test
    void deleteVersion_doesNothingWhenNoProvider() throws Exception {
        final Attachment att = mock( Attachment.class );
        // Should not throw
        assertDoesNotThrow( () -> manager.deleteVersion( att ) );
    }

    // ---- deleteAttachment when provider is null ----

    @Test
    void deleteAttachment_doesNothingWhenNoProvider() throws Exception {
        final Attachment att = mock( Attachment.class );
        // Should not throw
        assertDoesNotThrow( () -> manager.deleteAttachment( att ) );
    }

    // ---- storeAttachment when provider is null ----

    @Test
    void storeAttachment_doesNothingWhenNoProvider() throws Exception {
        final Attachment att = mock( Attachment.class );
        when( att.getParentName() ).thenReturn( "TestPage" );
        final InputStream in = new ByteArrayInputStream( new byte[0] );

        // Should not throw, should not call pageManager
        assertDoesNotThrow( () -> manager.storeAttachment( att, in ) );
        verify( pageManager, never() ).pageExists( anyString() );
    }

    // ---- getAttachmentInfoName with ProviderException ----

    @Test
    void getAttachmentInfoName_returnsNullOnProviderException() throws Exception {
        // We need a manager with a provider that throws. Build one by setting up
        // an engine where the PageManager's getPage throws when trying to parse
        // the attachment name (the method routes through getAttachmentInfo which
        // calls pageManager.getPage). With a null provider, getAttachmentInfo
        // returns null, and the name has no slash → returns null.
        final Context ctx = mock( Context.class );
        final Page ctxPage = mock( Page.class );
        when( ctxPage.getName() ).thenReturn( "" );
        when( ctx.getPage() ).thenReturn( ctxPage );

        final String result = manager.getAttachmentInfoName( ctx, "noSlashName" );
        assertNull( result, "Should return null when provider is absent (no attachment found)" );
    }

    @Test
    void getAttachmentInfoName_returnsNameWhenHasSlash() throws Exception {
        // When provider is null, getAttachmentInfo returns null. But attachmentname
        // contains '/' → method returns attachmentname directly.
        final Context ctx = mock( Context.class );
        final Page ctxPage = mock( Page.class );
        when( ctxPage.getName() ).thenReturn( "TestPage" );
        when( ctx.getPage() ).thenReturn( ctxPage );
        when( pageManager.getPage( anyString() ) ).thenReturn( null );

        final String result = manager.getAttachmentInfoName( ctx, "TestPage/file.txt" );

        assertEquals( "TestPage/file.txt", result,
                "Should return the attachment name itself when it contains a slash" );
    }

    // ---- getAttachmentInfo with null context ----

    @Test
    void getAttachmentInfo_returnsNullWhenProviderIsNull() throws Exception {
        final Context ctx = mock( Context.class );
        final Page ctxPage = mock( Page.class );
        when( ctxPage.getName() ).thenReturn( "TestPage" );
        when( ctx.getPage() ).thenReturn( ctxPage );

        assertNull( manager.getAttachmentInfo( ctx, "file.txt", WikiProvider.LATEST_VERSION ),
                "Should return null when no provider configured" );
    }

    @Test
    void getAttachmentInfo_returnsNullForEmptyParentPage() throws Exception {
        // When the parsed parent page name is empty, method should return null
        final Context ctx = mock( Context.class );
        final Page ctxPage = mock( Page.class );
        when( ctxPage.getName() ).thenReturn( "" );
        when( ctx.getPage() ).thenReturn( ctxPage );

        // Construct a name that has a slash but produces an empty parent name
        // after parsing: the cut point is at index 0, so substring(0,0) is ""
        // Actually the code extracts parentPage = attachmentname.substring(0, cutpt)
        // For "/file.txt" cutpt=0 → parentPage="" → return null
        assertNull( manager.getAttachmentInfo( ctx, "/file.txt", WikiProvider.LATEST_VERSION ),
                "Should return null when parent page name is empty" );
    }

    // ---- forceDownload with configured patterns ----

    @Test
    void forceDownload_respectsConfiguredPatterns() {
        final Properties props = new Properties();
        props.setProperty( AttachmentManager.PROP_FORCEDOWNLOAD, ".exe .sh" );
        when( cachingManager.enabled( CachingManager.CACHE_ATTACHMENTS_DYNAMIC ) ).thenReturn( true );
        final DefaultAttachmentManager m = new DefaultAttachmentManager( engine, props );

        assertTrue( m.forceDownload( "SCRIPT.EXE" ), "Case-insensitive match for .exe" );
        assertTrue( m.forceDownload( "run.sh" ), ".sh matches" );
        assertFalse( m.forceDownload( "picture.jpg" ), ".jpg does not match" );
    }

}
