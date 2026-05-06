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
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.content.PageRenamer;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;

/**
 * Namespace for the Page subsystem's input and output contracts.
 *
 * <p>Phase 5 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Page owns the page lifecycle (read / save / delete / lock / version /
 * rename) and attachments behind a typed surface. Phase 5 Checkpoint 1
 * exposes the existing manager-level objects ({@link PageManager},
 * {@link AttachmentManager}, {@link PageRenamer}, {@link PageSaveHelper},
 * {@link PageProvider}) without changing how they are constructed. Ckpt 2
 * lifts {@link PageProvider} chain construction into
 * {@link PageSubsystemFactory}; Ckpt 3 decomposes {@code DefaultPageManager}
 * into {@code PageRepository} / {@code PageLifecycle} / {@code PageLockService}
 * helpers and adds them as additional {@link Services} fields.</p>
 */
public final class PageSubsystem {

    private PageSubsystem() {}

    /**
     * What the Page subsystem requires from upstream.
     *
     * <p>{@code persistence} is currently unused by the page lifecycle (the
     * page provider chain is filesystem-backed in production), but is
     * threaded through so future schema-backed providers can consume DAOs
     * via the typed surface.</p>
     *
     * <p>{@code engine} is the legacy seam — {@code FilterManager},
     * {@code WikiEvents}, and the JAAS callbacks all still reach back through
     * {@link Engine} during page-save flows. Subsequent phases narrow this.</p>
     */
    public record Deps(
        CoreSubsystem.Services core,
        PersistenceSubsystem.Services persistence,
        AuthSubsystem.Services auth,
        Engine engine
    ) {}

    /**
     * What the Page subsystem exposes to downstream consumers.
     *
     * <p>Every field is non-null after a successful
     * {@link PageSubsystemFactory#create} call. Phase 5 Ckpt 3 will
     * extend this record with the decomposed {@code PageRepository},
     * {@code PageLifecycle}, and {@code PageLockService} helpers.</p>
     */
    public record Services(
        PageManager       pages,
        AttachmentManager attachments,
        PageRenamer       pageRenamer,
        PageSaveHelper    pageSaveHelper,
        PageProvider      pageProvider
    ) {}
}
