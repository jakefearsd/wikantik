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
package com.wikantik.page.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.content.PageRenamer;
import com.wikantik.page.subsystem.lifecycle.PageLifecycle;
import com.wikantik.page.subsystem.lifecycle.PageLockService;
import com.wikantik.page.subsystem.lifecycle.PageRepository;
import com.wikantik.pages.DefaultPageManager;

/**
 * Adapter that synthesises a sparse {@link PageSubsystem.Services} record
 * from {@link Engine#getManager(Class)} lookups, mirroring the other
 * subsystem bridges.
 *
 * <p>Used by non-servlet callers (plugins, providers, internal managers)
 * and by test fixtures that build the engine via
 * {@code TestEngine.setManager(...)} rather than a full
 * {@code WikiEngine.initialize()} cycle. Production servlet code uses
 * the typed bundle stashed on the {@link jakarta.servlet.ServletContext}.</p>
 *
 * <p>Fields whose corresponding manager is not registered come back as
 * {@code null}, mirroring the legacy {@code getManager()} behavior.</p>
 */
public final class PageSubsystemBridge {

    private PageSubsystemBridge() {}

    public static PageSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new PageSubsystem.Services(
                null, null, null, null, null, null, null, null, null );
        }
        final PageSubsystem.Services typed = wikiEngine.getPageSubsystem();
        if ( typed != null ) return typed;
        // Snapshot not yet built (mid-initialize path) — synthesise from registry.
        // Post-initialize paths (setManager hot-swaps) rebuild the snapshot directly,
        // so tests reaching this branch return a coherent record.
        return rebuildFromManagers( wikiEngine );
    }

    /**
     * Synthesises a {@link PageSubsystem.Services} record directly from the
     * {@code WikiEngine}'s manager registry. Called by
     * {@link com.wikantik.WikiEngine#setManager} whenever a page-layer manager
     * is hot-swapped (e.g. by a unit test installing a mock) so that the typed
     * snapshot stays coherent without requiring a full re-initialization cycle.
     */
    public static PageSubsystem.Services rebuildFromManagers( final com.wikantik.WikiEngine engine ) {
        final PageManager       pages       = engine.getManager( PageManager.class );
        final AttachmentManager attachments = engine.getManager( AttachmentManager.class );
        final PageRenamer       renamer     = engine.getManager( PageRenamer.class );
        final PageSaveHelper    saveHelper  = new PageSaveHelper( engine, pages );
        final PageProvider      provider    = pages != null ? pages.getProvider() : null;
        final ReferenceManager  refMgr      = engine.getManager( ReferenceManager.class );

        PageRepository  pageRepository  = null;
        PageLifecycle   pageLifecycle   = null;
        PageLockService pageLockService = null;
        if ( pages instanceof DefaultPageManager dpm ) {
            pageRepository  = dpm.getRepository();
            pageLifecycle   = dpm.getLifecycle();
            pageLockService = dpm.getLockService();
        }

        return new PageSubsystem.Services( pages, attachments, renamer, saveHelper, provider,
                                           pageRepository, pageLifecycle, pageLockService, refMgr );
    }
}
