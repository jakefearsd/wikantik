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

import com.wikantik.PostgresTestContainer;
import com.wikantik.WikiEngine;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.blog.BlogManager;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.core.subsystem.CoreSubsystemFactory;
import com.wikantik.core.subsystem.DefaultWikiProperties;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystemFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Phase 1 subsystem-isolation test for {@link KnowledgeSubsystemFactory}.
 *
 * <p>Demonstrates that the Knowledge subsystem can be instantiated and
 * exercised end-to-end without {@code WikiEngine} or {@code TestEngine}.
 * This is the contract every subsystem extracted by the wikantik-main
 * decomposition will satisfy: a {@code Deps} record built from a real
 * Testcontainers Postgres + plain mocks for upstream collaborators is
 * enough to verify the {@code Services} record's wiring.</p>
 *
 * <p>Coverage rationale: this test does not duplicate the per-service
 * coverage already provided by {@code DefaultKnowledgeGraphServiceTest},
 * {@code HubProposalServiceTest}, etc. It only verifies that
 * {@link KnowledgeSubsystemFactory#create} returns a fully-populated
 * {@link KnowledgeSubsystem.Services} record and that one round-trip
 * (kg-graph upsert/read) flows through the wired services.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class KnowledgeSubsystemFactoryTest {

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
            mock( RecentArticlesManager.class ), mock( BlogManager.class ), engine ) );
    }

    private PersistenceSubsystem.Services persistence() {
        return PersistenceSubsystemFactory.create( new PersistenceSubsystem.Deps(
            dataSource, new DefaultWikiProperties( new Properties() ) ) );
    }

    private com.wikantik.page.subsystem.PageSubsystem.Services page() {
        return new com.wikantik.page.subsystem.PageSubsystem.Services(
            pageManager, /*attachments=*/ null, /*renamer=*/ null,
            pageSaveHelper, /*provider=*/ null,
            /*repository=*/ null, /*lifecycle=*/ null, /*lockService=*/ null,
            /*referenceManager=*/ null );
    }

    @BeforeEach
    void setUp() throws Exception {
        dataSource = PostgresTestContainer.createDataSource();
        try ( final Connection c = dataSource.getConnection(); final Statement st = c.createStatement() ) {
            st.execute( "DELETE FROM kg_judge_timeouts" );
            st.execute( "DELETE FROM kg_excluded_pages" );
            st.execute( "DELETE FROM kg_edges" );
            st.execute( "DELETE FROM kg_proposals" );
            st.execute( "DELETE FROM kg_nodes" );
        }
        meterRegistry      = new SimpleMeterRegistry();
        systemPageRegistry = mock( SystemPageRegistry.class );
        pageManager        = mock( PageManager.class );
        pageSaveHelper     = mock( PageSaveHelper.class );
    }

    @Test
    void create_populatesEveryServiceField_defaultProperties() {
        // Default Properties leaves judge enabled (KgJudgeConfig falls back
        // to DEFAULT_ENDPOINT). The judge service + runner are wired here;
        // the runner is closed after the test to avoid a leaked scheduler.
        final KnowledgeSubsystem.Deps deps = new KnowledgeSubsystem.Deps(
            dataSource, persistence(), core( new Properties() ), page(),
            /*luceneMlt=*/ null );

        final KnowledgeSubsystem.Services services = KnowledgeSubsystemFactory.create( deps );
        try {
            assertNotNull( services.kgService(),                      "kgService" );
            assertNotNull( services.kgMaterialization(),              "kgMaterialization" );
            assertNotNull( services.judgeTimeoutRepository(),         "judgeTimeoutRepository" );
            assertNotNull( services.judgeService(),                   "judgeService" );
            assertNotNull( services.judgeRunner(),                    "judgeRunner" );
            assertNotNull( services.hubProposalService(),             "hubProposalService" );
            assertNotNull( services.hubDiscoveryService(),            "hubDiscoveryService" );
            assertNotNull( services.hubOverviewService(),             "hubOverviewService" );
            assertNotNull( services.hubProposalRepository(),          "hubProposalRepository" );
            assertNotNull( services.hubDiscoveryRepository(),         "hubDiscoveryRepository" );
            assertNotNull( services.contentChunkRepository(),         "contentChunkRepository" );
            assertNotNull( services.chunkProjector(),                 "chunkProjector" );
            assertNotNull( services.mentionIndex(),                   "mentionIndex" );
            assertNotNull( services.nodeMentionSimilarity(),          "nodeMentionSimilarity" );
            assertNotNull( services.frontmatterDefaultsFilter(),      "frontmatterDefaultsFilter" );
            assertNotNull( services.hubSyncFilter(),                  "hubSyncFilter" );
            // Phase 8 Ckpt 1.5 post-construction fields — null from factory
            // (WikiEngine wires them after create() returns; bridge reads from getManager).
            assertNull( services.contextRetrievalService(),           "contextRetrievalService (post-construction)" );
            assertNull( services.forAgentProjectionService(),         "forAgentProjectionService (post-construction)" );
            assertNull( services.bootstrapEntityExtractionIndexer(),  "bootstrapEntityExtractionIndexer (post-construction)" );
            assertNull( services.kgInclusionPolicy(),                 "kgInclusionPolicy (post-construction)" );
            assertNull( services.reconciliationJobRunner(),           "reconciliationJobRunner (post-construction)" );
            assertNull( services.retrievalQualityRunner(),            "retrievalQualityRunner (post-construction)" );
        } finally {
            services.judgeRunner().close();
        }
    }

    @Test
    void create_judgeServiceAndRunnerNullWhenJudgeDisabled() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.kg.judge.enabled", "false" );

        final KnowledgeSubsystem.Deps deps = new KnowledgeSubsystem.Deps(
            dataSource, persistence(), core( props ), page(),
            /*luceneMlt=*/ null );

        final KnowledgeSubsystem.Services services = KnowledgeSubsystemFactory.create( deps );

        assertNull( services.judgeService(),
            "judgeService should be null when wikantik.kg.judge.enabled=false" );
        assertNull( services.judgeRunner(),
            "judgeRunner should be null alongside judgeService" );
        // Timeout-tracking repo is unconditional so the admin surface still works.
        assertNotNull( services.judgeTimeoutRepository(),
            "judgeTimeoutRepository should be wired regardless of judge enable state" );
    }

    @Test
    void create_kgServiceRoundTripsThroughTheSubsystem() {
        final KnowledgeSubsystem.Services services = KnowledgeSubsystemFactory.create(
            new KnowledgeSubsystem.Deps( dataSource, persistence(), core( new Properties() ),
                page(),
                /*luceneMlt=*/ null ) );

        // Upsert a node via the public service interface...
        final KgNode created = services.kgService().upsertNode(
            "SubsystemFixtureNode", "concept", "SubsystemFixturePage",
            Provenance.HUMAN_AUTHORED, Map.of() );
        assertNotNull( created );
        assertNotNull( created.id() );

        // ...and read it back through the same service to prove repository
        // wiring is intact end-to-end without WikiEngine.
        final KgNode roundTrip = services.kgService().getNodeByName( "SubsystemFixtureNode" );
        assertNotNull( roundTrip );
        assertEquals( created.id(), roundTrip.id() );
        assertEquals( "concept", roundTrip.nodeType() );
    }
}
