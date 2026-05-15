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
import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.admin.LuceneReindexQueue;
import com.wikantik.admin.LuceneSearchProviderAdapter;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.cache.CachingManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.knowledge.agent.AgentHintsDeriver;
import com.wikantik.knowledge.agent.DefaultForAgentProjectionService;
import com.wikantik.knowledge.agent.ForAgentMetrics;
import com.wikantik.knowledge.agent.HubSummarySynthesizer;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.knowledge.extraction.AsyncEntityExtractionListener;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.extraction.ChunkEntityMentionRepository;
import com.wikantik.knowledge.extraction.EntityExtractorConfig;
import com.wikantik.knowledge.extraction.EntityExtractorFactory;
import com.wikantik.knowledge.extraction.EvidenceGroundingVerifier;
import com.wikantik.knowledge.extraction.MentionAttributor;
import com.wikantik.knowledge.extraction.NoOpProposalJudge;
import com.wikantik.knowledge.extraction.OllamaPageExtractor;
import com.wikantik.knowledge.extraction.PageEmbeddingProvider;
import com.wikantik.knowledge.extraction.PageExtractionResponseParser;
import com.wikantik.knowledge.extraction.ProposalConsolidator;
import com.wikantik.knowledge.extraction.ProposalUpserter;
import com.wikantik.kgpolicy.DefaultKgInclusionPolicy;
import com.wikantik.kgpolicy.KgClusterPolicyRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import com.wikantik.kgpolicy.PagesByCluster;
import com.wikantik.kgpolicy.ReconciliationHook;
import com.wikantik.kgpolicy.ReconciliationJobRunner;
import com.wikantik.kgpolicy.StructuralIndexFrontmatterOverrideReader;
import com.wikantik.kgpolicy.SystemPageBackfillTask;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.search.LuceneSearchProvider;
import com.wikantik.search.SearchManager;
import com.wikantik.util.TextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Wiring helpers for Knowledge-graph-owned services that are built after
 * {@link KnowledgeSubsystemFactory#create} returns: KG inclusion policy,
 * {@link ForAgentProjectionService}, {@link ContentIndexRebuildService},
 * entity extraction, and bootstrap indexer.
 *
 * <p>Phase 9 Ckpt 4c of the wikantik-main decomposition. These methods were
 * previously private helpers on {@link WikiEngine}. Moving them here reduces
 * {@code WikiEngine.java} toward the &lt;1500 LOC target and collocates the
 * construction logic with the subsystem that owns it.</p>
 *
 * <p>Each method registers services via {@code engine.setManager(X.class, foo)}.
 * The registry is not deleted in this checkpoint (that is Ckpt 4d).</p>
 */
public final class KnowledgeWiringHelper {

    private static final Logger LOG = LogManager.getLogger( KnowledgeWiringHelper.class );

    private KnowledgeWiringHelper() {}

    // -----------------------------------------------------------------------
    // KG inclusion policy + ForAgent + ContentIndexRebuild
    // -----------------------------------------------------------------------

    /**
     * Wires the KG inclusion policy, {@link ForAgentProjectionService}, and
     * {@link ContentIndexRebuildService}.
     *
     * @return the {@link ContentIndexRebuildService} produced (may be {@code null}
     *         when Lucene is not the configured search provider)
     */
    public static ContentIndexRebuildService wireKgPolicyAndContent(
            final Properties props,
            final StructuralIndexService structuralIndex,
            final CoreSubsystem.Services coreSubsystem,
            final PersistenceSubsystem.Services persistenceSubsystem,
            final KnowledgeSubsystem.Services knowledgeSvcs,
            final SearchManager searchMgr,
            final MeterRegistry meterRegistry,
            final PageManager pageManager,
            final CachingManager cachingManager,
            final ReferenceManager referenceManager,
            final WikiEngine engine ) {

        // KG inclusion policy
        final boolean kgPolicyEnabled = TextUtil.getBooleanProperty(
            props, "wikantik.kg_policy.enabled", true );
        if ( kgPolicyEnabled ) {
            final KgClusterPolicyRepository policyRepo = persistenceSubsystem.kgClusterPolicy();
            final KgExcludedPagesRepository excludedRepo = persistenceSubsystem.kgExcludedPages();
            final StructuralIndexFrontmatterOverrideReader overrides =
                new StructuralIndexFrontmatterOverrideReader( structuralIndex );
            final DefaultKgInclusionPolicy policy = new DefaultKgInclusionPolicy(
                coreSubsystem.systemPageRegistry(), structuralIndex, policyRepo, overrides );
            policy.initialize( engine, props );

            final PagesByCluster pagesByCluster =
                PagesByCluster.fromStructural( structuralIndex );
            @SuppressWarnings( "PMD.CloseResource" ) // ownership transferred to engine.registerReconciliationJobRunner()
            final ReconciliationJobRunner reconciler =
                new ReconciliationJobRunner( policy, excludedRepo, pagesByCluster );
            ReconciliationHook.install( reconciler::enqueue );

            engine.registerKgInclusionPolicy( policy );
            engine.registerKgClusterPolicyRepository( policyRepo );
            engine.registerKgExcludedPagesRepository( excludedRepo );
            engine.registerReconciliationJobRunner( reconciler );

            LOG.info( "KG inclusion policy wired (default-exclude active)" );

            new SystemPageBackfillTask( coreSubsystem.systemPageRegistry(), excludedRepo ).run();
        } else {
            LOG.info( "KG inclusion policy DISABLED via wikantik.kg_policy.enabled=false" );
        }

        // ForAgentProjectionService
        final ForAgentMetrics forAgentMetrics = ForAgentMetrics.resolveAndBind();
        final AgentHintsDeriver hintsDeriver =
            new AgentHintsDeriver( structuralIndex, pageManager, referenceManager );
        final DefaultForAgentProjectionService forAgentService =
            new DefaultForAgentProjectionService(
                structuralIndex,
                pageManager,
                cachingManager,
                forAgentMetrics,
                hintsDeriver,
                new HubSummarySynthesizer() );
        engine.registerForAgentProjectionService( forAgentService );
        LOG.info( "ForAgentProjectionService registered" );

        // ContentIndexRebuildService (Lucene-only)
        ContentIndexRebuildService rebuildService = null;
        if ( searchMgr != null && searchMgr.getSearchEngine() instanceof LuceneSearchProvider lsp ) {
            final LuceneReindexQueue queue = new LuceneSearchProviderAdapter( lsp );
            final ContentChunker rebuildChunker = new ContentChunker(
                new ContentChunker.Config(
                    TextUtil.getIntegerProperty( props, "wikantik.chunker.max_tokens", 512 ),
                    TextUtil.getIntegerProperty( props, "wikantik.chunker.merge_forward_tokens", 150 ) ) );
            rebuildService = meterRegistry != null
                ? new ContentIndexRebuildService(
                    pageManager,
                    coreSubsystem.systemPageRegistry(),
                    queue,
                    knowledgeSvcs.contentChunkRepository(),
                    rebuildChunker,
                    () -> TextUtil.getBooleanProperty( props, "wikantik.rebuild.enabled", true ),
                    TextUtil.getIntegerProperty( props, "wikantik.rebuild.lucene_drain_poll_ms", 2000 ),
                    meterRegistry )
                : new ContentIndexRebuildService(
                    pageManager,
                    coreSubsystem.systemPageRegistry(),
                    queue,
                    knowledgeSvcs.contentChunkRepository(),
                    rebuildChunker,
                    () -> TextUtil.getBooleanProperty( props, "wikantik.rebuild.enabled", true ),
                    TextUtil.getIntegerProperty( props, "wikantik.rebuild.lucene_drain_poll_ms", 2000 ) );
            engine.registerContentIndexRebuildService( rebuildService );
            LOG.info( "ContentIndexRebuildService registered" );
        } else {
            LOG.info( "ContentIndexRebuildService NOT registered — no LuceneSearchProvider in use" );
        }
        return rebuildService;
    }

    // -----------------------------------------------------------------------
    // Entity extraction
    // -----------------------------------------------------------------------

    /**
     * Wires the entity-extraction pipeline: extractor backend (Claude or Ollama),
     * async listener on {@code ChunkProjector}'s post-chunk sink, and the mention
     * repository. Opt-in via
     * {@code wikantik.knowledge.extractor.backend=claude|ollama|disabled}
     * (default {@code disabled}).
     */
    @SuppressWarnings( "PMD.CloseResource" ) // Listener stored on engine; lifecycle follows engine shutdown.
    public static void wireEntityExtraction( final Properties props,
                                              final javax.sql.DataSource ds,
                                              final ChunkProjector chunkProjector,
                                              final ContentChunkRepository contentChunkRepo,
                                              final PersistenceSubsystem.Services persistenceSubsystem,
                                              final KgExcludedPagesRepository excludedPagesRepo,
                                              final WikiEngine engine ) {
        final EntityExtractorConfig extractorCfg = EntityExtractorConfig.fromProperties( props );
        if ( !extractorCfg.enabled() ) {
            LOG.info( "Entity extraction disabled (wikantik.knowledge.extractor.backend=disabled)" );
            return;
        }
        Optional< com.wikantik.api.knowledge.EntityExtractor > extractorOpt =
            EntityExtractorFactory.create( extractorCfg );
        if ( extractorOpt.isEmpty() ) {
            LOG.warn( "Entity extraction configured ({}), but no usable backend; skipping wiring",
                      extractorCfg.backend() );
            return;
        }
        final com.wikantik.llm.activity.LlmActivityLog activityLog =
            com.wikantik.llm.activity.LlmActivityLogHolder.getOrCreate( props );
        if ( activityLog.enabled() ) {
            final String exBackend = extractorCfg.backend();
            final String exModel = EntityExtractorConfig.BACKEND_CLAUDE.equalsIgnoreCase( exBackend )
                ? extractorCfg.claudeModel() : extractorCfg.ollamaModel();
            extractorOpt = extractorOpt.map( e ->
                new com.wikantik.llm.activity.RecordingEntityExtractor( e, activityLog, exBackend, exModel ) );
            LOG.info( "LLM activity recording enabled for entity extraction" );
        }
        final ChunkEntityMentionRepository mentionRepo =
            new ChunkEntityMentionRepository( ds );
        final KgNodeRepository kgNodes = persistenceSubsystem.kgNodes();
        final KgProposalRepository kgProposals = persistenceSubsystem.kgProposals();
        final KgRejectionRepository kgRejections = persistenceSubsystem.kgRejections();
        final MeterRegistry meter = Metrics.globalRegistry;

        final AsyncEntityExtractionListener listener =
            new AsyncEntityExtractionListener(
                extractorOpt.get(), extractorCfg, contentChunkRepo, mentionRepo,
                kgNodes, kgProposals, kgRejections, meter, excludedPagesRepo );
        engine.setEntityExtractionListener( listener );
        engine.registerChunkEntityMentionRepository( mentionRepo );
        engine.registerAsyncEntityExtractionListener( listener );

        if ( "ollama".equalsIgnoreCase( extractorCfg.backend() ) ) {
            wireBootstrapIndexer( props, ds, contentChunkRepo, mentionRepo, kgNodes,
                excludedPagesRepo, extractorCfg, persistenceSubsystem, engine );
        } else {
            LOG.info( "Bootstrap indexer not wired (backend={}); /admin/knowledge-graph/extract-mentions "
                    + "will return 503 until an Ollama-backed extractor is configured",
                extractorCfg.backend() );
        }

        // Chain with any existing post-chunk sink (hybrid embedding indexer).
        final Consumer< List< java.util.UUID > > prior = engine.getHybridIndexListener();
        final Consumer< List< java.util.UUID > > safePrior = prior == null
            ? null
            : ids -> {
                try {
                    prior.accept( ids );
                } catch ( final RuntimeException e ) {
                    LOG.warn( "Hybrid index listener failed; entity extraction will still run: {}",
                              e.getMessage(), e );
                }
            };
        final Consumer< List< java.util.UUID > > composite =
            safePrior == null ? listener : safePrior.andThen( listener );
        chunkProjector.setPostChunkSink( composite );

        final String modelLabel = "claude".equalsIgnoreCase( extractorCfg.backend() )
            ? extractorCfg.claudeModel()
            : extractorCfg.ollamaModel();
        LOG.info( "Entity extraction wired (backend={}, model={}, threshold={}, timeoutMs={}, batchConcurrency={})",
                  extractorCfg.backend(), modelLabel, extractorCfg.confidenceThreshold(),
                  extractorCfg.timeoutMs(), extractorCfg.concurrency() );
    }

    // -----------------------------------------------------------------------
    // Bootstrap indexer (Ollama-backend only)
    // -----------------------------------------------------------------------

    private static void wireBootstrapIndexer( final Properties props,
                                               final javax.sql.DataSource ds,
                                               final ContentChunkRepository chunkRepo,
                                               final ChunkEntityMentionRepository mentionRepo,
                                               final KgNodeRepository kgNodes,
                                               final KgExcludedPagesRepository excludedPagesRepo,
                                               final EntityExtractorConfig extractorCfg,
                                               final PersistenceSubsystem.Services persistenceSubsystem,
                                               final WikiEngine engine ) {
        @SuppressWarnings( "PMD.CloseResource" ) // ownership transferred to OllamaPageExtractor
        final HttpClient http = HttpClient.newHttpClient();
        final int maxEntitiesPerPage = 12;
        final int maxRelationsPerPage = 8;
        final int dictionaryTopK = 0; // No PageEmbeddingProvider wired — top-K skipped.

        final PageExtractionResponseParser parser =
            new PageExtractionResponseParser(
                new EvidenceGroundingVerifier(), maxEntitiesPerPage, maxRelationsPerPage );
        final OllamaPageExtractor extractor =
            new OllamaPageExtractor(
                http, extractorCfg.ollamaBaseUrl(), extractorCfg.ollamaModel(),
                extractorCfg.timeoutMs(), parser );

        @SuppressWarnings( "PMD.CloseResource" ) // ownership transferred to engine.registerBootstrapEntityExtractionIndexer()
        final BootstrapEntityExtractionIndexer indexer =
            new BootstrapEntityExtractionIndexer(
                extractor,
                new NoOpProposalJudge(),
                new ProposalConsolidator(),
                new ProposalUpserter( persistenceSubsystem.kgProposals() ),
                /*embeddingService*/ null,
                /*embeddingRepo*/ null,
                chunkRepo, mentionRepo, kgNodes,
                new MentionAttributor(),
                PageEmbeddingProvider.EMPTY,
                excludedPagesRepo,
                extractorCfg.concurrency(), dictionaryTopK,
                maxEntitiesPerPage, maxRelationsPerPage );
        engine.registerBootstrapEntityExtractionIndexer( indexer );
        LOG.info( "Bootstrap indexer wired (model={}, concurrency={}, judge=none, "
                + "maxEntitiesPerPage={}, maxRelationsPerPage={})",
            extractorCfg.ollamaModel(), extractorCfg.concurrency(),
            maxEntitiesPerPage, maxRelationsPerPage );
    }
}
