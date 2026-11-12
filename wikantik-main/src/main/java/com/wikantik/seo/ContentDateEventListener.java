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
package com.wikantik.seo;

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.content.PageRenamer;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forwards save, delete and rename events to {@link ContentDateService}. Saves arrive from the FilterManager
 * ({@code POST_SAVE_END}; the bare {@code POST_SAVE} is ignored so a save is never handled twice), deletes
 * from the PageManager, renames from the PageRenamer.
 */
public final class ContentDateEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( ContentDateEventListener.class );

    private final ContentDateService service;

    public ContentDateEventListener( final ContentDateService service ) {
        this.service = service;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager,
                          final PageRenamer pageRenamer ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        WikiEventManager.addWikiEventListener( filterManager, this );
        if( pageRenamer != null ) {
            WikiEventManager.addWikiEventListener( pageRenamer, this );
        }
        LOG.info( "Content date event listener registered" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        try {
            if( event instanceof WikiPageRenameEvent rename ) {
                service.onPageRenamed( rename.getOldPageName(), rename.getNewPageName() );
            } else if( event instanceof WikiPageEvent pageEvent ) {
                switch( pageEvent.getType() ) {
                    case WikiPageEvent.POST_SAVE_END -> service.onPageSaved( pageEvent.getPageName() );
                    case WikiPageEvent.PAGE_DELETED  -> service.onPageDeleted( pageEvent.getPageName() );
                    default                          -> { /* ignore other event types */ }
                }
            }
        } catch( final RuntimeException e ) {
            LOG.warn( "content date event handling failed for {}: {}", event, e.getMessage(), e );
        }
    }
}
