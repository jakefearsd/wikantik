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
import com.wikantik.diff.DifferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.plugin.PluginManager;
import com.wikantik.render.RenderingManager;

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
        if ( engine instanceof com.wikantik.WikiEngine wikiEngine ) {
            final RenderingSubsystem.Services typed = wikiEngine.getRenderingSubsystem();
            if ( typed != null ) return typed;
        }

        final RenderingManager  renderingManager  = engine.getManager( RenderingManager.class );
        final PluginManager     pluginManager     = engine.getManager( PluginManager.class );
        final FilterManager     filterManager     = engine.getManager( FilterManager.class );
        final DifferenceManager differenceManager = engine.getManager( DifferenceManager.class );

        return new RenderingSubsystem.Services(
            renderingManager, pluginManager, filterManager, differenceManager,
            /*spamRateLimiter=*/      null,
            /*spamPatternMatcher=*/   null,
            /*spamExternalSignals=*/  null,
            /*spamPolicy=*/           null );
    }
}
