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
package com.wikantik.mcp.resources;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.api.managers.PageManager;
import com.wikantik.filters.FilterManager;

/**
 * Bridges JSPWiki's WikiEvent system to MCP resource subscriptions,
 * notifying MCP clients when wiki pages are saved or deleted.
 *
 * <p>Save-side events fire from {@code DefaultFilterManager} ({@code POST_SAVE_END},
 * source = {@link FilterManager}); deletes fire from {@link PageManager}. The
 * bridge therefore registers on both sources, exactly like
 * {@code StructuralIndexEventListener}. Registering on PageManager alone would
 * silently miss every REST-driven save.</p>
 *
 * <p>The owning {@code McpServerInitializer} must hold a strong reference to
 * this bridge — {@link WikiEventManager} keeps listeners as
 * {@link java.lang.ref.WeakReference}s, so a locally-scoped bridge would be
 * GC'd between events.</p>
 */
public class WikiEventSubscriptionBridge implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( WikiEventSubscriptionBridge.class );

    private final McpSyncServer mcpServer;

    public WikiEventSubscriptionBridge( final McpSyncServer mcpServer ) {
        this.mcpServer = mcpServer;
    }

    public void register( final PageManager pageManager, final FilterManager filterManager ) {
        WikiEventManager.addWikiEventListener( pageManager, this );
        WikiEventManager.addWikiEventListener( filterManager, this );
        LOG.info( "MCP resource subscription bridge registered for PageManager + FilterManager events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( !( event instanceof WikiPageEvent pageEvent ) ) {
            return;
        }

        final int type = pageEvent.getType();
        if ( type == WikiPageEvent.POST_SAVE_END
                || type == WikiPageEvent.POST_SAVE
                || type == WikiPageEvent.PAGE_DELETED ) {
            final String pageName = pageEvent.getPageName();
            try {
                mcpServer.notifyResourcesUpdated(
                        new McpSchema.ResourcesUpdatedNotification( "wiki://pages/" + pageName ) );
            } catch ( final Exception e ) {
                LOG.debug( "Failed to send MCP resource update notification for {}: {}", pageName, e.getMessage() );
            }
        }
    }
}
