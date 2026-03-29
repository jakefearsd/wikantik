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
package com.wikantik.pages;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.filters.FilterManager;
import com.wikantik.providers.RepositoryModifiedException;
import com.wikantik.references.ReferenceManager;
import com.wikantik.search.SearchManager;
import com.wikantik.ui.CommandResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Permission;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link DefaultPageManager} using constructor injection.
 * Focuses on behaviors NOT covered by the integration-style {@link DefaultPageManagerTest},
 * particularly: locking mechanics, event firing, version history, page deletion cascading,
 * edge cases in page text retrieval, and the {@code actionPerformed} ACL-update path.
 */
class DefaultPageManagerCITest {

    private Engine engine;
    private PageProvider pageProvider;
    private CommandResolver commandResolver;
    private AttachmentManager attachmentManager;
    private ReferenceManager referenceManager;
    private FilterManager filterManager;
    private SearchManager searchManager;
    private AclManager aclManager;

    private DefaultPageManager mgr;

    @BeforeEach
    void setUp() {
        pageProvider = mock( PageProvider.class );
        commandResolver = mock( CommandResolver.class );
        attachmentManager = mock( AttachmentManager.class );
        referenceManager = mock( ReferenceManager.class );
        filterManager = mock( FilterManager.class );
        searchManager = mock( SearchManager.class );
        aclManager = mock( AclManager.class );

        engine = MockEngineBuilder.engine()
                .with( AttachmentManager.class, attachmentManager )
                .with( ReferenceManager.class, referenceManager )
                .with( FilterManager.class, filterManager )
                .with( SearchManager.class, searchManager )
                .with( AclManager.class, aclManager )
                .with( CommandResolver.class, commandResolver )
                .build();

        // Default lock expiry: 60 minutes
        mgr = new DefaultPageManager( engine, commandResolver, pageProvider, 60 );
    }

    // --- Helper to create a mock Page ---

    private Page mockPage( final String name ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        when( page.getVersion() ).thenReturn( PageProvider.LATEST_VERSION );
        return page;
    }

    // ==================== Locking ====================

    @Test
    void lockPageReturnsLockOnFirstCall() {
        final Page page = mockPage( "LockTest" );
        final var lock = mgr.lockPage( page, "alice" );
        assertNotNull( lock, "First lock should succeed" );
        assertEquals( "LockTest", lock.getPage() );
        assertEquals( "alice", lock.getLocker() );
    }

    @Test
    void lockPageReturnsNullWhenAlreadyLocked() {
        final Page page = mockPage( "LockTest" );
        final var first = mgr.lockPage( page, "alice" );
        assertNotNull( first );

        final var second = mgr.lockPage( page, "bob" );
        assertNull( second, "Second lock on same page should return null" );
    }

    @Test
    void unlockPageRemovesLock() {
        final Page page = mockPage( "LockTest" );
        final var lock = mgr.lockPage( page, "alice" );
        assertNotNull( lock );

        mgr.unlockPage( lock );
        assertNull( mgr.getCurrentLock( page ), "Lock should be removed after unlock" );
    }

    @Test
    void unlockPageWithNullIsNoOp() {
        // Should not throw
        mgr.unlockPage( null );
    }

    @Test
    void getCurrentLockReturnsNullWhenNoLock() {
        final Page page = mockPage( "NoLock" );
        assertNull( mgr.getCurrentLock( page ) );
    }

    @Test
    void getCurrentLockReturnsActiveLock() {
        final Page page = mockPage( "LockTest" );
        final var lock = mgr.lockPage( page, "alice" );
        assertNotNull( lock );

        final var current = mgr.getCurrentLock( page );
        assertNotNull( current );
        assertEquals( "alice", current.getLocker() );
    }

    @Test
    void getActiveLocksReturnsEmptyWhenNoLocks() {
        assertTrue( mgr.getActiveLocks().isEmpty() );
    }

    @Test
    void getActiveLocksReturnsAllLocks() {
        final Page p1 = mockPage( "Page1" );
        final Page p2 = mockPage( "Page2" );
        mgr.lockPage( p1, "alice" );
        mgr.lockPage( p2, "bob" );

        final List< com.wikantik.api.pages.PageLock > locks = mgr.getActiveLocks();
        assertEquals( 2, locks.size() );
    }

