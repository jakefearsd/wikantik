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

import com.wikantik.api.core.Engine;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;
import com.wikantik.search.embedding.AsyncEmbeddingIndexListener;
import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;
import com.wikantik.search.embedding.EmbeddingIndexService;
import com.wikantik.search.embedding.OllamaEmbeddingClient;
import com.wikantik.search.hybrid.GraphProximityScorer;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.InMemoryGraphNeighborIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.QueryEntityResolver;

/**
 * Adapter that synthesises a sparse {@link SearchSubsystem.Services}
 * record from {@link Engine#getManager(Class)} lookups, mirroring the
 * other subsystem bridges.
 *
 * <p>Used by non-servlet callers (plugins, providers, internal managers)
 * and by test fixtures that build the engine via
 * {@code TestEngine.setManager(...)} rather than a full
 * {@code WikiEngine.initialize()} cycle. Production servlet code uses
 * the typed bundle stashed on the {@link jakarta.servlet.ServletContext}.</p>
 *
 * <p>Fields whose corresponding manager is not registered come back as
 * {@code null}, mirroring the legacy {@code getManager()} behaviour. The
 * three Lucene helper fields stay {@code null} in Phase 7 Ckpt 1 — Ckpt 4
 * will extract them from the decomposed {@code LuceneSearchProvider}.</p>
 */
public final class SearchSubsystemBridge {

    private SearchSubsystemBridge() {}

    public static SearchSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new SearchSubsystem.Services(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null );
        }
        final SearchSubsystem.Services typed = wikiEngine.getSearchSubsystem();
        if ( typed != null ) return typed;

        final SearchManager  searchManager  = wikiEngine.getManager( SearchManager.class );
        final SearchProvider searchProvider =
            searchManager != null ? safeGetSearchEngine( searchManager ) : null;

        final HybridSearchService  hybridSearch         = wikiEngine.getManager( HybridSearchService.class );
        final QueryEmbedder        queryEmbedder        = wikiEngine.getManager( QueryEmbedder.class );
        final QueryEntityResolver  queryEntityResolver  = wikiEngine.getManager( QueryEntityResolver.class );
        final GraphRerankStep      graphRerankStep      = wikiEngine.getManager( GraphRerankStep.class );
        final GraphProximityScorer graphProximityScorer = wikiEngine.getManager( GraphProximityScorer.class );

        final InMemoryChunkVectorIndex   chunkVectorIndex   =
            wikiEngine.getManager( InMemoryChunkVectorIndex.class );
        final InMemoryGraphNeighborIndex graphNeighborIndex =
            wikiEngine.getManager( InMemoryGraphNeighborIndex.class );

        final EmbeddingIndexService       embeddingIndexService       =
            wikiEngine.getManager( EmbeddingIndexService.class );
        final OllamaEmbeddingClient       embeddingClient             =
            wikiEngine.getManager( OllamaEmbeddingClient.class );
        final BootstrapEmbeddingIndexer   bootstrapEmbeddingIndexer   =
            wikiEngine.getManager( BootstrapEmbeddingIndexer.class );
        final AsyncEmbeddingIndexListener asyncEmbeddingIndexListener =
            wikiEngine.getManager( AsyncEmbeddingIndexListener.class );

        final FrontmatterMetadataCache frontmatterMetadataCache =
            wikiEngine.getManager( FrontmatterMetadataCache.class );

        return new SearchSubsystem.Services(
            searchManager,
            searchProvider,
            /*luceneIndexer=*/        null,
            /*luceneSearcher=*/       null,
            /*luceneIndexLifecycle=*/ null,
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

    private static SearchProvider safeGetSearchEngine( final SearchManager sm ) {
        try {
            return sm.getSearchEngine();
        } catch ( final RuntimeException e ) {
            return null;
        }
    }
}
