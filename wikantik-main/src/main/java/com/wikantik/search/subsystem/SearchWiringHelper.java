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
package com.wikantik.search.subsystem;

import com.wikantik.WikiContext;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.search.SearchResult;
import com.wikantik.search.SearchManager;
import com.wikantik.search.embedding.AsyncEmbeddingIndexListener;
import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;
import com.wikantik.search.embedding.EmbeddingClientFactory;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.embedding.EmbeddingIndexService;
import com.wikantik.search.embedding.TextEmbeddingClient;
import com.wikantik.search.hybrid.DenseRetriever;
import com.wikantik.search.hybrid.GraphProximityScorer;
import com.wikantik.search.hybrid.GraphRerankConfig;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridConfig;
import com.wikantik.search.hybrid.HybridFuser;
import com.wikantik.search.hybrid.HybridMetricsBridge;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.InMemoryGraphNeighborIndex;
import com.wikantik.search.hybrid.PageMentionsLoader;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.QueryEmbedderConfig;
import com.wikantik.search.hybrid.QueryEntityResolver;
import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner;
import com.wikantik.knowledge.eval.RetrievalQualityDao;
import com.wikantik.knowledge.eval.RetrievalQualityMetrics;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Wiring helpers for the Search subsystem's optional services: hybrid retrieval,
 * graph rerank, and the retrieval-quality runner.
 *
 * <p>Phase 9 Ckpt 4c of the wikantik-main decomposition. These methods were
 * previously private helpers on {@link WikiEngine}. Moving them here reduces
 * {@code WikiEngine.java} toward the <1500 LOC target and collocates the
 * construction logic with the subsystem that owns it.</p>
 *
 * <p>Each method takes a {@link WikiEngine} reference and calls
 * {@code engine.setManager(X.class, foo)} to register services in the legacy
 * registry. The registry is not deleted in this checkpoint (that is Ckpt 4d).</p>
 */
public final class SearchWiringHelper {

    private static final Logger LOG = LogManager.getLogger( SearchWiringHelper.class );

    private SearchWiringHelper() {}

    // -----------------------------------------------------------------------
    // Hybrid retrieval
    // -----------------------------------------------------------------------