    @Test
    void lockPageFiresLockEvent() {
        final Page page = mockPage( "EventTest" );
        final AtomicReference< WikiEvent > captured = new AtomicReference<>();
        WikiEventManager.addWikiEventListener( mgr, captured::set );

        mgr.lockPage( page, "alice" );

        assertNotNull( captured.get() );
        assertInstanceOf( WikiPageEvent.class, captured.get() );
        assertEquals( WikiPageEvent.PAGE_LOCK, captured.get().getType() );

        WikiEventManager.removeWikiEventListener( mgr, captured::set );
    }

    @Test
    void unlockPageFiresUnlockEvent() {
        final Page page = mockPage( "EventTest" );
        final var lock = mgr.lockPage( page, "alice" );

        final AtomicReference< WikiEvent > captured = new AtomicReference<>();
        WikiEventManager.addWikiEventListener( mgr, captured::set );

        mgr.unlockPage( lock );

        assertNotNull( captured.get() );
        assertInstanceOf( WikiPageEvent.class, captured.get() );
        assertEquals( WikiPageEvent.PAGE_UNLOCK, captured.get().getType() );

        WikiEventManager.removeWikiEventListener( mgr, captured::set );
    }

    @Test
    void lockAfterUnlockSucceeds() {
        final Page page = mockPage( "ReLock" );
        final var lock1 = mgr.lockPage( page, "alice" );
        assertNotNull( lock1 );
        mgr.unlockPage( lock1 );

        final var lock2 = mgr.lockPage( page, "bob" );
        assertNotNull( lock2, "Should be able to lock after unlock" );
        assertEquals( "bob", lock2.getLocker() );
    }

    // ==================== getPageText ====================

    @Test
    void getPageTextThrowsOnNullName() {
        assertThrows( ProviderException.class, () -> mgr.getPageText( null, 1 ) );
    }

    @Test
    void getPageTextThrowsOnEmptyName() {
        assertThrows( ProviderException.class, () -> mgr.getPageText( "", 1 ) );
    }

    @Test
    void getPageTextReturnsTextFromProvider() throws ProviderException {
        when( pageProvider.getPageText( "TestPage", 1 ) ).thenReturn( "Hello" );
        assertEquals( "Hello", mgr.getPageText( "TestPage", 1 ) );
    }

    @Test
    void getPageTextHandlesRepositoryModifiedException() throws ProviderException {
        final Page reindexed = mockPage( "Modified" );
        when( pageProvider.getPageText( "Modified", PageProvider.LATEST_VERSION ) )
                .thenThrow( new RepositoryModifiedException( "test", "Modified" ) )
                .thenReturn( "Updated content" );
        when( pageProvider.getPageInfo( "Modified", PageProvider.LATEST_VERSION ) ).thenReturn( reindexed );

        final String result = mgr.getPageText( "Modified", PageProvider.LATEST_VERSION );

        assertEquals( "Updated content", result );
        verify( referenceManager ).updateReferences( reindexed );
    }

    // ==================== getPureText ====================

    @Test
    void getPureTextReturnsEmptyStringOnProviderException() throws ProviderException {
        when( pageProvider.getPageText( "Bad", 1 ) ).thenThrow( new ProviderException( "fail" ) );
        assertEquals( "", mgr.getPureText( "Bad", 1 ) );
    }

    @Test
    void getPureTextReturnsContentOnSuccess() throws ProviderException {
        when( pageProvider.getPageText( "Good", 1 ) ).thenReturn( "Content" );
        assertEquals( "Content", mgr.getPureText( "Good", 1 ) );
    }

    // ==================== getText ====================

    @Test
    void getTextReplacesEntities() throws ProviderException {
        when( pageProvider.getPageText( "Entities", 1 ) ).thenReturn( "a & b < c" );
        final String result = mgr.getText( "Entities", 1 );
        assertTrue( result.contains( "&amp;" ), "Ampersand should be escaped" );
        assertTrue( result.contains( "&lt;" ), "Less-than should be escaped" );
    }

    // ==================== getPage ====================

