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
     *
     * <p>Delegates to {@link PageSubsystemFactory#create} using a {@link PageSubsystem.Deps}
     * synthesised from the engine's manager registry and sibling subsystem bridges.</p>
     */
    public static PageSubsystem.Services rebuildFromManagers( final com.wikantik.WikiEngine engine ) {
        return PageSubsystemFactory.create( synthDepsFromEngine( engine ) );
    }

    private static PageSubsystem.Deps synthDepsFromEngine( final com.wikantik.WikiEngine engine ) {
        // core, persistence, and auth are reserved in Deps for future use but are not yet
        // read by PageSubsystemFactory.create. Pass null to avoid cascading getManager calls
        // into sibling subsystem bridges during hot-swap rebuilds.
        return new PageSubsystem.Deps(
            /* core= */        null,
            /* persistence= */ engine.getPersistenceSubsystem(),
            /* auth= */        null,
            engine
        );
    }
}
