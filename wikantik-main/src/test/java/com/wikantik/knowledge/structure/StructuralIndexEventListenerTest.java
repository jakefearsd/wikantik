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
package com.wikantik.knowledge.structure;

import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class StructuralIndexEventListenerTest {

    @Test
    void post_save_triggers_onPageSaved() {
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );

        final WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "HybridRetrieval" );
        listener.actionPerformed( evt );

        verify( svc, times( 1 ) ).onPageSaved( "HybridRetrieval" );
    }

    @Test
    void page_deleted_triggers_onPageDeleted() {
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );

        final WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "GoneBaby" );
        listener.actionPerformed( evt );

        verify( svc, times( 1 ) ).onPageDeleted( "GoneBaby" );
    }

    @Test
    void other_events_are_ignored() {
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );

        final WikiPageEvent evt = new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "X" );
        listener.actionPerformed( evt );

        verifyNoInteractions( svc );
    }

    @Test
    void post_save_end_event_reaches_on_page_saved() {
        // Production fires POST_SAVE_END (not the bare POST_SAVE) from DefaultFilterManager
        // during doPostSaveFiltering. Mirror that wiring: register on a FilterManager-shaped
        // source, fire from the same source, and assert the listener routed the event through
        // to onPageSaved.
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );
        final FilterManager filterManager = mock( FilterManager.class );
        final PageManager pageManager = mock( PageManager.class );
        try {
            listener.register( pageManager, filterManager );

            WikiEventManager.fireEvent( filterManager,
                new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "RestSavedPage" ) );

            verify( svc, times( 1 ) ).onPageSaved( "RestSavedPage" );
        } finally {
            WikiEventManager.removeWikiEventListener( filterManager, listener );
            WikiEventManager.removeWikiEventListener( pageManager, listener );
        }
    }

    @Test
    void page_deleted_event_from_page_manager_reaches_on_page_deleted() {
        // PAGE_DELETED is still fired from DefaultPageManager (source = PageManager),
        // so the listener must keep listening on that source too.
        final DefaultStructuralIndexService svc = mock( DefaultStructuralIndexService.class );
        final StructuralIndexEventListener listener = new StructuralIndexEventListener( svc );
        final FilterManager filterManager = mock( FilterManager.class );
        final PageManager pageManager = mock( PageManager.class );
        try {
            listener.register( pageManager, filterManager );

            WikiEventManager.fireEvent( pageManager,
                new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "GoneBaby" ) );

            verify( svc, times( 1 ) ).onPageDeleted( "GoneBaby" );
        } finally {
            WikiEventManager.removeWikiEventListener( filterManager, listener );
            WikiEventManager.removeWikiEventListener( pageManager, listener );
        }
    }
}
