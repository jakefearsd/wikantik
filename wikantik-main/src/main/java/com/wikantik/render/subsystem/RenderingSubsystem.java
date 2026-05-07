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
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.content.NewsPageGenerator;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.plugin.PluginManager;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.spam.SpamExternalSignals;
import com.wikantik.render.subsystem.spam.SpamPatternMatcher;
import com.wikantik.render.subsystem.spam.SpamPolicy;
import com.wikantik.render.subsystem.spam.SpamRateLimiter;

/**
 * Namespace for the Rendering subsystem's input and output contracts.
 *
 * <p>Phase 6 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}
 * and {@code docs/superpowers/plans/2026-05-07-decomposition-phase-6-rendering-subsystem.md}.</p>
 *
 * <p>Rendering walls off the parser / plugin / filter / diff pipeline
 * behind a typed {@link Services} surface. Checkpoint 1 exposes the four
 * existing manager-level objects ({@link RenderingManager},
 * {@link PluginManager}, {@link FilterManager}, {@link DifferenceManager})
 * without changing how they are constructed and reserves four typed-but-
 * null slots for the SpamFilter helpers; Checkpoint 2 migrates 35
 * production callsites of those managers to this surface; Checkpoint 3
 * decomposes {@code SpamFilter} into {@link SpamRateLimiter},
 * {@link SpamPatternMatcher}, {@link SpamExternalSignals}, and
 * {@link SpamPolicy}; Checkpoint 4 wires those four helpers into
 * {@link Services}.</p>
 */
public final class RenderingSubsystem {

    private RenderingSubsystem() {}

    /**
     * What the Rendering subsystem requires from upstream.
     *
     * <p>{@code engine} is the legacy seam — filter registration and
     * plugin instantiation still go through {@link Engine#getManager}
     * during boot. Subsequent phases narrow this.</p>
     */
    public record Deps(
        CoreSubsystem.Services core,
        AuthSubsystem.Services auth,
        PageSubsystem.Services page,
        Engine engine
    ) {}

    /**
     * What the Rendering subsystem exposes to downstream consumers.
     *
     * <p>The first four fields ({@code renderingManager},
     * {@code pluginManager}, {@code filterManager},
     * {@code differenceManager}) are non-null after a successful
     * {@link RenderingSubsystemFactory#create} call. The four spam helper
     * fields are stubbed {@code null} in Phase 6 Ckpt 1 and populated by
     * Phase 6 Ckpt 4 once {@code SpamFilter} has been decomposed. The
     * {@code newsPageGenerator} field is added in Phase 9 Ckpt 2.</p>
     */
    public record Services(
        // Manager-level interfaces:
        RenderingManager   renderingManager,
        PluginManager      pluginManager,
        FilterManager      filterManager,
        DifferenceManager  differenceManager,

        // Decomposed SpamFilter helpers (Phase 6 Ckpt 3 / Ckpt 4):
        SpamRateLimiter     spamRateLimiter,
        SpamPatternMatcher  spamPatternMatcher,
        SpamExternalSignals spamExternalSignals,
        SpamPolicy          spamPolicy,

        // Content rendering helpers (Phase 9 Ckpt 2):
        NewsPageGenerator   newsPageGenerator
    ) {}
}
