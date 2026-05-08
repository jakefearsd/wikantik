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

import com.wikantik.WikiEngine;
import com.wikantik.cache.CachingManager;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.progress.ProgressManager;
import com.wikantik.url.URLConstructor;
import com.wikantik.variables.VariableManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Objects;

/**
 * Builds {@link CoreSubsystem.Services} from {@link CoreSubsystem.Deps}.
 *
 * <p>Phase 2 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Pure: no static engine lookups, no servlet-context reads beyond what
 * arrives via {@link CoreSubsystem.Deps}. Tests build it without
 * {@code TestEngine}.</p>
 */
public final class CoreSubsystemFactory {

    private CoreSubsystemFactory() {}

    public static CoreSubsystem.Services create( final CoreSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        Objects.requireNonNull( deps.rawProperties(), "rawProperties" );
        Objects.requireNonNull( deps.engine(), "engine" );
        // systemPageRegistry, recentArticlesManager, and blogManager are optional
        // (null in mid-initialize and test-fixture paths that haven't registered them yet).

        final WikiProperties properties = new DefaultWikiProperties( deps.rawProperties() );
        final WikiEventBus eventBus = new DefaultWikiEventBus();
        final MeterRegistry meters =
            deps.meterRegistry() != null ? deps.meterRegistry() : new SimpleMeterRegistry();
        final WikiEngine wikiEngine = ( WikiEngine ) deps.engine();
        final CachingManager cachingManager = wikiEngine.getManager( CachingManager.class );
        final VariableManager variableManager = wikiEngine.getManager( VariableManager.class );
        final ProgressManager progressManager = wikiEngine.getManager( ProgressManager.class );
        final CommandResolver commandResolver = wikiEngine.getManager( CommandResolver.class );
        final URLConstructor urlConstructor = wikiEngine.getManager( URLConstructor.class );
        final InternationalizationManager i18n = wikiEngine.getManager( InternationalizationManager.class );

        return new CoreSubsystem.Services(
            properties,
            eventBus,
            meters,
            deps.systemPageRegistry(),
            deps.recentArticlesManager(),
            deps.blogManager(),
            cachingManager,
            variableManager,
            progressManager,
            commandResolver,
            urlConstructor,
            i18n
        );
    }
}
