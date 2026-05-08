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

import com.wikantik.api.core.Page;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.knowledge.HubSyncFilter;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.judge.DefaultKgProposalJudgeService;
import com.wikantik.knowledge.judge.JdbcKgJudgeTimeoutRepository;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.judge.KgJudgeConfig;
import com.wikantik.knowledge.judge.KgJudgeTimeoutRepository;
import com.wikantik.knowledge.judge.KgMaterializationService;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds the Knowledge subsystem's complete service graph from a
 * {@link KnowledgeSubsystem.Deps} bundle.
 *
 * <p>This factory is the new home of the wiring that lived in
 * {@code KnowledgeGraphServiceFactory} (now a deprecated alias). The body is
 * a pure refactor — same construction order, same behaviour. The only
 * structural changes are:</p>
 *
 * <ul>
 *   <li>Inputs are bundled into {@link KnowledgeSubsystem.Deps} so future
 *       phases can swap them for typed subsystem services without touching
 *       this signature.</li>
 *   <li>Outputs are returned as {@link KnowledgeSubsystem.Services}, the
 *       subsystem's public contract.</li>
 * </ul>
 *
 * <p>The factory deliberately produces the graph without mutating any engine
 * state, so a failed construction throws cleanly without leaving
 * half-registered managers behind.</p>
 */
public final class KnowledgeSubsystemFactory {

    private static final Logger LOG = LogManager.getLogger( KnowledgeSubsystemFactory.class );

    private KnowledgeSubsystemFactory() {}

