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
package com.wikantik.core.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.blog.BlogManager;
import com.wikantik.content.RecentArticlesManager;

/**
 * Adapter that synthesises a sparse {@link CoreSubsystem.Services} record
 * from {@link Engine#getManager(Class)} lookups, mirroring
 * {@code KnowledgeSubsystemBridge}.
 *
 * <p>Used by {@code RestServletBase.getSubsystems()} when a test harness
 * built the engine via {@code TestEngine.setManager(...)} rather than a
 * full {@code WikiEngine.initialize()} cycle. Production paths use the
 * authoritative {@code WikiSubsystems} bundle stashed on the
 * {@link jakarta.servlet.ServletContext} at boot.</p>
 *
 * <p>Properties, event bus, and metrics registry are constructed fresh
 * (typed wrappers around the engine's raw {@link java.util.Properties},
 * the static {@code WikiEventManager}, and a dedicated
 * {@code SimpleMeterRegistry}). Leaf-manager fields fall back to {@code null}
 * when no manager is registered, matching the legacy {@code getManager()}
 * behavior.</p>
 */
public final class CoreSubsystemBridge {

    private CoreSubsystemBridge() {}

    public static CoreSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a minimal all-null record.
            final java.util.Properties raw = engine.getWikiProperties() != null
                ? engine.getWikiProperties()
                : new java.util.Properties();
            return new CoreSubsystem.Services(
                new DefaultWikiProperties( raw ),
                new DefaultWikiEventBus(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                null, null, null, null, null, null, null, null, null );
        }
        final CoreSubsystem.Services typed = wikiEngine.getCoreSubsystem();
        if ( typed != null ) return typed;
        // Snapshot not yet built (mid-initialize path) — synthesise from registry.
        // Post-initialize paths (setManager hot-swaps) rebuild the snapshot directly,
        // so tests reaching this branch return a coherent record.
        return rebuildFromManagers( wikiEngine );
    }

    /**
     * Synthesises a {@link CoreSubsystem.Services} record directly from the
     * {@code WikiEngine}'s manager registry. Called by
     * {@link com.wikantik.WikiEngine#setManager} whenever a core manager is
     * hot-swapped (e.g. by a unit test installing a mock) so that the typed
     * snapshot stays coherent without requiring a full re-initialization cycle.
     *
     * <p>Delegates to {@link CoreSubsystemFactory#create} using a {@link CoreSubsystem.Deps}
     * synthesised from the engine's manager registry.</p>
     */
    public static CoreSubsystem.Services rebuildFromManagers( final com.wikantik.WikiEngine engine ) {
        return CoreSubsystemFactory.create( synthDepsFromEngine( engine ) );
    }

    private static CoreSubsystem.Deps synthDepsFromEngine( final com.wikantik.WikiEngine engine ) {
        final java.util.Properties raw = engine.getWikiProperties() != null
            ? engine.getWikiProperties()
            : new java.util.Properties();
        return new CoreSubsystem.Deps(
            raw,
            /* servletContext= */ null,
            /* meterRegistry= */ null,
            engine.getManager( SystemPageRegistry.class ),
            engine.getManager( RecentArticlesManager.class ),
            engine.getManager( BlogManager.class ),
            engine
        );
    }
}
