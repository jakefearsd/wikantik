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
import com.wikantik.cache.CachingManager;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.progress.ProgressManager;
import com.wikantik.url.URLConstructor;
import com.wikantik.variables.VariableManager;

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

        final java.util.Properties raw = wikiEngine.getWikiProperties() != null
            ? wikiEngine.getWikiProperties()
            : new java.util.Properties();
        return new CoreSubsystem.Services(
            new DefaultWikiProperties( raw ),
            new DefaultWikiEventBus(),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            wikiEngine.getManager( SystemPageRegistry.class ),
            wikiEngine.getManager( RecentArticlesManager.class ),
            wikiEngine.getManager( BlogManager.class ),
            wikiEngine.getManager( CachingManager.class ),
            wikiEngine.getManager( VariableManager.class ),
            wikiEngine.getManager( ProgressManager.class ),
            wikiEngine.getManager( CommandResolver.class ),
            wikiEngine.getManager( URLConstructor.class ),
            wikiEngine.getManager( InternationalizationManager.class )
        );
    }
}
