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
package com.wikantik.pagegraph.subsystem;

import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageGraphService;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;

/**
 * Namespace for the Page Graph subsystem's input and output contracts.
 *
 * <p>Phase 9 Checkpoint 1 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}
 * and {@code docs/superpowers/plans/2026-05-07-decomposition-phase-9-engine-simplification.md}.</p>
 *
 * <p>The Page Graph subsystem owns the structural index (canonical IDs, hub
 * clusters), the page graph service (wikilink traversal), the reference
 * manager (page-to-page wikilink tracking), and the content-index rebuild
 * service (admin-triggered re-crawl). It is distinct from the Knowledge
 * Graph subsystem per the Page Graph vs Knowledge Graph design documented at
 * {@code docs/wikantik-pages/PageGraphVsKnowledgeGraph.md}.</p>
 *
 * <p>The {@link Deps} record names exactly what this subsystem consumes from
 * upstream subsystems. The {@link Services} record names exactly what this
 * subsystem exposes to downstream consumers, instead of
 * {@code WikiEngine#getManager(Class)}.</p>
 */
public final class PageGraphSubsystem {

    private PageGraphSubsystem() {}

    /**
     * What the Page Graph subsystem requires from upstream.
     *
     * <p>{@code engine} is the legacy seam — the services are still
     * constructed inline in {@code WikiEngine.initialize()} and stored in
     * the managers registry. Subsequent phases narrow this dependency as
     * service construction moves into this factory.</p>
     */
    public record Deps(
        CoreSubsystem.Services       core,
        PersistenceSubsystem.Services persistence,
        PageSubsystem.Services       page,
        Engine                       engine
    ) {}

    /**
     * What the Page Graph subsystem exposes to downstream consumers.
     *
     * <p>All four fields are non-null when the services have been registered
     * in the engine's manager registry by {@code WikiEngine.initialize()}.
     * {@code structuralIndexService} and {@code pageGraphService} are null
     * when the engine boots without a configured datasource (some unit-test
     * paths). {@code referenceManager} is null until the asynchronous
     * page-scan that initialises it completes. {@code contentIndexRebuildService}
     * is null when the content-index rebuild pipeline is unavailable.</p>
     */
    public record Services(
        StructuralIndexService       structuralIndexService,
        PageGraphService             pageGraphService,
        ReferenceManager             referenceManager,
        ContentIndexRebuildService   contentIndexRebuildService,
        OntologyRebuildCoordinator   ontologyRebuildCoordinator
    ) {}
}
