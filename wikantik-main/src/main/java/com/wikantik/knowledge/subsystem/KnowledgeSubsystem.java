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
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.knowledge.HubSyncFilter;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.judge.KgJudgeTimeoutRepository;
import com.wikantik.knowledge.judge.KgMaterializationService;
import com.wikantik.kgpolicy.ReconciliationJobRunner;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * Namespace for the Knowledge Graph subsystem's input and output contracts.
 *
 * <p>Phase 1 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.
 *
 * <p>The {@link Deps} record names exactly what this subsystem consumes from
 * upstream subsystems (or, today, from the still-monolithic engine). The
 * {@link Services} record names exactly what this subsystem exposes to
 * downstream consumers. Together they form the public contract that
 * {@link KnowledgeSubsystemFactory} produces and that callers depend on
 * — instead of {@code WikiEngine#getManager(Class)}.</p>
 *
 * <p>Phase 1 scope: the 16 services currently produced by the legacy
 * {@code KnowledgeGraphServiceFactory}. Additional KG-flavored services
 * still constructed inline in {@code WikiEngine.initialize()}
 * ({@code BootstrapEntityExtractionIndexer}, {@code AsyncEntityExtractionListener},
 * {@code KgInclusionPolicy}, {@code ReconciliationJobRunner},
 * {@code KgClusterPolicyRepository}, {@code KgExcludedPagesRepository},
 * {@code ForAgentProjectionService}, {@code RetrievalQualityRunner},
 * {@code ContentIndexRebuildService}, {@code PageGraphService}) join the
 * {@code Services} record in a follow-up checkpoint of this phase, after
 * the box is in place and consumers have started migrating.</p>
 */
public final class KnowledgeSubsystem {

    private KnowledgeSubsystem() {}

    /**
     * What the Knowledge subsystem requires from upstream.
     *
     * <p>{@code core} sources typed properties, the metrics registry, and
     * the {@code SystemPageRegistry} leaf manager — first cross-subsystem
     * edge in the wikantik-main DAG (established 2026-05-06).
     * {@code dataSource} is infrastructure today; after Phase 3 it will
     * come from {@code PersistenceSubsystem.Services}.</p>
     *
     * <p>{@code pageManager} and {@code pageSaveHelper} come from the
     * page-services layer; after Phase 5 they will come from
     * {@code PageSubsystem.Services}.</p>
     *
     * <p>{@code luceneMlt} is an optional Lucene MoreLikeThis seam consumed
     * by {@link HubOverviewService}; after Phase 7 it will come from
     * {@code SearchSubsystem.Services}. May be {@code null} when Lucene is
     * unavailable, in which case the service falls back to an empty MLT list.</p>
     */
    public record Deps(
        DataSource dataSource,
        PersistenceSubsystem.Services persistence,
        CoreSubsystem.Services core,
        PageSubsystem.Services page,
        HubOverviewService.LuceneMlt luceneMlt
    ) {}

    /**
     * What the Knowledge subsystem exposes to downstream consumers.
     *
     * <p>Every field is non-null after a successful {@link KnowledgeSubsystemFactory#create}
     * call, except where a runtime configuration disables a service:</p>
     *
     * <ul>
     *   <li>{@code judgeService} and {@code judgeRunner} are {@code null} when
     *       {@code wikantik.kg.judge.enabled=false} or no judge endpoint is set.
     *       The {@code judgeTimeoutRepository} is always non-null so the admin
     *       timeout-tracking surface works regardless.</li>
     * </ul>
     *
     * <p>{@code frontmatterDefaultsFilter} and {@code hubSyncFilter} are
     * page-save filters (Rendering subsystem territory in Phase 6). They are
     * Knowledge-driven (read/write KG state) and produced here for now;
     * Phase 6 will route them through Rendering's filter registration without
     * moving their construction.</p>
     *
     * <p>Phase 8 Checkpoint 1.5 additions (all nullable):</p>
     * <ul>
     *   <li>{@code forAgentProjectionService} — null when the Knowledge Graph
     *       datasource is unavailable.</li>
     *   <li>{@code bootstrapEntityExtractionIndexer} — null when extraction is
     *       disabled via {@code wikantik.knowledge.extractor.backend=disabled}.</li>
     *   <li>{@code kgInclusionPolicy} — null when the KG datasource is absent.</li>
     *   <li>{@code reconciliationJobRunner} — null when KG datasource is absent.</li>
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
        KnowledgeGraphService kgService,
        KgProposalJudgeService judgeService,
        JudgeRunner judgeRunner,
        KgMaterializationService kgMaterialization,
        KgJudgeTimeoutRepository judgeTimeoutRepository,
        HubProposalService hubProposalService,
        HubDiscoveryService hubDiscoveryService,
        HubOverviewService hubOverviewService,
        HubProposalRepository hubProposalRepository,
        HubDiscoveryRepository hubDiscoveryRepository,
        ContentChunkRepository contentChunkRepository,
        ChunkProjector chunkProjector,
        MentionIndex mentionIndex,
        NodeMentionSimilarity nodeMentionSimilarity,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        HubSyncFilter hubSyncFilter,
        ForAgentProjectionService forAgentProjectionService,
        BootstrapEntityExtractionIndexer bootstrapEntityExtractionIndexer,
        KgInclusionPolicy kgInclusionPolicy,
        ReconciliationJobRunner reconciliationJobRunner,
        RetrievalQualityRunner retrievalQualityRunner,
        KgCurationOps kgCurationOps,
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
