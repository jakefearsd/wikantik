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
import com.wikantik.api.core.Engine;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
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

/**
 * Adapter that synthesises a sparse {@link KnowledgeSubsystem.Services}
 * record from {@link Engine#getManager(Class)} lookups.
 *
 * <p>Used by {@code RestServletBase.getSubsystems()} when a test harness
 * built the engine via {@code TestEngine.setManager(...)} rather than a
 * full {@link Engine#initialize} cycle. Production code paths use the
 * authoritative {@code WikiSubsystems} bundle stashed on the
 * {@link jakarta.servlet.ServletContext} at boot — this bridge is the
 * legacy-test escape hatch, retained only until the registry deletion in
 * Phase 9.</p>
 *
 * <p>Fields whose corresponding manager is not registered come back as
 * {@code null}, mirroring the legacy {@code getManager()} behavior and
 * keeping existing test setups working without modification.</p>
 */
public final class KnowledgeSubsystemBridge {

    private KnowledgeSubsystemBridge() {}

    /**
     * Returns the engine's Knowledge subsystem services. Prefers the
     * engine's typed {@code WikiEngine.getKnowledgeSubsystem()} accessor
     * (the production path: services produced by
     * {@link com.wikantik.WikiEngine#initialize}); falls back to a sparse
     * record built from {@code engine.getManager(...)} lookups when the
     * typed accessor is unavailable (e.g. mocked {@code Engine} in unit
     * tests, or a {@code TestEngine} that registered services via
     * {@code setManager(...)} without going through full initialization).
     *
     * <p>The returned record is always non-null but its fields may be
     * {@code null} where no manager is registered (test fixtures only
     * register the services they need).</p>
     */
    public static KnowledgeSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new KnowledgeSubsystem.Services(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null );
        }
        final KnowledgeSubsystem.Services typed = wikiEngine.getKnowledgeSubsystem();
        if ( typed != null ) return typed;

        return new KnowledgeSubsystem.Services(
            wikiEngine.getManager( KnowledgeGraphService.class ),
            wikiEngine.getManager( KgProposalJudgeService.class ),
            wikiEngine.getManager( JudgeRunner.class ),
            wikiEngine.getManager( KgMaterializationService.class ),
            wikiEngine.getManager( KgJudgeTimeoutRepository.class ),
            wikiEngine.getManager( HubProposalService.class ),
            wikiEngine.getManager( HubDiscoveryService.class ),
            wikiEngine.getManager( HubOverviewService.class ),
            wikiEngine.getManager( HubProposalRepository.class ),
            wikiEngine.getManager( HubDiscoveryRepository.class ),
            wikiEngine.getManager( ContentChunkRepository.class ),
            wikiEngine.getManager( ChunkProjector.class ),
            wikiEngine.getManager( MentionIndex.class ),
            wikiEngine.getManager( NodeMentionSimilarity.class ),
            wikiEngine.getManager( FrontmatterDefaultsFilter.class ),
            wikiEngine.getManager( HubSyncFilter.class ),
            wikiEngine.getManager( ContextRetrievalService.class ),
            wikiEngine.getManager( ForAgentProjectionService.class ),
            wikiEngine.getManager( BootstrapEntityExtractionIndexer.class ),
            wikiEngine.getManager( KgInclusionPolicy.class ),
            wikiEngine.getManager( ReconciliationJobRunner.class ),
            wikiEngine.getManager( RetrievalQualityRunner.class )
        );
    }
}
