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
import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.api.core.Page;
import com.wikantik.api.eval.RetrievalMode;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner;
import com.wikantik.search.SearchManager;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.HybridConfig;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import com.wikantik.knowledge.chunking.ChunkProjector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the dense retrieval backend default. Regression guard for the perf
 * flip away from the brute-force {@code inmemory} scan (O(corpus) per query
 * and per save) to the RAM-backed, true-ANN {@code lucene-hnsw} backend,
 * which is also the prod (docker1) choice.
 */
class SearchWiringHelperTest {

    @Test
    void resolveDenseBackend_defaultsToLuceneHnswWhenPropertyAbsent() {
        final Properties props = new Properties();
        assertEquals( "lucene-hnsw", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    @Test
    void resolveDenseBackend_respectsExplicitPropertyCaseInsensitively() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "PgVector" );
        assertEquals( "pgvector", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    @Test
    void resolveDenseBackend_respectsExplicitInmemory() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.backend", "inmemory" );
        assertEquals( "inmemory", SearchWiringHelper.resolveDenseBackend( props ) );
    }

    // -- Single-instance-sharing regression tests (post d027a546da default flip) --
    //
    // SearchSubsystemFactory runs later in boot (WikiEngine.initialize():
    // buildSearchSubsystem(), AFTER initKnowledgeGraph() -> wireHybridRetrieval) and reads
    // engine.getChunkVectorIndex() to avoid building its own, orphaned, second index instance.
    // Before this fix, wireHybridRetrieval only wired that typed slot for the "inmemory"
    // backend — pgvector/lucene-hnsw left it unwired, so the factory always built (and the
    // AsyncEmbeddingIndexListener upserts never reached) a second copy.

    @Test
    void wireHybridRetrieval_luceneHnswBackend_registersSharedChunkVectorIndex() throws SQLException {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "lucene-hnsw" );

        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "no db in unit test" ) );

        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        final ArgumentCaptor< ChunkVectorIndex > captor = ArgumentCaptor.forClass( ChunkVectorIndex.class );
        verify( engine ).setChunkVectorIndex( captor.capture() );
        assertInstanceOf( LuceneHnswChunkVectorIndex.class, captor.getValue(),
            "the lucene-hnsw backend must wire its ChunkVectorIndex, same as inmemory always did" );
    }

    @Test
    void wireHybridRetrieval_pgvectorBackend_registersSharedChunkVectorIndex() {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "pgvector" );

        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        final ArgumentCaptor< ChunkVectorIndex > captor = ArgumentCaptor.forClass( ChunkVectorIndex.class );
        verify( engine ).setChunkVectorIndex( captor.capture() );
        assertInstanceOf( PgVectorChunkVectorIndex.class, captor.getValue(),
            "the pgvector backend must wire its ChunkVectorIndex, same as inmemory always did" );
    }

    @RequiresPostgres
    @Test
    void wireHybridRetrieval_inmemoryBackend_registersSharedChunkVectorIndex() throws Exception {
        // Unlike lucene-hnsw/pgvector, InMemoryChunkVectorIndex's constructor loads eagerly and
        // rethrows on any SQL failure rather than degrading — so it needs a real (if empty) DB.
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "inmemory" );

        final DataSource ds = PostgresTestDb.createDataSource();
        // No chunks -> bootstrap.startIfNeeded() lands in SKIPPED_NO_CHUNKS without ever
        // constructing an HTTP call to the (default, remote) embedding backend.
        PostgresTestDb.truncate( "kg_content_chunks" );

        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        final ArgumentCaptor< ChunkVectorIndex > captor = ArgumentCaptor.forClass( ChunkVectorIndex.class );
        verify( engine ).setChunkVectorIndex( captor.capture() );
        assertInstanceOf( InMemoryChunkVectorIndex.class, captor.getValue() );
        verify( engine ).setManager( eq( InMemoryChunkVectorIndex.class ), any() );
    }

    @Test
    void wireHybridRetrieval_unsupportedDenseBackend_disablesHybridRetrieval() {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "some-bogus-backend" );

        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        verify( engine, never() ).setChunkVectorIndex( any() );
    }

    @Test
    void wireHybridRetrieval_invalidEmbeddingConfig_disablesHybridRetrieval() {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        // Unknown model code -> EmbeddingConfig.fromProperties throws IllegalArgumentException.
        props.setProperty( EmbeddingConfig.PROP_MODEL, "not-a-real-model" );

        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        verifyNoInteractions( engine );
    }

    @Test
    void wireHybridRetrieval_invalidHybridConfig_wiresEmbeddingOnlyAndSkipsSearchService() throws SQLException {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "lucene-hnsw" );
        // Not an integer -> HybridConfig.fromProperties throws IllegalArgumentException.
        props.setProperty( "wikantik.search.hybrid.rrf.k", "not-a-number" );

        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "no db in unit test" ) );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, /*rebuildService=*/ null, engine );

        // The ChunkVectorIndex is wired before HybridConfig is parsed, so that much survives...
        verify( engine ).setChunkVectorIndex( any() );
        // ...but a malformed hybrid config must stop short of registering the search service.
        verify( engine, never() ).setManager( eq( HybridSearchService.class ), any() );
    }

    @Test
    void wireHybridRetrieval_registersEmbeddingHookOnRebuildServiceWhenPresent() throws SQLException {
        final Properties props = new Properties();
        props.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        props.setProperty( "wikantik.search.dense.backend", "lucene-hnsw" );

        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "no db in unit test" ) );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final WikiEngine engine = mock( WikiEngine.class );
        final ContentIndexRebuildService rebuildService = mock( ContentIndexRebuildService.class );

        SearchWiringHelper.wireHybridRetrieval( props, ds, chunkProjector,
            /*chunkRepo=*/ null, /*fmCache=*/ null, rebuildService, engine );

        verify( rebuildService ).setEmbeddingHook( any(), eq( "qwen3-embedding-0.6b" ) );
    }

    // -----------------------------------------------------------------------
    // wireRetrievalQualityRunner
    // -----------------------------------------------------------------------

    @Test
    void wireRetrievalQualityRunner_registersRunnerWithoutSchedulingWhenCronDisabled() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.retrieval.cron.enabled", "false" );

        final DataSource ds = mock( DataSource.class );
        final StructuralIndexService structuralIndex = mock( StructuralIndexService.class );
        final WikiEngine engine = mock( WikiEngine.class );

        SearchWiringHelper.wireRetrievalQualityRunner( props, ds, structuralIndex,
            null, null, null, engine );

        final ArgumentCaptor< RetrievalQualityRunner > captor = ArgumentCaptor.forClass( RetrievalQualityRunner.class );
        verify( engine ).setManager( eq( RetrievalQualityRunner.class ), captor.capture() );
        assertNotNull( captor.getValue() );
    }

    @Test
    void wireRetrievalQualityRunner_wiringFailureIsCaughtAndLogged() {
        final StructuralIndexService structuralIndex = mock( StructuralIndexService.class );
        final WikiEngine engine = mock( WikiEngine.class );

        // A null DataSource makes RetrievalQualityDao's constructor throw IllegalArgumentException,
        // which the method must catch rather than propagate.
        SearchWiringHelper.wireRetrievalQualityRunner( new Properties(), null, structuralIndex,
            null, null, null, engine );

        verifyNoInteractions( engine );
    }

    // -----------------------------------------------------------------------
    // buildRetriever
    // -----------------------------------------------------------------------

    @Test
    void buildRetriever_returnsEmptyListWhenSearchManagerIsNull() {
        final WikiEngine engine = mock( WikiEngine.class );
        final PageManager pageManager = mock( PageManager.class );

        final DefaultRetrievalQualityRunner.Retriever retriever =
            SearchWiringHelper.buildRetriever( engine, /*searchManager=*/ null, pageManager, null );

        assertEquals( List.of(), retriever.retrieve( RetrievalMode.BM25, "some query" ) );
        verifyNoInteractions( pageManager );
    }

    @Test
    void buildRetriever_returnsEmptyListWhenPageManagerIsNull() {
        final WikiEngine engine = mock( WikiEngine.class );
        final SearchManager searchManager = mock( SearchManager.class );

        final DefaultRetrievalQualityRunner.Retriever retriever =
            SearchWiringHelper.buildRetriever( engine, searchManager, /*pageManager=*/ null, null );

        assertEquals( List.of(), retriever.retrieve( RetrievalMode.BM25, "some query" ) );
        verifyNoInteractions( searchManager );
    }

    @Test
    void buildRetriever_swallowsContextConstructionFailureAndReturnsEmptyList() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getFrontPage() ).thenReturn( "Main" );
        final SearchManager searchManager = mock( SearchManager.class );
        final PageManager pageManager = mock( PageManager.class );
        final Page front = mock( Page.class );
        when( pageManager.getPage( "Main" ) ).thenReturn( front );

        final DefaultRetrievalQualityRunner.Retriever retriever =
            SearchWiringHelper.buildRetriever( engine, searchManager, pageManager, null );

        // A bare mock WikiEngine has no registered CommandResolver, so constructing a
        // WikiContext for evaluation fails — the retriever must degrade to empty rather
        // than propagate the failure into the nightly retrieval-quality run.
        assertEquals( List.of(), retriever.retrieve( RetrievalMode.HYBRID, "some query" ) );
        verifyNoInteractions( searchManager );
    }
}
