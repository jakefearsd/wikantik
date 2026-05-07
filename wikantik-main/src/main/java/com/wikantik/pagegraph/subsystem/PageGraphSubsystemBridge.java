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
package com.wikantik.pagegraph.subsystem;

import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageGraphService;
import com.wikantik.api.pagegraph.StructuralIndexService;

/**
 * Adapter that synthesises a sparse {@link PageGraphSubsystem.Services}
 * record from {@link Engine#getManager(Class)} lookups, mirroring the
 * other subsystem bridges.
 *
 * <p>Phase 9 Checkpoint 1 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/plans/2026-05-07-decomposition-phase-9-engine-simplification.md}.</p>
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
public final class PageGraphSubsystemBridge {

    private PageGraphSubsystemBridge() {}

    /**
     * Returns the Page Graph subsystem services for {@code engine}.
     *
     * <p>Checks for the typed accessor on {@link com.wikantik.WikiEngine}
     * first (returns the pre-built snapshot when available). Falls through
     * to factory synthesis from the legacy manager registry for
     * {@code TestEngine} and non-WikiEngine callers.</p>
     *
     * @param engine  the wiki engine (must not be null)
     * @return a (possibly sparsely populated) Services record; never null
     */
    public static PageGraphSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new PageGraphSubsystem.Services( null, null, null, null );
        }
        final PageGraphSubsystem.Services typed = wikiEngine.getPageGraphSubsystem();
        if ( typed != null ) return typed;

        final StructuralIndexService     structuralIndexService     =
            wikiEngine.getManager( StructuralIndexService.class );
        final PageGraphService           pageGraphService           =
            wikiEngine.getManager( PageGraphService.class );
        final ReferenceManager           referenceManager           =
            wikiEngine.getManager( ReferenceManager.class );
        final ContentIndexRebuildService contentIndexRebuildService =
            wikiEngine.getManager( ContentIndexRebuildService.class );

        return new PageGraphSubsystem.Services(
            structuralIndexService,
            pageGraphService,
            referenceManager,
            contentIndexRebuildService );
    }
}
