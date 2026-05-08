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
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.MentionIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 11 Checkpoint 5 test for {@link KnowledgeSubsystemBridge}.
 *
 * <p>KnowledgeSubsystemBridge is intentionally NOT delegating to
 * {@link KnowledgeSubsystemFactory}: the factory is a full-construction pipeline
 * that creates new service instances (including scheduling a cron runner). The bridge
 * reads already-registered services from the engine's manager registry, which is the
 * correct behaviour for hot-swap rebuild paths. These tests verify the registry-read
 * behaviour directly.</p>
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
}
