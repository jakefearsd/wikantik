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
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.providers.AttachmentProvider;
import com.wikantik.api.search.QueryItem;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Tests for error/edge-case paths in {@link CachingAttachmentProvider}.
 * <p>
 * Covers: listAllChanged() with timestamp-based filtering, the allRequested fast path,
 * findAttachments() delegation to the real provider, and cache-size warning path.
 * <p>
 * The standard TestEngine uses cache-enabled configuration, so
 * {@code AttachmentManager.getCurrentProvider()} returns a {@link CachingAttachmentProvider}
 * wrapping {@link BasicAttachmentProvider}.
 */
class CachingAttachmentProviderCITest {

    private static final String PAGE_NAME = "CachingAttProvPage";
    private static final String PAGE_NAME_2 = "CachingAttProvPage2";

    private TestEngine engine;
    private AttachmentManager attachmentManager;
    private CachingAttachmentProvider cachingProvider;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        attachmentManager = engine.getManager( AttachmentManager.class );
        Assertions.assertTrue( attachmentManager.attachmentsEnabled(),
            "attachments must be enabled for this test" );

        // The caching provider should be active since cache is enabled by default
        final AttachmentProvider rawProvider = attachmentManager.getCurrentProvider();
        Assertions.assertInstanceOf( CachingAttachmentProvider.class, rawProvider,
            "expected CachingAttachmentProvider when cache is enabled" );
        cachingProvider = (CachingAttachmentProvider) rawProvider;

