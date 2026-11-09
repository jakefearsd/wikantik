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
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
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
 * construction (cron scheduling). These tests verify both the registry-read behaviour
 * and the side-effect-absence guarantee.</p>
 */
final class KnowledgeSubsystemBridgeTest {

    @Test
    void rebuildFromManagers_readsFromRegistryDirectly() {
        final ContentChunkRepository chunks    = mock( ContentChunkRepository.class );
        final ChunkProjector         projector = mock( ChunkProjector.class );
        final FrontmatterDefaultsFilter filter = mock( FrontmatterDefaultsFilter.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( ContentChunkRepository.class ) ).thenReturn( chunks );
        when( engine.getManager( ChunkProjector.class ) ).thenReturn( projector );
        when( engine.getManager( FrontmatterDefaultsFilter.class ) ).thenReturn( filter );

        final KnowledgeSubsystem.Services services = KnowledgeSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertSame( chunks,    services.contentChunkRepository() );
        assertSame( projector, services.chunkProjector() );
        assertSame( filter,    services.frontmatterDefaultsFilter() );
    }

    @Test
    void rebuildFromManagers_toleratesUnregisteredManagers() {
        final WikiEngine engine = mock( WikiEngine.class );
        // No managers registered — all getManager() calls return null

        final KnowledgeSubsystem.Services services = KnowledgeSubsystemBridge.rebuildFromManagers( engine );

        assertNotNull( services );
        assertNull( services.contentChunkRepository() );
        assertNull( services.forAgentProjectionService() );
        assertNull( services.contextRetrievalService() );
    }

    @Test
    void rebuildFromManagers_doesNotReInvokeFactorySideEffects() {
        // A setManager hot-swap of one manager must not re-instantiate (or re-schedule)
        // the others: the rebuilt record's side-effect-risk fields are the SAME INSTANCES
        // as the existing snapshot when their registered manager is unchanged.
        final ChunkProjector projector = mock( ChunkProjector.class );
        final RetrievalQualityRunner rqRunner = mock( RetrievalQualityRunner.class );
        final ForAgentProjectionService forAgent = mock( ForAgentProjectionService.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( ChunkProjector.class ) ).thenReturn( projector );
        when( engine.getManager( RetrievalQualityRunner.class ) ).thenReturn( rqRunner );
        when( engine.getManager( ForAgentProjectionService.class ) ).thenReturn( forAgent );
        // First call (existing == null inside rebuildFromExisting) — the
        // readFromManagerRegistry path establishes the snapshot.
        when( engine.getKnowledgeSubsystem() ).thenReturn( null );
        final KnowledgeSubsystem.Services first = KnowledgeSubsystemBridge.rebuildFromManagers( engine );
        assertNotNull( first );
        assertSame( rqRunner, first.retrievalQualityRunner() );
        assertSame( forAgent, first.forAgentProjectionService() );

        // Now simulate a setManager-triggered rebuild with an existing snapshot.
        when( engine.getKnowledgeSubsystem() ).thenReturn( first );
        final ChunkProjector swapped = mock( ChunkProjector.class );
        when( engine.getManager( ChunkProjector.class ) ).thenReturn( swapped );
        final KnowledgeSubsystem.Services second = KnowledgeSubsystemBridge.rebuildFromManagers( engine );

        assertSame( swapped, second.chunkProjector(), "chunkProjector was swapped" );
        assertSame( first.retrievalQualityRunner(), second.retrievalQualityRunner(),
            "retrievalQualityRunner side-effect re-invocation suspected" );
        assertSame( first.forAgentProjectionService(), second.forAgentProjectionService(),
            "forAgentProjectionService re-instantiation suspected" );
        assertSame( first.retrieval(), second.retrieval(), "the set-once retrieval holder is carried forward" );
    }
}
