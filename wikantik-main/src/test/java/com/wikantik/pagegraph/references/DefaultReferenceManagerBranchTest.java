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
package com.wikantik.pagegraph.references;

import com.wikantik.InternalWikiException;
import com.wikantik.MockEngineBuilder;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for uncovered paths in {@link DefaultReferenceManager}.
 *
 * <p>Targets:
 * <ul>
 *   <li>{@code pageRemoved}: the {@link InternalWikiException} thrown when a referred page's
 *       referredBy entry is {@code null} (refmgr out-of-sync guard)</li>
 *   <li>{@code pageRemoved}: the "refBy non-empty OR page exists" branch where the referredBy
 *       entry IS kept after removal</li>
 *   <li>{@code pageRemoved}: the "empty set AND page doesn't exist" branch where the referredBy
 *       entry IS removed</li>
 *   <li>{@code serializeAttrsToDisk}: empty-attributes branch → no file created</li>
 *   <li>{@code findUnreferenced} and {@code findUncreated}: warnIfNotInitialized path is exercised</li>
 *   <li>{@code updateReferredBy}: English-plural self-skip (referrer equals the plural/singular form
 *       of the page) exercises the early-return branch inside that private helper</li>
 *   <li>{@code getReferenceList}: plural-matching path where only the plural variant has referrers</li>
 * </ul>
 */
class DefaultReferenceManagerBranchTest {