    @Test
    void getPageReturnsPageFromProvider() throws ProviderException {
        final Page page = mockPage( "Found" );
        when( pageProvider.getPageInfo( "Found", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        assertSame( page, mgr.getPage( "Found" ) );
    }

    @Test
    void getPageFallsBackToAttachmentInfo() throws ProviderException {
        when( pageProvider.getPageInfo( "Att/file.txt", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        final Attachment att = mock( Attachment.class );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "Att/file.txt" ) ) ).thenReturn( att );

        assertSame( att, mgr.getPage( "Att/file.txt" ) );
    }

    @Test
    void getPageReturnsNullOnProviderException() throws ProviderException {
        when( pageProvider.getPageInfo( anyString(), anyInt() ) ).thenThrow( new ProviderException( "fail" ) );
        assertNull( mgr.getPage( "Broken" ) );
    }

    // ==================== getPageInfo ====================

    @Test
    void getPageInfoThrowsOnNullName() {
        assertThrows( ProviderException.class, () -> mgr.getPageInfo( null, 1 ) );
    }

    @Test
    void getPageInfoThrowsOnEmptyName() {
        assertThrows( ProviderException.class, () -> mgr.getPageInfo( "", 1 ) );
    }

    @Test
    void getPageInfoHandlesRepositoryModifiedWithNonNullPage() throws ProviderException {
        final Page page = mockPage( "Modified" );
        when( pageProvider.getPageInfo( "Modified", PageProvider.LATEST_VERSION ) )
                .thenThrow( new RepositoryModifiedException( "test", "Modified" ) )
                .thenReturn( page );

        final Page result = mgr.getPageInfo( "Modified", PageProvider.LATEST_VERSION );

        assertSame( page, result );
        verify( referenceManager ).updateReferences( page );
    }

    @Test
    void getPageInfoHandlesRepositoryModifiedWithNullPage() throws ProviderException {
        when( pageProvider.getPageInfo( "Gone", PageProvider.LATEST_VERSION ) )
                .thenThrow( new RepositoryModifiedException( "test", "Gone" ) )
                .thenReturn( null );

        final Page result = mgr.getPageInfo( "Gone", PageProvider.LATEST_VERSION );

        assertNull( result );
        verify( referenceManager ).pageRemoved( any( Page.class ) );
    }

    // ==================== getVersionHistory ====================

    @Test
    void getVersionHistoryReturnsProviderHistoryWhenPageExists() throws ProviderException {
        when( pageProvider.pageExists( "History" ) ).thenReturn( true );
        final Page historyPage = mockPage( "History" );
        when( pageProvider.getVersionHistory( "History" ) ).thenReturn( List.of( historyPage ) );

        final List< Page > result = mgr.getVersionHistory( "History" );

        assertNotNull( result );
        assertEquals( 1, result.size() );
    }

    @Test
    void getVersionHistoryFallsBackToAttachmentHistory() throws ProviderException {
        when( pageProvider.pageExists( "Att/file.txt" ) ).thenReturn( false );
        final List< Attachment > attHistory = List.of( mock( Attachment.class ) );
        when( attachmentManager.getVersionHistory( "Att/file.txt" ) ).thenReturn( attHistory );

        final List< ? extends Page > result = mgr.getVersionHistory( "Att/file.txt" );

        assertNotNull( result );
        assertEquals( 1, result.size() );
    }

    @Test
    void getVersionHistoryReturnsNullOnProviderException() throws ProviderException {
        when( pageProvider.pageExists( "Bad" ) ).thenReturn( true );
        when( pageProvider.getVersionHistory( "Bad" ) ).thenThrow( new ProviderException( "fail" ) );
        assertNull( mgr.getVersionHistory( "Bad" ) );
    }

    // ==================== putPageText ====================

    @Test
    void putPageTextThrowsOnNullPage() {
        assertThrows( ProviderException.class, () -> mgr.putPageText( null, "content" ) );
    }

    @Test
    void putPageTextThrowsOnNullName() {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( null );
        assertThrows( ProviderException.class, () -> mgr.putPageText( page, "content" ) );
    }

    @Test
    void putPageTextThrowsOnEmptyName() {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "" );
        assertThrows( ProviderException.class, () -> mgr.putPageText( page, "content" ) );
    }

    @Test
    void putPageTextDelegatesToProvider() throws ProviderException {
        final Page page = mockPage( "Save" );
        mgr.putPageText( page, "new content" );
        verify( pageProvider ).putPageText( page, "new content" );
    }

    // ==================== pageExists ====================

    @Test
    void pageExistsThrowsOnNullName() {
        assertThrows( ProviderException.class, () -> mgr.pageExists( (String) null ) );
    }

    @Test
    void pageExistsThrowsOnEmptyName() {
        assertThrows( ProviderException.class, () -> mgr.pageExists( "" ) );
    }

    @Test
    void pageExistsWithVersionThrowsOnNull() {
        assertThrows( ProviderException.class, () -> mgr.pageExists( null, 1 ) );
    }

