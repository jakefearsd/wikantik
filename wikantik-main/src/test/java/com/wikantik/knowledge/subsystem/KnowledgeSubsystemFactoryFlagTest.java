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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.core.subsystem.CoreSubsystemFactory;
import com.wikantik.core.subsystem.DefaultWikiProperties;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystemFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code wikantik.knowledge.enabled} master flag on
 * {@link KnowledgeSubsystemFactory#create}.
 *
 * <p>Contract: when the flag is {@code false}, every Knowledge-Graph-specific
 * service is {@code null}, but the chunking + embedding pipeline
 * ({@code ChunkProjector}, {@code ContentChunkRepository}) remains constructed
 * — chunks feed dense retrieval independently of the KG. When the property is
 * absent (the default), everything is built exactly as before (regression
 * guard).</p>
 */
@RequiresPostgres
class KnowledgeSubsystemFactoryFlagTest {

    private DataSource dataSource;
    private SimpleMeterRegistry meterRegistry;
    private SystemPageRegistry systemPageRegistry;
    private PageManager pageManager;
    private PageSaveHelper pageSaveHelper;

    private CoreSubsystem.Services core( final Properties props ) {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( com.wikantik.cache.CachingManager.class ) ).thenReturn( null );
        return CoreSubsystemFactory.create( new CoreSubsystem.Deps(
            props, null, meterRegistry, systemPageRegistry,
            mock( RecentArticlesManager.class ), engine ) );
    }

    /**
     * Persistence built with the KG repositories present, so these tests exercise the knowledge factory's own flag
     * check. (In production both factories read the same properties, so they always agree.)
     */
    private PersistenceSubsystem.Services persistence() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.enabled", "true" );
        return PersistenceSubsystemFactory.create( new PersistenceSubsystem.Deps(
            dataSource, new DefaultWikiProperties( props ) ) );
    }

    private com.wikantik.page.subsystem.PageSubsystem.Services page() {
        return new com.wikantik.page.subsystem.PageSubsystem.Services(
            pageManager, /*attachments=*/ null, /*renamer=*/ null,
            pageSaveHelper, /*provider=*/ null,
            /*repository=*/ null, /*lifecycle=*/ null,
            /*referenceManager=*/ null );
    }

    private KnowledgeSubsystem.Deps deps( final Properties props ) {
        return new KnowledgeSubsystem.Deps(
            dataSource, persistence(), core( props ), page(), /*luceneMlt=*/ null );
    }

    @BeforeEach
    void setUp() {
        dataSource         = PostgresTestDb.createDataSource();
        meterRegistry      = new SimpleMeterRegistry();
        systemPageRegistry = mock( SystemPageRegistry.class );
        pageManager        = mock( PageManager.class );
        pageSaveHelper     = mock( PageSaveHelper.class );
    }

    @Test
    void create_knowledgeDisabled_nullsKgServicesButKeepsChunking() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.enabled", "false" );

        final KnowledgeSubsystem.Services s = KnowledgeSubsystemFactory.create( deps( props ) );

        // Load-bearing boundary: chunking + embedding pipeline stays alive.
        assertNotNull( s.chunkProjector(),           "chunkProjector must survive KG off" );
        assertNotNull( s.contentChunkRepository(),   "contentChunkRepository must survive KG off" );
        assertNotNull( s.frontmatterDefaultsFilter(),"frontmatterDefaultsFilter is KG-independent" );

        // Every KG-specific service is null.
        assertNull( s.kgService(),               "kgService" );
        assertNull( s.kgMaterialization(),       "kgMaterialization" );
        assertNull( s.judgeService(),            "judgeService" );
        assertNull( s.judgeRunner(),             "judgeRunner" );
        assertNull( s.judgeTimeoutRepository(),  "judgeTimeoutRepository" );
        assertNull( s.hubProposalService(),      "hubProposalService" );
        assertNull( s.hubDiscoveryService(),     "hubDiscoveryService" );
        assertNull( s.hubOverviewService(),      "hubOverviewService" );
        assertNull( s.hubProposalRepository(),   "hubProposalRepository" );
        assertNull( s.hubDiscoveryRepository(),  "hubDiscoveryRepository" );
        assertNull( s.mentionIndex(),            "mentionIndex" );
        assertNull( s.nodeMentionSimilarity(),   "nodeMentionSimilarity" );
        assertNull( s.kgCurationOps(),           "kgCurationOps" );
    }

    @Test
    void create_propertyAbsent_buildsNoKnowledgeGraph_offIsTheDefault() {
        // The KG was retired 2026-11-08: an ABSENT wikantik.knowledge.enabled means OFF.
        final Properties props = new Properties();
        props.setProperty( "wikantik.kg.judge.enabled", "false" );

        final KnowledgeSubsystem.Services off = KnowledgeSubsystemFactory.create( deps( props ) );

        assertNull( off.kgService(), "kgService absent by default" );
        assertNull( off.kgMaterialization(), "kgMaterialization absent by default" );
        assertNull( off.hubDiscoveryService(), "hubDiscoveryService absent by default" );
        assertNotNull( off.chunkProjector(), "chunking survives" );
    }

    @Test
    void create_propertyTrue_buildsEverything_regressionGuard() {
        // Judge disabled only to avoid leaking a scheduled runner.
        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.enabled", "true" );
        props.setProperty( "wikantik.kg.judge.enabled", "false" );

        final KnowledgeSubsystem.Services s = KnowledgeSubsystemFactory.create( deps( props ) );

        assertNotNull( s.kgService(),             "kgService built by default" );
        assertNotNull( s.kgMaterialization(),     "kgMaterialization built by default" );
        assertNotNull( s.hubProposalService(),    "hubProposalService built by default" );
        assertNotNull( s.hubDiscoveryService(),   "hubDiscoveryService built by default" );
        assertNotNull( s.hubOverviewService(),    "hubOverviewService built by default" );
        assertNotNull( s.mentionIndex(),          "mentionIndex built by default" );
        assertNotNull( s.nodeMentionSimilarity(), "nodeMentionSimilarity built by default" );
        assertNotNull( s.kgCurationOps(),         "kgCurationOps built by default" );
        assertNotNull( s.chunkProjector(),        "chunkProjector built by default" );
        assertNotNull( s.contentChunkRepository(),"contentChunkRepository built by default" );
    }
}
