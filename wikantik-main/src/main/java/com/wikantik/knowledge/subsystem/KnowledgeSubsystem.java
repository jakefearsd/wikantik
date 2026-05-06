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

import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
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
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.judge.KgJudgeTimeoutRepository;
import com.wikantik.knowledge.judge.KgMaterializationService;
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
        PageManager pageManager,
        PageSaveHelper pageSaveHelper,
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
        HubSyncFilter hubSyncFilter
    ) {}
}
