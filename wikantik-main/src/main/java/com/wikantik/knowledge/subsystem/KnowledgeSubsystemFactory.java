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
import com.wikantik.api.core.Page;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgProposalJudgeService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
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
import com.wikantik.knowledge.curation.DefaultKgCurationOps;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.judge.DefaultKgProposalJudgeService;
import com.wikantik.knowledge.judge.JudgeRunner;
import com.wikantik.knowledge.judge.KgJudgeConfig;
import com.wikantik.knowledge.judge.KgJudgeTimeoutRepository;
import com.wikantik.knowledge.judge.KgMaterializationService;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import com.wikantik.kgpolicy.ReconciliationJobRunner;
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

    /**
     * Shared write-time ontology gate. Parses the bundled SHACL shapes once; {@code validate}/
     * {@code validateEdge} are read-only over immutable {@code Shapes}, so a single instance is
     * safe to share across the curation facade and the materialisation service.
     */
    private static final com.wikantik.ontology.OntologyShaclValidator ONTOLOGY_VALIDATOR =
            new com.wikantik.ontology.OntologyShaclValidator();

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
            kgNodes, kgEdges, kgProposals, kgRejections, ONTOLOGY_VALIDATOR );

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
            final com.wikantik.api.knowledge.KgProposalJudgeService rawJudge =
                new DefaultKgProposalJudgeService( http, judgeCfg, judgeTimeoutRepo );
            final com.wikantik.llm.activity.LlmActivityLog judgeActivityLog =
                com.wikantik.llm.activity.LlmActivityLogHolder.getOrCreate( props );
            kgJudge = judgeActivityLog.enabled()
                ? new com.wikantik.llm.activity.RecordingKgProposalJudgeService(
                      rawJudge, judgeActivityLog, "ollama", judgeCfg.model() )
                : rawJudge;
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
            TextUtil.getIntegerProperty( props, "wikantik.chunker.merge_forward_tokens", 150 ),
            TextUtil.getIntegerProperty( props, "wikantik.chunker.fragment_floor_tokens", 24 ) ) );
        final ChunkProjector chunkProjector = meterReg != null
            ? new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ),
                meterReg )
            : new ChunkProjector( chunker, contentChunkRepo,
                () -> TextUtil.getBooleanProperty( props, "wikantik.chunker.enabled", true ) );

        // KG curation facade — built once here, shared by both the REST admin surface and
        // the MCP write tools. PageSaveHelper is already available via saveHelper above.
        // Pass KgExcludedPagesRepository so approve can surface warnings when the source
        // page is on the exclusion list.
        final KgExcludedPagesRepository kgExcludedPages = persistence.kgExcludedPages();
        final KgCurationOps curation = new DefaultKgCurationOps(
            kgService, pageMgr, saveHelper, kgExcludedPages, ONTOLOGY_VALIDATOR );

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
            /*retrievalQualityRunner=*/      null,
            /*kgCurationOps=*/               curation,
            // Derived from contextRetrievalService (null here); built post-startup at the
            // same seam — see BundleServiceWiring / WikiEngine.patchContextRetrievalService.
            /*bundleAssemblyService=*/       null
        );
    }

    // -------------------------------------------------------------------------
    // Phase 12 Ckpt 1 — side-effect-free rebuild adapter
    // -------------------------------------------------------------------------

    /**
     * Rebuilds a {@link KnowledgeSubsystem.Services} record from the engine's
     * manager registry, preferring live registry values over the existing snapshot
     * for every hot-swappable field.
     *
     * <p>This method is the side-effect-free complement to {@link #create}: it
     * NEVER calls {@code kgRunner.schedule()}, starts background indexers, or
     * wires any cron scheduler. For each field it delegates to
     * {@link #preferRegistry}, which simply reads the registry and falls back to
     * the existing instance — no new objects are constructed for fields that
     * were not swapped.</p>
     *
     * <p>If {@code existing} is {@code null} (first-init path, no prior snapshot),
     * delegates to {@link #readFromManagerRegistry} which mirrors the legacy
     * bridge body verbatim.</p>
     *
     * <p>Intended to be called by {@code KnowledgeSubsystemBridge.rebuildFromManagers}
     * in Ckpt 2, once the bridge is wired to delegate here instead of doing
     * manual synthesis itself.</p>
     *
     * <p><b>ContextRetrievalService invariant:</b> this field is intentionally
     * excluded from {@code setManager} rebuilds (see
     * {@code WikiEngine.SNAPSHOT_REBUILDERS}). It is always taken from
     * {@code existing} when a prior snapshot exists; when existing is null the
     * registry is consulted as a fallback (consistent with
     * {@code KnowledgeSubsystemBridge.rebuildFromManagers}).</p>
     */
    public static KnowledgeSubsystem.Services rebuildFromExisting(
            final WikiEngine engine,
            final KnowledgeSubsystem.Services existing ) {
        if ( existing == null ) {
            return readFromManagerRegistry( engine );
        }
        return new KnowledgeSubsystem.Services(
            // 1. kgService — hot-swappable, no side-effects
            preferRegistry( engine, KnowledgeGraphService.class,             existing.kgService() ),
            // 2. judgeService — hot-swappable; side-effect risk: preferRegistry reads the
            //    singleton, never re-instantiates, so no duplicate scheduler.
            preferRegistry( engine, KgProposalJudgeService.class,           existing.judgeService() ),
            // 3. judgeRunner — hot-swappable; side-effect risk: same — we never call schedule()
            preferRegistry( engine, JudgeRunner.class,                      existing.judgeRunner() ),
            // 4. kgMaterialization — hot-swappable, no side-effects
            preferRegistry( engine, KgMaterializationService.class,         existing.kgMaterialization() ),
            // 5. judgeTimeoutRepository — hot-swappable, no side-effects
            preferRegistry( engine, KgJudgeTimeoutRepository.class,         existing.judgeTimeoutRepository() ),
            // 6. hubProposalService — hot-swappable, no side-effects
            preferRegistry( engine, HubProposalService.class,               existing.hubProposalService() ),
            // 7. hubDiscoveryService — hot-swappable, no side-effects
            preferRegistry( engine, HubDiscoveryService.class,              existing.hubDiscoveryService() ),
            // 8. hubOverviewService — hot-swappable, no side-effects
            preferRegistry( engine, HubOverviewService.class,               existing.hubOverviewService() ),
            // 9. hubProposalRepository — hot-swappable, no side-effects
            preferRegistry( engine, HubProposalRepository.class,            existing.hubProposalRepository() ),
            // 10. hubDiscoveryRepository — hot-swappable, no side-effects
            preferRegistry( engine, HubDiscoveryRepository.class,           existing.hubDiscoveryRepository() ),
            // 11. contentChunkRepository — hot-swappable, no side-effects
            preferRegistry( engine, ContentChunkRepository.class,           existing.contentChunkRepository() ),
            // 12. chunkProjector — hot-swappable, no side-effects
            preferRegistry( engine, ChunkProjector.class,                   existing.chunkProjector() ),
            // 13. mentionIndex — hot-swappable; side-effect risk: lazy DataSource holder —
            //     preferRegistry reads the singleton, never constructs a new one.
            preferRegistry( engine, MentionIndex.class,                     existing.mentionIndex() ),
            // 14. nodeMentionSimilarity — hot-swappable; side-effect risk: same as mentionIndex
            preferRegistry( engine, NodeMentionSimilarity.class,            existing.nodeMentionSimilarity() ),
            // 15. frontmatterDefaultsFilter — hot-swappable, no side-effects
            preferRegistry( engine, FrontmatterDefaultsFilter.class,        existing.frontmatterDefaultsFilter() ),
            // 16. hubSyncFilter — hot-swappable, no side-effects
            preferRegistry( engine, HubSyncFilter.class,                    existing.hubSyncFilter() ),
            // 17. contextRetrievalService — intentionally excluded from setManager rebuilds
            //     (audit row 17; WikiEngine.SNAPSHOT_REBUILDERS comment at line 463).
            //     Always reuse the existing instance; the ContextRetrievalServiceInitializer
            //     servlet listener owns its lifecycle and re-wires it directly.
            existing.contextRetrievalService(),
            // 18. forAgentProjectionService — hot-swappable; side-effect risk: memoized cache —
            //     preferRegistry reads the singleton, preserving cache state.
            preferRegistry( engine, ForAgentProjectionService.class,        existing.forAgentProjectionService() ),
            // 19. bootstrapEntityExtractionIndexer — hot-swappable; side-effect risk: background
            //     indexer — preferRegistry reads the singleton, never restarts it.
            preferRegistry( engine, BootstrapEntityExtractionIndexer.class, existing.bootstrapEntityExtractionIndexer() ),
            // 20. kgInclusionPolicy — hot-swappable, no side-effects
            preferRegistry( engine, KgInclusionPolicy.class,                existing.kgInclusionPolicy() ),
            // 21. reconciliationJobRunner — hot-swappable; side-effect risk: cron scheduler —
            //     preferRegistry reads the singleton, never re-schedules.
            preferRegistry( engine, ReconciliationJobRunner.class,          existing.reconciliationJobRunner() ),
            // 22. retrievalQualityRunner — hot-swappable; side-effect risk: nightly CI cron —
            //     preferRegistry reads the singleton, never re-schedules.
            preferRegistry( engine, RetrievalQualityRunner.class,           existing.retrievalQualityRunner() ),
            // 23. kgCurationOps — DERIVED; reconstructed when upstreams changed.
            rebuildKgCurationOps( engine, existing ),
            // 24. bundleAssemblyService — DERIVED from contextRetrievalService and built once
            //     at the retrieval-patch seam (WikiEngine.patchContextRetrievalService). Reuse
            //     the existing instance; this side-effect-free rebuild never reconstructs it.
            existing.bundleAssemblyService()
        );
    }

    /**
     * Reconstructs {@link KgCurationOps} only when one of its upstream dependencies
     * was hot-swapped into the engine registry.  If {@code kgService} did not change
     * and the existing curation ops are still valid, they are reused unchanged.
     *
     * <p>PageManager is not a field on {@link KnowledgeSubsystem.Services}; it is
     * read directly from the engine registry here, consistent with how
     * {@code KnowledgeSubsystemBridge.rebuildFromManagers} reads it today.</p>
     *
     * <p>Reuse heuristic: if the registry's {@code KnowledgeGraphService} resolves
     * to the same instance as {@code existing.kgService()} (i.e. kgService was not
     * hot-swapped), and existing curation ops are non-null, reuse them.
     * PageManager is almost never hot-swapped independently of kgService in
     * production; when it is, the next setManager call will trigger a second
     * rebuild that catches the change.</p>
     *
     * <p>If kgService changed or existing curation ops are null, reconstruct the
     * facade following the same logic as
     * {@code KnowledgeSubsystemBridge.rebuildFromManagers} lines 113–123.</p>
     */
    private static KgCurationOps rebuildKgCurationOps(
            final WikiEngine engine,
            final KnowledgeSubsystem.Services existing ) {
        final KnowledgeGraphService newKg = preferRegistry(
            engine, KnowledgeGraphService.class, existing.kgService() );

        // Reuse the existing ops when the KG service identity is unchanged and
        // the existing facade is non-null.  A null facade means a prior rebuild
        // failed due to missing managers — always retry.
        if ( newKg == existing.kgService() && existing.kgCurationOps() != null ) {
            return existing.kgCurationOps();
        }

        // kgService changed, or prior build failed — reconstruct.
        // Mirror the logic in KnowledgeSubsystemBridge.rebuildFromManagers lines 113-123:
        // both kgService and pageManager must be non-null to build a valid facade.
        final PageManager newPm = engine.getManager( PageManager.class );
        if ( newKg != null && newPm != null ) {
            final PageSaveHelper saver = new PageSaveHelper( engine, newPm );
            // KgExcludedPagesRepository may not be registered in lightweight test fixtures;
            // fall back to three-arg ctor (warnings silently disabled) when it is absent.
            final KgExcludedPagesRepository excludedRepo =
                engine.getManager( KgExcludedPagesRepository.class );
            return new DefaultKgCurationOps( newKg, newPm, saver, excludedRepo, ONTOLOGY_VALIDATOR );
        }
        return null;
    }

    /**
     * Reads every {@link KnowledgeSubsystem.Services} field directly from the engine's
     * manager registry, with no existing snapshot to fall back on.
     *
     * <p>This is the verbatim body of
     * {@code KnowledgeSubsystemBridge.rebuildFromManagers} extracted here so
     * that {@link #rebuildFromExisting} can delegate to it on the first-init
     * path (when no snapshot yet exists). Once Ckpt 2 wires the bridge to
     * call {@link #rebuildFromExisting}, this method becomes the single
     * authoritative zero-state initializer.</p>
     *
     * <p>Side-effect risk fields (judgeRunner, mentionIndex, nodeMentionSimilarity,
     * forAgentProjectionService, bootstrapEntityExtractionIndexer,
     * reconciliationJobRunner, retrievalQualityRunner) are read from the
     * registry — the same singletons that were put there by {@link #create} or
     * by post-construction wiring. No new objects are constructed.</p>
     */
    static KnowledgeSubsystem.Services readFromManagerRegistry( final WikiEngine engine ) {
        final KnowledgeGraphService kgSvc = engine.getManager( KnowledgeGraphService.class );
        final PageManager pm = engine.getManager( PageManager.class );
        // Synthesise a DefaultKgCurationOps when both the KG service and page manager are
        // available (the normal test-fixture case after setManager hot-swaps). Falls back
        // to null when the engine is a minimal stub that only registers some managers.
        final KgCurationOps kgCurationOps;
        if ( kgSvc != null && pm != null ) {
            final PageSaveHelper saver = new PageSaveHelper( engine, pm );
            // KgExcludedPagesRepository may not be registered in lightweight test fixtures;
            // fall back to three-arg ctor (warnings silently disabled) when it is absent.
            final KgExcludedPagesRepository excludedRepo =
                engine.getManager( KgExcludedPagesRepository.class );
            kgCurationOps = new DefaultKgCurationOps( kgSvc, pm, saver, excludedRepo, ONTOLOGY_VALIDATOR );
        } else {
            kgCurationOps = null;
        }
        return new KnowledgeSubsystem.Services(
            kgSvc,
            engine.getManager( KgProposalJudgeService.class ),
            engine.getManager( JudgeRunner.class ),
            engine.getManager( KgMaterializationService.class ),
            engine.getManager( KgJudgeTimeoutRepository.class ),
            engine.getManager( HubProposalService.class ),
            engine.getManager( HubDiscoveryService.class ),
            engine.getManager( HubOverviewService.class ),
            engine.getManager( HubProposalRepository.class ),
            engine.getManager( HubDiscoveryRepository.class ),
            engine.getManager( ContentChunkRepository.class ),
            engine.getManager( ChunkProjector.class ),
            engine.getManager( MentionIndex.class ),
            engine.getManager( NodeMentionSimilarity.class ),
            engine.getManager( FrontmatterDefaultsFilter.class ),
            engine.getManager( HubSyncFilter.class ),
            engine.getManager( ContextRetrievalService.class ),
            engine.getManager( ForAgentProjectionService.class ),
            engine.getManager( BootstrapEntityExtractionIndexer.class ),
            engine.getManager( KgInclusionPolicy.class ),
            engine.getManager( ReconciliationJobRunner.class ),
            engine.getManager( RetrievalQualityRunner.class ),
            kgCurationOps,
            // bundleAssemblyService — built at the retrieval-patch seam, not on this cold
            // no-snapshot path (the stashed snapshot, patched with the bundle, is what
            // production reads). Null here is correct: surfaces degrade until the patch fires.
            null
        );
    }

    /**
     * Returns the manager of type {@code klass} from the engine registry if
     * present, otherwise falls back to {@code existing}.
     *
     * <p>This is the core of the side-effect-free rebuild: reading from the
     * registry never re-instantiates, never schedules, never starts
     * background tasks — it simply returns the singleton that is already
     * live in the engine.</p>
     */
    private static <T> T preferRegistry(
            final WikiEngine engine,
            final Class<T> klass,
            final T existing ) {
        final T fromRegistry = engine.getManager( klass );
        return fromRegistry != null ? fromRegistry : existing;
    }
}
