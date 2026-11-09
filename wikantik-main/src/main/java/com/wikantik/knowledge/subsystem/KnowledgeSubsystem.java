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
package com.wikantik.knowledge.subsystem;

import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Namespace for the Knowledge subsystem's input and output contracts: content
 * chunking, frontmatter defaulting, the agent projection, retrieval-quality CI and
 * the retrieval-derived services (context retrieval, bundle, briefing).
 *
 * <p>Phase 1 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.
 *
 * <p>The {@link Deps} record names exactly what this subsystem consumes from
 * upstream subsystems. The {@link Services} record names exactly what this
 * subsystem exposes to downstream consumers. Together they form the public
 * contract that {@link KnowledgeSubsystemFactory} produces and that callers
 * depend on — instead of {@code WikiEngine#getManager(Class)}.</p>
 *
 * <p>The LLM-extracted Knowledge Graph that used to live here was deleted
 * (2026-11-09); the package name is historical.</p>
 */
public final class KnowledgeSubsystem {

    private KnowledgeSubsystem() {}

    /**
     * What the Knowledge subsystem requires from upstream.
     *
     * <p>{@code persistence} supplies the chunk repository; {@code core} sources typed
     * properties, the metrics registry, and the {@code SystemPageRegistry} leaf manager.</p>
     */
    public record Deps(
        PersistenceSubsystem.Services persistence,
        CoreSubsystem.Services core
    ) {}

    /**
     * What the Knowledge subsystem exposes to downstream consumers.
     *
     * <p>{@code contentChunkRepository}, {@code chunkProjector} and
     * {@code frontmatterDefaultsFilter} are non-null after a successful
     * {@link KnowledgeSubsystemFactory#create} call. {@code frontmatterDefaultsFilter}
     * is a page-save filter produced here; Rendering registers it.</p>
     *
     * <p>Post-construction additions (nullable):</p>
     * <ul>
     *   <li>{@code forAgentProjectionService} — null when the datasource is unavailable.</li>
     *   <li>{@code retrievalQualityRunner} — null when wiring fails (e.g. no
     *       search stack available); failures are logged at WARN and the admin
     *       surface returns 503.</li>
     * </ul>
     *
     * <p>{@code retrieval} — the set-once holder for the three retrieval-derived
     * services that cannot exist at engine-boot time. It is installed exactly once
     * by {@code WikiEngine.patchContextRetrievalService} via {@link #installRetrieval};
     * every construction path passes {@code null} (normalized to an empty reference)
     * except the rebuild paths, which carry the <em>same</em> reference forward so a
     * patch installed before or after a hot-swap rebuild is visible everywhere.
     * Read it through {@link #contextRetrievalService()}, {@link #bundleAssemblyService()}
     * and {@link #briefingAssemblyService()} — never dereference the raw
     * {@code AtomicReference} at call sites.</p>
     */
    public record Services(
        ContentChunkRepository contentChunkRepository,
        ChunkProjector chunkProjector,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        ForAgentProjectionService forAgentProjectionService,
        RetrievalQualityRunner retrievalQualityRunner,
        AtomicReference< RetrievalServices > retrieval
    ) {

        /** Normalizes a null retrieval holder so accessors and installs are always safe. */
        public Services {
            retrieval = retrieval != null ? retrieval : new AtomicReference<>();
        }

        /** The live retrieval service, or {@code null} until the patch seam installs it. */
        public ContextRetrievalService contextRetrievalService() {
            final RetrievalServices rs = retrieval.get();
            return rs == null ? null : rs.contextRetrievalService();
        }

        /** The live bundle assembler, or {@code null} until the patch seam installs it. */
        public BundleAssemblyService bundleAssemblyService() {
            final RetrievalServices rs = retrieval.get();
            return rs == null ? null : rs.bundleAssemblyService();
        }

        /** The live briefing assembler, or {@code null} until the patch seam installs it. */
        public BriefingAssemblyService briefingAssemblyService() {
            final RetrievalServices rs = retrieval.get();
            return rs == null ? null : rs.briefingAssemblyService();
        }

        /**
         * Installs the retrieval trio exactly once (compare-and-set against an empty
         * holder). Returns {@code false} — changing nothing — if a trio was already
         * installed; the caller must not start any side-effecting collaborator (e.g.
         * the bundle-eval scheduler) when this returns {@code false}.
         */
        public boolean installRetrieval( final RetrievalServices services ) {
            return retrieval.compareAndSet( null, services );
        }
    }

    /**
     * The three retrieval-derived services that cannot exist at engine-boot time:
     * {@code contextRetrievalService} is wired by {@code ContextRetrievalServiceInitializer}
     * (a {@code ServletContextListener} that fires after {@code Engine#initialize} returns),
     * and the bundle + briefing assemblers are derived from it at that same seam
     * ({@code WikiEngine.patchContextRetrievalService}). Grouped so the whole trio is
     * installed atomically, exactly once, via {@link Services#installRetrieval}.
     */
    public record RetrievalServices(
        ContextRetrievalService contextRetrievalService,
        BundleAssemblyService bundleAssemblyService,
        BriefingAssemblyService briefingAssemblyService
    ) {}
}
