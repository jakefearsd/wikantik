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
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageGraphService;
import com.wikantik.api.pagegraph.StructuralIndexService;

import java.util.Objects;

/**
 * Builds {@link PageGraphSubsystem.Services} from {@link PageGraphSubsystem.Deps}.
 *
 * <p>Phase 9 Checkpoint 1 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/plans/2026-05-07-decomposition-phase-9-engine-simplification.md}.</p>
 *
 * <p>Pulls each Page Graph service off the engine's legacy manager registry,
 * mirroring the convention established by the Search and Rendering subsystem
 * factories. All four services ({@link StructuralIndexService},
 * {@link PageGraphService}, {@link ReferenceManager},
 * {@link ContentIndexRebuildService}) are registered by
 * {@code WikiEngine.initialize()} (specifically the
 * {@code initPageGraphServices} section) before this factory is called.
 * When a service was not registered (disabled path, no datasource, or
 * unit-test fixture that does not wire the service) the corresponding slot
 * is {@code null} — same behaviour as a missing legacy manager.</p>
 */
public final class PageGraphSubsystemFactory {

    private PageGraphSubsystemFactory() {}

    /**
     * Produces a {@link PageGraphSubsystem.Services} record from the
     * provided {@link PageGraphSubsystem.Deps}.
     *
     * @param deps  non-null; {@code core} and {@code engine} must be present
     * @return a fully-populated (or partially-null on disabled paths) Services record
     * @throws NullPointerException if {@code deps} or {@code deps.engine()} is null
     */
    public static PageGraphSubsystem.Services create( final PageGraphSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        // deps.core(), deps.persistence(), deps.page() are reserved for future use when
        // PageGraphSubsystemFactory takes over service construction; they are not yet
        // read in the method body.
        final WikiEngine engine = ( WikiEngine ) Objects.requireNonNull( deps.engine(), "engine" );

        final StructuralIndexService     structuralIndexService     =
            engine.getManager( StructuralIndexService.class );
        final PageGraphService           pageGraphService           =
            engine.getManager( PageGraphService.class );
        final ReferenceManager           referenceManager           =
            engine.getManager( ReferenceManager.class );
        final ContentIndexRebuildService contentIndexRebuildService =
            engine.getManager( ContentIndexRebuildService.class );
        final com.wikantik.ontology.runtime.OntologyRebuildCoordinator ontologyRebuildCoordinator =
            engine.getManager( com.wikantik.ontology.runtime.OntologyRebuildCoordinator.class );

        return new PageGraphSubsystem.Services(
            structuralIndexService,
            pageGraphService,
            referenceManager,
            contentIndexRebuildService,
            ontologyRebuildCoordinator );
    }
}
