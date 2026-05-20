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
import com.wikantik.search.embedding.AsyncEmbeddingIndexListener;
import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;
import com.wikantik.search.embedding.EmbeddingIndexService;
import com.wikantik.search.embedding.OllamaEmbeddingClient;
import com.wikantik.search.hybrid.GraphProximityScorer;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.InMemoryGraphNeighborIndex;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.QueryEntityResolver;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7 Checkpoint 1 subsystem-isolation test for
 * {@link SearchSubsystemFactory}.
 *
 * <p>Demonstrates that the Search subsystem can be assembled without
 * {@code WikiEngine} or {@code TestEngine}: a mocked {@link Engine}
 * stocked with the relevant managers is enough to produce a populated
 * {@link SearchSubsystem.Services}. The three Lucene helper slots stay
 * {@code null} until Ckpt 4 — that's the intended Ckpt 1 shape.</p>
 */
final class SearchSubsystemFactoryTest {

    @Test
    void createWiresManagerAndProviderFromEngineRegistry() {
        final SearchProvider provider = mock( SearchProvider.class );
        final SearchManager  manager  = mock( SearchManager.class );
        when( manager.getSearchEngine() ).thenReturn( provider );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( SearchManager.class ) ).thenReturn( manager );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/ null,
                /*knowledge=*/ null,
                engine ) );

        assertNotNull( services );
        assertSame( manager,  services.searchManager() );
        assertSame( provider, services.searchProvider() );

        // Ckpt 1: the three Lucene helper slots are unconditionally null.
        assertNull( services.luceneIndexer(),        "luceneIndexer null until Ckpt 4" );
        assertNull( services.luceneSearcher(),       "luceneSearcher null until Ckpt 4" );
        assertNull( services.luceneIndexLifecycle(), "luceneIndexLifecycle null until Ckpt 4" );
    }

    @Test
    void createPropagatesHybridAndEmbeddingServices() {
        final HybridSearchService  hybrid               = mock( HybridSearchService.class );
        final QueryEmbedder        queryEmbedder        = mock( QueryEmbedder.class );
        final QueryEntityResolver  queryEntityResolver  = mock( QueryEntityResolver.class );
        final GraphRerankStep      graphRerankStep      = mock( GraphRerankStep.class );
        final GraphProximityScorer graphProximityScorer = mock( GraphProximityScorer.class );

        final InMemoryChunkVectorIndex   chunkVectorIndex   = mock( InMemoryChunkVectorIndex.class );
        final InMemoryGraphNeighborIndex graphNeighborIndex = mock( InMemoryGraphNeighborIndex.class );

        final EmbeddingIndexService       embeddingIndexService       = mock( EmbeddingIndexService.class );
        final OllamaEmbeddingClient       embeddingClient             = mock( OllamaEmbeddingClient.class );
        final BootstrapEmbeddingIndexer   bootstrapEmbeddingIndexer   = mock( BootstrapEmbeddingIndexer.class );
        final AsyncEmbeddingIndexListener asyncEmbeddingIndexListener = mock( AsyncEmbeddingIndexListener.class );

        final FrontmatterMetadataCache frontmatterCache = mock( FrontmatterMetadataCache.class );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( HybridSearchService.class ) ).thenReturn( hybrid );
        when( engine.getManager( QueryEmbedder.class ) ).thenReturn( queryEmbedder );
        when( engine.getManager( QueryEntityResolver.class ) ).thenReturn( queryEntityResolver );
        when( engine.getManager( GraphRerankStep.class ) ).thenReturn( graphRerankStep );
        when( engine.getManager( GraphProximityScorer.class ) ).thenReturn( graphProximityScorer );
        when( engine.getManager( InMemoryChunkVectorIndex.class ) ).thenReturn( chunkVectorIndex );
        when( engine.getManager( InMemoryGraphNeighborIndex.class ) ).thenReturn( graphNeighborIndex );
        when( engine.getManager( EmbeddingIndexService.class ) ).thenReturn( embeddingIndexService );
        when( engine.getManager( OllamaEmbeddingClient.class ) ).thenReturn( embeddingClient );
        when( engine.getManager( BootstrapEmbeddingIndexer.class ) ).thenReturn( bootstrapEmbeddingIndexer );
        when( engine.getManager( AsyncEmbeddingIndexListener.class ) ).thenReturn( asyncEmbeddingIndexListener );
        when( engine.getManager( FrontmatterMetadataCache.class ) ).thenReturn( frontmatterCache );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/ null,
                /*knowledge=*/ null,
                engine ) );

        assertSame( hybrid,                      services.hybridSearch() );
        assertSame( queryEmbedder,               services.queryEmbedder() );
        assertSame( queryEntityResolver,         services.queryEntityResolver() );
        assertSame( graphRerankStep,             services.graphRerankStep() );
        assertSame( graphProximityScorer,        services.graphProximityScorer() );
        assertSame( chunkVectorIndex,            services.chunkVectorIndex() );
        assertSame( graphNeighborIndex,          services.graphNeighborIndex() );
        assertSame( embeddingIndexService,       services.embeddingIndexService() );
        assertSame( embeddingClient,             services.embeddingClient() );
        assertSame( bootstrapEmbeddingIndexer,   services.bootstrapEmbeddingIndexer() );
        assertSame( asyncEmbeddingIndexListener, services.asyncEmbeddingIndexListener() );
        assertSame( frontmatterCache,            services.frontmatterMetadataCache() );
    }

    @Test
    void createTreatsMissingManagersAsNullSlots() {
        // No managers stubbed — every getManager() returns null. The
        // factory still produces a Services record with every field null
        // (mirrors the legacy getManager() behaviour for unwired engines).
        final WikiEngine engine = mock( WikiEngine.class );
        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/ null,
                /*knowledge=*/ null,
                engine ) );

        assertNotNull( services );
        assertNull( services.searchManager() );
        assertNull( services.searchProvider() );
        assertNull( services.hybridSearch() );
        assertNull( services.queryEmbedder() );
        assertNull( services.embeddingIndexService() );
        assertNull( services.frontmatterMetadataCache() );
    }

    @Test
    void createPopulatesLuceneHelpersWhenProviderIsLucene() {
        // Phase 7 Ckpt 4: when the configured SearchProvider is a
        // LuceneSearchProvider, the factory pulls the three decomposed
        // helpers off the facade accessors and installs them on Services.
        final LuceneIndexer        indexer   = mock( LuceneIndexer.class );
        final LuceneSearcher       searcher  = mock( LuceneSearcher.class );
        final LuceneIndexLifecycle lifecycle = mock( LuceneIndexLifecycle.class );

        final LuceneSearchProvider provider = mock( LuceneSearchProvider.class );
        when( provider.getIndexer() ).thenReturn( indexer );
        when( provider.getSearcher() ).thenReturn( searcher );
        when( provider.getIndexLifecycle() ).thenReturn( lifecycle );

        final SearchManager manager = mock( SearchManager.class );
        when( manager.getSearchEngine() ).thenReturn( provider );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( SearchManager.class ) ).thenReturn( manager );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/ null,
                /*knowledge=*/ null,
                engine ) );

        assertSame( provider,  services.searchProvider() );
        assertSame( indexer,   services.luceneIndexer() );
        assertSame( searcher,  services.luceneSearcher() );
        assertSame( lifecycle, services.luceneIndexLifecycle() );
    }

    @Test
    void createLeavesLuceneHelpersNullWhenProviderIsNotLucene() {
        // A non-Lucene SearchProvider (e.g. a future alternative impl, or
        // a vanilla mock as in createWiresManagerAndProviderFromEngineRegistry
        // above) leaves the slots null with a LOG.warn. This test asserts
        // the null shape — the warn is emitted but not asserted here.
        final SearchProvider provider = mock( SearchProvider.class );
        final SearchManager  manager  = mock( SearchManager.class );
        when( manager.getSearchEngine() ).thenReturn( provider );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getManager( SearchManager.class ) ).thenReturn( manager );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/ null,
                /*knowledge=*/ null,
                engine ) );

        assertNull( services.luceneIndexer() );
        assertNull( services.luceneSearcher() );
        assertNull( services.luceneIndexLifecycle() );
    }

    // -- Backend-selection tests (Task 6) --

    @Test
    void selectsPgvectorBackendWhenPropertySet() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "pgvector" );
        props.setProperty( "wikantik.search.dense.pgvector.ef_search", "150" );

        final DataSource dataSource = mock( DataSource.class );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( props );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ dataSource,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/ null,
                /*knowledge=*/ null,
                engine ) );

        assertInstanceOf( PgVectorChunkVectorIndex.class, services.chunkVectorIndex(),
            "expected PgVectorChunkVectorIndex, got "
                + ( services.chunkVectorIndex() == null ? "null"
                    : services.chunkVectorIndex().getClass().getName() ) );
    }

    @Test
    void defaultsToInmemoryWhenPropertyAbsent() {
        final InMemoryChunkVectorIndex vectorIndex = mock( InMemoryChunkVectorIndex.class );
        final WikiEngine engine = mock( WikiEngine.class );
        // getWikiProperties() returns null → factory defaults to "inmemory"
        when( engine.getManager( InMemoryChunkVectorIndex.class ) ).thenReturn( vectorIndex );

        final SearchSubsystem.Services services = SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                /*dataSource=*/ null,
                /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                /*persistence=*/ null,
                /*page=*/ null,
                /*knowledge=*/ null,
                engine ) );

        assertInstanceOf( InMemoryChunkVectorIndex.class, services.chunkVectorIndex() );
        assertSame( vectorIndex, services.chunkVectorIndex() );
    }

    @Test
    void rejectsUnknownBackendValue() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "elasticsearch" );

        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getWikiProperties() ).thenReturn( props );

        assertThrows( IllegalArgumentException.class,
            () -> SearchSubsystemFactory.create(
                new SearchSubsystem.Deps(
                    /*dataSource=*/ null,
                    /*core=*/ mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                    /*persistence=*/ null,
                    /*page=*/ null,
                    /*knowledge=*/ null,
                    engine ) ) );
    }

    @Test
    void createRejectsMissingDeps() {
        // null deps record → NPE
        assertThrows( NullPointerException.class, () -> SearchSubsystemFactory.create( null ) );
        // null engine → NPE (dataSource/core/persistence/page/knowledge are optional — factory does not use them today)
        assertThrows( NullPointerException.class, () -> SearchSubsystemFactory.create(
            new SearchSubsystem.Deps(
                null,
                mock( com.wikantik.core.subsystem.CoreSubsystem.Services.class ),
                null, null, null, null ) ) );
    }
}
