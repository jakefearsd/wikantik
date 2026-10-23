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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.managers.PageManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps {@code kg_content_chunks} in step with page deletes and renames, which the save-time
 * {@link ChunkProjector} filter never sees. Chunks are keyed by page name, so a delete drops them
 * and a rename re-keys them; until this existed a deleted page's chunks stayed retrievable (dense
 * retrieval, bundles) until an admin content rebuild swept them.
 */
public final class ChunkLifecycleListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( ChunkLifecycleListener.class );

    private final ChunkProjector projector;

    public ChunkLifecycleListener( final ChunkProjector projector ) {
        this.projector = projector;
    }

    /**
     * Listens for PAGE_DELETED on the page manager and for renames on the renamer —
     * {@code DefaultPageRenamer} fires its {@link WikiPageRenameEvent} keyed on itself, and a
     * rename fires no PAGE_DELETED, so it is never mistaken for a delete. The caller must hold a
     * strong reference to this listener: WikiEventManager keeps listeners weakly.
     */
    public void register( final PageManager pageManager, final PageRenamer pageRenamer ) {
        if ( pageManager != null ) {
            WikiEventManager.addWikiEventListener( pageManager, this );
        }
        if ( pageRenamer != null ) {
            WikiEventManager.addWikiEventListener( pageRenamer, this );
        }
        LOG.info( "Chunk lifecycle listener registered (pageManager={}, pageRenamer={})",
            pageManager != null, pageRenamer != null );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiPageRenameEvent rename ) {
            projector.onPageRenamed( rename.getOldPageName(), rename.getNewPageName() );
        } else if ( event instanceof WikiPageEvent pageEvent && pageEvent.getType() == WikiPageEvent.PAGE_DELETED ) {
            projector.onPageDeleted( pageEvent.getPageName() );
        }
    }
}
