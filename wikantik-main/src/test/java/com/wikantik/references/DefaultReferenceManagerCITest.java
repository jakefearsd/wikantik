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
package com.wikantik.references;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link DefaultReferenceManager} using constructor injection.
 * Focuses on behaviors NOT covered by the integration-style {@link ReferenceManagerTest}:
 * reference tracking, undefined page detection, unreferenced page detection,
 * reference updates on page save/delete, reference rebuilding, edge cases in link scanning,
 * and the {@code actionPerformed} event handler.
 */
class DefaultReferenceManagerCITest {

    private Engine engine;
    private PageManager pageManager;
    private AttachmentManager attachmentManager;

    private DefaultReferenceManager mgr;

    @TempDir
    File workDir;

    @BeforeEach
    void setUp() throws ProviderException {
        pageManager = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );

        engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();

        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        // By default, getFinalPageName returns the input (no aliasing)
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        // Default: no attachments for any page
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );

        mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );
    }

    // --- Helper methods ---

    private Page mockPage( final String name ) {
        return mockPage( name, new Date() );
    }

    private Page mockPage( final String name, final Date lastModified ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        when( page.getVersion() ).thenReturn( PageProvider.LATEST_VERSION );
        when( page.getLastModified() ).thenReturn( lastModified );
        when( page.getAttributes() ).thenReturn( Collections.emptyMap() );
        return page;
    }

    // ========================================================================
    // Reference tracking: page A links to page B
    // ========================================================================

    @Test
    void updateReferencesTracksOutboundLinks() {
        mgr.updateReferences( "PageA", List.of( "PageB", "PageC" ) );

        final Collection< String > refersTo = mgr.findRefersTo( "PageA" );
        assertTrue( refersTo.contains( "PageB" ), "PageA should refer to PageB" );
        assertTrue( refersTo.contains( "PageC" ), "PageA should refer to PageC" );
    }

    @Test
    void updateReferencesTracksInboundLinks() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );

        final Set< String > referrers = mgr.findReferrers( "PageB" );
        assertTrue( referrers.contains( "PageA" ), "PageB should be referred by PageA" );
    }

    @Test
    void updateReferencesReplacesOldReferences() {
        when( pageManager.wikiPageExists( "PageB" ) ).thenReturn( false );
        when( pageManager.wikiPageExists( "PageC" ) ).thenReturn( false );

        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageA", List.of( "PageC" ) );

        final Collection< String > refersTo = mgr.findRefersTo( "PageA" );
        assertFalse( refersTo.contains( "PageB" ), "PageB should no longer be referenced" );
        assertTrue( refersTo.contains( "PageC" ), "PageC should be referenced" );
    }

    @Test
    void updateReferencesRemovesOldReferredByEntry() {
        when( pageManager.wikiPageExists( "PageB" ) ).thenReturn( false );

        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        assertEquals( 1, mgr.findReferrers( "PageB" ).size() );

        // Now PageA no longer references PageB
        mgr.updateReferences( "PageA", List.of( "PageC" ) );

        // PageB should no longer be in referredBy since it doesn't exist and has no referrers
        final Set< String > referrers = mgr.findReferrers( "PageB" );
        assertTrue( referrers.isEmpty(), "PageB should have no referrers after update" );
    }

    @Test
    void multiplePagesSameTarget() {
        mgr.updateReferences( "PageA", List.of( "Target" ) );
        mgr.updateReferences( "PageB", List.of( "Target" ) );

        final Set< String > referrers = mgr.findReferrers( "Target" );
        assertEquals( 2, referrers.size() );
        assertTrue( referrers.contains( "PageA" ) );
        assertTrue( referrers.contains( "PageB" ) );
    }

    // ========================================================================
    // Undefined (uncreated) page detection
    // ========================================================================

    @Test
    void findUncreatedDetectsNonExistentReferencedPages() {
        when( pageManager.wikiPageExists( "ExistingPage" ) ).thenReturn( true );
        when( pageManager.wikiPageExists( "GhostPage" ) ).thenReturn( false );

        mgr.updateReferences( "ExistingPage", List.of( "GhostPage" ) );

        final Collection< String > uncreated = mgr.findUncreated();
        assertTrue( uncreated.contains( "GhostPage" ), "GhostPage should appear in uncreated" );
    }

    @Test
    void findUncreatedExcludesExistingPages() {
        when( pageManager.wikiPageExists( "PageA" ) ).thenReturn( true );
        when( pageManager.wikiPageExists( "PageB" ) ).thenReturn( true );

        mgr.updateReferences( "PageA", List.of( "PageB" ) );

        final Collection< String > uncreated = mgr.findUncreated();
        assertFalse( uncreated.contains( "PageB" ), "Existing page should not be uncreated" );
    }

    @Test
    void findUncreatedReturnsEmptyWhenAllPagesExist() {
        when( pageManager.wikiPageExists( anyString() ) ).thenReturn( true );

        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageB", List.of( "PageA" ) );

        assertTrue( mgr.findUncreated().isEmpty() );
    }

    @Test
    void findUncreatedNoDuplicates() {
        when( pageManager.wikiPageExists( "GhostPage" ) ).thenReturn( false );

        mgr.updateReferences( "PageA", List.of( "GhostPage" ) );
        mgr.updateReferences( "PageB", List.of( "GhostPage" ) );

        final Collection< String > uncreated = mgr.findUncreated();
        // TreeSet guarantees uniqueness
        assertEquals( 1, uncreated.stream().filter( "GhostPage"::equals ).count() );
    }

    // ========================================================================
    // Unreferenced page detection
    // ========================================================================

    @Test
    void findUnreferencedDetectsOrphanPages() {
        // PageA exists and references PageB, but nothing references PageA
        mgr.updateReferences( "PageA", List.of( "PageB" ) );

        final Collection< String > unreferenced = mgr.findUnreferenced();
        assertTrue( unreferenced.contains( "PageA" ), "PageA should be unreferenced (nothing links to it)" );
    }

    @Test
    void findUnreferencedExcludesReferencedPages() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageB", List.of( "PageA" ) );

        final Collection< String > unreferenced = mgr.findUnreferenced();
        assertFalse( unreferenced.contains( "PageA" ), "PageA is referenced by PageB" );
        assertFalse( unreferenced.contains( "PageB" ), "PageB is referenced by PageA" );
    }

    @Test
    void findUnreferencedWithEmptyReferenceManager() {
        assertTrue( mgr.findUnreferenced().isEmpty(), "No pages = no unreferenced" );
    }

    // ========================================================================
    // Reference updates on page save (postSave)
    // ========================================================================

    @Test
    void postSaveUpdatesReferences() {
        final Page page = mockPage( "SavedPage" );
        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );

        mgr.postSave( context, "Link to [TargetPage]()." );

        final Collection< String > refersTo = mgr.findRefersTo( "SavedPage" );
        assertTrue( refersTo.contains( "TargetPage" ), "postSave should update refersTo" );

        final Set< String > referrers = mgr.findReferrers( "TargetPage" );
        assertTrue( referrers.contains( "SavedPage" ), "postSave should update referredBy" );
    }

    @Test
    void postSaveReplacesOldReferences() {
        final Page page = mockPage( "SavedPage" );
        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );

        when( pageManager.wikiPageExists( "OldTarget" ) ).thenReturn( false );

        // First save: link to OldTarget
        mgr.postSave( context, "Link to [OldTarget]()." );
        assertTrue( mgr.findRefersTo( "SavedPage" ).contains( "OldTarget" ) );

        // Second save: link changed to NewTarget
        mgr.postSave( context, "Link to [NewTarget]()." );
        assertFalse( mgr.findRefersTo( "SavedPage" ).contains( "OldTarget" ),
                "Old reference should be removed" );
        assertTrue( mgr.findRefersTo( "SavedPage" ).contains( "NewTarget" ),
                "New reference should be present" );
    }

    @Test
    void postSaveWithNoLinks() {
        final Page page = mockPage( "PlainPage" );
        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );

        mgr.postSave( context, "No links here, just plain text." );

        assertTrue( mgr.findRefersTo( "PlainPage" ).isEmpty(),
                "Page with no links should have empty refersTo" );
    }

    // ========================================================================
    // Reference updates on page delete (pageRemoved)
    // ========================================================================

    @Test
    void pageRemovedCleansUpRefersTo() {
        when( pageManager.wikiPageExists( "PageB" ) ).thenReturn( false );
        mgr.updateReferences( "PageA", List.of( "PageB" ) );

        final Page page = mockPage( "PageA" );
        mgr.pageRemoved( page );

        assertTrue( mgr.findRefersTo( "PageA" ).isEmpty(),
                "Deleted page should have no refersTo" );
    }

    @Test
    void pageRemovedCleansUpReferredBy() {
        when( pageManager.wikiPageExists( "PageB" ) ).thenReturn( false );
        mgr.updateReferences( "PageA", List.of( "PageB" ) );

        assertTrue( mgr.findReferrers( "PageB" ).contains( "PageA" ) );

        final Page page = mockPage( "PageA" );
        mgr.pageRemoved( page );

        assertFalse( mgr.findReferrers( "PageB" ).contains( "PageA" ),
                "Deleted page should be removed from referrers of pages it linked to" );
    }

    @Test
    void pageRemovedKeepsReferredByForExistingPages() {
        when( pageManager.wikiPageExists( "PageB" ) ).thenReturn( true );
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageC", List.of( "PageB" ) );

        final Page page = mockPage( "PageA" );
        mgr.pageRemoved( page );

        // PageB still exists and is still referenced by PageC
        final Set< String > referrers = mgr.findReferrers( "PageB" );
        assertTrue( referrers.contains( "PageC" ), "PageC should still reference PageB" );
        assertFalse( referrers.contains( "PageA" ), "PageA should be removed" );
    }

    @Test
    void pageRemovedWithNoReferences() {
        // page is in referredBy but has no refersTo and no referrers
        mgr.updateReferences( "LonelyPage", Collections.emptyList() );

        final Page page = mockPage( "LonelyPage" );
        // Should not throw
        mgr.pageRemoved( page );

        assertTrue( mgr.findRefersTo( "LonelyPage" ).isEmpty() );
    }

    // ========================================================================
    // actionPerformed event handler
    // ========================================================================

    @Test
    void actionPerformedHandlesPageDeletedEvent() {
        when( pageManager.wikiPageExists( "Target" ) ).thenReturn( false );
        mgr.updateReferences( "DeletedPage", List.of( "Target" ) );
        assertTrue( mgr.findReferrers( "Target" ).contains( "DeletedPage" ) );

        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_DELETED, "DeletedPage" );
        mgr.actionPerformed( event );

        assertTrue( mgr.findRefersTo( "DeletedPage" ).isEmpty(),
                "PAGE_DELETED event should trigger pageRemoved" );
    }

    @Test
    void actionPerformedIgnoresNonDeleteEvents() {
        mgr.updateReferences( "SomePage", List.of( "Target" ) );

        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_REQUESTED, "SomePage" );
        mgr.actionPerformed( event );

        // References should be untouched
        assertTrue( mgr.findRefersTo( "SomePage" ).contains( "Target" ) );
    }

    @Test
    void actionPerformedIgnoresNullPageName() {
        mgr.updateReferences( "SomePage", List.of( "Target" ) );

        // PAGE_DELETED with null name should be a no-op
        final WikiPageEvent event = new WikiPageEvent( engine, WikiPageEvent.PAGE_DELETED, null );
        mgr.actionPerformed( event );

        assertTrue( mgr.findRefersTo( "SomePage" ).contains( "Target" ),
                "References should be untouched for null page name" );
    }

    // ========================================================================
    // clearPageEntries
    // ========================================================================

    @Test
    void clearPageEntriesRemovesBothMaps() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageC", List.of( "PageA" ) );

        mgr.clearPageEntries( "PageA" );

        assertTrue( mgr.findRefersTo( "PageA" ).isEmpty(), "refersTo should be cleared" );
        assertNull( mgr.findReferredBy( "PageA" ), "referredBy entry should be removed" );
    }

    @Test
    void clearPageEntriesUpdatesReferredByOfTarget() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );

        mgr.clearPageEntries( "PageA" );

        // PageB should no longer show PageA as a referrer
        final Set< String > referredBy = mgr.findReferredBy( "PageB" );
        if ( referredBy != null ) {
            assertFalse( referredBy.contains( "PageA" ),
                    "PageA should be removed from PageB's referredBy after clearing" );
        }
    }

    // ========================================================================
    // findCreated
    // ========================================================================

    @Test
    void findCreatedReturnsAllKnownPages() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageC", Collections.emptyList() );

        final Set< String > created = mgr.findCreated();
        assertTrue( created.contains( "PageA" ) );
        assertTrue( created.contains( "PageC" ) );
    }

    @Test
    void findCreatedReturnsDefensiveCopy() {
        mgr.updateReferences( "PageA", Collections.emptyList() );
        final Set< String > created = mgr.findCreated();
        created.add( "Bogus" );

        assertFalse( mgr.findCreated().contains( "Bogus" ),
                "Modifying returned set should not affect internal state" );
    }

    // ========================================================================
    // findReferredBy (unmodifiable direct access)
    // ========================================================================

    @Test
    void findReferredByReturnsNullForUnknownPage() {
        assertNull( mgr.findReferredBy( "NeverHeardOfIt" ) );
    }

    @Test
    void findReferredByReturnsLiveView() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );

        final Set< String > referredBy = mgr.findReferredBy( "PageB" );
        assertNotNull( referredBy );
        assertTrue( referredBy.contains( "PageA" ) );
    }

    // ========================================================================
    // findRefersTo edge cases
    // ========================================================================

    @Test
    void findRefersToReturnsEmptyForUnknownPage() {
        final Collection< String > result = mgr.findRefersTo( "NonExistent" );
        assertNotNull( result );
        assertTrue( result.isEmpty() );
    }

    // ========================================================================
    // findReferrers edge cases
    // ========================================================================

    @Test
    void findReferrersReturnsEmptySetForPageWithNoReferrers() {
        mgr.updateReferences( "LonelyPage", Collections.emptyList() );
        final Set< String > referrers = mgr.findReferrers( "LonelyPage" );
        assertNotNull( referrers );
        assertTrue( referrers.isEmpty() );
    }

    @Test
    void findReferrersNeverReturnsNullForUnknownPage() {
        final Set< String > referrers = mgr.findReferrers( "CompletelyUnknown" );
        assertNotNull( referrers );
        assertTrue( referrers.isEmpty() );
    }

    // ========================================================================
    // scanWikiLinks
    // ========================================================================

    @Test
    void scanWikiLinksExtractsMarkdownLinks() {
        final Page page = mockPage( "TestPage" );
        final Collection< String > links = mgr.scanWikiLinks( page, "See [PageA]() and [PageB]()." );

        assertTrue( links.contains( "PageA" ) );
        assertTrue( links.contains( "PageB" ) );
        assertEquals( 2, links.size() );
    }

    @Test
    void scanWikiLinksExtractsFrontmatterRelatedLinks() {
        final Page page = mockPage( "TestPage" );
        final String content = """
                ---
                related:
                  - RelatedPageA
                  - RelatedPageB
                ---
                Some body text.""";

        final Collection< String > links = mgr.scanWikiLinks( page, content );

        assertTrue( links.contains( "RelatedPageA" ) );
        assertTrue( links.contains( "RelatedPageB" ) );
    }

    @Test
    void scanWikiLinksCombinesBodyAndFrontmatterLinks() {
        final Page page = mockPage( "TestPage" );
        final String content = """
                ---
                related:
                  - FrontmatterLink
                ---
                Body [BodyLink]().""";

        final Collection< String > links = mgr.scanWikiLinks( page, content );

        assertTrue( links.contains( "FrontmatterLink" ) );
        assertTrue( links.contains( "BodyLink" ) );
        assertEquals( 2, links.size() );
    }

    @Test
    void scanWikiLinksReturnsEmptyForNoLinks() {
        final Page page = mockPage( "TestPage" );
        final Collection< String > links = mgr.scanWikiLinks( page, "Just plain text, no links." );

        assertTrue( links.isEmpty() );
    }

    @Test
    void scanWikiLinksIgnoresEmptyRelatedEntries() {
        final Page page = mockPage( "TestPage" );
        final String content = """
                ---
                related:
                  - ValidLink
                  - ""
                ---
                Body text.""";

        final Collection< String > links = mgr.scanWikiLinks( page, content );
        // Empty string should be filtered out by the !name.isEmpty() check
        assertFalse( links.contains( "" ), "Empty related entries should be ignored" );
        assertTrue( links.contains( "ValidLink" ) );
    }

    @Test
    void scanWikiLinksWithSectionFragmentsInTarget() {
        final Page page = mockPage( "TestPage" );
        // Use standard Markdown syntax where section fragment is in the target URL
        final Collection< String > links = mgr.scanWikiLinks( page, "See [details](TargetPage#section)." );

        // MarkdownLinkScanner strips anchor fragment from target
        assertTrue( links.contains( "TargetPage" ),
                "Section links should track page name without fragment: " + links );
    }

    @Test
    void scanWikiLinksWithFragmentInWikantikConvention() {
        final Page page = mockPage( "TestPage" );
        // Wikantik convention [PageName#section]() preserves the full text as page name
        final Collection< String > links = mgr.scanWikiLinks( page, "See [TargetPage#section]()." );

        assertTrue( links.contains( "TargetPage#section" ),
                "Wikantik convention uses link text as-is: " + links );
    }

    // ========================================================================
    // updateReferences(Page) - delegates to PageManager for text
    // ========================================================================

    @Test
    void updateReferencesPageDelegatesToPageManager() {
        final Page page = mockPage( "SomePage" );
        when( pageManager.getPureText( "SomePage", WikiProvider.LATEST_VERSION ) )
                .thenReturn( "Link to [LinkedPage]()." );

        mgr.updateReferences( page );

        verify( pageManager ).getPureText( "SomePage", WikiProvider.LATEST_VERSION );
        assertTrue( mgr.findRefersTo( "SomePage" ).contains( "LinkedPage" ) );
    }

    // ========================================================================
    // deepHashCode
    // ========================================================================

    @Test
    void deepHashCodeChangesAfterUpdate() {
        final int hash1 = mgr.deepHashCode();
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        final int hash2 = mgr.deepHashCode();

        assertNotEquals( hash1, hash2, "Hash should change after modifying references" );
    }

    @Test
    void deepHashCodeConsistentWithoutChanges() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        final int hash1 = mgr.deepHashCode();
        final int hash2 = mgr.deepHashCode();

        assertEquals( hash1, hash2, "Hash should be consistent when called twice without changes" );
    }

    // ========================================================================
    // isInitialized
    // ========================================================================

    @Test
    void isInitializedFalseBeforeInitialize() {
        assertFalse( mgr.isInitialized(), "Should be false before initialize() is called" );
    }

    // ========================================================================
    // English plural matching
    // ========================================================================

    @Test
    void pluralMatchingDisabledByDefault() {
        // Our setUp doesn't set PROP_MATCHPLURALS, so it defaults to false
        mgr.updateReferences( "PageA", List.of( "Bug" ) );

        final Set< String > referrers = mgr.findReferrers( "Bugs" );
        // Without plural matching, "Bugs" should not find "Bug" referrers
        assertTrue( referrers.isEmpty(), "Plural matching should be off by default" );
    }

    @Test
    void pluralMatchingWhenEnabled() throws ProviderException {
        // Create a new manager with plural matching enabled
        final Engine pluralEngine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .property( Engine.PROP_MATCHPLURALS, "true" )
                .build();
        when( pluralEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( pluralEngine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager pluralMgr = new DefaultReferenceManager(
                pluralEngine, pageManager, attachmentManager );

        pluralMgr.updateReferences( "PageA", List.of( "Bug" ) );

        final Set< String > referrers = pluralMgr.findReferrers( "Bugs" );
        assertTrue( referrers.contains( "PageA" ),
                "With plural matching enabled, 'Bugs' should find referrers of 'Bug'" );
    }

    @Test
    void pluralMatchingSuppressesSelfReferenceOnPlural() throws ProviderException {
        final Engine pluralEngine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .property( Engine.PROP_MATCHPLURALS, "true" )
                .build();
        when( pluralEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( pluralEngine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager pluralMgr = new DefaultReferenceManager(
                pluralEngine, pageManager, attachmentManager );

        // "Bugs" refers to "Bug" -- with plural matching, this is a plural self-reference
        pluralMgr.updateReferences( "Bugs", List.of( "Bug" ) );

        final Set< String > referrers = pluralMgr.findReferrers( "Bug" );
        // The plural self-reference should be suppressed
        assertFalse( referrers.contains( "Bugs" ),
                "Plural form referring to singular form should be suppressed" );
    }

    // ========================================================================
    // getFinalPageName integration with engine
    // ========================================================================

    @Test
    void updateReferencesUsesEnginePageNameResolution() throws ProviderException {
        // If engine resolves "oldname" to "NewName", references should use "NewName"
        when( engine.getFinalPageName( "OldName" ) ).thenReturn( "NewName" );

        mgr.updateReferences( "OldName", List.of( "Target" ) );

        // The page name should be resolved to "NewName" in refersTo
        assertTrue( mgr.findRefersTo( "NewName" ).contains( "Target" ),
                "Engine page name resolution should be used" );
    }

    @Test
    void getFinalPageNameFallsBackOnProviderException() throws ProviderException {
        when( engine.getFinalPageName( "BadPage" ) ).thenThrow( new ProviderException( "test error" ) );

        // Should not throw, should fall back to original name
        mgr.updateReferences( "BadPage", List.of( "Target" ) );

        assertTrue( mgr.findRefersTo( "BadPage" ).contains( "Target" ),
                "Should fall back to original page name on error" );
    }

    @Test
    void getFinalPageNameUsesOriginalWhenResolvedNull() throws ProviderException {
        when( engine.getFinalPageName( "Unknown" ) ).thenReturn( null );

        mgr.updateReferences( "Unknown", List.of( "Target" ) );

        assertTrue( mgr.findRefersTo( "Unknown" ).contains( "Target" ),
                "Should use original name when getFinalPageName returns null" );
    }

    // ========================================================================
    // cleanReferredBy edge cases
    // ========================================================================

    @Test
    void cleanReferredByRemovesNonExistentUnreferencedPages() {
        when( pageManager.wikiPageExists( "GhostPage" ) ).thenReturn( false );

        // First: PageA references GhostPage
        mgr.updateReferences( "PageA", List.of( "GhostPage" ) );
        assertTrue( mgr.findReferrers( "GhostPage" ).contains( "PageA" ) );

        // Now PageA stops referencing GhostPage
        mgr.updateReferences( "PageA", Collections.emptyList() );

        // GhostPage should be entirely removed from referredBy since it doesn't exist
        // and has no more referrers
        assertTrue( mgr.findReferrers( "GhostPage" ).isEmpty() );
    }

    @Test
    void cleanReferredByKeepsExistingUnreferencedPages() {
        when( pageManager.wikiPageExists( "RealPage" ) ).thenReturn( true );

        mgr.updateReferences( "PageA", List.of( "RealPage" ) );
        mgr.updateReferences( "PageA", Collections.emptyList() );

        // RealPage still exists, so its referredBy entry should be kept (though empty)
        final Set< String > referredBy = mgr.findReferredBy( "RealPage" );
        assertNotNull( referredBy, "Existing page should retain referredBy entry" );
    }

    // ========================================================================
    // Complex scenarios
    // ========================================================================

    @Test
    void chainedReferences() {
        mgr.updateReferences( "A", List.of( "B" ) );
        mgr.updateReferences( "B", List.of( "C" ) );
        mgr.updateReferences( "C", List.of( "A" ) );

        // Circular chain: every page has a referrer and refers to something
        assertTrue( mgr.findReferrers( "A" ).contains( "C" ) );
        assertTrue( mgr.findReferrers( "B" ).contains( "A" ) );
        assertTrue( mgr.findReferrers( "C" ).contains( "B" ) );

        assertTrue( mgr.findRefersTo( "A" ).contains( "B" ) );
        assertTrue( mgr.findRefersTo( "B" ).contains( "C" ) );
        assertTrue( mgr.findRefersTo( "C" ).contains( "A" ) );

        // No unreferenced pages in a complete cycle
        assertTrue( mgr.findUnreferenced().isEmpty() );
    }

    @Test
    void selfReference() {
        mgr.updateReferences( "PageA", List.of( "PageA" ) );

        assertTrue( mgr.findReferrers( "PageA" ).contains( "PageA" ),
                "Self-reference should appear in referrers" );
        assertTrue( mgr.findRefersTo( "PageA" ).contains( "PageA" ),
                "Self-reference should appear in refersTo" );
    }

    @Test
    void pageRemovedThenReAdded() {
        when( pageManager.wikiPageExists( "Target" ) ).thenReturn( false );

        mgr.updateReferences( "PageA", List.of( "Target" ) );
        mgr.pageRemoved( mockPage( "PageA" ) );

        assertTrue( mgr.findRefersTo( "PageA" ).isEmpty() );

        // Re-add the page with new references
        mgr.updateReferences( "PageA", List.of( "NewTarget" ) );
        assertTrue( mgr.findRefersTo( "PageA" ).contains( "NewTarget" ) );
        assertFalse( mgr.findRefersTo( "PageA" ).contains( "Target" ) );
    }

    @Test
    void emptyReferencesFromPage() {
        mgr.updateReferences( "PageA", Collections.emptyList() );

        assertTrue( mgr.findRefersTo( "PageA" ).isEmpty() );
        // PageA should be in referredBy key list (it exists) but with no referrers
        assertTrue( mgr.findUnreferenced().contains( "PageA" ) );
    }

    // ========================================================================
    // Constructor injection verification
    // ========================================================================

    @Test
    void constructorInjectionUsesProvidedManagers() throws ProviderException {
        final Page page = mockPage( "TestCI" );

        when( pageManager.getPureText( "TestCI", WikiProvider.LATEST_VERSION ) )
                .thenReturn( "Link to [SomePage]()." );

        mgr.updateReferences( page );

        verify( pageManager ).getPureText( "TestCI", WikiProvider.LATEST_VERSION );
        assertTrue( mgr.findRefersTo( "TestCI" ).contains( "SomePage" ) );
    }

    @Test
    void delegatingConstructorResolvesFromEngine() {
        // The single-arg constructor should call engine.getManager() for both managers
        final Engine testEngine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( testEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );

        // This exercises the delegating constructor path
        final DefaultReferenceManager delegated = new DefaultReferenceManager( testEngine );

        // Verify managers were fetched from engine
        verify( testEngine ).getManager( PageManager.class );
        verify( testEngine ).getManager( AttachmentManager.class );

        assertNotNull( delegated );
    }
}
