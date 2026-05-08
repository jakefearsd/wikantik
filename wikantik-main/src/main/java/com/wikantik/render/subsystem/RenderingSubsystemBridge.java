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
package com.wikantik.render.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.content.NewsPageGenerator;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.filters.SpamFilter;
import com.wikantik.plugin.PluginManager;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.spam.SpamExternalSignals;
import com.wikantik.render.subsystem.spam.SpamPatternMatcher;
import com.wikantik.render.subsystem.spam.SpamPolicy;
import com.wikantik.render.subsystem.spam.SpamRateLimiter;

/**
 * Adapter that synthesises a sparse {@link RenderingSubsystem.Services}
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
 * {@code null}, mirroring the legacy {@code getManager()} behavior. The
 * four spam-helper fields stay {@code null} in Phase 6 Ckpt 1 — Ckpt 4
 * will extract them off the live {@code SpamFilter} instance.</p>
 */
public final class RenderingSubsystemBridge {

    private RenderingSubsystemBridge() {}

    public static RenderingSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new RenderingSubsystem.Services(
                null, null, null, null, null, null, null, null, null );
        }
        final RenderingSubsystem.Services typed = wikiEngine.getRenderingSubsystem();
        if ( typed != null ) return typed;
        // Snapshot not yet built (mid-initialize path) — synthesise from registry.
        // Post-initialize paths (setManager hot-swaps) rebuild the snapshot directly,
        // so tests reaching this branch return a coherent record.
        return rebuildFromManagers( wikiEngine );
    }

    /**
     * Synthesises a {@link RenderingSubsystem.Services} record directly from the
     * {@code WikiEngine}'s manager registry. Called by
     * {@link com.wikantik.WikiEngine#setManager} whenever a rendering-layer manager
     * is hot-swapped (e.g. by a unit test installing a mock) so that the typed
     * snapshot stays coherent without requiring a full re-initialization cycle.
     */
    public static RenderingSubsystem.Services rebuildFromManagers( final com.wikantik.WikiEngine engine ) {
        final RenderingManager  renderingManager  = engine.getManager( RenderingManager.class );
        final PluginManager     pluginManager     = engine.getManager( PluginManager.class );
        final FilterManager     filterManager     = engine.getManager( FilterManager.class );
        final DifferenceManager differenceManager = engine.getManager( DifferenceManager.class );

        // Extract the decomposed helpers from the registered SpamFilter.
        // Absent SpamFilter (test fixtures) keeps the four slots null.
        final SpamFilter spam = findSpamFilter( filterManager );
        final SpamRateLimiter     spamRateLimiter     = spam != null ? spam.getRateLimiter()     : null;
        final SpamPatternMatcher  spamPatternMatcher  = spam != null ? spam.getPatternMatcher()  : null;
        final SpamExternalSignals spamExternalSignals = spam != null ? spam.getExternalSignals() : null;
        final SpamPolicy          spamPolicy          = spam != null ? spam.getPolicy()          : null;

        final NewsPageGenerator newsPageGenerator = engine.getManager( NewsPageGenerator.class );

        return new RenderingSubsystem.Services(
            renderingManager, pluginManager, filterManager, differenceManager,
            spamRateLimiter, spamPatternMatcher, spamExternalSignals, spamPolicy,
            newsPageGenerator );
    }

    private static SpamFilter findSpamFilter( final FilterManager filterManager ) {
        if ( filterManager == null ) return null;
        try {
            return filterManager.getFilterList().stream()
                .filter( SpamFilter.class::isInstance )
                .map( SpamFilter.class::cast )
                .findFirst()
                .orElse( null );
        } catch ( final RuntimeException e ) {
            return null;
        }
    }
}
