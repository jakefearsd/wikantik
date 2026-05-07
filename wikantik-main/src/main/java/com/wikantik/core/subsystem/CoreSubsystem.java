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
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletContext;

import java.util.Properties;

/**
 * Namespace for the Core subsystem's input and output contracts.
 *
 * <p>Phase 2 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Core is the foundation that every other subsystem depends on: typed
 * properties access, the wiki event bus, the shared metrics registry, and
 * the small leaf managers ({@link SystemPageRegistry},
 * {@link RecentArticlesManager}, {@link BlogManager}) that don't justify
 * their own subsystem boundary.</p>
 */
public final class CoreSubsystem {

    private CoreSubsystem() {}

    /**
     * What the Core subsystem requires from upstream (effectively the
     * raw inputs available at engine boot).
     *
     * <p>{@code servletContext} may be {@code null} for non-servlet
     * engines (test harnesses). {@code meterRegistry} may be {@code null};
     * the factory falls back to an in-process {@code SimpleMeterRegistry}
     * that won't be scraped at {@code /observability/metrics}. {@code engine}
     * is the legacy manager registry seam — used to pull {@link CachingManager}
     * which is initialized early in Phase 1 of {@code WikiEngine.initialize()}.</p>
     */
    public record Deps(
        Properties rawProperties,
        ServletContext servletContext,
        MeterRegistry meterRegistry,
        SystemPageRegistry systemPageRegistry,
        RecentArticlesManager recentArticlesManager,
        BlogManager blogManager,
        Engine engine
    ) {}

    /**
     * What the Core subsystem exposes to downstream consumers.
     *
     * <p>Every field is non-null after a successful
     * {@link CoreSubsystemFactory#create} call. The {@code cachingManager}
     * field is added in Phase 9 Ckpt 2.</p>
     */
    public record Services(
        WikiProperties properties,
        WikiEventBus eventBus,
        MeterRegistry meterRegistry,
        SystemPageRegistry systemPageRegistry,
        RecentArticlesManager recentArticlesManager,
        BlogManager blogManager,
        CachingManager cachingManager,
        VariableManager variableManager,
        ProgressManager progressManager,
        CommandResolver commandResolver,
        URLConstructor urlConstructor,
        InternationalizationManager i18n
    ) {}
}
