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
package com.wikantik.ontology.runtime;

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
 * Forwards wiki page events to {@link OntologyPageSync}, mirroring
 * {@code StructuralIndexEventListener}: save events arrive from the FilterManager
 * (doPostSaveFiltering), PAGE_DELETED from the PageManager — so register on both.
 */
public final class OntologyEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( OntologyEventListener.class );

    private final OntologyPageSync sync;

    public OntologyEventListener( final OntologyPageSync sync ) {
        this.sync = sync;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        WikiEventManager.addWikiEventListener( filterManager, this );
        LOG.info( "Ontology event listener registered for PageManager + FilterManager events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiPageRenameEvent rename ) {
            sync.onPageRenamed( rename.getOldPageName(), rename.getNewPageName() );
            return;
        }
        if ( !( event instanceof WikiPageEvent pageEvent ) ) {
            return;
        }
        switch ( pageEvent.getType() ) {
            case WikiPageEvent.POST_SAVE_END,
                 WikiPageEvent.POST_SAVE     -> sync.onPageSaved( pageEvent.getPageName() );
            case WikiPageEvent.PAGE_DELETED  -> sync.onPageDeleted( pageEvent.getPageName() );
            default                          -> { /* ignore */ }
        }
    }
}
