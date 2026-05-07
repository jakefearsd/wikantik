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
import com.wikantik.cache.CachingManager;
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
        Objects.requireNonNull( deps.systemPageRegistry(), "systemPageRegistry" );
        Objects.requireNonNull( deps.recentArticlesManager(), "recentArticlesManager" );
        Objects.requireNonNull( deps.blogManager(), "blogManager" );
        Objects.requireNonNull( deps.engine(), "engine" );

        final WikiProperties properties = new DefaultWikiProperties( deps.rawProperties() );
        final WikiEventBus eventBus = new DefaultWikiEventBus();
        final MeterRegistry meters =
            deps.meterRegistry() != null ? deps.meterRegistry() : new SimpleMeterRegistry();
        final CachingManager cachingManager = deps.engine().getManager( CachingManager.class );

        return new CoreSubsystem.Services(
            properties,
            eventBus,
            meters,
            deps.systemPageRegistry(),
            deps.recentArticlesManager(),
            deps.blogManager(),
            cachingManager
        );
    }
}
