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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A true delete retires the page's canonical_id and tells the subsystems keyed on it; a rename
 * re-points the id at the new slug and retires nothing.
 */
class DefaultStructuralIndexServiceRetirementTest {

    private static final String CID = "01H8G3Z1K6Q5W7P9X2V4R0T8R1";
    private static final String SHARED = "01KS6S8Z8QYAS6P09AM61S5E2O";

    private PageManager pageManager;
    private PageCanonicalIdsDao dao;
    private DefaultStructuralIndexService svc;
    private final List< String > retired = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void setUp() throws Exception {
        pageManager = mock( PageManager.class );
        dao = mock( PageCanonicalIdsDao.class );
        svc = new DefaultStructuralIndexService( pageManager, dao );
        final Page oldPage = fakePage( "OldName", "canonical_id: " + CID + "\ntitle: Old" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( oldPage ) );
        svc.rebuild();
        svc.addPageRetirementListener( ( slug, cid ) -> retired.add( slug + "=" + cid ) );
    }

    private Page fakePage( final String name, final String frontmatter ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );
        when( pageManager.getPureText( p ) ).thenReturn( "---\n" + frontmatter + "\n---\nbody" );
        return p;
    }

    @Test
    void deletingAnIndexedPageRetiresItsCanonicalId() {
        svc.onPageDeleted( "OldName" );

        assertEquals( List.of( "OldName=" + CID ), retired );
        verify( dao ).delete( CID );
    }

    @Test
    void deletingAnUnknownPageRetiresNothing() {
        svc.onPageDeleted( "NeverIndexed" );

        assertTrue( retired.isEmpty(), retired.toString() );
    }

    @Test
    void theSecondPageDeletedEventOfOneDeleteRetiresOnlyOnce() {
        // A re-delivered PAGE_DELETED (the repository used to fire two per delete) must not retire twice.
        svc.onPageDeleted( "OldName" );
        svc.onPageDeleted( "OldName" );

        assertEquals( 1, retired.size(), retired.toString() );
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() {
        final List< String > later = new ArrayList<>();
        final DefaultStructuralIndexService fresh = new DefaultStructuralIndexService( pageManager, dao );
        fresh.rebuild();
        fresh.addPageRetirementListener( ( slug, cid ) -> { throw new IllegalStateException( "boom" ); } );
        fresh.addPageRetirementListener( ( slug, cid ) -> later.add( slug ) );

        fresh.onPageDeleted( "OldName" );

        assertEquals( List.of( "OldName" ), later );
    }

    @Test
    void renameRepointsTheCanonicalIdAtTheNewSlugAndRetiresNothing() throws Exception {
        final Page renamed = fakePage( "NewName", "canonical_id: " + CID + "\ntitle: Old" );
        when( pageManager.getPage( "NewName" ) ).thenReturn( renamed );

        svc.onPageRenamed( "OldName", "NewName" );

        assertEquals( Optional.of( CID ), svc.resolveCanonicalIdFromSlug( "NewName" ) );
        assertEquals( Optional.empty(), svc.resolveCanonicalIdFromSlug( "OldName" ) );
        assertEquals( Optional.of( "NewName" ), svc.resolveSlugFromCanonicalId( CID ) );
        verify( dao ).upsert( eq( CID ), eq( "NewName" ), any(), any(), any() );
        verify( dao, never() ).delete( anyString() );
        assertTrue( retired.isEmpty(), retired.toString() );

        // A later delete of the renamed page is a true delete under its NEW name.
        svc.onPageDeleted( "NewName" );
        assertEquals( List.of( "NewName=" + CID ), retired );
    }

    @Test
    void renameOfAPageWithoutAnAuthoredCanonicalIdDropsTheOldSlug() throws Exception {
        final Page bare = fakePage( "BareOld", "title: Bare" );
        final Page bareRenamed = fakePage( "BareNew", "title: Bare" );
        when( pageManager.getPage( "BareOld" ) ).thenReturn( bare );
        when( pageManager.getPage( "BareNew" ) ).thenReturn( bareRenamed );
        svc.onPageSaved( "BareOld" );
        assertTrue( svc.resolveCanonicalIdFromSlug( "BareOld" ).isPresent() );

        svc.onPageRenamed( "BareOld", "BareNew" );

        assertTrue( svc.resolveCanonicalIdFromSlug( "BareNew" ).isPresent() );
        assertEquals( Optional.empty(), svc.resolveCanonicalIdFromSlug( "BareOld" ),
                "the old slug must not linger in the projection under its synthesised id" );
        assertTrue( retired.isEmpty(), "a rename never retires: " + retired );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void deletingOneOfTwoPagesSharingACanonicalIdRetiresNothingAndReindexesTheSurvivor() throws Exception {
        // Production carries such a pair (two pages authored with one canonical_id). Retiring the
        // id on either delete would wipe the SURVIVING page's owners, comments and citations.
        final Page first = fakePage( "SharerA", "canonical_id: " + SHARED + "\ntitle: A" );
        final Page second = fakePage( "SharerB", "canonical_id: " + SHARED + "\ntitle: B" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( first, second ) );
        when( pageManager.getPage( "SharerB" ) ).thenReturn( second );
        when( pageManager.pageExists( "SharerB" ) ).thenReturn( true );
        svc.rebuild();
        clearInvocations( dao );

        svc.onPageDeleted( "SharerA" );

        assertTrue( retired.isEmpty(), "a shared id must not be retired while a page still carries it: " + retired );
        verify( dao, never() ).delete( anyString() );
        assertEquals( Optional.of( "SharerB" ), svc.resolveSlugFromCanonicalId( SHARED ) );
        assertEquals( Optional.of( SHARED ), svc.resolveCanonicalIdFromSlug( "SharerB" ) );
        assertEquals( Optional.empty(), svc.resolveCanonicalIdFromSlug( "SharerA" ) );
        verify( dao ).upsert( eq( SHARED ), eq( "SharerB" ), any(), any(), any() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void aSharerTheProjectionLostTrackOfIsFoundThroughTheCanonicalIdRowAndItsSlugHistory() throws Exception {
        // The projection holds one descriptor per canonical_id, so an incremental save can drop the
        // other sharer's slug mapping. The id row's current slug and slug history still name it.
        final Page first = fakePage( "SharerA", "canonical_id: " + SHARED + "\ntitle: A" );
        final Page second = fakePage( "SharerB", "canonical_id: " + SHARED + "\ntitle: B" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( first, second ) );
        when( pageManager.getPage( "SharerA" ) ).thenReturn( first );
        when( pageManager.getPage( "SharerB" ) ).thenReturn( second );
        when( pageManager.pageExists( "SharerB" ) ).thenReturn( true );
        svc.rebuild();
        svc.onPageSaved( "SharerA" );   // the incremental splice drops SharerB's slug mapping
        when( dao.findByCanonicalId( SHARED ) ).thenReturn( Optional.of( new PageCanonicalIdsDao.Row(
                SHARED, "SharerA", "A", "article", null, java.time.Instant.EPOCH, java.time.Instant.EPOCH ) ) );
        when( dao.slugHistory( SHARED ) ).thenReturn( List.of( "SharerB" ) );

        svc.onPageDeleted( "SharerA" );

        assertTrue( retired.isEmpty(), retired.toString() );
        verify( dao, never() ).delete( anyString() );
        assertEquals( Optional.of( "SharerB" ), svc.resolveSlugFromCanonicalId( SHARED ) );
    }

    @Test
    void aPageDeletedEventForAPageThatStillExistsRetiresNothing() throws Exception {
        // A provider whose file delete failed still fires PAGE_DELETED; the page is still there.
        when( pageManager.pageExists( "OldName" ) ).thenReturn( true );

        svc.onPageDeleted( "OldName" );

        assertTrue( retired.isEmpty(), retired.toString() );
        verify( dao, never() ).delete( anyString() );
        assertEquals( Optional.of( CID ), svc.resolveCanonicalIdFromSlug( "OldName" ) );
    }

    @Test
    void aRenameWhoseNewSlugFailsToIndexKeepsTheOldSlugIndexed() throws Exception {
        when( pageManager.getPage( "NewName" ) ).thenThrow( new IllegalStateException( "provider down" ) );

        svc.onPageRenamed( "OldName", "NewName" );

        assertEquals( Optional.of( CID ), svc.resolveCanonicalIdFromSlug( "OldName" ),
                "dropping the old slug before the new one indexed makes the page invisible to the index" );
    }

    @Test
    void aDeleteOfTheOldSlugAfterTheNewSlugWasSavedIsTreatedAsARename() throws Exception {
        // Rename surfaced as save-new then delete-old: the id already lives under the new slug.
        final Page renamed = fakePage( "NewName", "canonical_id: " + CID + "\ntitle: Old" );
        when( pageManager.getPage( "NewName" ) ).thenReturn( renamed );
        svc.onPageSaved( "NewName" );

        svc.onPageDeleted( "OldName" );

        assertTrue( retired.isEmpty(), retired.toString() );
        verify( dao, never() ).delete( anyString() );
    }
}