    private WikiEngine engine;
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
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getVersion() ).thenReturn( PageProvider.LATEST_VERSION );
        when( p.getLastModified() ).thenReturn( new Date() );
        when( p.getAttributes() ).thenReturn( Collections.emptyMap() );
        return p;
    }

    // =========================================================================
    // pageRemoved — InternalWikiException when referredBy entry is null
    // =========================================================================

    /**
     * The {@code pageRemoved} method iterates the pages that the deleted page refers to.
     * For each such referred page it looks up {@code referredBy.get(referredPageName)}.
     * If that entry is null (refmgr out-of-sync), an {@link InternalWikiException} must be thrown.
     *
     * <p>We force this by removing the PageB entry from referredBy after linking PageA → PageB.
     */
    @Test
    void pageRemoved_nullReferredByEntry_throwsInternalWikiException() {
        // PageA refers to PageB
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        // Corrupt the referredBy map: remove the PageB entry
        mgr.getReferredBy().remove( "PageB" );

        // Removing PageA must detect the corrupt state and throw
        assertThrows( InternalWikiException.class,
            () -> mgr.pageRemoved( mockPage( "PageA" ) ),
            "pageRemoved must throw InternalWikiException when referredBy entry for a referred page is null" );
    }

    // =========================================================================
    // pageRemoved — referredBy entry kept when refBy is non-empty after removal
    // =========================================================================

    /**
     * When PageA is removed from the referrers of PageB, but PageB still has PageC
     * as another referrer, the referredBy entry for PageB must be kept.
     */
    @Test
    void pageRemoved_referredByKeptWhenOtherReferrersExist() {
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageC", List.of( "PageB" ) );
        when( pageManager.wikiPageExists( "PageB" ) ).thenReturn( false );

        mgr.pageRemoved( mockPage( "PageA" ) );

        final Set< String > remaining = mgr.getReferredBy().get( "PageB" );
        assertNotNull( remaining, "referredBy entry for PageB must still exist (PageC still refers to it)" );
        assertTrue( remaining.contains( "PageC" ),
            "PageC must still appear as a referrer of PageB after removing PageA" );
    }

    // =========================================================================
    // pageRemoved — referredBy entry removed when set is empty AND page doesn't exist
    // =========================================================================

    /**
     * When PageA is the LAST referrer of a non-existent target page (GhostPage),
     * removing PageA must drop the referredBy entry for GhostPage entirely.
     */
    @Test
    void pageRemoved_referredByRemovedWhenEmptyAndPageNotExists() {
        mgr.updateReferences( "PageA", List.of( "GhostPage" ) );
        when( pageManager.wikiPageExists( "GhostPage" ) ).thenReturn( false );

        mgr.pageRemoved( mockPage( "PageA" ) );

        assertNull( mgr.getReferredBy().get( "GhostPage" ),
            "referredBy entry for a non-existent page with no referrers must be removed" );
    }

    /**
     * When PageA is the last referrer of an EXISTING target page (ExistingTarget),
     * the referredBy entry must be KEPT even though the set is empty, because the
     * target page still exists (condition: !( refBy.isEmpty() && !pageExists )).
     */
    @Test
    void pageRemoved_referredByKeptWhenEmptyButPageExists() {
        mgr.updateReferences( "PageA", List.of( "ExistingTarget" ) );
        when( pageManager.wikiPageExists( "ExistingTarget" ) ).thenReturn( true );

        mgr.pageRemoved( mockPage( "PageA" ) );

        final Set< String > remaining = mgr.getReferredBy().get( "ExistingTarget" );
        assertNotNull( remaining, "referredBy entry for an existing target must be kept after referrer is removed" );
        assertTrue( remaining.isEmpty(), "referredBy set for existing target must be empty after removing its sole referrer" );
    }

    // =========================================================================
    // serializeAttrsToDisk — empty attributes: the early-return / delete branch
    // =========================================================================

    /**
     * When a page has no attributes, {@code serializeAttrsToDisk} must take the
     * {@code entries.isEmpty() → delete → return} branch without creating a new file.
     * We trigger this via {@code postSave} (which calls serializeAttrsToDisk internally).
     */
    @Test
    void serializeAttrsToDisk_emptyAttributes_noFileCreated() throws ProviderException {
        final Page page = mockPage( "EmptyAttrPage" );
        when( page.getAttributes() ).thenReturn( Collections.emptyMap() );
        when( pageManager.getPageText( "EmptyAttrPage", PageProvider.LATEST_VERSION ) ).thenReturn( "" );

        final Context ctx = mock( Context.class );
        when( ctx.getPage() ).thenReturn( page );

        // Must not throw; the empty-attributes branch just returns
        assertDoesNotThrow( () -> mgr.postSave( ctx, "" ),
            "postSave with an empty-attributes page must not throw" );

        // The attr-cache directory either doesn't exist or is empty
        final File serDir = new File( workDir, "refmgr-attr" );
        if ( serDir.exists() ) {
            final File[] files = serDir.listFiles();
            assertTrue( files == null || files.length == 0,
                "No attr cache file should exist for a page with empty attributes" );
        }
    }

    // =========================================================================
    // findUnreferenced — exercises warnIfNotInitialized (before init) + correct scan
    // =========================================================================

    /**
     * Before {@code initialize()} is called, findUnreferenced must not throw and
     * must return an empty collection (no pages have been added yet).
     * This also exercises the {@code warnIfNotInitialized()} LOG.debug path.
     */
    @Test
    void findUnreferenced_beforeInit_returnsEmptyCollection() {
        final Collection< String > unref = mgr.findUnreferenced();
        assertNotNull( unref );
        assertTrue( unref.isEmpty(), "Before init with no pages added, findUnreferenced must be empty" );
    }

    /**
     * After adding pages with no inbound references, findUnreferenced returns them.
     */
    @Test
    void findUnreferenced_afterUpdate_returnsCorrectSet() {
        // PageA → PageB; PageC has no inbound refs; PageA has no inbound refs
        mgr.updateReferences( "PageA", List.of( "PageB" ) );
        mgr.updateReferences( "PageC", List.of() );

        final Collection< String > unref = mgr.findUnreferenced();
        assertTrue( unref.contains( "PageA" ), "PageA has no inbound refs → must be unreferenced" );
        assertTrue( unref.contains( "PageC" ), "PageC has no inbound refs → must be unreferenced" );
    }

    // =========================================================================
    // findUncreated — exercises the warnIfNotInitialized path
    // =========================================================================

    /**
     * findUncreated before init must not throw and return empty when no references added.
     */
    @Test
    void findUncreated_beforeInit_returnsEmptyCollection() {
        final Collection< String > uncreated = mgr.findUncreated();
        assertNotNull( uncreated );
        assertTrue( uncreated.isEmpty(), "No references → no uncreated pages" );
    }

    // =========================================================================
    // updateReferredBy — English-plural mutual-reference skip (early-return branch)
    // =========================================================================

    /**
     * When English plural matching is enabled and a page "WikiWords" links to "WikiWord"
     * (its singular form), the reference is a plural self-reference and must be silently
     * dropped. {@code findReferrers("WikiWord")} must NOT contain "WikiWords".
     *
     * <p>This exercises the {@code if (referrer.equals(p2)) return;} branch inside the
     * private {@code updateReferredBy} method.
     */
    @Test
    void updateReferredBy_pluralSelfReference_isSkipped() throws ProviderException {
        final WikiEngine pluralEngine = MockEngineBuilder.engine()
            .property( com.wikantik.api.core.Engine.PROP_MATCHPLURALS, "true" )
            .with( PageManager.class, pageManager )
            .with( AttachmentManager.class, attachmentManager )
            .build();
        when( pluralEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( pluralEngine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager pluralMgr =
            new DefaultReferenceManager( pluralEngine, pageManager, attachmentManager );

        // "WikiWords" links to "WikiWord" (singular = plural minus 's')
        pluralMgr.updateReferences( "WikiWords", List.of( "WikiWord" ) );

        // The plural-self-reference early-return branch fires: WikiWords must NOT
        // appear in referrers of WikiWord
        final Set< String > referrers = pluralMgr.findReferrers( "WikiWord" );
        assertTrue( referrers.isEmpty(),
            "Plural form self-reference (WikiWords→WikiWord) must be silently skipped" );
    }

    // =========================================================================
    // getReferenceList — plural-match branch where only the plural variant is stored
    // =========================================================================

    /**
     * When English plurals are enabled and "PageBs" (the plural) is stored in referredBy,
     * {@code findReferrers("PageB")} (singular) must still find the referrer recorded under
     * the plural. This hits the {@code refs == null, refs = refs2} branch in
     * {@code getReferenceList}.
     */
    @Test
    void getReferenceList_singularLookup_matchesPluralEntry() throws ProviderException {
        final WikiEngine pluralEngine = MockEngineBuilder.engine()
            .property( com.wikantik.api.core.Engine.PROP_MATCHPLURALS, "true" )
            .with( PageManager.class, pageManager )
            .with( AttachmentManager.class, attachmentManager )
            .build();
        when( pluralEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( pluralEngine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager pluralMgr =
            new DefaultReferenceManager( pluralEngine, pageManager, attachmentManager );

        // Referrer links to the PLURAL form "PageBs"
        pluralMgr.updateReferences( "Referrer", List.of( "PageBs" ) );

        // findReferrers for the SINGULAR "PageB" must find "Referrer" via plural matching
        final Set< String > referrers = pluralMgr.findReferrers( "PageB" );
        assertTrue( referrers.contains( "Referrer" ),
            "findReferrers('PageB') must match the plural-stored entry 'PageBs' when English plurals enabled" );
    }

    /**
     * When English plurals are enabled and BOTH the singular and plural forms have entries
     * in referredBy, {@code findReferrers} must merge them. This exercises the
     * {@code refs != null → refs.addAll(refs2)} branch in {@code getReferenceList}.
     */
    @Test
    void getReferenceList_bothSingularAndPluralHaveReferrers_mergedResult() throws ProviderException {
        final WikiEngine pluralEngine = MockEngineBuilder.engine()
            .property( com.wikantik.api.core.Engine.PROP_MATCHPLURALS, "true" )
            .with( PageManager.class, pageManager )
            .with( AttachmentManager.class, attachmentManager )
            .build();
        when( pluralEngine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( pluralEngine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager pluralMgr =
            new DefaultReferenceManager( pluralEngine, pageManager, attachmentManager );

        // One referrer links to singular "Cat", another to plural "Cats"
        pluralMgr.updateReferences( "PageSingular", List.of( "Cat" ) );
        pluralMgr.updateReferences( "PagePlural", List.of( "Cats" ) );

        // findReferrers("Cat") must return both referrers (singular entry + plural entry merged)
        final Set< String > referrers = pluralMgr.findReferrers( "Cat" );
        assertTrue( referrers.contains( "PageSingular" ),
            "Direct referrer to 'Cat' must be present" );
        assertTrue( referrers.contains( "PagePlural" ),
            "Referrer to plural 'Cats' must also appear via plural matching" );
    }
}
