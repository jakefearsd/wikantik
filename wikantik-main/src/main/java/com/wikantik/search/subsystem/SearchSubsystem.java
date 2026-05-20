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
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.knowledge.subsystem.KnowledgeSubsystem;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;
import com.wikantik.search.embedding.AsyncEmbeddingIndexListener;
import com.wikantik.search.embedding.BootstrapEmbeddingIndexer;
import com.wikantik.search.embedding.EmbeddingIndexService;
import com.wikantik.search.embedding.OllamaEmbeddingClient;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.GraphProximityScorer;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.InMemoryGraphNeighborIndex;
import com.wikantik.search.hybrid.QueryEmbedder;
import com.wikantik.search.hybrid.QueryEntityResolver;
import com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle;
import com.wikantik.search.subsystem.lucene.LuceneIndexer;
import com.wikantik.search.subsystem.lucene.LuceneSearcher;

import javax.sql.DataSource;

/**
 * Namespace for the Search subsystem's input and output contracts.
 *
 * <p>Phase 7 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}
 * and {@code docs/superpowers/plans/2026-05-07-decomposition-phase-7-search-subsystem.md}.</p>
 *
 * <p>Search walls off Lucene + hybrid retrieval + the embedding pipeline
 * behind a typed {@link Services} surface. Checkpoint 1 (this file)
 * exposes the manager / provider / hybrid services / embedding services /
 * cache slots produced by the existing {@code WikiEngine.initialize}
 * wiring; Checkpoint 2 migrates the eight production callsites of
 * {@code SearchManager}; Checkpoint 3 decomposes
 * {@code LuceneSearchProvider} into {@link LuceneIndexer},
 * {@link LuceneSearcher}, and {@link LuceneIndexLifecycle}; Checkpoint 4
 * wires those three helpers into {@link Services}.</p>
 */
public final class SearchSubsystem {

    private SearchSubsystem() {}

    /**
     * What the Search subsystem requires from upstream.
     *
     * <p>{@code engine} is the legacy seam — every service in
     * {@link Services} is constructed by {@code WikiEngine.initialize}
     * (specifically {@code wireHybridRetrieval} / {@code wireGraphRerank})
     * and registered via {@code managers.put(...)}. The Phase 7 Ckpt 1
     * factory pulls each one off the engine's manager registry. Subsequent
     * checkpoints narrow this seam.</p>
     *
     * <p>{@code dataSource} is the raw JDBC handle, used by
     * {@link SearchSubsystemFactory} to construct
     * {@link com.wikantik.search.hybrid.PgVectorChunkVectorIndex} when
     * {@code wikantik.search.dense.backend=pgvector}. {@code null} on
     * paths that do not configure a datasource (unit-test bridges).</p>
     */
    public record Deps(
        DataSource                    dataSource,
        CoreSubsystem.Services        core,
        PersistenceSubsystem.Services persistence,
        PageSubsystem.Services        page,
        KnowledgeSubsystem.Services   knowledge,
        Engine                        engine
    ) {}

    /**
     * What the Search subsystem exposes to downstream consumers.
     *
     * <p>{@code searchManager} and {@code searchProvider} are non-null
     * after a successful {@link SearchSubsystemFactory#create} call when
     * the engine has registered them. The remaining hybrid + embedding
     * fields are non-null when the corresponding wiring path was taken
     * (master flag on, datasource available, embedding backend reachable);
     * absent wiring leaves the slot {@code null}, mirroring the engine's
     * {@code getManager(...)} return for an unregistered class.</p>
     *
     * <p>The three Lucene helper slots ({@code luceneIndexer},
     * {@code luceneSearcher}, {@code luceneIndexLifecycle}) are populated
     * in Ckpt 3 after {@code LuceneSearchProvider} decomposition; null
     * until then.</p>
     */
    public record Services(
        // Manager + provider:
        SearchManager  searchManager,
        SearchProvider searchProvider,

        // Decomposed Lucene helpers (populated in Ckpt 3 after
        // LuceneSearchProvider decomposition; null until then):
        LuceneIndexer        luceneIndexer,
        LuceneSearcher       luceneSearcher,
        LuceneIndexLifecycle luceneIndexLifecycle,

        // Hybrid retrieval:
        HybridSearchService  hybridSearch,
        QueryEmbedder        queryEmbedder,
        QueryEntityResolver  queryEntityResolver,
        GraphRerankStep      graphRerankStep,
        GraphProximityScorer graphProximityScorer,

        // Chunk vector index (may be in-memory or pgvector-backed):
        ChunkVectorIndex           chunkVectorIndex,
        InMemoryGraphNeighborIndex graphNeighborIndex,

        // Embedding pipeline:
        EmbeddingIndexService       embeddingIndexService,
        OllamaEmbeddingClient       embeddingClient,
        BootstrapEmbeddingIndexer   bootstrapEmbeddingIndexer,
        AsyncEmbeddingIndexListener asyncEmbeddingIndexListener,

        // Cache:
        FrontmatterMetadataCache frontmatterMetadataCache
    ) {}
}