    /**
     * Wires the hybrid-retrieval infrastructure: embedding client, batch indexer,
     * async listener on {@code ChunkProjector}, in-memory vector index, and the
     * query-side {@link QueryEmbedder}.
     *
     * <p>Every wiring step is flag-gated — when
     * {@link EmbeddingConfig#PROP_ENABLED} is {@code false} the factory returns
     * {@link Optional#empty()} and this method is a no-op.</p>
     *
     * <p>Writes the lifecycle handles back into the engine fields via the package-
     * accessible {@link WikiEngine#setHybridLifecycleHandles} shim.</p>
     */
    @SuppressWarnings( "PMD.CloseResource" ) // Lifecycle handles stored on engine; closed in shutdown().
    public static void wireHybridRetrieval( final Properties props,
                                            final javax.sql.DataSource ds,
                                            final com.wikantik.knowledge.chunking.ChunkProjector chunkProjector,
                                            final ContentIndexRebuildService rebuildService,
                                            final WikiEngine engine ) {
        final EmbeddingConfig cfg;
        try {
            cfg = EmbeddingConfig.fromProperties( props );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Invalid embedding configuration; hybrid retrieval disabled: {}", e.getMessage() );
            return;
        }
        final Optional< TextEmbeddingClient > clientOpt = EmbeddingClientFactory.create( cfg );
        if ( clientOpt.isEmpty() ) {
            // Master flag off — nothing to wire.
            return;
        }
        final String modelCode = cfg.model().code();
        final com.wikantik.llm.activity.LlmActivityLog embedActivityLog =
            com.wikantik.llm.activity.LlmActivityLogHolder.getOrCreate( props );
        final TextEmbeddingClient client = embedActivityLog.enabled()
            ? new com.wikantik.llm.activity.RecordingEmbeddingClient(
                  clientOpt.get(), embedActivityLog, cfg.backend(), modelCode )
            : clientOpt.get();

        final EmbeddingIndexService indexService =
            new EmbeddingIndexService( ds, client, cfg.batchSize() );
        engine.registerEmbeddingIndexService( indexService );

        // Pick the dense retrieval backend up front so DenseRetriever (constructed
        // below) actually holds the configured impl. SearchSubsystemFactory exposes
        // the same choice via Services.chunkVectorIndex(), but nothing in production
        // reads that slot — the live wiring is here. Defaulting to in-memory matches
        // the ini bundle default and preserves zero-risk first-deploys.
        final String denseBackend = props.getProperty(
            "wikantik.search.dense.backend", "inmemory" ).toLowerCase( java.util.Locale.ROOT );

        final com.wikantik.search.hybrid.ChunkVectorIndex vectorIndex;
        final Runnable indexReloadHook;
        final InMemoryChunkVectorIndex inMemoryForListener;
        try {
            if ( "pgvector".equals( denseBackend ) ) {
                final int efSearch = Integer.parseInt( props.getProperty(
                    "wikantik.search.dense.pgvector.ef_search", "100" ) );
                final com.wikantik.search.hybrid.PgVectorChunkVectorIndex pgIndex =
                    new com.wikantik.search.hybrid.PgVectorChunkVectorIndex( ds, modelCode, efSearch );
                vectorIndex = pgIndex;
                inMemoryForListener = null;
                // pgvector keeps its HNSW index in sync via the dual-write INSERT
                // in EmbeddingIndexService.UPSERT_SQL (Task 7) — no in-memory
                // snapshot to refresh, so the bootstrap reload hook is a no-op.
                indexReloadHook = () -> {};
                LOG.info( "Dense retrieval backend: pgvector HNSW (model={}, ef_search={})",
                    modelCode, efSearch );
            } else if ( "inmemory".equals( denseBackend ) ) {
                final InMemoryChunkVectorIndex memIndex = new InMemoryChunkVectorIndex( ds, modelCode );
                vectorIndex = memIndex;
                inMemoryForListener = memIndex;
                indexReloadHook = memIndex::reload;
                engine.registerChunkVectorIndex( memIndex );
                LOG.info( "Dense retrieval backend: in-memory brute-force (model={}, size={})",
                    modelCode, memIndex.size() );
            } else {
                throw new IllegalArgumentException(
                    "wikantik.search.dense.backend must be 'inmemory' or 'pgvector', got: '"
                    + denseBackend + "'" );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "Failed to initialize {} ChunkVectorIndex (model={}); "
                + "hybrid retrieval disabled: {}", denseBackend, modelCode, e.getMessage(), e );
            return;
        }

        final AsyncEmbeddingIndexListener listener =
            new AsyncEmbeddingIndexListener( indexService, modelCode );
        // Only set the upsertChunks callback for the in-memory backend.
        // Under pgvector, EmbeddingIndexService.indexChunks dual-writes the
        // embedding column via its UPSERT SQL (Task 7), so the HNSW index
        // stays in sync without any listener-side reload step.
        if ( inMemoryForListener != null ) {
            listener.setPostIndexCallback( inMemoryForListener::upsertChunks );
        }
        chunkProjector.setPostChunkSink( listener );
        engine.setHybridIndexListener( listener );

        if ( rebuildService != null ) {
            rebuildService.setEmbeddingHook( indexService, modelCode );
        }

        final QueryEmbedderConfig qeCfg = QueryEmbedderConfig.fromProperties( props );
        final QueryEmbedder embedder =
            new QueryEmbedder( client, qeCfg, java.time.Clock.systemUTC() );
        engine.registerQueryEmbedder( embedder );
        engine.setHybridQueryEmbedder( embedder );

        final HybridConfig hybridCfg;
        try {
            hybridCfg = HybridConfig.fromProperties( props );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Invalid hybrid retrieval configuration; hybrid search disabled: {}", e.getMessage() );
            LOG.info( "Hybrid retrieval wired (embedding-only; search path NOT enabled)" );
            return;
        }
        final DenseRetriever denseRetriever =
            new DenseRetriever( vectorIndex,
                hybridCfg.pageAggregation(), hybridCfg.denseChunkTop(), hybridCfg.densePageTop() );
        final HybridFuser fuser =
            new HybridFuser( hybridCfg.rrfK(),
                hybridCfg.bm25Weight(), hybridCfg.denseWeight(), hybridCfg.rrfTruncate() );
        final HybridSearchService hybridSearch =
            new HybridSearchService( embedder, denseRetriever, fuser, hybridCfg.enabled() );
        engine.registerHybridSearchService( hybridSearch );

        final BootstrapEmbeddingIndexer bootstrap =
            new BootstrapEmbeddingIndexer( ds, indexService, modelCode, indexReloadHook );
        engine.registerBootstrapEmbeddingIndexer( bootstrap );
        engine.setHybridBootstrapIndexer( bootstrap );
        try {
            bootstrap.startIfNeeded();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Embedding bootstrap start failed (model={}): {}", modelCode, e.getMessage(), e );
        }

        HybridMetricsBridge.register(
            com.wikantik.api.observability.MeterRegistryHolder.get(),
            embedder, bootstrap, vectorIndex );

        LOG.info( "Hybrid retrieval wired (model={}, embed_backend={}, dense_backend={})",
            modelCode, cfg.backend(), denseBackend );
    }

