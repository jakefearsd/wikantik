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

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.filters.FilterManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StructuralIndexEventListenerTest {

    private DefaultStructuralIndexService svc;
    private StructuralIndexEventListener listener;
    private FilterManager filterManager;
    private PageManager pageManager;

    @BeforeEach
    void setUp() {
        svc = mock( DefaultStructuralIndexService.class );
        listener = new StructuralIndexEventListener( svc );
        filterManager = mock( FilterManager.class );
        pageManager = mock( PageManager.class );
    }

    @AfterEach
    void tearDown() {
        WikiEventManager.removeWikiEventListener( filterManager, listener );
        WikiEventManager.removeWikiEventListener( pageManager, listener );
    }

    /** The Page Graph snapshot is dropped after the index has taken each change in, so a rebuild never reads the old page. */
    @Test
    void the_after_change_hook_runs_after_each_save_delete_and_rename_is_indexed() {
        final Runnable afterChange = mock( Runnable.class );
        final StructuralIndexEventListener hooked = new StructuralIndexEventListener( svc, afterChange );
        final org.mockito.InOrder order = inOrder( svc, afterChange );

        hooked.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "Saved" ) );
        order.verify( svc ).onPageSaved( "Saved" );
        order.verify( afterChange ).run();

        hooked.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "Gone" ) );
        order.verify( svc ).onPageDeleted( "Gone" );
        order.verify( afterChange ).run();

        hooked.actionPerformed( new WikiPageRenameEvent( this, "Old", "New" ) );
        order.verify( svc ).onPageRenamed( "Old", "New" );
        order.verify( afterChange ).run();

        hooked.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "Ignored" ) );
        verifyNoMoreInteractions( afterChange );
    }

    @Test
    void the_after_change_hook_still_runs_when_indexing_the_change_fails() {
        final Runnable afterChange = mock( Runnable.class );
        doThrow( new IllegalStateException( "index down" ) ).when( svc ).onPageSaved( "Saved" );
        final StructuralIndexEventListener hooked = new StructuralIndexEventListener( svc, afterChange );
        try {
            hooked.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "Saved" ) );
        } catch ( final IllegalStateException expected ) {
            // whether the listener base swallows it is not this test's concern
        }
        verify( afterChange ).run();
    }

    @Test
    void post_save_does_not_trigger_onPageSaved() {
        // A save fires POST_SAVE_END once; reacting to POST_SAVE as well would run the sync twice per save.
        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "HybridRetrieval" ) );

        verifyNoInteractions( svc );
    }

    @Test
    void post_save_end_triggers_onPageSaved_once() {
        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "HybridRetrieval" ) );

        verify( svc, times( 1 ) ).onPageSaved( "HybridRetrieval" );
    }

    @Test
    void page_deleted_triggers_onPageDeleted() {
        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "GoneBaby" ) );

        verify( svc, times( 1 ) ).onPageDeleted( "GoneBaby" );
    }

    @Test
    void other_events_are_ignored() {
        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "X" ) );

        verifyNoInteractions( svc );
    }

    @Test
    void post_save_end_event_reaches_on_page_saved() {
        // Production fires POST_SAVE_END (not the bare POST_SAVE) from DefaultFilterManager
        // during doPostSaveFiltering. Mirror that wiring: register on a FilterManager-shaped
        // source, fire from the same source, and assert the listener routed it through.
        listener.register( pageManager, filterManager );

        WikiEventManager.fireEvent( filterManager,
            new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "RestSavedPage" ) );

        verify( svc, times( 1 ) ).onPageSaved( "RestSavedPage" );
    }

    @Test
    void page_deleted_event_from_page_manager_reaches_on_page_deleted() {
        // PAGE_DELETED is still fired from DefaultPageManager (source = PageManager),
        // so the listener must keep listening on that source too.
        listener.register( pageManager, filterManager );

        WikiEventManager.fireEvent( pageManager,
            new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "GoneBaby" ) );

        verify( svc, times( 1 ) ).onPageDeleted( "GoneBaby" );
    }

    @Test
    void rename_event_from_the_renamer_reaches_on_page_renamed() {
        // DefaultPageRenamer fires WikiPageRenameEvent keyed on ITSELF, never on the PageManager —
        // a listener registered only on PageManager + FilterManager never hears a rename, and the
        // index keeps pointing the canonical_id at the old slug.
        final com.wikantik.content.PageRenamer renamer = mock( com.wikantik.content.PageRenamer.class );
        listener.register( pageManager, filterManager, renamer );
        try {
            WikiEventManager.fireEvent( renamer, new WikiPageRenameEvent( renamer, "OldName", "NewName" ) );

            verify( svc, times( 1 ) ).onPageRenamed( "OldName", "NewName" );
            verify( svc, never() ).onPageDeleted( anyString() );
        } finally {
            WikiEventManager.removeWikiEventListener( renamer, listener );
        }
    }
}
