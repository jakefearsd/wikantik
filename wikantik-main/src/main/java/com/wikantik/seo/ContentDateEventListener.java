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
import com.wikantik.event.PageLifecycleListener;
import com.wikantik.content.PageRenamer;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forwards save, delete and rename events to {@link ContentDateService}. Saves arrive from the FilterManager
 * ({@code POST_SAVE_END}; the bare {@code POST_SAVE} is ignored so a save is never handled twice), deletes
 * from the PageManager, renames from the PageRenamer.
 */
public final class ContentDateEventListener extends PageLifecycleListener {

    private static final Logger LOG = LogManager.getLogger( ContentDateEventListener.class );

    private final ContentDateService service;

    public ContentDateEventListener( final ContentDateService service ) {
        this.service = service;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager,
                          final PageRenamer pageRenamer ) {
        listenTo( pageManager, filterManager, pageRenamer );
        LOG.info( "Content date event listener registered" );
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
        return "content date";
    }
}