    // -----------------------------------------------------------------------
    // Graph rerank
    // -----------------------------------------------------------------------

    /**
     * Wires the graph-aware rerank step: loads {@code kg_edges} into an
     * {@link InMemoryGraphNeighborIndex}, builds the name-based
     * {@link QueryEntityResolver}, and registers a {@link GraphRerankStep}.
     * Config-gated: {@code wikantik.search.graph.boost=0} skips wiring.
     */
    public static void wireGraphRerank( final Properties props,
                                        final javax.sql.DataSource ds,
                                        final WikiEngine engine ) {
        final GraphRerankConfig cfg;
        try {
            cfg = GraphRerankConfig.fromProperties( props );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Invalid graph rerank config; feature disabled: {}", e.getMessage() );
            return;
        }
        if ( !cfg.enabled() ) {
            LOG.info( "Graph rerank disabled (wikantik.search.graph.boost=0)" );
            return;
        }
        final InMemoryGraphNeighborIndex neighborIndex;
        try {
            // Always build a weighted index — the unweighted BFS used by HYBRID_GRAPH
            // ignores per-edge weights, while HYBRID_GRAPH_WEIGHTED consults them.
            final java.util.Map< String, Double > tierWeights = java.util.Map.of(
                "human",   cfg.tierHumanWeight(),
                "machine", cfg.tierMachineWeight()
            );
            neighborIndex = new InMemoryGraphNeighborIndex( ds, cfg.neighborIndexMaxEdges(), tierWeights );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Graph neighbor index failed to initialize; graph rerank disabled: {}", e.getMessage(), e );
            return;
        }
        final GraphProximityScorer scorer = new GraphProximityScorer( neighborIndex );
        final QueryEntityResolver resolver = new QueryEntityResolver( ds, cfg );
        final PageMentionsLoader mentionsLoader = new PageMentionsLoader( ds );
        final GraphRerankStep step =
            new GraphRerankStep( resolver, mentionsLoader, scorer, neighborIndex, cfg );

        engine.registerGraphNeighborIndex( neighborIndex );
        engine.registerGraphProximityScorer( scorer );
        engine.registerQueryEntityResolver( resolver );
        engine.registerPageMentionsLoader( mentionsLoader );
        engine.registerGraphRerankStep( step );

        LOG.info( "Graph rerank wired (boost={}, maxHops={}, indexNodes={})",
            cfg.boost(), cfg.maxHops(), neighborIndex.nodeCount() );
    }

    // -----------------------------------------------------------------------
    // Retrieval-quality runner
    // -----------------------------------------------------------------------

