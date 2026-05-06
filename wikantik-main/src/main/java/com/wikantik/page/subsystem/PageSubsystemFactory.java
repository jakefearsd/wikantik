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
package com.wikantik.page.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.content.PageRenamer;

import java.util.Objects;

/**
 * Builds {@link PageSubsystem.Services} from {@link PageSubsystem.Deps}.
 *
 * <p>Phase 5 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Phase 5 Checkpoint 1 locates the four manager-level objects on the
 * engine's legacy registry, mirroring the Auth pattern. Construction of
 * {@code DefaultPageManager} stays in {@code WikiEngine.initComponent}
 * for now; Ckpt 2 lifts the page-provider chain construction here, and
 * Ckpt 3 lifts the manager construction itself.</p>
 */
public final class PageSubsystemFactory {

    private PageSubsystemFactory() {}

    public static PageSubsystem.Services create( final PageSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        Objects.requireNonNull( deps.core(), "core" );
        final Engine engine = Objects.requireNonNull( deps.engine(), "engine" );

        final PageManager       pages       = engine.getManager( PageManager.class );
        final AttachmentManager attachments = engine.getManager( AttachmentManager.class );
        final PageRenamer       renamer     = engine.getManager( PageRenamer.class );
        final PageSaveHelper    saveHelper  = new PageSaveHelper( engine );
        final PageProvider      provider    = pages != null ? pages.getProvider() : null;

        return new PageSubsystem.Services( pages, attachments, renamer, saveHelper, provider );
    }
}