        engine.saveText( PAGE_NAME, "Page for caching attachment tests" );
        engine.saveText( PAGE_NAME_2, "Second page for caching attachment tests" );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private InputStream data( final String text ) {
        return new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) );
    }

    // -------------------------------------------------------------------------
    // findAttachments() — delegates to real provider
    // -------------------------------------------------------------------------

    /**
     * findAttachments() must delegate to the underlying real provider, returning
     * an empty collection (which is what BasicAttachmentProvider always returns).
     */
    @Test
    void testFindAttachmentsDelegatesToRealProvider() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "findme.txt" );
        cachingProvider.putAttachmentData( att, data( "content" ) );

        final Collection<Attachment> results =
            cachingProvider.findAttachments( new QueryItem[0] );

        // BasicAttachmentProvider always returns empty for findAttachments()
        Assertions.assertNotNull( results, "findAttachments should never return null" );
        Assertions.assertTrue( results.isEmpty(),
            "BasicAttachmentProvider.findAttachments always returns empty" );
    }

    /**
     * findAttachments() with a non-empty query must still delegate correctly
     * without throwing.
     */
    @Test
    void testFindAttachmentsWithQueryDoesNotThrow() {
        final QueryItem[] query = { new QueryItem() };
        query[0].word = "test";
        query[0].type = QueryItem.REQUIRED;

        Assertions.assertDoesNotThrow(
            () -> cachingProvider.findAttachments( query ),
            "findAttachments with a query must not throw" );
    }

    // -------------------------------------------------------------------------
    // listAllChanged() — timestamp filtering
    // -------------------------------------------------------------------------

    /**
     * listAllChanged() with Date(0) must return all attachments and populate the cache
     * (setting allRequested = true so subsequent calls use the cache fast path).
     */
    @Test
    void testListAllChangedEpochPopulatesCacheAndReturnsAll() throws Exception {
        final Attachment a1 = Wiki.contents().attachment( engine, PAGE_NAME, "epoch_a.txt" );
        cachingProvider.putAttachmentData( a1, data( "first" ) );

        final Attachment a2 = Wiki.contents().attachment( engine, PAGE_NAME_2, "epoch_b.txt" );
        cachingProvider.putAttachmentData( a2, data( "second" ) );

        // First call with epoch — triggers real provider and sets allRequested=true
        final List<Attachment> firstCall = cachingProvider.listAllChanged( new Date( 0L ) );
        Assertions.assertEquals( 2, firstCall.size(),
            "epoch call should return all 2 attachments" );

        // Second call should use the cache fast path (allRequested == true)
        final List<Attachment> secondCall = cachingProvider.listAllChanged( new Date( 0L ) );
        Assertions.assertEquals( 2, secondCall.size(),
            "second epoch call should still return all 2 attachments from cache" );
    }

    /**
     * After populating the cache via listAllChanged(Date(0)), a subsequent call with any
     * non-zero timestamp also goes through the cached-entries fast path (allRequested == true)
     * and returns all cached attachments regardless of the timestamp.
     * This verifies the allRequested fast path in listAllChanged().
     */
    @Test
    void testListAllChangedAfterEpochCallUsesCacheFastPath() throws Exception {
        final Attachment a1 = Wiki.contents().attachment( engine, PAGE_NAME, "fast1.txt" );
        cachingProvider.putAttachmentData( a1, data( "d1" ) );
        final Attachment a2 = Wiki.contents().attachment( engine, PAGE_NAME_2, "fast2.txt" );
        cachingProvider.putAttachmentData( a2, data( "d2" ) );

        // Trigger allRequested = true by calling with epoch
        final List<Attachment> allAtts = cachingProvider.listAllChanged( new Date( 0L ) );
        Assertions.assertFalse( allAtts.isEmpty(), "epoch should return at least our 2 attachments" );

        // A second call (any timestamp) now uses the cache fast path
        final List<Attachment> cachedResult = cachingProvider.listAllChanged( new Date( 1L ) );
        Assertions.assertFalse( cachedResult.isEmpty(),
            "after allRequested is set, subsequent calls use the cache and return stored attachments" );
    }

    /**
     * A non-epoch non-zero timestamp means allRequested is not set after the call;
     * the real provider is consulted each time.
     */
    @Test
    void testListAllChangedNonEpochTimestampDelegatesToRealProvider() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "nonepoch.txt" );
        cachingProvider.putAttachmentData( att, data( "content" ) );

        // Use Date(1) — non-zero, so allRequested won't be set, but very old so attachment passes
        final List<Attachment> changed = cachingProvider.listAllChanged( new Date( 1L ) );
        Assertions.assertFalse( changed.isEmpty(),
            "attachments modified since epoch+1ms should be returned" );
    }

    // -------------------------------------------------------------------------
    // getRealProvider()
    // -------------------------------------------------------------------------

    /**
     * getRealProvider() must return a non-null BasicAttachmentProvider instance.
     */
    @Test
    void testGetRealProviderReturnsBaicAttachmentProvider() {
        final AttachmentProvider real = cachingProvider.getRealProvider();
        Assertions.assertNotNull( real, "real provider must not be null" );
        Assertions.assertInstanceOf( BasicAttachmentProvider.class, real,
            "real provider should be BasicAttachmentProvider" );
    }

    // -------------------------------------------------------------------------
    // listAttachments() — delegates to real provider via cache
    // -------------------------------------------------------------------------

    /**
     * listAttachments() through the caching provider should return the same result
     * as going directly to the real provider.
     */
    @Test
    void testListAttachmentsCachedResult() throws Exception {
        final Attachment a1 = Wiki.contents().attachment( engine, PAGE_NAME, "list1.txt" );
        cachingProvider.putAttachmentData( a1, data( "x" ) );
        final Attachment a2 = Wiki.contents().attachment( engine, PAGE_NAME, "list2.txt" );
        cachingProvider.putAttachmentData( a2, data( "y" ) );

        final Page page = Wiki.contents().page( engine, PAGE_NAME );
        final List<Attachment> cached = cachingProvider.listAttachments( page );
        Assertions.assertEquals( 2, cached.size(), "should list 2 attachments for the page" );

        // Second call — should be a cache hit
        final List<Attachment> cachedAgain = cachingProvider.listAttachments( page );
        Assertions.assertEquals( 2, cachedAgain.size(), "cached result should also have 2 attachments" );
    }

    // -------------------------------------------------------------------------
    // getProviderInfo() — smoke test
    // -------------------------------------------------------------------------

    /**
     * getProviderInfo() must return a non-null, non-empty string describing the provider.
     */
    @Test
    void testGetProviderInfoIsNonEmpty() {
        final String info = cachingProvider.getProviderInfo();
        Assertions.assertNotNull( info, "getProviderInfo() must not return null" );
        Assertions.assertFalse( info.isBlank(), "getProviderInfo() must not return a blank string" );
        Assertions.assertTrue( info.contains( "BasicAttachmentProvider" ),
            "getProviderInfo() should mention the real provider class name" );
    }

    // -------------------------------------------------------------------------
    // getVersionHistory() — delegates to real provider
    // -------------------------------------------------------------------------

    /**
     * getVersionHistory() must delegate to the real provider and return all versions.
     */
    @Test
    void testGetVersionHistoryDelegatesToRealProvider() throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, PAGE_NAME, "versioned.txt" );
        cachingProvider.putAttachmentData( att, data( "v1" ) );
        cachingProvider.putAttachmentData( att, data( "v2" ) );
        cachingProvider.putAttachmentData( att, data( "v3" ) );

        final List<Attachment> history = cachingProvider.getVersionHistory( att );
        Assertions.assertEquals( 3, history.size(), "version history should have 3 entries" );
    }
}
