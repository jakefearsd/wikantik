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
package com.wikantik.knowledge.subsystem;

import com.wikantik.api.core.Engine;

/**
 * Delegates to {@link KnowledgeSubsystemFactory#rebuildFromExisting} for snapshot
 * rebuild. Matches the structural pattern of the other 7 {@code *SubsystemBridge}
 * classes after Phase 12 closed the asymmetry — see
 * {@code docs/superpowers/specs/2026-05-14-subsystem-bridge-retirement-design.md}.
 *
 * <p>Used by {@code RestServletBase.getSubsystems()} when a test harness
 * built the engine via {@code TestEngine.setManager(...)} rather than a
 * full {@link Engine#initialize} cycle. Production code paths use the
 * authoritative {@code WikiSubsystems} bundle stashed on the
 * {@link jakarta.servlet.ServletContext} at boot.</p>
 *
 * <p>Fields whose corresponding manager is not registered come back as
 * {@code null}, mirroring the legacy {@code getManager()} behavior and
 * keeping existing test setups working without modification.</p>
 */
public final class KnowledgeSubsystemBridge {

    private KnowledgeSubsystemBridge() {}

    /**
     * Returns the engine's Knowledge subsystem services. Prefers the
     * engine's typed {@code WikiEngine.getKnowledgeSubsystem()} accessor
     * (the production path: services produced by
     * {@link com.wikantik.WikiEngine#initialize}); falls back to a sparse
     * record built from {@code engine.getManager(...)} lookups when the
     * typed accessor is unavailable (e.g. mocked {@code Engine} in unit
     * tests, or a {@code TestEngine} that registered services via
     * {@code setManager(...)} without going through full initialization).
     *
     * <p>The returned record is always non-null but its fields may be
     * {@code null} where no manager is registered (test fixtures only
     * register the services they need).</p>
     */
    public static KnowledgeSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new KnowledgeSubsystem.Services(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null );
        }
        final KnowledgeSubsystem.Services typed = wikiEngine.getKnowledgeSubsystem();
        if ( typed != null ) return typed;
        // Snapshot not yet built (mid-initialize path) — synthesise from registry.
        // Post-initialize paths (setManager hot-swaps) rebuild the snapshot directly,
        // so tests reaching this branch return a coherent record.
        return rebuildFromManagers( wikiEngine );
    }

    /**
     * Rebuilds a {@link KnowledgeSubsystem.Services} snapshot by delegating to
     * {@link KnowledgeSubsystemFactory#rebuildFromExisting}. Called by
     * {@link com.wikantik.WikiEngine#setManager} whenever a knowledge-layer manager
     * is hot-swapped so that the typed snapshot stays coherent without requiring a
     * full re-initialization cycle. No side-effecting construction (cron scheduling,
     * background indexer start) is performed.
     */
    public static KnowledgeSubsystem.Services rebuildFromManagers( final com.wikantik.WikiEngine engine ) {
        return KnowledgeSubsystemFactory.rebuildFromExisting(
            engine, engine.getKnowledgeSubsystem() );
    }
}
