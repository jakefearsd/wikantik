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

        // Chunk vector index: select implementation based on
        // wikantik.search.dense.backend (default "inmemory").
        final Properties wikiProps = engine.getWikiProperties();
        final String backend = ( wikiProps != null
            ? wikiProps.getProperty( "wikantik.search.dense.backend", "inmemory" )
            : "inmemory" ).toLowerCase( Locale.ROOT );
        final ChunkVectorIndex chunkVectorIndex;
        switch ( backend ) {
            case "pgvector" -> {
                final DataSource dataSource = deps.dataSource();
                if ( dataSource == null ) {
                    throw new IllegalStateException(
                        "wikantik.search.dense.backend=pgvector but no DataSource is available; "
                      + "check that wikantik.datasource is configured" );
                }
                final String modelCode = wikiProps.getProperty(
                    EmbeddingConfig.PROP_MODEL, EmbeddingConfig.DEFAULT_MODEL_CODE );
                final int efSearch = Integer.parseInt( wikiProps.getProperty(
                    "wikantik.search.dense.pgvector.ef_search", "100" ) );
                chunkVectorIndex = new PgVectorChunkVectorIndex( dataSource, modelCode, efSearch );
                LOG.info( "Dense retrieval backend: pgvector HNSW (model={}, ef_search={})",
                    modelCode, efSearch );
            }
            case "inmemory" -> {
                chunkVectorIndex = engine.getManager( InMemoryChunkVectorIndex.class );
                LOG.info( "Dense retrieval backend: in-memory brute-force" );
            }
            default -> throw new IllegalArgumentException(
                "wikantik.search.dense.backend must be 'inmemory' or 'pgvector', got: '"
              + backend + "'" );
        }

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

        // Phase 7 Ckpt 4: pull the three Lucene helpers off the decomposed
        // LuceneSearchProvider facade (Ckpt 3) when the configured provider
        // is in fact Lucene. A non-Lucene SearchProvider (e.g. a future
        // alternative impl, or a test mock) leaves the slots null — same
        // shape as a missing legacy manager — and we LOG.warn so the gap
        // is visible to operators reading the startup log.
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

        return new SearchSubsystem.Services(
            searchManager,
            searchProvider,
            luceneIndexer,
            luceneSearcher,
            luceneIndexLifecycle,
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
}
