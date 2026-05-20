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
package com.wikantik.search.subsystem;

import com.wikantik.api.core.Engine;

/**
 * Adapter that synthesises a sparse {@link SearchSubsystem.Services}
 * record from {@link Engine#getManager(Class)} lookups, mirroring the
 * other subsystem bridges.
 *
 * <p>Used by non-servlet callers (plugins, providers, internal managers)
 * and by test fixtures that build the engine via
 * {@code TestEngine.setManager(...)} rather than a full
 * {@code WikiEngine.initialize()} cycle. Production servlet code uses
 * the typed bundle stashed on the {@link jakarta.servlet.ServletContext}.</p>
 *
 * <p>Fields whose corresponding manager is not registered come back as
 * {@code null}, mirroring the legacy {@code getManager()} behaviour. The
 * three Lucene helper fields stay {@code null} in Phase 7 Ckpt 1 — Ckpt 4
 * will extract them from the decomposed {@code LuceneSearchProvider}.</p>
 */
public final class SearchSubsystemBridge {

    private SearchSubsystemBridge() {}

    public static SearchSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new SearchSubsystem.Services(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null );
        }
        final SearchSubsystem.Services typed = wikiEngine.getSearchSubsystem();
        if ( typed != null ) return typed;
        // Snapshot not yet built (mid-initialize path) — synthesise from registry.
        // Post-initialize paths (setManager hot-swaps) rebuild the snapshot directly,
        // so tests reaching this branch return a coherent record.
        return rebuildFromManagers( wikiEngine );
    }

    /**
     * Synthesises a {@link SearchSubsystem.Services} record directly from the
     * {@code WikiEngine}'s manager registry. Called by
     * {@link com.wikantik.WikiEngine#setManager} whenever a search-layer manager
     * is hot-swapped (e.g. by a unit test installing a mock) so that the typed
     * snapshot stays coherent without requiring a full re-initialization cycle.
     *
     * <p>Delegates to {@link SearchSubsystemFactory#create} using a
     * {@link SearchSubsystem.Deps} synthesised from the engine's manager
     * registry and sibling subsystem bridges.</p>
     */
    public static SearchSubsystem.Services rebuildFromManagers( final com.wikantik.WikiEngine engine ) {
        return SearchSubsystemFactory.create( synthDepsFromEngine( engine ) );
    }

    private static SearchSubsystem.Deps synthDepsFromEngine( final com.wikantik.WikiEngine engine ) {
        // Pull the DataSource from the persistence subsystem so that hot-swap
        // rebuilds (e.g. unit tests installing a mock via engine.setManager)
        // don't trip on a null-DataSource IllegalStateException when the
        // pgvector backend is configured. The persistence subsystem is
        // available from the first initialize() call; it is null only during
        // very early startup before persistence wiring has run, in which case
        // the pgvector branch won't be reached either.
        final com.wikantik.persistence.subsystem.PersistenceSubsystem.Services persistence =
            engine.getPersistenceSubsystem();
        final javax.sql.DataSource dataSource =
            persistence != null ? persistence.dataSource() : null;
        return new SearchSubsystem.Deps(
            /* dataSource= */  dataSource,
            /* core= */        null,
            /* persistence= */ null,
            /* page= */        null,
            /* knowledge= */   null,
            engine
        );
    }
}