    /**
     * Registers the {@link RetrievalQualityRunner} so the
     * {@code /admin/retrieval-quality} endpoint and the nightly schedule have
     * a backend.
     *
     * <p>{@code searchManager}, {@code pageManager}, {@code hybridSearch}, and
     * {@code graphRerankStep} are the collaborators consumed by the retriever
     * lambda.  Each may be {@code null} when the corresponding feature is
     * disabled; the lambda degrades gracefully in that case.</p>
     */
    public static void wireRetrievalQualityRunner( final Properties props,
                                                    final javax.sql.DataSource ds,
                                                    final StructuralIndexService structuralIndex,
                                                    final SearchManager searchManager,
                                                    final PageManager pageManager,
                                                    final HybridSearchService hybridSearch,
                                                    final GraphRerankStep graphRerankStep,
                                                    final WikiEngine engine ) {
        try {
            final RetrievalQualityDao rqDao = new RetrievalQualityDao( ds );
            final RetrievalQualityMetrics rqMetrics = RetrievalQualityMetrics.resolveAndBind();
            final DefaultRetrievalQualityRunner.Retriever retriever =
                buildRetriever( engine, searchManager, pageManager, hybridSearch, graphRerankStep );
            final DefaultRetrievalQualityRunner.CanonicalIdResolver resolver =
                slug -> structuralIndex.resolveCanonicalIdFromSlug( slug );
            final int hour = TextUtil.getIntegerProperty( props, "wikantik.retrieval.cron.hour_utc", 3 );
            @SuppressWarnings( "PMD.CloseResource" ) // ownership transferred to engine.registerRetrievalQualityRunner()
            final DefaultRetrievalQualityRunner runner =
                new DefaultRetrievalQualityRunner( rqDao, retriever, resolver, rqMetrics, hour );
            engine.registerRetrievalQualityRunner( runner );

            if ( TextUtil.getBooleanProperty( props, "wikantik.retrieval.cron.enabled", true ) ) {
                runner.scheduleNightly();
                LOG.info( "RetrievalQualityRunner registered with nightly schedule (hour={}Z)", hour );
            } else {
                LOG.info( "RetrievalQualityRunner registered (nightly schedule disabled by config)" );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "RetrievalQualityRunner wiring failed; /admin/retrieval-quality will return 503: {}",
                e.getMessage(), e );
        }
    }

    /**
     * Bridges the live search stack to the
     * {@link DefaultRetrievalQualityRunner.Retriever} functional interface.
     *
     * <p>All four service parameters may be {@code null} when the corresponding
     * feature is disabled; the lambda degrades gracefully (falls back to BM25
     * or returns an empty list).</p>
     */
    private static DefaultRetrievalQualityRunner.Retriever buildRetriever(
            final WikiEngine engine,
            final SearchManager searchManager,
            final PageManager pageManager,
            final HybridSearchService hybridSearch,
            final GraphRerankStep graphRerankStep ) {
        return ( mode, query ) -> {
            if ( searchManager == null ) return List.of();
            final com.wikantik.api.core.Context ctx;
            try {
                final Page front = pageManager == null ? null : pageManager.getPage( engine.getFrontPage() );
                ctx = front == null ? null : new WikiContext( engine, front );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Could not build evaluation Context; aborting query: {}", e.getMessage() );
                return List.of();
            }
            if ( ctx == null ) return List.of();
            final List< String > bm25Names;
            try {
                final Collection< SearchResult > raw = searchManager.findPages( query, ctx );
                bm25Names = new ArrayList<>( raw.size() );
                for ( final SearchResult sr : raw ) {
                    if ( sr.getPage() != null ) bm25Names.add( sr.getPage().getName() );
                }
            } catch ( final Exception e ) {
                LOG.warn( "BM25 findPages failed for '{}': {}", query, e.getMessage(), e );
                return List.of();
            }
            switch ( mode ) {
                case BM25:
                    return bm25Names;
                case HYBRID:
                    return hybridSearch == null ? bm25Names : hybridSearch.rerank( query, bm25Names );
                case HYBRID_GRAPH: {
                    final List< String > fused =
                        hybridSearch == null ? bm25Names : hybridSearch.rerank( query, bm25Names );
                    return graphRerankStep == null ? fused : graphRerankStep.rerank( query, fused );
                }
                case HYBRID_GRAPH_WEIGHTED: {
                    final List< String > fused =
                        hybridSearch == null ? bm25Names : hybridSearch.rerank( query, bm25Names );
                    return graphRerankStep == null ? fused : graphRerankStep.rerankWeighted( query, fused );
                }
                default:
                    return bm25Names;
            }
        };
    }
}