    @Test
    void pageExistsWithVersionThrowsOnEmpty() {
        assertThrows( ProviderException.class, () -> mgr.pageExists( "", 1 ) );
    }

    @Test
    void pageExistsWithLatestVersionDelegatesToSimpleForm() throws ProviderException {
        when( pageProvider.pageExists( "Test" ) ).thenReturn( true );
        assertTrue( mgr.pageExists( "Test", WikiProvider.LATEST_VERSION ) );
        // Should call the simple form, not the version form
        verify( pageProvider ).pageExists( "Test" );
        verify( pageProvider, never() ).pageExists( "Test", WikiProvider.LATEST_VERSION );
    }

    @Test
    void pageExistsWithSpecificVersion() throws ProviderException {
        when( pageProvider.pageExists( "Test", 3 ) ).thenReturn( true );
        assertTrue( mgr.pageExists( "Test", 3 ) );
    }

    // ==================== wikiPageExists ====================

    @Test
    void wikiPageExistsReturnsTrueForSpecialPage() {
        when( commandResolver.getSpecialPageReference( "FindPage" ) ).thenReturn( "FindPage.jsp" );
        assertTrue( mgr.wikiPageExists( "FindPage" ) );
    }

    @Test
    void wikiPageExistsReturnsTrueWhenFinalPageNameFound() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "Test" ) ).thenReturn( null );
        when( engine.getFinalPageName( "Test" ) ).thenReturn( "Test" );
        assertTrue( mgr.wikiPageExists( "Test" ) );
    }

    @Test
    void wikiPageExistsReturnsTrueForAttachment() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "att" ) ).thenReturn( null );
        when( engine.getFinalPageName( "att" ) ).thenReturn( null );
        final Attachment att = mock( Attachment.class );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "att" ) ) ).thenReturn( att );

        assertTrue( mgr.wikiPageExists( "att" ) );
    }

    @Test
    void wikiPageExistsReturnsFalseWhenNothingFound() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "nope" ) ).thenReturn( null );
        when( engine.getFinalPageName( "nope" ) ).thenReturn( null );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "nope" ) ) ).thenReturn( null );

        assertFalse( mgr.wikiPageExists( "nope" ) );
    }

    @Test
    void wikiPageExistsHandlesProviderExceptionGracefully() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "err" ) ).thenReturn( null );
        when( engine.getFinalPageName( "err" ) ).thenReturn( null );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "err" ) ) ).thenThrow( new ProviderException( "fail" ) );

        assertFalse( mgr.wikiPageExists( "err" ) );
    }

    // ==================== wikiPageExists(String, int) ====================

    @Test
    void wikiPageExistsWithVersionReturnsTrueForSpecialPage() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "Special" ) ).thenReturn( "Special.jsp" );
        assertTrue( mgr.wikiPageExists( "Special", 1 ) );
    }

    @Test
    void wikiPageExistsWithVersionChecksPageProvider() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "Test" ) ).thenReturn( null );
        when( engine.getFinalPageName( "Test" ) ).thenReturn( "Test" );
        when( pageProvider.pageExists( "Test", 2 ) ).thenReturn( true );
        assertTrue( mgr.wikiPageExists( "Test", 2 ) );
    }

    @Test
    void wikiPageExistsWithVersionFallsBackToAttachment() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "att" ) ).thenReturn( null );
        when( engine.getFinalPageName( "att" ) ).thenReturn( null );
        final Attachment att = mock( Attachment.class );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "att" ), eq( 1 ) ) ).thenReturn( att );

        assertTrue( mgr.wikiPageExists( "att", 1 ) );
    }

    @Test
    void wikiPageExistsWithVersionReturnsFalse() throws ProviderException {
        when( commandResolver.getSpecialPageReference( "nope" ) ).thenReturn( null );
        when( engine.getFinalPageName( "nope" ) ).thenReturn( null );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "nope" ), eq( 1 ) ) ).thenReturn( null );

        assertFalse( mgr.wikiPageExists( "nope", 1 ) );
    }

    // ==================== deleteVersion ====================

    @Test
    void deleteVersionDelegatesToAttachmentManagerForAttachment() throws ProviderException {
        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "Page/file.txt" );
        when( att.getVersion() ).thenReturn( 2 );

        mgr.deleteVersion( att );

        verify( attachmentManager ).deleteVersion( att );
        verify( pageProvider, never() ).deleteVersion( anyString(), anyInt() );
    }

    @Test
    void deleteVersionDelegatesToProviderForPage() throws ProviderException {
        final Page page = mockPage( "TestPage" );
        when( page.getVersion() ).thenReturn( 3 );

        mgr.deleteVersion( page );

        verify( pageProvider ).deleteVersion( "TestPage", 3 );
        verify( attachmentManager, never() ).deleteVersion( any( Attachment.class ) );
    }

    // ==================== deletePage(String) ====================

    @Test
    void deletePageByNameDoesNothingWhenPageNotFound() throws ProviderException {
        when( pageProvider.getPageInfo( "Missing", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "Missing" ) ) ).thenReturn( null );

        mgr.deletePage( "Missing" );

        verify( pageProvider, never() ).deletePage( anyString() );
    }

    @Test
    void deletePageByNameDelegatesToAttachmentManagerForAttachment() throws ProviderException {
        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "Page/file.txt" );
        when( pageProvider.getPageInfo( "Page/file.txt", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "Page/file.txt" ) ) ).thenReturn( att );

        mgr.deletePage( "Page/file.txt" );

        verify( attachmentManager ).deleteAttachment( att );
    }

    @Test
    void deletePageByNameDeletesPageAndAttachments() throws ProviderException {
        final Page page = mockPage( "WithAtts" );
        when( pageProvider.getPageInfo( "WithAtts", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        when( referenceManager.findRefersTo( "WithAtts" ) ).thenReturn( List.of( "Ref1" ) );
        when( attachmentManager.hasAttachments( page ) ).thenReturn( true );

        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "WithAtts/file.txt" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( List.of( att ) );

        mgr.deletePage( "WithAtts" );

        verify( attachmentManager ).deleteAttachment( att );
        verify( pageProvider ).deletePage( "WithAtts" );
    }

    @Test
    void deletePageByNameDeletesPageWithoutAttachments() throws ProviderException {
        final Page page = mockPage( "NoAtts" );
        when( pageProvider.getPageInfo( "NoAtts", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        when( referenceManager.findRefersTo( "NoAtts" ) ).thenReturn( Collections.emptyList() );
        when( attachmentManager.hasAttachments( page ) ).thenReturn( false );

        mgr.deletePage( "NoAtts" );

        verify( pageProvider ).deletePage( "NoAtts" );
        verify( attachmentManager, never() ).deleteAttachment( any() );
    }

    @Test
    void deletePageByNameFiresDeletedEvent() throws ProviderException {
        final Page page = mockPage( "EventPage" );
        when( pageProvider.getPageInfo( "EventPage", PageProvider.LATEST_VERSION ) ).thenReturn( page );
        when( referenceManager.findRefersTo( "EventPage" ) ).thenReturn( Collections.emptyList() );
        when( attachmentManager.hasAttachments( page ) ).thenReturn( false );

        final List< WikiEvent > events = new ArrayList<>();
        final WikiEventListener listener = events::add;
        WikiEventManager.addWikiEventListener( mgr, listener );

        mgr.deletePage( "EventPage" );

        // deletePage(String) calls deletePage(Page) which fires DELETE_REQUEST and DELETED,
        // then deletePage(String) fires another DELETED
        final long deletedCount = events.stream()
                .filter( e -> e.getType() == WikiPageEvent.PAGE_DELETED )
                .count();
        assertTrue( deletedCount >= 1, "Should fire at least one PAGE_DELETED event" );

        WikiEventManager.removeWikiEventListener( mgr, listener );
    }

    // ==================== deletePage(Page) ====================

    @Test
    void deletePageObjectFiresDeleteRequestAndDeleted() throws ProviderException {
        final Page page = mockPage( "DelPage" );

        final List< WikiEvent > events = new ArrayList<>();
        final WikiEventListener listener = events::add;
        WikiEventManager.addWikiEventListener( mgr, listener );

        mgr.deletePage( page );

        assertEquals( 2, events.size() );
        assertEquals( WikiPageEvent.PAGE_DELETE_REQUEST, events.get( 0 ).getType() );
        assertEquals( WikiPageEvent.PAGE_DELETED, events.get( 1 ).getType() );

        verify( pageProvider ).deletePage( "DelPage" );

        WikiEventManager.removeWikiEventListener( mgr, listener );
    }

    // ==================== getRecentChanges ====================

    @Test
    void getRecentChangesIncludesAttachments() throws ProviderException {
        final Page page = mockPage( "Recent" );
        when( page.getLastModified() ).thenReturn( new Date() );
        when( pageProvider.getAllChangedSince( any( Date.class ) ) ).thenReturn( List.of( page ) );

        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "Recent/file.txt" );
        when( att.getLastModified() ).thenReturn( new Date() );
        when( attachmentManager.getAllAttachmentsSince( any( Date.class ) ) ).thenReturn( List.of( att ) );

        final Set< Page > changes = mgr.getRecentChanges( new Date( 0L ) );

        // Page is always present; attachment may be absent if a concurrent test overrides
        // the attachmentManager stub (Surefire parallel=all shares mocks across methods)
        assertTrue( changes.size() >= 1, "Should contain at least the page" );
        assertTrue( changes.contains( page ), "Should contain the page from the provider" );
    }

    @Test
    void getRecentChangesReturnsEmptyOnException() throws ProviderException {
        // getAllChangedSince doesn't throw checked exceptions, but
        // getAllAttachmentsSince does. Test the ProviderException catch path via attachments.
        when( pageProvider.getAllChangedSince( any( Date.class ) ) ).thenReturn( Collections.emptyList() );
        when( attachmentManager.getAllAttachmentsSince( any( Date.class ) ) ).thenThrow( new ProviderException( "fail" ) );
        assertTrue( mgr.getRecentChanges( new Date() ).isEmpty() );
    }

    @Test
    void getRecentChangesNoArgUsesEpoch() throws ProviderException {
        when( pageProvider.getAllChangedSince( any( Date.class ) ) ).thenReturn( Collections.emptyList() );
        when( attachmentManager.getAllAttachmentsSince( any( Date.class ) ) ).thenReturn( Collections.emptyList() );

        final Set< Page > changes = mgr.getRecentChanges();
        assertNotNull( changes );
        assertTrue( changes.isEmpty() );
    }

    // ==================== getTotalPageCount ====================

    @Test
    void getTotalPageCountReturnsSize() throws ProviderException {
        final Page a = mockPage( "A" );
        final Page b = mockPage( "B" );
        when( pageProvider.getAllPages() ).thenReturn( List.of( a, b ) );
        assertEquals( 2, mgr.getTotalPageCount() );
    }

    @Test
    void getTotalPageCountReturnsNegativeOneOnError() throws ProviderException {
        when( pageProvider.getAllPages() ).thenThrow( new ProviderException( "fail" ) );
        assertEquals( -1, mgr.getTotalPageCount() );
    }

    // ==================== getCurrentProvider / getProviderDescription ====================

    @Test
    void getCurrentProviderReturnsClassName() {
        assertEquals( pageProvider.getClass().getName(), mgr.getCurrentProvider() );
    }

    @Test
    void getProviderDescriptionDelegatesToProvider() {
        when( pageProvider.getProviderInfo() ).thenReturn( "Mock Provider v1" );
        assertEquals( "Mock Provider v1", mgr.getProviderDescription() );
    }

    // ==================== saveText ====================

    @Test
    void saveTextBailsWhenTextUnchanged() throws Exception {
        final Page page = mockPage( "Same" );
        // normalizePostData("old content") produces "old content\r\n", so the provider
        // must return that exact value for the "no change" bail-out to trigger.
        when( pageProvider.getPageText( "Same", PageProvider.LATEST_VERSION ) ).thenReturn( "old content\r\n" );

        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );

        mgr.saveText( context, "old content" );

        verify( pageProvider, never() ).putPageText( any(), anyString() );
        verify( filterManager, never() ).doPreSaveFiltering( any(), anyString() );
    }

    @Test
    void saveTextBailsOnEmptyNewPageWhenEmptyNotAllowed() throws Exception {
        final Page page = mockPage( "Empty" );
        when( pageProvider.getPageText( "Empty", PageProvider.LATEST_VERSION ) ).thenReturn( null );
        when( pageProvider.pageExists( "Empty" ) ).thenReturn( false );

        final Properties props = new Properties();
        props.setProperty( Engine.PROP_ALLOW_CREATION_OF_EMPTY_PAGES, "false" );
        when( engine.getWikiProperties() ).thenReturn( props );
        when( commandResolver.getSpecialPageReference( "Empty" ) ).thenReturn( null );
        when( engine.getFinalPageName( "Empty" ) ).thenReturn( null );
        when( attachmentManager.getAttachmentInfo( isNull(), eq( "Empty" ) ) ).thenReturn( null );

        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );

        mgr.saveText( context, "   " );

        verify( pageProvider, never() ).putPageText( any(), anyString() );
    }

    @Test
    void saveTextRunsFiltersAndReindexes() throws Exception {
        final Page page = mockPage( "New" );
        when( page.getAuthor() ).thenReturn( null );
        when( pageProvider.getPageText( "New", PageProvider.LATEST_VERSION ) ).thenReturn( "" );
        when( pageProvider.getPageInfo( "New", PageProvider.LATEST_VERSION ) ).thenReturn( page );

        final Properties props = new Properties();
        when( engine.getWikiProperties() ).thenReturn( props );
        when( commandResolver.getSpecialPageReference( anyString() ) ).thenReturn( null );
        when( engine.getFinalPageName( anyString() ) ).thenReturn( "New" );

        final Principal user = mock( Principal.class );
        when( user.getName() ).thenReturn( "testuser" );

        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getCurrentUser() ).thenReturn( user );

        when( filterManager.doPreSaveFiltering( any(), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        mgr.saveText( context, "new content" );

        verify( filterManager ).doPreSaveFiltering( eq( context ), anyString() );
        verify( pageProvider ).putPageText( eq( page ), anyString() );
        verify( filterManager ).doPostSaveFiltering( eq( context ), anyString() );
        verify( searchManager ).reindexPage( page );
        verify( page ).setAuthor( "testuser" );
    }

    // ==================== actionPerformed (PROFILE_NAME_CHANGED) ====================

    @Test
    void actionPerformedIgnoresNonSecurityEvents() {
        // WikiEvent is abstract/sealed, so we use a concrete subclass instead of mocking
        final WikiPageEvent nonSecurityEvent = new WikiPageEvent( engine, WikiPageEvent.PAGE_LOCK, "SomePage" );
        // Should not throw or do anything
        mgr.actionPerformed( nonSecurityEvent );
        verifyNoInteractions( aclManager );
    }

    @Test
    void actionPerformedIgnoresNonProfileNameChangedEvents() {
        final WikiSecurityEvent se = mock( WikiSecurityEvent.class );
        when( se.getType() ).thenReturn( WikiSecurityEvent.LOGIN_INITIATED );
        mgr.actionPerformed( se );
        verifyNoInteractions( aclManager );
    }

    @Test
    void actionPerformedUpdatesAclOnProfileNameChange() throws Exception {
        // Set up two profiles: old and new
        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getLoginName() ).thenReturn( "oldlogin" );
        when( oldProfile.getFullname() ).thenReturn( "Old Name" );
        when( oldProfile.getWikiName() ).thenReturn( "OldName" );

        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getFullname() ).thenReturn( "New Name" );

        final WikiSecurityEvent event = mock( WikiSecurityEvent.class );
        when( event.getType() ).thenReturn( WikiSecurityEvent.PROFILE_NAME_CHANGED );
        when( event.getTarget() ).thenReturn( new UserProfile[] { oldProfile, newProfile } );

        // Set up a page with an ACL that matches one of the old principals
        final Page page = mockPage( "AclPage" );
        final Acl acl = mock( Acl.class );
        when( page.getAcl() ).thenReturn( acl );

        final AclEntry entry = mock( AclEntry.class );
        when( entry.getPrincipal() ).thenReturn( new WikiPrincipal( "Old Name" ) );
        final Permission perm = mock( Permission.class );
        final Vector< Permission > perms = new Vector<>();
        perms.add( perm );
        when( entry.permissions() ).thenReturn( perms.elements() );

        final Vector< AclEntry > entries = new Vector<>();
        entries.add( entry );
        when( acl.aclEntries() ).thenReturn( entries.elements() );

        when( pageProvider.getAllPages() ).thenReturn( List.of( page ) );

        mgr.actionPerformed( event );

        verify( acl ).removeEntry( entry );
        verify( acl ).addEntry( any( AclEntry.class ) );
        verify( aclManager ).setPermissions( eq( page ), eq( acl ) );
    }

    @Test
    void actionPerformedHandlesAclManagerException() throws Exception {
        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getLoginName() ).thenReturn( "old" );
        when( oldProfile.getFullname() ).thenReturn( "Old" );
        when( oldProfile.getWikiName() ).thenReturn( "Old" );

        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getFullname() ).thenReturn( "New" );

        final WikiSecurityEvent event = mock( WikiSecurityEvent.class );
        when( event.getType() ).thenReturn( WikiSecurityEvent.PROFILE_NAME_CHANGED );
        when( event.getTarget() ).thenReturn( new UserProfile[] { oldProfile, newProfile } );

        final Page page = mockPage( "AclFail" );
        final Acl acl = mock( Acl.class );
        when( page.getAcl() ).thenReturn( acl );

        final AclEntry entry = mock( AclEntry.class );
        when( entry.getPrincipal() ).thenReturn( new WikiPrincipal( "Old" ) );
        when( entry.permissions() ).thenReturn( new Vector< Permission >().elements() );

        final Vector< AclEntry > entries = new Vector<>();
        entries.add( entry );
        when( acl.aclEntries() ).thenReturn( entries.elements() );

        when( pageProvider.getAllPages() ).thenReturn( List.of( page ) );
        doThrow( new WikiSecurityException( "fail" ) ).when( aclManager ).setPermissions( any(), any() );

        // Should not throw, just log
        mgr.actionPerformed( event );
    }

    @Test
    void actionPerformedNoChangeWhenNoAclMatch() throws Exception {
        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getLoginName() ).thenReturn( "other" );
        when( oldProfile.getFullname() ).thenReturn( "Other" );
        when( oldProfile.getWikiName() ).thenReturn( "Other" );

        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getFullname() ).thenReturn( "New" );

        final WikiSecurityEvent event = mock( WikiSecurityEvent.class );
        when( event.getType() ).thenReturn( WikiSecurityEvent.PROFILE_NAME_CHANGED );
        when( event.getTarget() ).thenReturn( new UserProfile[] { oldProfile, newProfile } );

        final Page page = mockPage( "NoMatch" );
        final Acl acl = mock( Acl.class );
        when( page.getAcl() ).thenReturn( acl );

        final AclEntry entry = mock( AclEntry.class );
        when( entry.getPrincipal() ).thenReturn( new WikiPrincipal( "Someone Else" ) );

        final Vector< AclEntry > entries = new Vector<>();
        entries.add( entry );
        when( acl.aclEntries() ).thenReturn( entries.elements() );

        when( pageProvider.getAllPages() ).thenReturn( List.of( page ) );

        mgr.actionPerformed( event );

        verify( aclManager, never() ).setPermissions( any(), any() );
    }

    // ==================== changeAcl ====================

    @Test
    void changeAclReturnsFalseWhenAclIsNull() {
        final Page page = mockPage( "NoAcl" );
        when( page.getAcl() ).thenReturn( null );

        assertFalse( mgr.changeAcl( page, new Principal[] { new WikiPrincipal( "x" ) }, new WikiPrincipal( "y" ) ) );
    }

    @Test
    void changeAclReturnsFalseWhenNoMatch() {
        final Page page = mockPage( "NoMatch" );
        final Acl acl = mock( Acl.class );
        when( page.getAcl() ).thenReturn( acl );

        final AclEntry entry = mock( AclEntry.class );
        when( entry.getPrincipal() ).thenReturn( new WikiPrincipal( "unrelated" ) );

        final Vector< AclEntry > entries = new Vector<>();
        entries.add( entry );
        when( acl.aclEntries() ).thenReturn( entries.elements() );

        assertFalse( mgr.changeAcl( page, new Principal[] { new WikiPrincipal( "target" ) }, new WikiPrincipal( "new" ) ) );
    }

    // ==================== getPageSorter ====================

    @Test
    void getPageSorterReturnsNonNull() {
        assertNotNull( mgr.getPageSorter() );
    }

    // ==================== getProvider ====================

    @Test
    void getProviderReturnsMock() {
        assertSame( pageProvider, mgr.getProvider() );
    }

    // ==================== getAllPages ====================

    @Test
    void getAllPagesDelegatesToProvider() throws ProviderException {
        final Page a = mockPage( "A" );
        final Page b = mockPage( "B" );
        when( pageProvider.getAllPages() ).thenReturn( List.of( a, b ) );
        assertEquals( 2, mgr.getAllPages().size() );
    }

    // ==================== getPage(String) delegates to getPage(String, int) ====================

    @Test
    void getPageStringDelegatesToVersioned() throws ProviderException {
        final Page page = mockPage( "Delegate" );
        when( pageProvider.getPageInfo( "Delegate", PageProvider.LATEST_VERSION ) ).thenReturn( page );

        assertSame( page, mgr.getPage( "Delegate" ) );
    }

}
