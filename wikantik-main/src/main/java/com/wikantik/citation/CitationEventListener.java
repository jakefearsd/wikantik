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
package com.wikantik.citation;

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bridges page save/rename events to {@link CitationSync}. Mirrors
 * {@code OntologyEventListener}: save events arrive from the FilterManager
 * (doPostSaveFiltering), so register on it (and the PageManager). Only POST_SAVE_END is
 * handled: the bare POST_SAVE (fired by a PageEventFilter) is ignored so a save is never synced twice.
 * Deletes are not handled here — see {@link CitationSync#onPageRetired}.
 */
public final class CitationEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( CitationEventListener.class );

    private final CitationSync sync;

    public CitationEventListener( final CitationSync sync ) {
        this.sync = sync;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        WikiEventManager.addWikiEventListener( filterManager, this );
        LOG.info( "Citation event listener registered for PageManager + FilterManager events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        try {
            if ( event instanceof WikiPageRenameEvent rename ) {
                sync.onPageRenamed( rename.getOldPageName(), rename.getNewPageName() );
                return;
            }
            if ( !( event instanceof WikiPageEvent pageEvent ) ) {
                return;
            }
            switch ( pageEvent.getType() ) {
                case WikiPageEvent.POST_SAVE_END -> sync.onPageSaved( pageEvent.getPageName() );
                // PAGE_DELETED is deliberately not handled here: deletes reach CitationSync through
                // the structural index's retirement hook (CitationSync#onPageRetired), which knows
                // whether the canonical_id is truly gone — this listener's position relative to the
                // index's own PAGE_DELETED listener is undefined.
                default                          -> { /* ignore */ }
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "citation event handling failed for {}: {}", event, e.getMessage(), e );
        }
    }
}
