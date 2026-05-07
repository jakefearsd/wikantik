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

import java.util.Objects;

/**
 * Builds {@link RenderingSubsystem.Services} from {@link RenderingSubsystem.Deps}.
 *
 * <p>Phase 6 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/plans/2026-05-07-decomposition-phase-6-rendering-subsystem.md}.</p>
 *
 * <p>Phase 6 Checkpoint 1 locates the four manager-level objects on the
 * engine's legacy registry, mirroring the Page pattern. The four
 * {@code SpamFilter} helper slots ({@link com.wikantik.render.subsystem.spam.SpamRateLimiter},
 * {@link com.wikantik.render.subsystem.spam.SpamPatternMatcher},
 * {@link com.wikantik.render.subsystem.spam.SpamExternalSignals},
 * {@link com.wikantik.render.subsystem.spam.SpamPolicy}) are populated as
 * {@code null} in Ckpt 1; Ckpt 4 extracts them off the live
 * {@code SpamFilter} instance once it has been decomposed in Ckpt 3.</p>
 */
public final class RenderingSubsystemFactory {

    private RenderingSubsystemFactory() {}

    public static RenderingSubsystem.Services create( final RenderingSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        Objects.requireNonNull( deps.core(), "core" );
        final Engine engine = Objects.requireNonNull( deps.engine(), "engine" );

        final RenderingManager  renderingManager  = engine.getManager( RenderingManager.class );
        final PluginManager     pluginManager     = engine.getManager( PluginManager.class );
        final FilterManager     filterManager     = engine.getManager( FilterManager.class );
        final DifferenceManager differenceManager = engine.getManager( DifferenceManager.class );

        // Phase 6 Ckpt 4: pull the decomposed helpers off the registered
        // SpamFilter instance. When SpamFilter is absent (test fixtures
        // that don't register the page-save filters) the four slots stay
        // null — same shape as Ckpt 1.
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
            // Test mocks may not stub getFilterList(); fall back to null
            // rather than blowing up subsystem construction.
            return null;
        }
    }
}