    /** Builds the Knowledge subsystem from its declared dependencies. */
    public static KnowledgeSubsystem.Services create( final KnowledgeSubsystem.Deps deps ) {
        final var dataSource  = deps.dataSource();
        final var persistence = deps.persistence();
        final var core        = deps.core();
        final var props       = core.properties().asProperties();
        final var spr         = core.systemPageRegistry();
        final var page        = deps.page();
        final var pageMgr     = page.pages();
        final var saveHelper  = page.pageSaveHelper();
        final var luceneMlt   = deps.luceneMlt();
        final var meterReg    = core.meterRegistry();

        final KgNodeRepository       kgNodes      = persistence.kgNodes();
        final KgEdgeRepository       kgEdges      = persistence.kgEdges();
        final KgProposalRepository   kgProposals  = persistence.kgProposals();
        final KgRejectionRepository  kgRejections = persistence.kgRejections();

        // MentionIndex isn't a repository — it's a service that keeps a
        // DataSource for lazy-loaded mention reads. Stays on dataSource
        // until the chunking pipeline gets its own typed accessor.
        final MentionIndex mentionIndex = new MentionIndex( dataSource );

        // KG staged validation: judge service, materialisation, runner.
        final KgJudgeConfig judgeCfg = KgJudgeConfig.fromProperties( props );
        final KgMaterializationService kgMat = new KgMaterializationService(
            kgNodes, kgEdges, kgProposals, kgRejections );

        // Timeout-tracking repo always constructed (cheap, no connections held);
        // surfaces chronic-timeout proposals to the admin UI even when the
        // judge cron is disabled.
        final KgJudgeTimeoutRepository judgeTimeoutRepo = persistence.judgeTimeouts();

        KgProposalJudgeService kgJudge = null;
        JudgeRunner kgRunner = null;
        if ( judgeCfg.enabled() && judgeCfg.endpoint() != null && !judgeCfg.endpoint().isBlank() ) {
            @SuppressWarnings( "PMD.CloseResource" ) // ownership transferred to DefaultKgProposalJudgeService
            final HttpClient http = HttpClient.newBuilder()
                .connectTimeout( Duration.ofSeconds( judgeCfg.timeoutSeconds() ) )
                .build();
            kgJudge = new DefaultKgProposalJudgeService( http, judgeCfg, judgeTimeoutRepo );
            kgRunner = new JudgeRunner( kgProposals, kgRejections, kgJudge, kgMat, judgeCfg );
            kgRunner.schedule();
            LOG.info( "KG judge service enabled: model={} endpoint={} cron={}m",
                judgeCfg.model(), judgeCfg.endpoint(), judgeCfg.cronIntervalMinutes() );
        } else {
            LOG.info( "KG judge service disabled (enabled={}, endpoint={})",
                judgeCfg.enabled(), judgeCfg.endpoint() );
        }

        final DefaultKnowledgeGraphService kgService =
            new DefaultKnowledgeGraphService( kgNodes, kgEdges, kgProposals, kgRejections,
                dataSource, null, mentionIndex, kgMat, kgJudge );

        final FrontmatterDefaultsFilter fmDefaults = new FrontmatterDefaultsFilter(
            name -> spr != null && spr.isSystemPage( name ), props );

        final HubSyncFilter hubSync = new HubSyncFilter(
            name -> {
                try {
                    final Page p = pageMgr.getPage( name );
                    return p != null ? pageMgr.getPureText( p ) : null;
                } catch ( final Exception e ) {
                    LOG.warn( "HubSyncFilter: failed to read page '{}': {}", name, e.getMessage() );
                    return null;
                }
            },
            ( name, content ) -> {
                try {
                    saveHelper.saveText( name, content,
                        SaveOptions.builder().changeNote( "Hub membership sync" ).build() );
                } catch ( final Exception e ) {
                    LOG.warn( "HubSyncFilter: failed to save page '{}': {}", name, e.getMessage() );
                }
            }
        );

        // Share the search-side embedding model code so the mention-centroid
        // reader picks the same vectors the hybrid retriever already stores.
        final String modelCode = EmbeddingConfig.fromProperties( props ).model().code();
        final NodeMentionSimilarity similarity = new NodeMentionSimilarity( dataSource, modelCode );

        final HubProposalRepository hubProposalRepo = persistence.hubProposals();
        final HubProposalService hubProposalService = HubProposalService.builder()
            .kgNodes( kgNodes )
            .kgEdges( kgEdges )
            .proposalRepo( hubProposalRepo )
            .similarity( similarity )
            .reviewPercentileFromProperties( props )
            .build();

        final HubDiscoveryRepository hubDiscoveryRepo = persistence.hubDiscovery();
        final HubDiscoveryService hubDiscoveryService = HubDiscoveryService.builder()
            .kgNodes( kgNodes )
            .kgEdges( kgEdges )
            .discoveryRepo( hubDiscoveryRepo )
            .similarity( similarity )
            .propsFrom( props )
            .pageWriter( ( name, content ) -> saveHelper.saveText( name, content,
                SaveOptions.builder().changeNote( "Hub discovery: stub created" ).build() ) )
            .pageExists( name -> {
                try {
                    final Page p = pageMgr.getPage( name );
                    return p != null;
                } catch ( final Exception e ) {
                    LOG.warn( "HubDiscoveryService pageExists: failed to check '{}': {}",
                        name, e.getMessage() );
                    return false;
                }
            } )
            .build();

        final HubOverviewService hubOverviewService = HubOverviewService.builder()
            .kgNodes( kgNodes )
            .kgEdges( kgEdges )
            .similarity( similarity )
            .pageManager( pageMgr )
            .pageWriter( ( name, content ) -> saveHelper.saveText( name, content,
                SaveOptions.builder().changeNote( "Hub overview: member removed" ).build() ) )
            .luceneMlt( luceneMlt ) // null → builder uses no-op default
            .propsFrom( props )
            .build();

        final ContentChunkRepository contentChunkRepo = persistence.contentChunks();
        final ContentChunker chunker = new ContentChunker( new ContentChunker.Config(
            TextUtil.getIntegerProperty( props, "wikantik.chunker.max_tokens", 512 ),
            TextUtil.getIntegerProperty( props, "wikantik.chunker.merge_forward_tokens", 150 ) ) );
        final ChunkProjector chunkProjector = meterReg != null
            ? new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ),
                meterReg )
            : new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ) );

        // Phase 8 Ckpt 1.5: the six post-construction services (ContextRetrievalService,
        // ForAgentProjectionService, BootstrapEntityExtractionIndexer, KgInclusionPolicy,
        // ReconciliationJobRunner, RetrievalQualityRunner) are wired into the engine's
        // manager registry AFTER this factory returns — either by WikiEngine.initKnowledgeGraph
        // continuations or by a servlet listener (ContextRetrievalServiceInitializer).
        // They start null here; WikiEngine rebuilds the Services record with the live
        // instances before stashing WikiSubsystems on the ServletContext.
        return new KnowledgeSubsystem.Services(
            kgService,
            kgJudge,
            kgRunner,
            kgMat,
            judgeTimeoutRepo,
            hubProposalService,
            hubDiscoveryService,
            hubOverviewService,
            hubProposalRepo,
            hubDiscoveryRepo,
            contentChunkRepo,
            chunkProjector,
            mentionIndex,
            similarity,
            fmDefaults,
            hubSync,
            /*contextRetrievalService=*/     null,
            /*forAgentProjectionService=*/   null,
            /*bootstrapEntityExtractionIndexer=*/ null,
            /*kgInclusionPolicy=*/           null,
            /*reconciliationJobRunner=*/     null,
            /*retrievalQualityRunner=*/      null
        );
    }
}
