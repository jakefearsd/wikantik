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
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.judge.KgJudgeTimeoutRepository;
import com.wikantik.knowledge.judge.KgMaterializationService;
import com.wikantik.knowledge.subsystem.KnowledgeSubsystem;
import com.wikantik.knowledge.subsystem.KnowledgeSubsystemFactory;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * @deprecated Phase 1 of the wikantik-main subsystem decomposition replaced
 * this class with {@link KnowledgeSubsystemFactory}. New callers should use
 * {@code KnowledgeSubsystemFactory.create(KnowledgeSubsystem.Deps)} and
 * consume the typed {@link KnowledgeSubsystem.Services} record. This thin
 * alias remains for backwards compatibility with any out-of-tree callers
 * and will be removed once all in-tree consumers have migrated.
 *
 * <p>See: {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 */
@Deprecated( forRemoval = true )
public final class KnowledgeGraphServiceFactory {

    /**
     * Legacy bundle of services produced by this deprecated factory. Each
     * accessor delegates to the corresponding field on
     * {@link KnowledgeSubsystem.Services}, with the historical (pre-Phase-1)
     * accessor names preserved for backwards compatibility.
     *
     * @deprecated Use {@link KnowledgeSubsystem.Services} directly.
     */
    @Deprecated( forRemoval = true )
    public record Services(
        KnowledgeGraphService kgService,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,
        HubSyncFilter hubSyncFilter,
        NodeMentionSimilarity nodeMentionSimilarity,
        MentionIndex mentionIndex,
        HubProposalRepository hubProposalRepo,
        HubProposalService hubProposalService,
        HubDiscoveryRepository hubDiscoveryRepo,
        HubDiscoveryService hubDiscoveryService,
        HubOverviewService hubOverviewService,
        ChunkProjector chunkProjector,
        ContentChunkRepository contentChunkRepo,
        KgMaterializationService kgMaterialization,
        KgProposalJudgeService kgJudgeService,
        JudgeRunner kgJudgeRunner,
        KgJudgeTimeoutRepository kgJudgeTimeoutRepository
    ) {
        /** Adapts the new {@link KnowledgeSubsystem.Services} record. */
        static Services from( final KnowledgeSubsystem.Services s ) {
            return new Services(
                s.kgService(), s.frontmatterDefaultsFilter(), s.hubSyncFilter(),
                s.nodeMentionSimilarity(), s.mentionIndex(),
                s.hubProposalRepository(), s.hubProposalService(),
                s.hubDiscoveryRepository(), s.hubDiscoveryService(), s.hubOverviewService(),
                s.chunkProjector(), s.contentChunkRepository(),
                s.kgMaterialization(), s.judgeService(), s.judgeRunner(),
                s.judgeTimeoutRepository()
            );
        }
    }

    private KnowledgeGraphServiceFactory() {}

    /** @deprecated Use {@link KnowledgeSubsystemFactory#create(KnowledgeSubsystem.Deps)}. */
    @Deprecated( forRemoval = true )
    public static Services create( final DataSource dataSource,
                                    final Properties props,
                                    final SystemPageRegistry spr,
                                    final PageManager pageManager,
                                    final PageSaveHelper saveHelper ) {
        return create( dataSource, props, spr, pageManager, saveHelper, null, null );
    }

    /** @deprecated Use {@link KnowledgeSubsystemFactory#create(KnowledgeSubsystem.Deps)}. */
    @Deprecated( forRemoval = true )
    public static Services create( final DataSource dataSource,
                                    final Properties props,
                                    final SystemPageRegistry spr,
                                    final PageManager pageManager,
                                    final PageSaveHelper saveHelper,
                                    final HubOverviewService.LuceneMlt luceneMlt ) {
        return create( dataSource, props, spr, pageManager, saveHelper, luceneMlt, null );
    }

    /** @deprecated Use {@link KnowledgeSubsystemFactory#create(KnowledgeSubsystem.Deps)}. */
    @Deprecated( forRemoval = true )
    public static Services create( final DataSource dataSource,
                                    final Properties props,
                                    final SystemPageRegistry spr,
                                    final PageManager pageManager,
                                    final PageSaveHelper saveHelper,
                                    final HubOverviewService.LuceneMlt luceneMlt,
                                    final MeterRegistry meterRegistry ) {
        return Services.from( KnowledgeSubsystemFactory.create(
            new KnowledgeSubsystem.Deps( dataSource, props, spr, pageManager,
                                          saveHelper, luceneMlt, meterRegistry ) ) );
    }
}
