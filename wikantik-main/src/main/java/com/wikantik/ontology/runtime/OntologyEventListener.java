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
import com.wikantik.content.PageRenamer;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Queues wiki page events for {@link OntologyPageSyncQueue}: save events arrive from the
 * FilterManager, PAGE_DELETED from the PageManager and renames from the PageRenamer — so register
 * on all three. Only enqueues: the TDB2
 * writes happen on the queue's worker thread, never on the saving request thread.
 *
 * <p>Saves are recognised by {@code POST_SAVE_END} alone. Every save path (REST PUT/PATCH, MCP
 * writes, import, derived ingest) goes through {@code PageSaveHelper} → {@code PageManager.saveText}
 * → {@code DefaultFilterManager.doPostSaveFiltering}, which fires it exactly once per save. The
 * in-phase {@code POST_SAVE} only comes from a {@code PageEventFilter}, which is fired from that
 * same filter loop; handling it too would project every save twice wherever one is configured.</p>
 */
public final class OntologyEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( OntologyEventListener.class );

    private final OntologyPageSyncQueue queue;

    public OntologyEventListener( final OntologyPageSyncQueue queue ) {
        this.queue = queue;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        WikiEventManager.addWikiEventListener( filterManager, this );
        LOG.info( "Ontology event listener registered for PageManager + FilterManager events" );
    }

    /**
     * As {@link #register(PageManager, FilterManager)}, plus the renamer: {@code DefaultPageRenamer}
     * fires its {@link WikiPageRenameEvent} keyed on itself, never on the PageManager, so without
     * this a renamed page's graph stays under its old IRI until the nightly reconcile.
     */
    public void register( final PageManager pageManager, final FilterManager filterManager,
                          final PageRenamer pageRenamer ) {
        register( pageManager, filterManager );
        if ( pageRenamer != null ) {
            WikiEventManager.addWikiEventListener( pageRenamer, this );
            LOG.info( "Ontology event listener registered for PageRenamer events" );
        }
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiPageRenameEvent rename ) {
            queue.renamed( rename.getOldPageName(), rename.getNewPageName() );
            return;
        }
        if ( !( event instanceof WikiPageEvent pageEvent ) ) {
            return;
        }
        switch ( pageEvent.getType() ) {
            case WikiPageEvent.POST_SAVE_END -> queue.saved( pageEvent.getPageName() );
            case WikiPageEvent.PAGE_DELETED  -> queue.deleted( pageEvent.getPageName() );
            default                          -> { /* ignore — incl. in-phase POST_SAVE, see class doc */ }
        }
    }
}
