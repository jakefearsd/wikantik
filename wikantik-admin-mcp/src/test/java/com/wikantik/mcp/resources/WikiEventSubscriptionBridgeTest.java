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

import com.wikantik.api.managers.PageManager;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WikiEventSubscriptionBridgeTest {

    private McpSyncServer mcpServer;
    private WikiEventSubscriptionBridge bridge;
    private PageManager pageManager;
    private FilterManager filterManager;

    @BeforeEach
    void setUp() {
        mcpServer = mock( McpSyncServer.class );
        bridge = new WikiEventSubscriptionBridge( mcpServer );
        pageManager = mock( PageManager.class );
        filterManager = mock( FilterManager.class );
    }

    @AfterEach
    void tearDown() {
        WikiEventManager.removeWikiEventListener( pageManager, bridge );
        WikiEventManager.removeWikiEventListener( filterManager, bridge );
    }

    @Test
    void post_save_end_event_from_filter_manager_notifies_mcp_server() {
        // Production fires POST_SAVE_END (not the bare POST_SAVE) from
        // DefaultFilterManager. Mirror StructuralIndexEventListener: register on a
        // FilterManager-shaped source and assert the bridge routes it through.
        bridge.register( pageManager, filterManager );

        WikiEventManager.fireEvent( filterManager,
                new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "RestSavedPage" ) );

        final ArgumentCaptor< McpSchema.ResourcesUpdatedNotification > cap =
                ArgumentCaptor.forClass( McpSchema.ResourcesUpdatedNotification.class );
        verify( mcpServer, times( 1 ) ).notifyResourcesUpdated( cap.capture() );
        assertEquals( "wiki://pages/RestSavedPage", cap.getValue().uri() );
    }

    @Test
    void page_deleted_event_from_page_manager_notifies_mcp_server() {
        bridge.register( pageManager, filterManager );

        WikiEventManager.fireEvent( pageManager,
                new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "GoneBaby" ) );

        final ArgumentCaptor< McpSchema.ResourcesUpdatedNotification > cap =
                ArgumentCaptor.forClass( McpSchema.ResourcesUpdatedNotification.class );
        verify( mcpServer, times( 1 ) ).notifyResourcesUpdated( cap.capture() );
        assertEquals( "wiki://pages/GoneBaby", cap.getValue().uri() );
    }

    @Test
    void bare_post_save_still_routes_through_for_back_compat() {
        // Kept so that if a PageEventFilter is ever wired in (it isn't in production
        // today, but the class exists), saves don't go silent.
        bridge.register( pageManager, filterManager );

        WikiEventManager.fireEvent( filterManager,
                new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "PageEventFilterPath" ) );

        verify( mcpServer, times( 1 ) ).notifyResourcesUpdated(
                new McpSchema.ResourcesUpdatedNotification( "wiki://pages/PageEventFilterPath" ) );
    }

    @Test
    void other_events_are_ignored() {
        bridge.register( pageManager, filterManager );

        WikiEventManager.fireEvent( pageManager,
                new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "X" ) );

        verifyNoInteractions( mcpServer );
    }
}
