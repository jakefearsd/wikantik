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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Additional tests for {@link DefaultReferenceManager} covering uncovered branches:
 * isInitialized, updateReferences(Page), clearPageEntries, findReferredBy,
 * deepHashCode, findCreated, scanWikiLinks with frontmatter related field,
 * English plural matching in getReferenceList, and the two-arg constructor.
 */
class DefaultReferenceManagerAdditionalTest {

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
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        when( attachmentManager.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );

        mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );
    }

    private Page mockPage( final String name ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        when( page.getVersion() ).thenReturn( PageProvider.LATEST_VERSION );
        when( page.getLastModified() ).thenReturn( new Date() );
        when( page.getAttributes() ).thenReturn( Collections.emptyMap() );
        return page;
    }

    // -----------------------------------------------------------------------
    // isInitialized — false before initialize(), true after
    // -----------------------------------------------------------------------

    @Test
    void isInitializedFalseBeforeInit() {
        assertFalse( mgr.isInitialized(),
                "Manager should not be initialized immediately after construction" );
    }

    @Test
    void isInitializedTrueAfterInit() throws ProviderException {
        when( pageManager.getPage( anyString() ) ).thenAnswer( inv -> mockPage( inv.getArgument( 0 ) ) );
        when( pageManager.getPageText( anyString(), anyInt() ) ).thenReturn( "" );

        mgr.initialize( Collections.emptyList() );
        assertTrue( mgr.isInitialized() );
    }

    // -----------------------------------------------------------------------
    // updateReferences(Page) — delegates to scanWikiLinks + updateReferences
    // -----------------------------------------------------------------------

    @Test
    void updateReferencesWithPageObjectUpdatesRefs() {
        final Page page = mockPage( "PageFromObject" );
        when( pageManager.getPureText( eq( "PageFromObject" ), anyInt() ) )
                .thenReturn( "Link to [LinkedPage]()." );

        mgr.updateReferences( page );

        assertTrue( mgr.findRefersTo( "PageFromObject" ).contains( "LinkedPage" ),
                "updateReferences(Page) should extract and record links" );
    }

    // -----------------------------------------------------------------------
    // clearPageEntries — removes both directions
    // -----------------------------------------------------------------------

    @Test
    void clearPageEntriesRemovesAllDirections() {
        mgr.updateReferences( "Clearer", List.of( "Target" ) );
        mgr.updateReferences( "Referrer", List.of( "Clearer" ) );

        assertTrue( mgr.findRefersTo( "Clearer" ).contains( "Target" ) );
        assertTrue( mgr.findReferrers( "Clearer" ).contains( "Referrer" ) );

        mgr.clearPageEntries( "Clearer" );

        assertTrue( mgr.findRefersTo( "Clearer" ).isEmpty(),
                "refersTo should be empty after clearPageEntries" );
        assertFalse( mgr.findReferredBy( "Clearer" ) != null && mgr.findReferredBy( "Clearer" ).contains( "Referrer" ),
                "referredBy should not contain Clearer after clearPageEntries" );
    }

    @Test
    void clearPageEntriesForUnknownPageDoesNotThrow() {
        assertDoesNotThrow( () -> mgr.clearPageEntries( "NeverAddedPage" ) );
    }

    // -----------------------------------------------------------------------
    // findReferredBy — returns unmutable view
    // -----------------------------------------------------------------------

    @Test
    void findReferredByReturnsCorrectSet() {
        mgr.updateReferences( "Referrer", List.of( "Target" ) );

        final Set<String> referredBy = mgr.findReferredBy( "Target" );
        assertNotNull( referredBy );
        assertTrue( referredBy.contains( "Referrer" ) );
    }

    @Test
    void findReferredByReturnsNullForUnknownPage() {
        // page was never updated — findReferredBy may return null per contract
        final Set<String> result = mgr.findReferredBy( "AbsolutelyUnknownPage" );
        assertNull( result );
    }

    // -----------------------------------------------------------------------
    // deepHashCode — returns a consistent value
    // -----------------------------------------------------------------------

    @Test
    void deepHashCodeReturnsSameValueWhenUnchanged() {
        mgr.updateReferences( "Page1", List.of( "Page2" ) );
        final int h1 = mgr.deepHashCode();
        final int h2 = mgr.deepHashCode();
        assertEquals( h1, h2, "deepHashCode should be stable when data has not changed" );
    }

    @Test
    void deepHashCodeChangesAfterUpdate() {
        final int before = mgr.deepHashCode();
        mgr.updateReferences( "NewPage", List.of( "AnotherPage" ) );
        final int after = mgr.deepHashCode();
        assertNotEquals( before, after, "deepHashCode should change after references are updated" );
    }

    // -----------------------------------------------------------------------
    // findCreated — returns all pages that have been indexed
    // -----------------------------------------------------------------------

    @Test
    void findCreatedContainsPagesAddedViaUpdateReferences() {
        mgr.updateReferences( "Alpha", List.of( "Beta" ) );
        mgr.updateReferences( "Beta", Collections.emptyList() );

        final Set<String> created = mgr.findCreated();
        assertTrue( created.contains( "Alpha" ) );
        assertTrue( created.contains( "Beta" ) );
    }

    @Test
    void findCreatedIsEmptyInitially() {
        assertTrue( mgr.findCreated().isEmpty() );
    }

    // -----------------------------------------------------------------------
    // scanWikiLinks — picks up related links from frontmatter
    // -----------------------------------------------------------------------

    @Test
    void scanWikiLinksPicksUpFrontmatterRelatedField() {
        final Page page = mockPage( "ArticlePage" );
        final String content = "---\nrelated:\n  - RelatedPageA\n  - RelatedPageB\n---\n\nContent.";

        final Collection<String> links = mgr.scanWikiLinks( page, content );
        assertTrue( links.contains( "RelatedPageA" ),
                "scanWikiLinks should include related pages from frontmatter" );
        assertTrue( links.contains( "RelatedPageB" ) );
    }

    @Test
    void scanWikiLinksPicksUpMarkdownLinks() {
        final Page page = mockPage( "BodyPage" );
        final String content = "See [PageOne]() and [PageTwo]() for details.";

        final Collection<String> links = mgr.scanWikiLinks( page, content );
        assertTrue( links.contains( "PageOne" ) );
        assertTrue( links.contains( "PageTwo" ) );
    }

    @Test
    void scanWikiLinksIgnoresExternalLinks() {
        final Page page = mockPage( "ExtPage" );
        final String content = "Visit [Google](https://google.com) for search.";

        final Collection<String> links = mgr.scanWikiLinks( page, content );
        assertFalse( links.stream().anyMatch( l -> l.contains( "://" ) ),
                "External links should not appear in wiki link scan" );
    }

    // -----------------------------------------------------------------------
    // English plural matching — enabled with matchEnglishPlurals
    // -----------------------------------------------------------------------

    @Test
    void pluralMatchingWorksWhenEnabled() throws ProviderException {
        // Build a manager with English plurals enabled
        final Engine pluralEngine = MockEngineBuilder.engine()
                .property( Engine.PROP_MATCHPLURALS, "true" )
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( pluralEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( pluralEngine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager pluralMgr = new DefaultReferenceManager( pluralEngine, pageManager, attachmentManager );

        // PageA refers to "WikiWord" (singular)
        pluralMgr.updateReferences( "PageA", List.of( "WikiWord" ) );

        // findReferrers for "WikiWords" (plural) should also find PageA via plural matching
        final Set<String> referrers = pluralMgr.findReferrers( "WikiWords" );
        assertTrue( referrers.contains( "PageA" ),
                "Plural matching should allow 'WikiWords' to find referrers of 'WikiWord'" );
    }

    // -----------------------------------------------------------------------
    // Two-arg constructor delegates to full constructor
    // -----------------------------------------------------------------------

    @Test
    void singleArgConstructorInitializesCorrectly() throws Exception {
        final Engine fullEngine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( fullEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( fullEngine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager m = new DefaultReferenceManager( fullEngine );
        assertNotNull( m );
        assertFalse( m.isInitialized() );
        assertTrue( m.findCreated().isEmpty() );
    }
}
