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

import com.wikantik.WikiEngine;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.LuceneSearchProvider;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;
import com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle;
import com.wikantik.search.subsystem.lucene.LuceneIndexer;
import com.wikantik.search.subsystem.lucene.LuceneSearcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.search.embedding.AsyncEmbeddingIndexListener;
import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.embedding.EmbeddingIndexService;
import com.wikantik.search.embedding.OllamaEmbeddingClient;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.GraphProximityScorer;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.InMemoryGraphNeighborIndex;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.QueryEntityResolver;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Builds {@link SearchSubsystem.Services} from {@link SearchSubsystem.Deps}.
 *
 * <p>Phase 7 Checkpoint 1 of the wikantik-main subsystem decomposition.
 * See {@code docs/superpowers/plans/2026-05-07-decomposition-phase-7-search-subsystem.md}.</p>
 *
 * <p>Pulls every Search-owned service off the engine's legacy manager
 * registry. Every field is constructed earlier in
 * {@code WikiEngine.initialize()} (specifically
 * {@code wireHybridRetrieval} / {@code wireGraphRerank} /
 * {@code wireEntityExtraction}) and registered via
 * {@code engine.setManager(...)}. When a wiring path was skipped (no
 * datasource, embeddings disabled, hybrid disabled, etc.) the
 * corresponding manager is absent and the slot stays {@code null} — same
 * shape as a missing legacy manager.</p>
 *
 * <p>The three Lucene helper slots ({@code luceneIndexer},
 * {@code luceneSearcher}, {@code luceneIndexLifecycle}) are
 * unconditionally {@code null} in Checkpoint 1. Checkpoint 3 decomposes
 * {@code LuceneSearchProvider}; Checkpoint 4 populates them off the live
 * provider via accessors.</p>
 *
 * <p>Extraction constraint: every {@code engine.getManager(...)} call must
 * stay lexically inside {@link #create} — the frozen
 * {@code DecompositionArchTest.no_new_get_manager_callers} store matches
 * violations by method signature, so a getManager call moved into a new
 * helper reads as a NEW violation. {@code create()} therefore resolves all
 * managers into locals up front and passes the resolved collaborators into
 * the private helpers below, which keep the construction logic
 * (NcssCount) without ever touching the engine registry themselves.</p>
 */
public final class SearchSubsystemFactory {

    private static final Logger LOG = LogManager.getLogger( SearchSubsystemFactory.class );

    private SearchSubsystemFactory() {}

    public static SearchSubsystem.Services create( final SearchSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        // deps.core(), deps.persistence(), deps.page(), deps.knowledge() are reserved for future
        // use when SearchSubsystemFactory takes over manager construction; they are not yet
        // read in the method body.
        final WikiEngine engine = ( WikiEngine ) Objects.requireNonNull( deps.engine(), "engine" );

        final SearchManager  searchManager  = engine.getManager( SearchManager.class );
        final SearchProvider searchProvider =
            searchManager != null ? safeGetSearchEngine( searchManager ) : null;

        // Hybrid retrieval (registered by wireHybridRetrieval +
        // wireGraphRerank when the master flags are on).
        final HybridSearchService  hybridSearch         = engine.getManager( HybridSearchService.class );
        final QueryEmbedder        queryEmbedder        = engine.getManager( QueryEmbedder.class );
        final QueryEntityResolver  queryEntityResolver  = engine.getManager( QueryEntityResolver.class );
        final GraphRerankStep      graphRerankStep      = engine.getManager( GraphRerankStep.class );
        final GraphProximityScorer graphProximityScorer = engine.getManager( GraphProximityScorer.class );

        // Chunk vector index inputs — resolved here (see the extraction
        // constraint in the class javadoc); selection + construction live
        // in resolveChunkVectorIndex. The typed field read
        // (engine.getChunkVectorIndex()), not the manager registry,
        // satisfies the no-new-getManager-callers architecture rule (see
        // WikiEngine.setChunkVectorIndex/getChunkVectorIndex). The
        // InMemoryChunkVectorIndex registry lookup is a side-effect-free
        // read, hoisted here unconditionally so the helper receives the
        // resolved instance instead of the engine.
        final Properties       wikiProps                = engine.getWikiProperties();
        final ChunkVectorIndex wiredChunkVectorIndex    = engine.getChunkVectorIndex();
        final ChunkVectorIndex inMemoryChunkVectorIndex =
            engine.getManager( InMemoryChunkVectorIndex.class );
        final ChunkVectorIndex chunkVectorIndex = resolveChunkVectorIndex(
            deps, wikiProps, wiredChunkVectorIndex, inMemoryChunkVectorIndex );

        // In-memory graph neighbor index (registered by wireGraphRerank).
        final InMemoryGraphNeighborIndex graphNeighborIndex =
            engine.getManager( InMemoryGraphNeighborIndex.class );

        // Embedding pipeline.
        final EmbeddingIndexService       embeddingIndexService       =
            engine.getManager( EmbeddingIndexService.class );
        final OllamaEmbeddingClient       embeddingClient             =
            engine.getManager( OllamaEmbeddingClient.class );
        final BootstrapEmbeddingIndexer   bootstrapEmbeddingIndexer   =
            engine.getManager( BootstrapEmbeddingIndexer.class );
        final AsyncEmbeddingIndexListener asyncEmbeddingIndexListener =
            engine.getManager( AsyncEmbeddingIndexListener.class );

        // Cache.
        final FrontmatterMetadataCache frontmatterMetadataCache =
            engine.getManager( FrontmatterMetadataCache.class );

        final LuceneHelperRefs luceneHelpers = resolveLuceneHelpers( searchProvider );

        return new SearchSubsystem.Services(
            searchManager,
            searchProvider,
            luceneHelpers.luceneIndexer(),
            luceneHelpers.luceneSearcher(),
            luceneHelpers.luceneIndexLifecycle(),
            hybridSearch,
            queryEmbedder,
            queryEntityResolver,
            graphRerankStep,
            graphProximityScorer,
            chunkVectorIndex,
            graphNeighborIndex,
            embeddingIndexService,
            embeddingClient,
            bootstrapEmbeddingIndexer,
            asyncEmbeddingIndexListener,
            frontmatterMetadataCache );
    }

    /**
     * Pulls the {@link SearchProvider} off the manager defensively. Some
     * test fixtures register a {@link SearchManager} mock without stubbing
     * {@code getSearchEngine()}; treat that as "no provider" rather than
     * failing the whole subsystem build.
     */
    private static SearchProvider safeGetSearchEngine( final SearchManager sm ) {
        try {
            return sm.getSearchEngine();
        } catch ( final RuntimeException e ) {
            return null;
        }
    }

    /**
     * Chunk vector index: reuse the single instance SearchWiringHelper.wireHybridRetrieval
     * already built and wired (it runs earlier — inside initKnowledgeGraph(), before
     * buildSearchSubsystem() calls this factory — see WikiEngine.initialize()). Reusing it
     * is what keeps this Services.chunkVectorIndex() slot (read by
     * DefaultContextRetrievalService -> ContributingChunkAssembler, the retrieve_context
     * MCP tool's dense fallback) in sync with the AsyncEmbeddingIndexListener upserts;
     * constructing a second instance here from the same property would silently drop new
     * content from that path until restart.
     *
     * <p>Only fall back to constructing our own instance when nothing was wired (embeddings
     * disabled, wireHybridRetrieval failed, or direct factory use in tests) — and even then,
     * a null DataSource must degrade (matching SearchWiringHelper's own catch-and-warn
     * behaviour) rather than throw and crash engine boot. "inmemory" stays the fallback
     * default here (unchanged from before this fix) — it is the one backend that never
     * needs a DataSource, so it is the safest thing to fall back to when properties
     * aren't even available (e.g. a bare mocked Engine in a unit test).</p>
     */
    private static ChunkVectorIndex resolveChunkVectorIndex(
            final SearchSubsystem.Deps deps, final Properties wikiProps,
            final ChunkVectorIndex wiredChunkVectorIndex,
            final ChunkVectorIndex inMemoryChunkVectorIndex ) {
        final String backend = ( wikiProps != null
            ? wikiProps.getProperty( "wikantik.search.dense.backend", "inmemory" )
            : "inmemory" ).toLowerCase( Locale.ROOT );
        final ChunkVectorIndex chunkVectorIndex;
        if ( wiredChunkVectorIndex != null ) {
            chunkVectorIndex = wiredChunkVectorIndex;
            LOG.info( "Dense retrieval backend: reusing ChunkVectorIndex wired by SearchWiringHelper ({})",
                wiredChunkVectorIndex.getClass().getSimpleName() );
        } else {
            chunkVectorIndex = buildChunkVectorIndexForBackend(
                backend, deps, wikiProps, inMemoryChunkVectorIndex );
        }
        return chunkVectorIndex;
    }

    private static ChunkVectorIndex buildChunkVectorIndexForBackend(
            final String backend, final SearchSubsystem.Deps deps, final Properties wikiProps,
            final ChunkVectorIndex inMemoryChunkVectorIndex ) {
        final ChunkVectorIndex chunkVectorIndex;
        switch ( backend ) {
            case "pgvector" -> chunkVectorIndex = buildPgVectorChunkVectorIndex( deps, wikiProps );
            case "lucene-hnsw" -> chunkVectorIndex = buildLuceneHnswChunkVectorIndex( deps, wikiProps );
            case "inmemory" -> {
                chunkVectorIndex = inMemoryChunkVectorIndex;
                LOG.info( "Dense retrieval backend: in-memory brute-force" );
            }
            default -> throw new IllegalArgumentException(
                "wikantik.search.dense.backend must be 'inmemory', 'pgvector', or 'lucene-hnsw', got: '"
              + backend + "'" );
        }
        return chunkVectorIndex;
    }

    private static ChunkVectorIndex buildPgVectorChunkVectorIndex(
            final SearchSubsystem.Deps deps, final Properties wikiProps ) {
        final DataSource dataSource = deps.dataSource();
        if ( dataSource == null ) {
            LOG.warn( "wikantik.search.dense.backend=pgvector but no DataSource is available "
              + "and no ChunkVectorIndex was wired; dense retrieval disabled for this process." );
            return null;
        }
        final String modelCode = wikiProps.getProperty(
            EmbeddingConfig.PROP_MODEL, EmbeddingConfig.DEFAULT_MODEL_CODE );
        final int efSearch = Integer.parseInt( wikiProps.getProperty(
            "wikantik.search.dense.pgvector.ef_search", "100" ) );
        final ChunkVectorIndex chunkVectorIndex = new PgVectorChunkVectorIndex( dataSource, modelCode, efSearch );
        LOG.info( "Dense retrieval backend: pgvector HNSW (model={}, ef_search={})",
            modelCode, efSearch );
        return chunkVectorIndex;
    }

    private static ChunkVectorIndex buildLuceneHnswChunkVectorIndex(
            final SearchSubsystem.Deps deps, final Properties wikiProps ) {
        final DataSource dataSource = deps.dataSource();
        if ( dataSource == null ) {
            LOG.warn( "wikantik.search.dense.backend=lucene-hnsw but no DataSource is available "
              + "and no ChunkVectorIndex was wired; dense retrieval disabled for this process." );
            return null;
        }
        final String modelCode = wikiProps.getProperty(
            EmbeddingConfig.PROP_MODEL, EmbeddingConfig.DEFAULT_MODEL_CODE );
        final com.wikantik.search.hybrid.HnswParams params =
            com.wikantik.search.hybrid.HnswParams.fromProperties( wikiProps );
        final ChunkVectorIndex chunkVectorIndex = new com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex(
            dataSource, modelCode,
            com.wikantik.search.hybrid.PgVectorChunkVectorIndex.EMBEDDING_DIM, params );
        LOG.info( "Dense retrieval backend: Lucene HNSW (model={}, m={}, ef_construction={}, ef_search={})",
            modelCode, params.m(), params.efConstruction(), params.efSearch() );
        return chunkVectorIndex;
    }

    /**
     * Phase 7 Ckpt 4: pull the three Lucene helpers off the decomposed
     * LuceneSearchProvider facade (Ckpt 3) when the configured provider
     * is in fact Lucene. A non-Lucene SearchProvider (e.g. a future
     * alternative impl, or a test mock) leaves the slots null — same
     * shape as a missing legacy manager — and we LOG.warn so the gap
     * is visible to operators reading the startup log.
     */
    private static LuceneHelperRefs resolveLuceneHelpers( final SearchProvider searchProvider ) {
        LuceneIndexer        luceneIndexer        = null;
        LuceneSearcher       luceneSearcher       = null;
        LuceneIndexLifecycle luceneIndexLifecycle = null;
        if ( searchProvider instanceof LuceneSearchProvider lsp ) {
            luceneIndexer        = lsp.getIndexer();
            luceneSearcher       = lsp.getSearcher();
            luceneIndexLifecycle = lsp.getIndexLifecycle();
            if ( luceneIndexer == null || luceneSearcher == null || luceneIndexLifecycle == null ) {
                LOG.warn( "LuceneSearchProvider returned null helper accessors "
                    + "(indexer={}, searcher={}, lifecycle={}); SearchSubsystem.Services "
                    + "Lucene slots will be partial.",
                    luceneIndexer, luceneSearcher, luceneIndexLifecycle );
            }
        } else if ( searchProvider != null ) {
            LOG.warn( "SearchProvider is not a LuceneSearchProvider (actual={}); "
                + "leaving SearchSubsystem.Services Lucene helper slots null.",
                searchProvider.getClass().getName() );
        }
        return new LuceneHelperRefs( luceneIndexer, luceneSearcher, luceneIndexLifecycle );
    }

    /** Bundles the three decomposed-Lucene helper slots (Ckpt 4). */
    private record LuceneHelperRefs(
        LuceneIndexer        luceneIndexer,
        LuceneSearcher       luceneSearcher,
        LuceneIndexLifecycle luceneIndexLifecycle ) {}
}
