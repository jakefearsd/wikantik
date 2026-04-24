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

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forwards wiki {@link WikiPageEvent}s to the {@link DefaultStructuralIndexService}.
 * Wired from {@link com.wikantik.knowledge.mcp.KnowledgeMcpInitializer} or the
 * WikiEngine bootstrap, following the same pattern as
 * {@code com.wikantik.mcp.resources.WikiEventSubscriptionBridge}.
 */
public class StructuralIndexEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( StructuralIndexEventListener.class );

    private final DefaultStructuralIndexService service;

    public StructuralIndexEventListener( final DefaultStructuralIndexService service ) {
        this.service = service;
    }

    public void register( final PageManager pageManager ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        LOG.info( "Structural index event listener registered for PageManager events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( !( event instanceof WikiPageEvent pageEvent ) ) {
            return;
        }
        switch ( pageEvent.getType() ) {
            case WikiPageEvent.POST_SAVE    -> service.onPageSaved( pageEvent.getPageName() );
            case WikiPageEvent.PAGE_DELETED -> service.onPageDeleted( pageEvent.getPageName() );
            default                         -> { /* ignore other event types */ }
        }
    }
}
