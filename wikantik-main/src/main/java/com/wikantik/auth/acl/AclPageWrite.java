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
package com.wikantik.auth.acl;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.page.subsystem.lifecycle.PageLifecycle;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;

/**
 * The write of {@link DefaultAclManager#setPermissions}: the read, rewrite and {@code putPageText} run under the page's
 * save lock, so a guarded save cannot land between the read and the write; then, outside the lock (a listener may save),
 * {@code POST_SAVE_BEGIN} is fired through the filter manager. {@code putPageText} runs no filters and fires no save
 * event, so this is the event the directory watcher fires for an outside write: render caches flush and live
 * collaboration sessions reconcile.
 */
final class AclPageWrite {

    /** The read, rewrite and put, given the page manager. */
    @FunctionalInterface
    interface Rewrite {
        void run( PageManager pageManager ) throws WikiSecurityException;
    }

    private AclPageWrite() {
    }

    static void write( final Engine engine, final String pageName, final Rewrite rewrite ) throws WikiSecurityException {
        final PageSubsystem.Services pages = PageSubsystemBridge.fromLegacyEngine( engine );
        try {
            PageLifecycle.underPageLock( pages.pageLifecycle(), pageName, () -> {
                rewrite.run( pages.pages() );
                return null;
            } );
        } catch( final WikiSecurityException e ) {
            throw e;
        } catch( final WikiException e ) {
            throw new WikiSecurityException( "Could not set the ACL of page " + pageName + ": " + e.getMessage(), e );
        }
        final FilterManager filterManager = RenderingSubsystemBridge.fromLegacyEngine( engine ).filterManager();
        if( filterManager != null && WikiEventManager.isListening( filterManager ) ) {
            CoreSubsystemBridge.fromLegacyEngine( engine ).eventBus().fireEvent( filterManager,
                    new WikiPageEvent( engine, WikiPageEvent.POST_SAVE_BEGIN, pageName ) );
        }
    }
}
