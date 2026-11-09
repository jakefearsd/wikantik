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
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.core.subsystem.CoreSubsystemFactory;
import com.wikantik.core.subsystem.DefaultWikiProperties;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystemFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 subsystem-isolation test for {@link KnowledgeSubsystemFactory}.
 *
 * <p>Demonstrates that the Knowledge subsystem can be instantiated without
 * {@code WikiEngine} or {@code TestEngine}: a {@code Deps} record built from
 * a real Postgres-backed persistence bundle plus plain mocks for upstream
 * collaborators is enough to verify the {@code Services} record's wiring.</p>
 */
@RequiresPostgres
class KnowledgeSubsystemFactoryTest {

    private static CoreSubsystem.Services core( final MeterRegistry meterRegistry ) {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( com.wikantik.cache.CachingManager.class ) ).thenReturn( null );
        return CoreSubsystemFactory.create( new CoreSubsystem.Deps(
            new Properties(), null, meterRegistry, mock( SystemPageRegistry.class ),
            mock( RecentArticlesManager.class ), engine ) );
    }

    private static PersistenceSubsystem.Services persistence() {
        return PersistenceSubsystemFactory.create( new PersistenceSubsystem.Deps(
            PostgresTestDb.createDataSource(), new DefaultWikiProperties( new Properties() ) ) );
    }

    @Test
    void create_populatesTheFactoryBuiltFields() {
        final PersistenceSubsystem.Services persistence = persistence();
        final KnowledgeSubsystem.Services services = KnowledgeSubsystemFactory.create(
            new KnowledgeSubsystem.Deps( persistence, core( new SimpleMeterRegistry() ) ) );

        assertSame( persistence.contentChunks(), services.contentChunkRepository(), "contentChunkRepository" );
        assertNotNull( services.chunkProjector(),            "chunkProjector" );
        assertNotNull( services.frontmatterDefaultsFilter(), "frontmatterDefaultsFilter" );
        // Post-construction fields — null from the factory (WikiEngine wires them after create() returns).
        assertNull( services.forAgentProjectionService(), "forAgentProjectionService (post-construction)" );
        assertNull( services.retrievalQualityRunner(),    "retrievalQualityRunner (post-construction)" );
        assertNull( services.contextRetrievalService(),   "contextRetrievalService (installed by the patch seam)" );
        assertNotNull( services.retrieval(),              "retrieval holder is never null" );
    }

    @Test
    void create_buildsTheChunkProjectorWithoutAMeterRegistry() {
        final KnowledgeSubsystem.Services services = KnowledgeSubsystemFactory.create(
            new KnowledgeSubsystem.Deps( persistence(), core( null ) ) );

        assertNotNull( services.chunkProjector(), "chunkProjector falls back to its metrics-less constructor" );
    }
}
