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

import com.wikantik.WikiEngine;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.kgpolicy.ReconciliationJobRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 12 Checkpoint 2 test for {@link KnowledgeSubsystemBridge}.
 *
 * <p>{@link KnowledgeSubsystemBridge#rebuildFromManagers} delegates to
 * {@link KnowledgeSubsystemFactory#rebuildFromExisting}, which reads already-registered
 * services from the engine's manager registry without re-invoking side-effecting
 * construction (cron scheduling, background indexer start). These tests verify both
 * the registry-read behaviour and the side-effect-absence guarantee.</p>
 */
final class KnowledgeSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_readsFromRegistryDirectly() {
        final KnowledgeGraphService kgService      = mock( KnowledgeGraphService.class );
        final HubOverviewService    hubOverview    = mock( HubOverviewService.class );
        final MentionIndex          mentionIndex   = mock( MentionIndex.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( KnowledgeGraphService.class ) ).thenReturn( kgService );
        when( engine.getManager( HubOverviewService.class ) ).thenReturn( hubOverview );
        when( engine.getManager( MentionIndex.class ) ).thenReturn( mentionIndex );

        final KnowledgeSubsystem.Services services = KnowledgeSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertSame( kgService,   services.kgService() );
        assertSame( hubOverview, services.hubOverviewService() );
        assertSame( mentionIndex, services.mentionIndex() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        // No managers registered — all getManager() calls return null

        final KnowledgeSubsystem.Services services = KnowledgeSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.kgService() );
        assertNull( services.hubOverviewService() );
    }

    @Test
    void rebuildFromManagers_doesNotReInvokeFactorySideEffects() {
        // Goal: assert that calling rebuildFromManagers after setManager
        // hot-swaps a single manager does NOT spawn a duplicate JudgeRunner,
        // ReconciliationJobRunner, RetrievalQualityRunner schedule, nor
        // restart BootstrapEntityExtractionIndexer.
        //
        // Mechanism: the audit identifies 7 side-effect-risk fields. We
        // can't easily mock a real WikiEngine and watch each side-effect
        // observable, so the assertion is structural: the returned Services
        // record's side-effect-risk fields are the SAME INSTANCES as the
        // existing snapshot when their underlying registered class is
        // unchanged.
        final KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        final JudgeRunner judgeRunner = mock(JudgeRunner.class);
        final ReconciliationJobRunner reconRunner = mock(ReconciliationJobRunner.class);
        final RetrievalQualityRunner rqRunner = mock(RetrievalQualityRunner.class);
        final BootstrapEntityExtractionIndexer indexer = mock(BootstrapEntityExtractionIndexer.class);
        final MentionIndex mentionIndex = mock(MentionIndex.class);
        final NodeMentionSimilarity nodeMentionSim = mock(NodeMentionSimilarity.class);

        final WikiEngine engine = mock(WikiEngine.class);
        when(engine.getManager(KnowledgeGraphService.class)).thenReturn(kgService);
        when(engine.getManager(JudgeRunner.class)).thenReturn(judgeRunner);
        when(engine.getManager(ReconciliationJobRunner.class)).thenReturn(reconRunner);
        when(engine.getManager(RetrievalQualityRunner.class)).thenReturn(rqRunner);
        when(engine.getManager(BootstrapEntityExtractionIndexer.class)).thenReturn(indexer);
        when(engine.getManager(MentionIndex.class)).thenReturn(mentionIndex);
        when(engine.getManager(NodeMentionSimilarity.class)).thenReturn(nodeMentionSim);
        // First call (existing == null inside rebuildFromExisting) — establishes
        // the snapshot. KnowledgeSubsystemBridge.fromLegacyEngine returns
        // existing-typed snapshot if non-null, else falls back to rebuildFromManagers.
        // For this test we directly call rebuildFromManagers so the (existing == null
        // ⇒ readFromManagerRegistry) path runs.
        when(engine.getKnowledgeSubsystem()).thenReturn(null);
        final KnowledgeSubsystem.Services first = KnowledgeSubsystemBridge.rebuildFromManagers(engine);
        assertNotNull(first);
        assertSame(judgeRunner,    first.judgeRunner());
        assertSame(reconRunner,    first.reconciliationJobRunner());
        assertSame(rqRunner,       first.retrievalQualityRunner());
        assertSame(indexer,        first.bootstrapEntityExtractionIndexer());
        assertSame(mentionIndex,   first.mentionIndex());
        assertSame(nodeMentionSim, first.nodeMentionSimilarity());

        // Now simulate a setManager-triggered rebuild: pretend the engine has
        // an existing snapshot (so rebuildFromExisting takes the preserve path).
        when(engine.getKnowledgeSubsystem()).thenReturn(first);

        // Hot-swap kgService only — every OTHER side-effect-risk field's
        // registered manager is unchanged, so they should all be IDENTICAL
        // INSTANCES to `first`.
        final KnowledgeGraphService swappedKg = mock(KnowledgeGraphService.class);
        when(engine.getManager(KnowledgeGraphService.class)).thenReturn(swappedKg);
        final KnowledgeSubsystem.Services second = KnowledgeSubsystemBridge.rebuildFromManagers(engine);

        assertSame(swappedKg, second.kgService(), "kgService was swapped");
        // Side-effect-risk fields: same instances (no re-instantiation).
        assertSame(first.judgeRunner(),                       second.judgeRunner(),
            "judgeRunner side-effect re-invocation suspected");
        assertSame(first.reconciliationJobRunner(),           second.reconciliationJobRunner(),
            "reconciliationJobRunner side-effect re-invocation suspected");
        assertSame(first.retrievalQualityRunner(),            second.retrievalQualityRunner(),
            "retrievalQualityRunner side-effect re-invocation suspected");
        assertSame(first.bootstrapEntityExtractionIndexer(),  second.bootstrapEntityExtractionIndexer(),
            "bootstrap extractor side-effect re-invocation suspected");
        assertSame(first.mentionIndex(),                      second.mentionIndex(),
            "mentionIndex re-instantiation suspected");
        assertSame(first.nodeMentionSimilarity(),             second.nodeMentionSimilarity(),
            "nodeMentionSimilarity re-instantiation suspected");
    }
}
