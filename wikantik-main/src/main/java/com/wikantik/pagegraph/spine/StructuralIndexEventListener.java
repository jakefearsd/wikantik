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
import com.wikantik.content.PageRenamer;
import com.wikantik.event.PageLifecycleListener;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forwards wiki {@link WikiPageEvent}s to the {@link DefaultStructuralIndexService}.
 * Wired from the {@code WikiEngine} bootstrap.
 *
 * <p>The save-side events fired in production come from
 * {@code DefaultFilterManager.fireEvent(...)} during {@code doPostSaveFiltering},
 * with the {@link FilterManager} as the event source — not the
 * {@link PageManager}. {@code PAGE_DELETED} is fired from {@link PageManager}.
 * The listener therefore registers on both sources and routes
 * {@code POST_SAVE_END} to {@code onPageSaved}, and {@code PAGE_DELETED} to
 * {@code onPageDeleted}. The bare {@code POST_SAVE} is deliberately ignored: a
 * {@code PageEventFilter} fires it as well, so handling both would run the sync twice per save.</p>
 */
public class StructuralIndexEventListener extends PageLifecycleListener {

    private static final Logger LOG = LogManager.getLogger( StructuralIndexEventListener.class );

    private final DefaultStructuralIndexService service;

    public StructuralIndexEventListener( final DefaultStructuralIndexService service ) {
        this.service = service;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager ) {
        listenTo( pageManager, filterManager );
        LOG.info( "Structural index event listener registered for PageManager + FilterManager events" );
    }

    /**
     * As {@link #register(PageManager, FilterManager)}, plus the renamer: {@code DefaultPageRenamer}
     * fires its {@link WikiPageRenameEvent} keyed on itself, never on the PageManager, so without
     * this the index keeps the canonical_id pointed at the old slug until the page is next saved.
     */
    public void register( final PageManager pageManager, final FilterManager filterManager,
                          final PageRenamer pageRenamer ) {
        register( pageManager, filterManager );
        if ( pageRenamer != null ) {
            listenTo( pageRenamer );
            LOG.info( "Structural index event listener registered for PageRenamer events" );
        }
    }

    @Override
    protected void onSaved( final String page ) {
        service.onPageSaved( page );
    }

    @Override
    protected void onDeleted( final String page ) {
        service.onPageDeleted( page );
    }

    @Override
    protected void onRenamed( final String from, final String to ) {
        service.onPageRenamed( from, to );
    }

    @Override
    protected String label() {
        return "structural index";
    }
}
